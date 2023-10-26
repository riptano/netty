/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.epoll;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import io.netty.channel.ChannelException;
import io.netty.channel.unix.Errors;
import io.netty.channel.unix.FileDescriptor;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

public class AIOContext {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AIOContext.class);

    private final long address;
    private final int maxPending;
    private final Request[] outstandingRequests;
    private final ArrayDeque<Batch> pendingBatches;
    private final FileDescriptor eventFd;
    private volatile boolean destroyed;

    AIOContext(long address, Config config) {
        this.address = address;
        this.maxPending = config.maxPending;
        this.outstandingRequests = new Request[config.maxConcurrency];
        this.pendingBatches = new ArrayDeque<Batch>(maxPending);
        this.eventFd = new FileDescriptor(Native.eventFd());
    }

    public void destroy() {
        if (destroyed) {
            return;
        }

        destroyed = true;
        try {
            Native.destroyAIOContext(this);
        } finally {
            try {
                eventFd.close();
            } catch (IOException ex) {
                logger.error("Failed to close aio event file descriptor: {}", ex.getMessage(), ex);
            }
        }
    }

    public FileDescriptor getEventFd() {
        return eventFd;
    }

    public long getAddress() {
        return address;
    }

    public <A> void read(Batch<A> batch) {
        assert !destroyed;
        submitBatch(batch, true);
    }

    private int submitBatch(Batch<?> batch, boolean pendingLast) {
        if (logger.isTraceEnabled()) {
            logger.trace("Received read batch {}", batch);
        }

        if (!batch.fileDescriptor.isOpen()) {
            batch.failed("File has been closed");
            return 0;
        }

        int currentIndex = 0;
        for (int i = 0; i < outstandingRequests.length; i++) {
            if (outstandingRequests[i] != null) {
                continue;
            }

            Request<?> current = batch.requests.get(currentIndex++);
            outstandingRequests[i] = current;
            current.slot = i;

            if (currentIndex == batch.requests.size()) {
                break;
            }
        }

        if (currentIndex > 0) {
            Batch<?> toSubmit = batch.split(0, currentIndex);
            try {
                Native.submitAIOReads(this, eventFd.intValue(), batch.fileDescriptor.intValue(), toSubmit.requests);
            }  catch (Throwable e) {
                if (e instanceof ChannelException && e.getMessage().contains("Resource temporarily unavailable")) {
                    addToPending(toSubmit, pendingLast);
                } else {
                    logger.error("Error reading " + batch.path + "@" + batch.offset(), e);
                    batch.failed(e);
                }
            }
        }

        if (currentIndex < batch.requests.size()) {
            addToPending(batch.split(currentIndex, batch.requests.size()), pendingLast);
        }

        return currentIndex; // the number of requests submitted
    }

    private <A> void addToPending(final Batch<A> batch, boolean last) {
        if (pendingBatches.size() >= maxPending) {
            batch.failed(new RuntimeException("Too many pending requests"));
        }
        boolean added = last ? pendingBatches.offerLast(batch) : pendingBatches.offerFirst(batch);
        assert added : "failed to add request batch";
    }

    /**
     * Process the read events for this AIO context.
     */
    public void processReady() {
        assert !destroyed : "AIO context already destroyed";
        try {
            innerProcessReady();
        } catch (Throwable t) {
            logger.error("Failed to process AIO events due to exception: {}/{}",
                    t.getClass().getName(), t.getMessage());
        }
    }

    private void innerProcessReady() throws IOException {
        long[] result = new long[outstandingRequests.length * 2];
        int numReady = Native.getAIOEvents(this, result);

        // this is fine, we read as many requests as available so it can happen that we race with
        // an epoll event for the next request
        if (numReady == 0) {
            return;
        }

        List<Request> completedRequests = new ArrayList<Request>(numReady);
        try {
            for (int i = 0; i < numReady; i++) {
                int slot = (int) result[i];
                assert slot >= 0 && slot < outstandingRequests.length : "Invalid slot number: " + slot;
                assert outstandingRequests[slot] != null : "Request at slot " + slot + " was null";

                // nothing here should throw, completedRequests has already been pre-allocated
                Request request = outstandingRequests[slot];
                int res = (int) result[i + numReady];
                if (res < 0) {
                    request.lengthRead = 0;
                    request.error = Errors.newIOException("aio-read", res);
                } else {
                    request.lengthRead = res;
                }
                outstandingRequests[slot] = null;
                completedRequests.add(request);
            }

            submitPendingReads(numReady);
        } finally {
            for (Request completedRequest : completedRequests) {
                try {
                    completedRequest.complete();
                } catch (Throwable t) {
                    logger.error("Error completing request {}", completedRequest, t);
                }
            }
        }
    }

    private void submitPendingReads(int numReady) {
        int numSubmitted = 0;
        while (numSubmitted < numReady) {
            Batch batch = pendingBatches.poll();
            if (batch == null) {
                break;
            }

            numSubmitted += submitBatch(batch, false);
        }
    }

    @Override
    public String toString() {
        int requests = 0;
        for (Request r : outstandingRequests) {
            if (r != null && r.slot != -1) {
                requests++;
            }
        }

        return String.format("AioContext[eventfd: %s, outstanding: %s, pending: %s]",
                             eventFd, requests, pendingBatches.size());
    }

    /**
     * A class with some configuration parameters used to create a {@link AIOContext}.
     */
    public static final class Config {
        /** The maximum size of the native queue depth, also known as the max concurrency in the Linux aio jargon. */
        public final int maxConcurrency;

        /** The maximum size of the queue of requests that are waiting to be sent to the native AIO context, because
         * the native queue size has been exceeded.
         */
        public final int maxPending;

        public Config(int maxConcurrency, int maxPending) {
            this.maxConcurrency = maxConcurrency;
            this.maxPending = maxPending;
        }

        @Override
        public String toString() {
            return String.format("Max concurrency: %d, Max pending: %d", maxConcurrency, maxPending);
        }
    }

    public static final class Batch<A> {
        private final String path;
        private final FileDescriptor fileDescriptor;
        private final boolean vectored;
        private final List<Request<A>> requests;

        Batch(AIOEpollFileChannel file, boolean vectored) {
            this(file.getFileObject().getPath(), file.getFile(), vectored, new ArrayList<Request<A>>());
        }

        Batch(String path, FileDescriptor fileDescriptor, boolean vectored, List<Request<A>> requests) {
            this.path = path;
            this.fileDescriptor = fileDescriptor;
            this.vectored = vectored;
            this.requests = requests;
        }

        public Batch<A> add(final long offset, final ByteBuffer buffer,
                            final A attachment, final CompletionHandler<Integer, ? super A> handler) {

            if (vectored) {
                for (Request<A> request : requests) {
                    if (request.maybeAdd(offset, buffer, handler, attachment)) {
                        return this;
                    }
                }
            }

            this.requests.add(new Request<A>(-1, offset, buffer, handler, attachment, path, fileDescriptor));
            return this;
        }

        public long offset() {
            return requests.get(0).offset;
        }

        boolean verify() {
            boolean ret = true;
            for (Request<A> request : requests) {
                ret &= request.verify();
            }
            return ret;
        }

        public void failed(String error) {
            for (Request<A> request : requests) {
                request.failed(error);
            }
        }

        public void failed(Throwable t) {
            for (Request<A> request : requests) {
                request.failed(t);
            }
        }

        Batch<A> split(int fromIndex, int toIndex) {
            return fromIndex == 0 && toIndex == requests.size()
                    ? this
                    : new Batch<A>(path, fileDescriptor, vectored, requests.subList(fromIndex, toIndex));
        }

        public int numRequests() {
            return requests.size();
        }

        @Override
        public String toString() {
            StringBuilder str = new StringBuilder();
            str.append("Batch:\n");
            for (Request<A> req: requests) {
                str.append(req.toString()).append("\n");
            }
            return str.toString();
        }
    }

    static final class BufferHolder<A> {
        final ByteBuffer buffer;
        final A attachment;
        final CompletionHandler<Integer, ? super A> handler;

        BufferHolder(ByteBuffer buffer, A attachment, CompletionHandler<Integer, ? super A> handler) {
            this.buffer = buffer;
            this.attachment = attachment;
            this.handler = handler;
        }

        Throwable verify() {
            if (!buffer.isDirect()) {
                return new IllegalArgumentException("ByteBuffer is not direct");
            }

            if (buffer.position() != 0) {
                return new IllegalArgumentException("ByteBuffer position must be 0");
            }

            return null;
        }

        void failed(Throwable t) {
            handler.failed(t, attachment);
        }

        void completed(int pos) {
            buffer.position(pos).limit(pos); // shrink limit to position to indicate buffer is fully filled
            handler.completed(buffer.limit(), attachment);
        }

        int limit() {
            return buffer.limit();
        }
    }

    public static final class Request<A> {
        int slot;
        final long offset;
        final List<BufferHolder<A>> buffers;
        final String path;
        final FileDescriptor fileDescriptor;
        int lengthRead;
        boolean completed;
        Throwable error;

        Request(int slot, long offset, ByteBuffer buffer, String path, FileDescriptor fileDescriptor) {
            this(slot, offset, buffer, null, null, path, fileDescriptor);
        }

        Request(int slot, long offset, ByteBuffer buffer,
                CompletionHandler<Integer, ? super A> handler,
                A attachment, String path, FileDescriptor fileDescriptor) {
            this(slot, offset, path, fileDescriptor);
            this.buffers.add(new BufferHolder<A>(buffer, attachment, handler));
        }

        Request(int slot, long offset, String path, FileDescriptor fileDescriptor) {
            this.slot = slot;
            this.offset = offset;
            this.buffers = new ArrayList<BufferHolder<A>>(1);
            this.path = path;
            this.fileDescriptor = fileDescriptor;
            this.lengthRead = -1;
        }

        boolean maybeAdd(long offset, ByteBuffer buffer, CompletionHandler<Integer, ? super A> handler, A attachment) {
            // for now there is a limitation native side of max 8 buffers per request, perhaps we need to remove this
            if (this.buffers.size() < 8 && offset == this.offset + totLength()) {
                this.buffers.add(new BufferHolder<A>(buffer, attachment, handler));
                return true;
            }

            return false;
        }

        boolean verify() {
            if (!fileDescriptor.isOpen()) {
                failed(new IOException("File has been closed"));
                return false;
            }

            if (offset < 0) {
                failed(new IllegalArgumentException("Position must be >= 0"));
                return false;
            }

            for (BufferHolder<A> buffer : buffers) {
                Throwable err = buffer.verify();
                if (err != null) {
                    failed(err);
                    return false;
                }
            }

            return true;
        }

        void failed(String error) {
            failed(new IOException(error));
        }

        void failed(Throwable t) {
            for (BufferHolder<A> buffer : buffers) {
                buffer.failed(t);
            }
        }

        int totLength() {
            int ret = 0;
            for (BufferHolder<A> buffer : buffers) {
                ret += buffer.limit();
            }

            return ret;
        }

        void complete() {
            assert !completed : "Request already completed";
            completed = true;

            if (error != null) {
                failed(error);
                return;
            }

            assert lengthRead >= 0 : String.format("Read negative length: %d", lengthRead);
            assert lengthRead <= totLength() : String.format("Read more than requested: %d > %d",
                    lengthRead, totLength());

            int remaining = lengthRead;
            for (BufferHolder<A> buffer : buffers) {
                int pos = remaining > 0 ? Math.min(buffer.limit(), remaining) : 0;
                buffer.completed(pos);
                remaining -= pos;
            }
        }

        @Override
        public String toString() {
            StringBuilder str = new StringBuilder();
            str.append(path).append(" (").append(fileDescriptor.intValue()).append("), ");
            str.append("Slot: ").append(slot).append(", ");
            str.append("Offset: ").append(offset).append(", ");
            str.append("Num buffers: ").append(buffers.size()).append(" [");
            for (BufferHolder<A> buffer : buffers) {
                str.append(buffer.buffer.limit()).append(", ");
            }
            str.append("]");
            return str.toString();
        }
    }
}
