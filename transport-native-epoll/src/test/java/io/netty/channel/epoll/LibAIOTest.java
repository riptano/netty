/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.epoll;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.CompletionHandler;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.util.SuppressForbidden;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.unix.FileDescriptor;
import io.netty.util.internal.PlatformDependent;
import sun.misc.SharedSecrets;
import sun.nio.ch.DirectBuffer;


public class LibAIOTest {

    private static Random random = new Random();
    private static final int SECTOR_SIZE = 512;

    @BeforeClass
    public static void setup() {
        System.setProperty("netty.aio.maxConcurrency", "1");
        random.setSeed(15414594750175L);
    }

    @Test
    public void globalFlagTest() {
        Epoll.ensureAvailability();
        Assert.assertTrue(Aio.isAvailable());
    }

    @Test
    @SuppressForbidden(reason = "to test a simple native aio read")
    public void nativeReadTestSingleRequest() throws IOException, InterruptedException {
        FileInputStream fileReader = new FileInputStream(createFile("netty-aio-single", 65536));

        final int maxConcurrency = 8;
        AIOContext aio = Native.createAIOContext(new AIOContext.Config(maxConcurrency, 1));

        try {
            for (int slot = 0; slot < maxConcurrency; slot++) {

                ByteBuffer buf = allocateAlignedByteBuffer(65536, 512);

                int fileFd = SharedSecrets.getJavaIOFileDescriptorAccess().get(fileReader.getFD());
                Native.submitAIORead(aio, aio.getEventFd().intValue(), fileFd,
                        new AIOContext.Request(slot, 0, buf, fileReader.toString(), new FileDescriptor(fileFd)));

                long[] result = new long[2];
                int numEvents = Native.getAIOEvents(aio, result);

                Assert.assertEquals(1, numEvents);
                Assert.assertEquals(slot, result[0]);

                checkContent(fileReader, buf, 0, 65536);

                freeAlignedByteBuffer(buf);
            }
        } finally {
            aio.destroy();
            fileReader.close();
        }
    }

    @Test
    @SuppressForbidden(reason = "to test multiple native aio reads")
    public void nativeReadTestMultipleRequests() throws IOException, InterruptedException {
        final int maxConcurrency = 8;
        final int fileSize = 524288;

        FileInputStream fileReader = new FileInputStream(createFile("netty-aio-multiple", fileSize));
        AIOContext aio = Native.createAIOContext(new AIOContext.Config(maxConcurrency, 1));

        final int numTrials = 5000;
        final int eventFd = aio.getEventFd().intValue();
        final int fileFd = SharedSecrets.getJavaIOFileDescriptorAccess().get(fileReader.getFD());

        try {
            for (int t = 0; t < numTrials; t++) {
                int numRequests = 1 + random.nextInt(maxConcurrency - 1);

                Map<Integer, AIOContext.Request<Void>> requests = new HashMap<Integer, AIOContext.Request<Void>>();
                for (int r = 0; r < numRequests; r++) {
                    int numBuffers = 1 + random.nextInt(8);
                    int bufferSize = (int) Math.max(SECTOR_SIZE,
                            roundDownToBlockSize(random.nextInt(fileSize / numBuffers)));
                    Assert.assertTrue(String.format("File size %d exceeded: %d buffers of size %d",
                            fileSize, numBuffers, bufferSize),
                            (numBuffers * bufferSize) <= fileSize);
                    long offset = roundDownToBlockSize(random.nextInt(fileSize - (numBuffers * bufferSize)));

                    List<ByteBuffer> buffers = new ArrayList<ByteBuffer>(numBuffers);
                    for (int i = 0; i < numBuffers; i++) {
                        buffers.add(allocateAlignedByteBuffer(bufferSize, SECTOR_SIZE));
                    }

                    AIOContext.Request<Void> request = new AIOContext.Request<Void>(r, offset, fileReader.toString(),
                            new FileDescriptor(fileFd));
                    for (int i = 0; i < buffers.size(); i++) {
                        Assert.assertTrue(request.maybeAdd(offset + i * bufferSize, buffers.get(i), null, null));
                    }

                    requests.put(r, request);
                }

                //System.out.println("Submitting requests: " + requests.values());
                Native.submitAIOReads(aio, eventFd, fileFd, requests.values());

                long[] result = new long[numRequests * 2];
                int numEvents = Native.getAIOEvents(aio, result);
                Assert.assertEquals(numRequests, numEvents);

                for (int i = 0; i < numRequests; i++) {
                    int slot = (int) result[i];
                    int totRead = (int) result[numRequests + i];

                    AIOContext.Request<?> request = requests.get(slot);
                    Assert.assertNotNull(request);

                    Assert.assertEquals(request.totLength(), totRead);

                    long offset = request.offset;
                    for (AIOContext.BufferHolder<?> buffer : request.buffers) {
                        checkContent(fileReader, buffer.buffer, offset, buffer.limit());
                        offset += buffer.limit();

                        freeAlignedByteBuffer(buffer.buffer);
                    }
                }
            }
        } finally {
            aio.destroy();
            fileReader.close();
        }
    }

//    private static int roundUpToBlockSize(int size) {
//        return (size + SECTOR_SIZE - 1) & -SECTOR_SIZE;
//    }

    private static long roundDownToBlockSize(long size) {
        return size & -SECTOR_SIZE;
    }

    private File createFile(String fileName, int size) throws IOException {
        File file = File.createTempFile(fileName, null);
        file.deleteOnExit();

        ByteBuffer data = ByteBuffer.allocate(size);
        random.nextBytes(data.array());

        FileChannel channel = new FileOutputStream(file, true).getChannel();
        channel.write(data);
        channel.force(false);
        channel.close();

        return file;
    }

    private void checkContent(FileInputStream fileReader, ByteBuffer buffer, long offset, int length)
            throws IOException {
        FileChannel channel = fileReader.getChannel();
        ByteBuffer expected = ByteBuffer.allocate(length);
        channel.read(expected, offset);

        byte[] output = new byte[length];
        buffer.get(output);

        Assert.assertArrayEquals(expected.array(), output);
    }

    @Test
    public void epollTriggeredSingleThreadReadTest() throws IOException, InterruptedException, ExecutionException {
        Collection<Exception> errors = epollTriggeredReadTest(1024, 1, 1, new AIOContext.Config(8, 65536));
        Assert.assertTrue(errors.isEmpty());
    }

    @Test
    public void epollTriggeredReadTest() throws IOException, InterruptedException, ExecutionException {
        Collection<Exception> errors = epollTriggeredReadTest(1024, 16, 8, new AIOContext.Config(128, 65536));
        Assert.assertTrue(errors.isEmpty());
    }

    @Test
    public void epollTriggeredReadTestHigherConcurrency() throws IOException, InterruptedException, ExecutionException {
        Collection<Exception> errors = epollTriggeredReadTest(1024, 16, 8, new AIOContext.Config(16, 65536));
        Assert.assertTrue(errors.isEmpty());
    }

    @Test
    public void epollTriggeredReadTestLowMaxPending() throws IOException, InterruptedException, ExecutionException {
        Collection<Exception> errors = epollTriggeredReadTest(1024, 8, 4, new AIOContext.Config(16, 32));
        Assert.assertFalse(errors.isEmpty()); // some requests should have failed with "Too many pending requests"
        for (Exception error : errors) {
            Assert.assertNotNull(error);
            Assert.assertTrue(error.getMessage().contains("Too many pending requests"));
        }
    }

    private Collection<Exception> epollTriggeredReadTest(final int trials,
                                                         final int numThreads,
                                                         final int numLoops,
                                                         final AIOContext.Config aio)
            throws IOException, InterruptedException {
        final int fileSize = 65536;
        final EventLoopGroup group = new EpollEventLoopGroup(numLoops, aio);
        final EpollEventLoop[] loops = new EpollEventLoop[numLoops];
        for (int i = 0; i < numLoops; i++) {
            loops[i] = (EpollEventLoop) group.next();
        }

        final File file = createFile("netty-aio-epoll", fileSize);
        final FileInputStream fileReader = new FileInputStream(file);

        final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        ExecutorService es = Executors.newFixedThreadPool(numThreads);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final BlockingQueue<Exception> errors = new LinkedBlockingQueue<Exception>();

        for (int i = 0; i < numThreads; i++) {
            final int tid = i;
            es.submit(new Runnable() {
                public void run() {
                    int idx = 0;
                    try {
                        final EpollEventLoop loop = loops[tid % numLoops];

                        AIOEpollFileChannel fc = new AIOEpollFileChannel(file, loop,
                                FileDescriptor.O_RDONLY | FileDescriptor.O_DIRECT);
                        List<CompletableFuture> futures = new ArrayList<CompletableFuture>();
                        final AtomicInteger totRead = new AtomicInteger(0);
                        int totExpected = 0;

                        for (int i = 0; i < trials; i++) {
                            final boolean vectored = random.nextBoolean();
                            final AIOContext.Batch<Pair<Long, ByteBuffer>> batch = new AIOContext.Batch(fc, vectored);
                            final int numBuffers = 1 + random.nextInt(8);
                            final int bufferSize = (int) Math.max(SECTOR_SIZE,
                                    roundDownToBlockSize(random.nextInt(fileSize / numBuffers)));
                            Assert.assertTrue(String.format("File size %d exceeded: %d buffers of size %d",
                                    fileSize, numBuffers, bufferSize),
                                    (numBuffers * bufferSize) <= fileSize);
                            long offset = roundDownToBlockSize(random.nextInt(fileSize -
                                                                                 (numBuffers * bufferSize)));

                            totExpected += numBuffers * bufferSize;
                            final List<ByteBuffer> buffers = new ArrayList<ByteBuffer>(numBuffers);
                            for (int b = 0; b < numBuffers; b++) {
                                buffers.add(allocateAlignedByteBuffer(bufferSize, SECTOR_SIZE));
                            }

                            for (ByteBuffer buffer : buffers) {
                                final CompletableFuture fut = new CompletableFuture();
                                futures.add(fut);
                                batch.add(offset, buffer, Pair.of(offset, buffer),
                                        new CompletionHandler<Integer, Pair<Long, ByteBuffer>>() {

                                    @Override
                                    public void completed(Integer read, Pair<Long, ByteBuffer> attachment) {

                                        try {
                                            long pos = attachment.getLeft();
                                            ByteBuffer buffer = attachment.getRight();
                                            buffer.flip();
                                            checkContent(fileReader, buffer, pos, buffer.limit());
                                            freeAlignedByteBuffer(buffer);
                                            totRead.addAndGet(read);
                                            fut.complete(null);

                                        } catch (Throwable t) {
                                            fut.completeExceptionally(t);
                                        }
                                    }

                                    @Override
                                    public void failed(Throwable exc, Pair<Long, ByteBuffer> attachment) {
                                        fut.completeExceptionally(exc);
                                    }
                                });

                                offset += bufferSize;
                            }

                            if (!vectored) {
                                Assert.assertEquals(numBuffers, batch.numRequests());
                            }

                            fc.read(batch);
                        }

                        try {
                            CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])).join();
                            Assert.assertEquals(totExpected, totRead.get());
                        } catch (Exception ex) {
                            errors.add(ex); // exceptions when completing the future are handled by the callers
                        }

                    } catch (Throwable t) {
                        System.err.println("Thread: " + tid + ", Index: " + idx);
                        t.printStackTrace();
                        err.set(t);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        latch.await();
        Assert.assertNull(err.get());
        group.shutdownGracefully();

        return errors;
    }

    @Test
    public void epollTriggeredSingleReadTest() throws Exception {
        final int fileSize = 65536;
        final int numTrials = 1024;
        EventLoopGroup group = new EpollEventLoopGroup(1, new AIOContext.Config(128, 65536));
        EpollEventLoop loop = (EpollEventLoop) group.next();

        final File file = createFile("netty-aio-epoll-single", fileSize);
        final FileInputStream fileReader = new FileInputStream(file);

        final AIOEpollFileChannel fc = new AIOEpollFileChannel(file, loop,
                FileDescriptor.O_RDONLY | FileDescriptor.O_DIRECT);

        for (int i = 0; i < numTrials; i++) {
            final int bufferSize = (int) Math.max(SECTOR_SIZE,
                    roundDownToBlockSize(random.nextInt(fileSize)));
            final long offset = roundDownToBlockSize(random.nextInt(fileSize - bufferSize));
            final ByteBuffer buffer = allocateAlignedByteBuffer(bufferSize, SECTOR_SIZE);

            fc.read(buffer, offset).get();
            buffer.flip();
            checkContent(fileReader, buffer, offset, bufferSize);
        }
    }

    @Test
    public void epollTriggeredReadLargerThanFileTest() throws Exception {
        final int fileSize = 10000;
        EventLoopGroup group = new EpollEventLoopGroup(1, new AIOContext.Config(128, 65536));
        EpollEventLoop loop = (EpollEventLoop) group.next();

        final File file = createFile("netty-aio-epoll-single", fileSize);
        final FileInputStream fileReader = new FileInputStream(file);

        final AIOEpollFileChannel fc = new AIOEpollFileChannel(file, loop,
                FileDescriptor.O_RDONLY | FileDescriptor.O_DIRECT);

        final int bufferSize = 4096;
        long offset = 0;
        final int numBuffers = 4; // 2 full buffers, 1 partially filled, 1 empty
        final List<ByteBuffer> buffers = new ArrayList<ByteBuffer>(numBuffers);
        for (int i = 0; i < numBuffers; i++) {
            buffers.add(allocateAlignedByteBuffer(bufferSize, SECTOR_SIZE));
        }

        final CountDownLatch latch = new CountDownLatch(buffers.size());
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>(null);

        AIOContext.Batch<Pair<Long, ByteBuffer>> batch = fc.newBatch();
        for (ByteBuffer buffer : buffers) {
            batch.add(offset, buffer, Pair.of(offset, buffer),
                    new CompletionHandler<Integer, Pair<Long, ByteBuffer>>() {
                @Override
                public void completed(Integer read, Pair<Long, ByteBuffer> attachment) {
                    try {
                        long pos = attachment.getLeft();
                        ByteBuffer buff = attachment.getRight();
                        buff.flip();
                        checkContent(fileReader, buff, pos, buff.limit());
                        latch.countDown();
                    } catch (Throwable t) {
                        failed(t, attachment);
                    }
                }

                @Override
                public void failed(Throwable exc, Pair<Long, ByteBuffer> attachment) {
                    err.set(exc);
                    latch.countDown();
                }
            });
            offset += bufferSize;
        }

        fc.read(batch);
        latch.await();
        Assert.assertNull(err.get());
    }

    @Test
    public void epollTriggeredReadInvalidOffsetTest() throws Exception {
        EventLoopGroup group = new EpollEventLoopGroup(1, new AIOContext.Config(128, 65536));
        EpollEventLoop loop = (EpollEventLoop) group.next();

        final int fileSize = 1000;
        final File file = createFile("netty-aio-epoll-single", fileSize);
        final AIOEpollFileChannel fc = new AIOEpollFileChannel(file, loop,
                FileDescriptor.O_RDONLY | FileDescriptor.O_DIRECT);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>(null);

        ByteBuffer buffer = allocateAlignedByteBuffer(4096, SECTOR_SIZE);
        AIOContext.Batch<ByteBuffer> batch = fc.newBatch();
        // set an offset not aligned with SECTOR_SIZE so that the read will fail with EINVAL
        batch.add(123, buffer, buffer,
                new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer read, ByteBuffer attachment) {
                        latch.countDown();
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment) {
                        err.set(exc);
                        latch.countDown();
                    }
                });

        fc.read(batch);
        latch.await();
        Assert.assertNotNull(err.get());
        System.out.println(err.get().getMessage());
    }

    @Test
    public void epollTriggeredReadInvalidBufferSizeTest() throws Exception {
        EventLoopGroup group = new EpollEventLoopGroup(1, new AIOContext.Config(128, 65536));
        EpollEventLoop loop = (EpollEventLoop) group.next();

        final int fileSize = 1000;
        final File file = createFile("netty-aio-epoll-single", fileSize);
        final AIOEpollFileChannel fc = new AIOEpollFileChannel(file, loop,
                FileDescriptor.O_RDONLY | FileDescriptor.O_DIRECT);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>(null);

        // create a buffer with a size not aligned with SECTOR_SIZE so that the read will fail with EINVAL
        ByteBuffer buffer = allocateAlignedByteBuffer(123, SECTOR_SIZE);
        AIOContext.Batch<ByteBuffer> batch = fc.newBatch();
        batch.add(0, buffer, buffer,
                new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer read, ByteBuffer attachment) {
                        latch.countDown();
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment) {
                        err.set(exc);
                        latch.countDown();
                    }
                });

        fc.read(batch);
        latch.await();
        Assert.assertNotNull(err.get());
        System.out.println(err.get().getMessage());
    }

    static ByteBuffer allocateAlignedByteBuffer(int capacity, long align) {
        // Power of 2 --> single bit, none power of 2 alignments are not allowed.
        if (Long.bitCount(align) != 1) {
            throw new IllegalArgumentException("Alignment must be a power of 2");
        }
        // We over allocate by the alignment so we know we can have a large enough aligned
        // block of memory to use. Also set order to native while we are here.
        ByteBuffer buffy = PlatformDependent.allocateDirectNoCleaner((int) (capacity + align));
        long address = PlatformDependent.directBufferAddress(buffy);
        // check if we got lucky and the address is already aligned
        if ((address & (align - 1)) == 0) {
            // set the new limit to intended capacity
            buffy.limit(capacity);
            // the slice is now an aligned buffer of the required capacity
            return buffy.slice().order(ByteOrder.nativeOrder());
        } else {
            // we need to shift the start position to an aligned address --> address + (align - (address % align))
            // the modulo replacement with the & trick is valid for power of 2 values only
            int newPosition = (int) (align - (address & (align - 1)));
            // change the position
            buffy.position(newPosition);
            int newLimit = newPosition + capacity;
            // set the new limit to accomodate offset + intended capacity
            buffy.limit(newLimit);
            // the slice is now an aligned buffer of the required capacity
            return buffy.slice().order(ByteOrder.nativeOrder());
        }
    }

    @SuppressForbidden(reason = "libaio")
    static void freeAlignedByteBuffer(ByteBuffer buffer) {
        PlatformDependent.freeDirectNoCleaner((ByteBuffer) ((DirectBuffer) buffer).attachment());
    }
}
