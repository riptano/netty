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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileLock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import io.netty.channel.unix.FileDescriptor;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import static io.netty.channel.epoll.AIOContext.Batch;

public class AIOEpollFileChannel extends AsynchronousFileChannel {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AIOEpollFileChannel.class);

    private final File fileObject;
    private final FileDescriptor file;
    private final EpollEventLoop epollEventLoop;
    private final boolean isDirect;

    public AIOEpollFileChannel(File file, EpollEventLoop eventLoop, int flags) throws IOException {
        this.fileObject = file;

        if (flags != 0 && flags != FileDescriptor.O_DIRECT) {
            throw new IllegalArgumentException("Only supports read-only files");
        }
        this.file = FileDescriptor.from(file, flags);
        this.epollEventLoop = eventLoop;
        this.isDirect = flags == FileDescriptor.O_DIRECT;
    }

    public EpollEventLoop getEpollEventLoop() {
        return epollEventLoop;
    }

    public int getFd() {
        return file.intValue();
    }

    File getFileObject() {
        return fileObject;
    }

    FileDescriptor getFile() {
        return file;
    }

    public boolean isDirect() {
        return isDirect;
    }

    public long size() throws IOException {
        return file.length();
    }

    public AsynchronousFileChannel truncate(long size) throws IOException {
        throw new UnsupportedOperationException();
    }

    public void force(boolean metaData) throws IOException {
        throw new UnsupportedOperationException();
    }

    public <A> void lock(long position, long size, boolean shared, A attachment,
                         CompletionHandler<FileLock, ? super A> handler) {
        throw new UnsupportedOperationException();
    }

    public Future<FileLock> lock(long position, long size, boolean shared) {
        throw new UnsupportedOperationException();
    }

    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        throw new UnsupportedOperationException();
    }

    public <A> Batch<A> newBatch() {
        return newBatch(true);
    }

    public <A> Batch<A> newBatch(boolean vectored) {
        return new Batch<A>(this, vectored);
    }

    public <A> void read(final Batch<A> batch) {
        assert epollEventLoop.aioContext != null : "No AIO for the event loop of this channel";
        read(batch, this.epollEventLoop);
    }

    public <A> void read(final Batch<A> batch, final EpollEventLoop epollEventLoop) {
        if (batch.numRequests() == 0) {
            return; // nothing to read
        }

        if (!batch.verify()) {
            return;
        }

        Runnable action = new Runnable() {
            public void run() {
                epollEventLoop.aioContext.read(batch);
            }
        };

        if (epollEventLoop.inEventLoop()) {
            action.run();
        } else {
            epollEventLoop.submit(action);
        }
    }

    public <A> void read(final ByteBuffer dst, final long position, final A attachment,
                         final CompletionHandler<Integer, ? super A> handler) {
        read(dst, position, attachment, handler, this.epollEventLoop);
    }

    public <A> void read(final ByteBuffer dst, final long position, final A attachment,
                         final CompletionHandler<Integer, ? super A> handler,
                         final EpollEventLoop epollEventLoop) {

        Batch<A> batch = newBatch();
        read(batch.add(position, dst, attachment, handler), epollEventLoop);
    }

    public Future<Integer> read(ByteBuffer dst, long position) {
        final CompletableFuture<Integer> future = new CompletableFuture<Integer>();
        read(dst, position, null, new CompletionHandler<Integer, Object>() {
            public void completed(Integer result, Object attachment) {
                future.complete(result);
            }

            public void failed(Throwable exc, Object attachment) {
                future.completeExceptionally(exc);
            }
        });

        return future;
    }

    public <A> void write(ByteBuffer src, long position, A attachment, CompletionHandler<Integer, ? super A> handler) {
        throw new UnsupportedOperationException();
    }

    public Future<Integer> write(ByteBuffer src, long position) {
       throw new UnsupportedOperationException();
    }

    public boolean isOpen() {
        return file.isOpen();
    }

    public void close() {
        Runnable close = new Runnable() {
            public void run() {
                if (!isOpen()) {
                    return;
                }

                try {
                    file.close();
                } catch (IOException e) {
                    logger.error("Error closing file", e);
                }
            }
        };

        if (epollEventLoop.inEventLoop()) {
            close.run();
        } else {
            epollEventLoop.submit(close);
        }
    }
}
