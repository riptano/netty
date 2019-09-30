/*
 * Copyright 2013 The Netty Project
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
#define _GNU_SOURCE
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <libaio.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/timerfd.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <sys/utsname.h>
#include <stddef.h>
#include <limits.h>
#include <inttypes.h>
#include <link.h>
#include <time.h>

#include "netty_epoll_linuxsocket.h"
#include "netty_unix_buffer.h"
#include "netty_unix_errors.h"
#include "netty_unix_filedescriptor.h"
#include "netty_unix_jni.h"
#include "netty_unix_limits.h"
#include "netty_unix_socket.h"
#include "netty_unix_util.h"

// TCP_FASTOPEN is defined in linux 3.7. We define this here so older kernels can compile.
#ifndef TCP_FASTOPEN
#define TCP_FASTOPEN 23
#endif
// optional
extern int epoll_create1(int flags) __attribute__((weak));

#ifdef IO_NETTY_SENDMMSG_NOT_FOUND
extern int sendmmsg(int sockfd, struct mmsghdr* msgvec, unsigned int vlen, unsigned int flags) __attribute__((weak));

#ifndef __USE_GNU
struct mmsghdr {
    struct msghdr msg_hdr;  /* Message header */
    unsigned int  msg_len;  /* Number of bytes transmitted */
};
#endif
#endif

// Those are initialized in the init(...) method and cached for performance reasons
static jfieldID packetAddrFieldId = NULL;
static jfieldID packetScopeIdFieldId = NULL;
static jfieldID packetPortFieldId = NULL;
static jfieldID packetMemoryAddressFieldId = NULL;
static jfieldID packetCountFieldId = NULL;

// util methods
static int getSysctlValue(const char * property, int* returnValue) {
    int rc = -1;
    FILE *fd=fopen(property, "r");
    if (fd != NULL) {
      char buf[32] = {0x0};
      if (fgets(buf, 32, fd) != NULL) {
        *returnValue = atoi(buf);
        rc = 0;
      }
      fclose(fd);
    }
    return rc;
}

static inline jint epollCtl(JNIEnv* env, jint efd, int op, jint fd, jint flags) {
    uint32_t events = flags;
    struct epoll_event ev = {
        .data.fd = fd,
        .events = events
    };

    return epoll_ctl(efd, op, fd, &ev);
}
// JNI Registered Methods Begin
static jint netty_epoll_native_eventFd(JNIEnv* env, jclass clazz) {
    jint eventFD = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);

    if (eventFD < 0) {
        netty_unix_errors_throwChannelExceptionErrorNo(env, "eventfd() failed: ", errno);
    }
    return eventFD;
}

static jint netty_epoll_native_timerFd(JNIEnv* env, jclass clazz) {
    jint timerFD = timerfd_create(CLOCK_MONOTONIC, TFD_CLOEXEC | TFD_NONBLOCK);

    if (timerFD < 0) {
        netty_unix_errors_throwChannelExceptionErrorNo(env, "timerfd_create() failed: ", errno);
    }
    return timerFD;
}

static void netty_epoll_native_eventFdWrite(JNIEnv* env, jclass clazz, jint fd, jlong value) {
    jint eventFD = eventfd_write(fd, (eventfd_t) value);

    if (eventFD < 0) {
        netty_unix_errors_throwChannelExceptionErrorNo(env, "eventfd_write() failed: ", errno);
    }
}

static jlong netty_epoll_native_eventFdRead(JNIEnv* env, jclass clazz, jint fd) {
    uint64_t eventfd_t;

    int err = eventfd_read(fd, &eventfd_t);
    if (err != 0) {
        if (errno != EAGAIN && errno != -EAGAIN){
            // something is serious wrong
            netty_unix_errors_throwRuntimeExceptionErrorNo(env, "eventfd_read() failed: ", errno);
        }
        return 0;
    }
	return (long) eventfd_t;
}

static void netty_epoll_native_timerFdRead(JNIEnv* env, jclass clazz, jint fd) {
    uint64_t timerFireCount;

    if (read(fd, &timerFireCount, sizeof(uint64_t)) < 0) {
        // it is expected that this is only called where there is known to be activity, so this is an error.
        netty_unix_errors_throwChannelExceptionErrorNo(env, "read() failed: ", errno);
    }
}

static jint netty_epoll_native_epollCreate(JNIEnv* env, jclass clazz) {
    jint efd;
    if (epoll_create1) {
        efd = epoll_create1(EPOLL_CLOEXEC);
    } else {
        // size will be ignored anyway but must be positive
        efd = epoll_create(126);
    }
    if (efd < 0) {
        int err = errno;
        if (epoll_create1) {
            netty_unix_errors_throwChannelExceptionErrorNo(env, "epoll_create1() failed: ", err);
        } else {
            netty_unix_errors_throwChannelExceptionErrorNo(env, "epoll_create() failed: ", err);
        }
        return efd;
    }
    if (!epoll_create1) {
        if (fcntl(efd, F_SETFD, FD_CLOEXEC) < 0) {
            int err = errno;
            close(efd);
            netty_unix_errors_throwChannelExceptionErrorNo(env, "fcntl() failed: ", err);
            return err;
        }
    }
    return efd;
}

static jint netty_epoll_native_epollWait0(JNIEnv* env, jclass clazz, jint efd, jlong address, jint len, jint timerFd, jint tvSec, jint tvNsec) {
    struct epoll_event *ev = (struct epoll_event*) (intptr_t) address;
    int result, err;

    if (tvSec == 0 && tvNsec == 0) {
        // Zeros = poll (aka return immediately).
        do {
            result = epoll_wait(efd, ev, len, 0);
            if (result >= 0) {
                return result;
            }
        } while((err = errno) == EINTR);
    } else {
        // only reschedule the timer if there is a newer event.
        // -1 is a special value used by EpollEventLoop.
        if (tvSec != ((jint) -1) && tvNsec != ((jint) -1)) {
            struct itimerspec ts;
            memset(&ts.it_interval, 0, sizeof(struct timespec));
            ts.it_value.tv_sec = tvSec;
            ts.it_value.tv_nsec = tvNsec;
            if (timerfd_settime(timerFd, 0, &ts, NULL) < 0) {
                netty_unix_errors_throwChannelExceptionErrorNo(env, "timerfd_settime() failed: ", errno);
                return -1;
            }
        }
        do {
            result = epoll_wait(efd, ev, len, -1);
            if (result > 0) {
                // Detect timeout, and preserve the epoll_wait API.
                if (result == 1 && ev[0].data.fd == timerFd) {
                    // We assume that timerFD is in ET mode. So we must consume this event to ensure we are notified
                    // of future timer events because ET mode only notifies a single time until the event is consumed.
                    uint64_t timerFireCount;
                    // We don't care what the result is. We just want to consume the wakeup event and reset ET.
                    result = read(timerFd, &timerFireCount, sizeof(uint64_t));
                    return 0;
                }
                return result;
            }
        } while((err = errno) == EINTR);
    }
    return -err;
}

static inline void cpu_relax() {
#if defined(__x86_64__)
    asm volatile("pause\n": : :"memory");
#endif
}

static jint netty_epoll_native_epollBusyWait0(JNIEnv* env, jclass clazz, jint efd, jlong address, jint len) {
    struct epoll_event *ev = (struct epoll_event*) (intptr_t) address;
    int result, err;

    // Zeros = poll (aka return immediately).
    do {
        result = epoll_wait(efd, ev, len, 0);
        if (result == 0) {
            // Since we're always polling epoll_wait with no timeout,
            // signal CPU that we're in a busy loop
            cpu_relax();
        }

        if (result >= 0) {
            return result;
        }
    } while((err = errno) == EINTR);

    return -err;
}

static jint netty_epoll_native_epollCtlAdd0(JNIEnv* env, jclass clazz, jint efd, jint fd, jint flags) {
    int res = epollCtl(env, efd, EPOLL_CTL_ADD, fd, flags);
    if (res < 0) {
        return -errno;
    }
    return res;
}
static jint netty_epoll_native_epollCtlMod0(JNIEnv* env, jclass clazz, jint efd, jint fd, jint flags) {
    int res = epollCtl(env, efd, EPOLL_CTL_MOD, fd, flags);
    if (res < 0) {
        return -errno;
    }
    return res;
}

static jint netty_epoll_native_epollCtlDel0(JNIEnv* env, jclass clazz, jint efd, jint fd) {
    // Create an empty event to workaround a bug in older kernels which can not handle NULL.
    struct epoll_event event = { 0 };
    int res = epoll_ctl(efd, EPOLL_CTL_DEL, fd, &event);
    if (res < 0) {
        return -errno;
    }
    return res;
}

static jint netty_epoll_native_sendmmsg0(JNIEnv* env, jclass clazz, jint fd, jobjectArray packets, jint offset, jint len) {
    struct mmsghdr msg[len];
    struct sockaddr_storage addr[len];
    socklen_t addrSize;
    int i;

    memset(msg, 0, sizeof(msg));

    for (i = 0; i < len; i++) {

        jobject packet = (*env)->GetObjectArrayElement(env, packets, i + offset);
        jbyteArray address = (jbyteArray) (*env)->GetObjectField(env, packet, packetAddrFieldId);
        jint scopeId = (*env)->GetIntField(env, packet, packetScopeIdFieldId);
        jint port = (*env)->GetIntField(env, packet, packetPortFieldId);

        if (netty_unix_socket_initSockaddr(env, address, scopeId, port, &addr[i], &addrSize) == -1) {
            return -1;
        }

        msg[i].msg_hdr.msg_name = &addr[i];
        msg[i].msg_hdr.msg_namelen = addrSize;

        msg[i].msg_hdr.msg_iov = (struct iovec*) (intptr_t) (*env)->GetLongField(env, packet, packetMemoryAddressFieldId);
        msg[i].msg_hdr.msg_iovlen = (*env)->GetIntField(env, packet, packetCountFieldId);;
    }

    ssize_t res;
    int err;
    do {
       res = sendmmsg(fd, msg, len, 0);
       // keep on writing if it was interrupted
    } while (res == -1 && ((err = errno) == EINTR));

    if (res < 0) {
        return -err;
    }
    return (jint) res;
}

static jstring netty_epoll_native_kernelVersion(JNIEnv* env, jclass clazz) {
    struct utsname name;

    int res = uname(&name);
    if (res == 0) {
        return (*env)->NewStringUTF(env, name.release);
    }
    netty_unix_errors_throwRuntimeExceptionErrorNo(env, "uname() failed: ", errno);
    return NULL;
}

static jboolean netty_epoll_native_isSupportingSendmmsg(JNIEnv* env, jclass clazz) {
    // Use & to avoid warnings with -Wtautological-pointer-compare when sendmmsg is
    // not weakly defined.
    if (&sendmmsg != NULL) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean netty_epoll_native_isSupportingTcpFastopen(JNIEnv* env, jclass clazz) {
    int fastopen = 0;
    getSysctlValue("/proc/sys/net/ipv4/tcp_fastopen", &fastopen);
    if (fastopen > 0) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jint netty_epoll_native_epollet(JNIEnv* env, jclass clazz) {
    return EPOLLET;
}

static jint netty_epoll_native_epollin(JNIEnv* env, jclass clazz) {
    return EPOLLIN;
}

static jint netty_epoll_native_epollout(JNIEnv* env, jclass clazz) {
    return EPOLLOUT;
}

static jint netty_epoll_native_epollrdhup(JNIEnv* env, jclass clazz) {
    return EPOLLRDHUP;
}

static jint netty_epoll_native_epollerr(JNIEnv* env, jclass clazz) {
    return EPOLLERR;
}

static jint netty_epoll_native_efdnonblock(JNIEnv* env, jclass clazz) {
    return EFD_NONBLOCK;
}

static jint netty_epoll_native_eagain(JNIEnv* env, jclass clazz) {
    return EAGAIN;
}

static jint netty_epoll_native_sizeofEpollEvent(JNIEnv* env, jclass clazz) {
    return sizeof(struct epoll_event);
}

static jint netty_epoll_native_offsetofEpollData(JNIEnv* env, jclass clazz) {
    return offsetof(struct epoll_event, data);
}

static jint netty_epoll_native_splice0(JNIEnv* env, jclass clazz, jint fd, jlong offIn, jint fdOut, jlong offOut, jlong len) {
    ssize_t res;
    int err;
    loff_t off_in = (loff_t) offIn;
    loff_t off_out = (loff_t) offOut;

    loff_t* p_off_in = off_in >= 0 ? &off_in : NULL;
    loff_t* p_off_out = off_in >= 0 ? &off_out : NULL;

    do {
       res = splice(fd, p_off_in, fdOut, p_off_out, (size_t) len, SPLICE_F_NONBLOCK | SPLICE_F_MOVE);
       // keep on splicing if it was interrupted
    } while (res == -1 && ((err = errno) == EINTR));

    if (res < 0) {
        return -err;
    }
    return (jint) res;
}

static jint netty_epoll_native_tcpMd5SigMaxKeyLen(JNIEnv* env, jclass clazz) {
    struct tcp_md5sig md5sig;

    // Defensive size check
    if (sizeof(md5sig.tcpm_key) < TCP_MD5SIG_MAXKEYLEN) {
        return sizeof(md5sig.tcpm_key);
    }

    return TCP_MD5SIG_MAXKEYLEN;
}

//LibAIO methods

// the maximum number of buffers for a vectored IO request
#define MAX_NUM_BUFFERS_PER_REQUEST 8

typedef struct netty_iocb
{
    struct iocb iocb;
    int slot; // -1 when not in use, the index in the array in netty_io_context when in use
    struct iovec iovec[MAX_NUM_BUFFERS_PER_REQUEST]; // for vectored requests, unused otherwise
} netty_iocb_t;

typedef struct netty_io_context
{
    io_context_t aio;
    int concurrency;
    netty_iocb_t* requests;
} netty_io_context_t;


static jlong netty_epoll_native_createAIOContext0(JNIEnv* env, jclass clazz, jint concurrency) {

    if (concurrency <= 0 || concurrency > 1024) {
        netty_unix_errors_throwRuntimeException(env, "invalid concurrency level, it should be > 0 and <= 1024.");
        return JNI_ERR;
    }

    netty_io_context_t* ctx = malloc(sizeof(netty_io_context_t));
    ctx->aio = 0;
    ctx->concurrency = concurrency;
    ctx->requests = calloc(concurrency, sizeof(netty_iocb_t));

    int i;
    for (i = 0; i < ctx->concurrency; i++) {
        ctx->requests[i].slot = -1;
    }

    int r;
    r = io_setup(concurrency, &(ctx->aio));
    if (r != 0) {
        netty_unix_errors_throwChannelExceptionErrorNo(env, "io_setup() failed: ", -r);
    }

    return (long) ctx;
}

static void netty_epoll_native_destroyAIOContext0(JNIEnv* env, jclass clazz, jlong ctxaddr) {

    netty_io_context_t* ctx = (netty_io_context_t*) ctxaddr;

    free(ctx->requests);
    free(ctx);
}

static void netty_epoll_native_submitAIORead0(JNIEnv* env, jclass clazz, jlong ctxaddr, jint efd, jint fd,
                                              jint num_requests, jintArray in_slots, jlongArray in_offsets, // every request has a slot, an offset
                                              jintArray in_num_buffers, jlongArray in_buf_addresses, // a number of buffers K, K buffer addresses
                                              jlongArray in_buf_lengths) { // and K buffer lenghts

    netty_io_context_t* ctx = (netty_io_context_t*) ctxaddr;

    if (num_requests <= 0 || num_requests > ctx->concurrency) {
        netty_unix_errors_throwRuntimeException(env, "invalid number of requests, it should be > 0 and <= max concurrency.");
        return;
    }

    int* slots = (*env)->GetIntArrayElements(env, in_slots, NULL);
    long* offsets = (*env)->GetLongArrayElements(env, in_offsets, NULL);
    int* num_buffers = (*env)->GetIntArrayElements(env, in_num_buffers, NULL);
    long* buf_addresses = (*env)->GetLongArrayElements(env, in_buf_addresses, NULL);
    long* buf_lengths = (*env)->GetLongArrayElements(env, in_buf_lengths, NULL);

    int i, j;
    int nbuffers_processed = 0;

    //TODO - we could avoid this array allocation by using seq. slots or a max fixed concurrency limit
    struct iocb** iocbps = calloc(num_requests, sizeof(struct iocb*));

    //printf("Num requests: %d\n", num_requests);

    for(i = 0; i < num_requests; i++) {

        int slot = slots[i];
        if (slot < 0 || slot >= ctx->concurrency) {
            netty_unix_errors_throwRuntimeException(env, "invalid slot");
            goto error;
        }

        //printf("Processing req. nr. %d on slot %d\n", i, slot);

        netty_iocb_t* niocbp = &(ctx->requests[slot]);
        if (niocbp->slot != -1) {
            netty_unix_errors_throwRuntimeException(env, "selected slot already in use");
            goto error;
        }

        niocbp->slot = slot;

        struct iocb* iocbp = &niocbp->iocb;
        iocbps[i] = iocbp;

        long offset = offsets[i];
        int nbuffers = num_buffers[i];
        memset(niocbp->iovec, 0, sizeof(niocbp->iovec));

        // TODO - see if the case nbuffers == 1 can be treated with vectored IO too
        if (nbuffers == 1) {
            long buf_address = buf_addresses[nbuffers_processed];
            long length = buf_lengths[nbuffers_processed];

            //printf("Processing simple req with buffer %ld of length %ld\n", buf_address, length);

            if (buf_address & 511 != 0) {
               netty_unix_errors_throwRuntimeException(env, "buffer is not memory aligned");
               goto error;
            }

            io_prep_pread(iocbp, fd, (void *)buf_address, length, offset);
        }
        else {
            if (nbuffers <= 0 || nbuffers > MAX_NUM_BUFFERS_PER_REQUEST) {
                netty_unix_errors_throwRuntimeException(env, "invalid number of buffers, it should be > 0 and <= MAX_NUM_BUFFERS_PER_REQUEST, typically 8.");
               goto error;
            }

            //printf("Processing vectored request with %d buffers\n", nbuffers);

            for(j = 0; j < nbuffers; j++) {
                long buf_address = buf_addresses[nbuffers_processed + j];
                long length = buf_lengths[nbuffers_processed + j];

                //printf("...buffer %ld of length %ld\n", buf_address, length);

                if (buf_address & 511 != 0) {
                    netty_unix_errors_throwRuntimeException(env, "buffer is not memory aligned");
                    goto error;
                }

                niocbp->iovec[j].iov_base = (void *)buf_address;
                niocbp->iovec[j].iov_len = length;
            }

            io_prep_preadv(iocbp, fd, niocbp->iovec, nbuffers, offset);
        }

        nbuffers_processed += nbuffers;
        io_set_eventfd(iocbp, efd);
    }

    //printf("Submitting request\n");
    int r = io_submit(ctx->aio, num_requests, iocbps);
    if (r != num_requests) {
        //printf("io_submit() failed with %d\n", r);
        netty_unix_errors_throwChannelExceptionErrorNo(env, "io_submit() failed: ", -r);
        goto error;
    }

    goto cleanup;

error: // release the slots
    for(i = 0; i < num_requests; i++) {
        netty_iocb_t* niocbp = (netty_iocb_t *)iocbps[i];
        if (niocbp != NULL) {
            niocbp->slot = -1;
        }
    }

cleanup: // release temporary arrays

    free(iocbps); // can be freed after io_submit()

    (*env)->ReleaseIntArrayElements(env, in_slots, slots, 0);
    (*env)->ReleaseLongArrayElements(env, in_offsets, offsets, 0);
    (*env)->ReleaseIntArrayElements(env, in_num_buffers, num_buffers, 0);
    (*env)->ReleaseLongArrayElements(env, in_buf_addresses, buf_addresses, 0);
    (*env)->ReleaseLongArrayElements(env, in_buf_lengths, buf_lengths, 0);
}

static jint netty_epoll_native_getAIOEvents0(JNIEnv* env, jclass clazz, jlong ctxaddr, jlongArray result) {

    netty_io_context_t* ctx = (netty_io_context_t*) ctxaddr;
    struct io_event events[ctx->concurrency];

    struct timespec timeout;
    timeout.tv_sec = 0;
    timeout.tv_nsec = 0;

    int r, j, slot;
    r = io_getevents(ctx->aio, 1, ctx->concurrency, events, &timeout);
    //printf("io_getevents() returned %d\n", r);

    if (r > 0) {
        unsigned long resultArray[r * 2];
        for (j = 0; j < r; ++j) {
            struct io_event event = events[j];
            struct netty_iocb* niocb = (netty_iocb_t *) event.obj;

            slot = niocb->slot;
            niocb->slot = -1;

            //printf("slot: %d, res: %ld, res2: %ld\n", slot, (long)event.res, (long)event.res2);
            resultArray[(int) j] = (long) slot;

            if (((long)event.res2) < 0) {
                // From what I understood by looking at inode.c, res2 will either be zero or neg.ve. It can
                // happen that res2 signals an error with a neg.ve value but res is > 0 if there was a partial
                // transfer of data
                resultArray[(int) (j + r)] = (long) event.res2;
            }
            else {
                // here res can be neg.ve, in which case it indicates a failure code handled java side. If it is >= 0
                // it instead indicates the number of bytes transferred.
                resultArray[(int) (j + r)] = (long) event.res;
            }
         }

         (*env)->SetLongArrayRegion(env, result, 0, r * 2, resultArray);
    } else if (r < 0) {
       netty_unix_errors_throwChannelExceptionErrorNo(env, "io_getevents() failed: ", r);
    }

    return r;
}

// JNI Registered Methods End

// JNI Method Registration Table Begin
static const JNINativeMethod statically_referenced_fixed_method_table[] = {
  { "epollet", "()I", (void *) netty_epoll_native_epollet },
  { "epollin", "()I", (void *) netty_epoll_native_epollin },
  { "epollout", "()I", (void *) netty_epoll_native_epollout },
  { "epollrdhup", "()I", (void *) netty_epoll_native_epollrdhup },
  { "epollerr", "()I", (void *) netty_epoll_native_epollerr },
  { "efdnonblock", "()I", (void *) netty_epoll_native_efdnonblock },
  { "eagain", "()I", (void *) netty_epoll_native_eagain },
  { "tcpMd5SigMaxKeyLen", "()I", (void *) netty_epoll_native_tcpMd5SigMaxKeyLen },
  { "isSupportingSendmmsg", "()Z", (void *) netty_epoll_native_isSupportingSendmmsg },
  { "isSupportingTcpFastopen", "()Z", (void *) netty_epoll_native_isSupportingTcpFastopen },
  { "kernelVersion", "()Ljava/lang/String;", (void *) netty_epoll_native_kernelVersion }
};
static const jint statically_referenced_fixed_method_table_size = sizeof(statically_referenced_fixed_method_table) / sizeof(statically_referenced_fixed_method_table[0]);
static const JNINativeMethod fixed_method_table[] = {
  { "eventFd", "()I", (void *) netty_epoll_native_eventFd },
  { "timerFd", "()I", (void *) netty_epoll_native_timerFd },
  { "eventFdWrite", "(IJ)V", (void *) netty_epoll_native_eventFdWrite },
  { "eventFdRead", "(I)J", (void *) netty_epoll_native_eventFdRead },
  { "timerFdRead", "(I)V", (void *) netty_epoll_native_timerFdRead },
  { "epollCreate", "()I", (void *) netty_epoll_native_epollCreate },
  { "epollWait0", "(IJIIII)I", (void *) netty_epoll_native_epollWait0 },
  { "epollBusyWait0", "(IJI)I", (void *) netty_epoll_native_epollBusyWait0 },
  { "epollCtlAdd0", "(III)I", (void *) netty_epoll_native_epollCtlAdd0 },
  { "epollCtlMod0", "(III)I", (void *) netty_epoll_native_epollCtlMod0 },
  { "epollCtlDel0", "(II)I", (void *) netty_epoll_native_epollCtlDel0 },
  // "sendmmsg0" has a dynamic signature
  { "sizeofEpollEvent", "()I", (void *) netty_epoll_native_sizeofEpollEvent },
  { "offsetofEpollData", "()I", (void *) netty_epoll_native_offsetofEpollData },
  { "splice0", "(IJIJJ)I", (void *) netty_epoll_native_splice0 },
  { "createAIOContext0", "(I)J", (void *) netty_epoll_native_createAIOContext0 },
  { "submitAIORead0", "(JIII[I[J[I[J[J)V", (void *) netty_epoll_native_submitAIORead0 },
  { "getAIOEvents0", "(J[J)I", (void *) netty_epoll_native_getAIOEvents0 },
  { "destroyAIOContext0", "(J)V", (void *) netty_epoll_native_destroyAIOContext0 }
};
static const jint fixed_method_table_size = sizeof(fixed_method_table) / sizeof(fixed_method_table[0]);

static jint dynamicMethodsTableSize() {
    return fixed_method_table_size + 1; // 1 is for the dynamic method signatures.
}

static JNINativeMethod* createDynamicMethodsTable(const char* packagePrefix) {
    JNINativeMethod* dynamicMethods = malloc(sizeof(JNINativeMethod) * dynamicMethodsTableSize());
    memcpy(dynamicMethods, fixed_method_table, sizeof(fixed_method_table));
    char* dynamicTypeName = netty_unix_util_prepend(packagePrefix, "io/netty/channel/epoll/NativeDatagramPacketArray$NativeDatagramPacket;II)I");
    JNINativeMethod* dynamicMethod = &dynamicMethods[fixed_method_table_size];
    dynamicMethod->name = "sendmmsg0";
    dynamicMethod->signature = netty_unix_util_prepend("(I[L", dynamicTypeName);
    dynamicMethod->fnPtr = (void *) netty_epoll_native_sendmmsg0;
    free(dynamicTypeName);
    return dynamicMethods;
}

static void freeDynamicMethodsTable(JNINativeMethod* dynamicMethods) {
    jint fullMethodTableSize = dynamicMethodsTableSize();
    jint i = fixed_method_table_size;
    for (; i < fullMethodTableSize; ++i) {
        free(dynamicMethods[i].signature);
    }
    free(dynamicMethods);
}
// JNI Method Registration Table End

static jint netty_epoll_native_JNI_OnLoad(JNIEnv* env, const char* packagePrefix) {
    int limitsOnLoadCalled = 0;
    int errorsOnLoadCalled = 0;
    int filedescriptorOnLoadCalled = 0;
    int socketOnLoadCalled = 0;
    int bufferOnLoadCalled = 0;
    int linuxsocketOnLoadCalled = 0;

    // We must register the statically referenced methods first!
    if (netty_unix_util_register_natives(env,
            packagePrefix,
            "io/netty/channel/epoll/NativeStaticallyReferencedJniMethods",
            statically_referenced_fixed_method_table,
            statically_referenced_fixed_method_table_size) != 0) {
        goto error;
    }
    // Register the methods which are not referenced by static member variables
    JNINativeMethod* dynamicMethods = createDynamicMethodsTable(packagePrefix);
    if (netty_unix_util_register_natives(env,
            packagePrefix,
            "io/netty/channel/epoll/Native",
            dynamicMethods,
            dynamicMethodsTableSize()) != 0) {
        freeDynamicMethodsTable(dynamicMethods);
        goto error;
    }
    freeDynamicMethodsTable(dynamicMethods);
    dynamicMethods = NULL;
    // Load all c modules that we depend upon
    if (netty_unix_limits_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto error;
    }
    limitsOnLoadCalled = 1;

    if (netty_unix_errors_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto error;
    }
    errorsOnLoadCalled = 1;

    if (netty_unix_filedescriptor_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto error;
    }
    filedescriptorOnLoadCalled = 1;

    if (netty_unix_socket_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto error;
    }
    socketOnLoadCalled = 1;

    if (netty_unix_buffer_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto error;
    }
    bufferOnLoadCalled = 1;

    if (netty_epoll_linuxsocket_JNI_OnLoad(env, packagePrefix) == JNI_ERR) {
        goto error;
    }
    linuxsocketOnLoadCalled = 1;

    // Initialize this module
    char* nettyClassName = netty_unix_util_prepend(packagePrefix, "io/netty/channel/epoll/NativeDatagramPacketArray$NativeDatagramPacket");
    jclass nativeDatagramPacketCls = (*env)->FindClass(env, nettyClassName);
    free(nettyClassName);
    nettyClassName = NULL;
    if (nativeDatagramPacketCls == NULL) {
        // pending exception...
        goto error;
    }

    packetAddrFieldId = (*env)->GetFieldID(env, nativeDatagramPacketCls, "addr", "[B");
    if (packetAddrFieldId == NULL) {
        netty_unix_errors_throwRuntimeException(env, "failed to get field ID: NativeDatagramPacket.addr");
        goto error;
    }
    packetScopeIdFieldId = (*env)->GetFieldID(env, nativeDatagramPacketCls, "scopeId", "I");
    if (packetScopeIdFieldId == NULL) {
        netty_unix_errors_throwRuntimeException(env, "failed to get field ID: NativeDatagramPacket.scopeId");
        goto error;
    }
    packetPortFieldId = (*env)->GetFieldID(env, nativeDatagramPacketCls, "port", "I");
    if (packetPortFieldId == NULL) {
        netty_unix_errors_throwRuntimeException(env, "failed to get field ID: NativeDatagramPacket.port");
        goto error;
    }
    packetMemoryAddressFieldId = (*env)->GetFieldID(env, nativeDatagramPacketCls, "memoryAddress", "J");
    if (packetMemoryAddressFieldId == NULL) {
        netty_unix_errors_throwRuntimeException(env, "failed to get field ID: NativeDatagramPacket.memoryAddress");
        goto error;
    }

    packetCountFieldId = (*env)->GetFieldID(env, nativeDatagramPacketCls, "count", "I");
    if (packetCountFieldId == NULL) {
        netty_unix_errors_throwRuntimeException(env, "failed to get field ID: NativeDatagramPacket.count");
        goto error;
    }

    return NETTY_JNI_VERSION;

error:
   if (limitsOnLoadCalled == 1) {
       netty_unix_limits_JNI_OnUnLoad(env);
   }
   if (errorsOnLoadCalled == 1) {
       netty_unix_errors_JNI_OnUnLoad(env);
   }
   if (filedescriptorOnLoadCalled == 1) {
       netty_unix_filedescriptor_JNI_OnUnLoad(env);
   }
   if (socketOnLoadCalled == 1) {
       netty_unix_socket_JNI_OnUnLoad(env);
   }
   if (bufferOnLoadCalled == 1) {
       netty_unix_buffer_JNI_OnUnLoad(env);
   }
   if (linuxsocketOnLoadCalled == 1) {
       netty_epoll_linuxsocket_JNI_OnUnLoad(env);
   }
   packetAddrFieldId = NULL;
   packetScopeIdFieldId = NULL;
   packetPortFieldId = NULL;
   packetMemoryAddressFieldId = NULL;
   packetCountFieldId = NULL;

   return JNI_ERR;
}

static void netty_epoll_native_JNI_OnUnLoad(JNIEnv* env) {
    netty_unix_limits_JNI_OnUnLoad(env);
    netty_unix_errors_JNI_OnUnLoad(env);
    netty_unix_filedescriptor_JNI_OnUnLoad(env);
    netty_unix_socket_JNI_OnUnLoad(env);
    netty_unix_buffer_JNI_OnUnLoad(env);
    netty_epoll_linuxsocket_JNI_OnUnLoad(env);

    packetAddrFieldId = NULL;
    packetScopeIdFieldId = NULL;
    packetPortFieldId = NULL;
    packetMemoryAddressFieldId = NULL;
    packetCountFieldId = NULL;
}

// Invoked by the JVM when statically linked
static jint JNI_OnLoad_netty_transport_native_epoll0(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**) &env, NETTY_JNI_VERSION) != JNI_OK) {
        return JNI_ERR;
    }
    char* packagePrefix = NULL;
#ifndef NETTY_BUILD_STATIC
    Dl_info dlinfo;
    jint status = 0;
    // We need to use an address of a function that is uniquely part of this library, so choose a static
    // function. See https://github.com/netty/netty/issues/4840.
    if (!dladdr((void*) netty_epoll_native_JNI_OnUnLoad, &dlinfo)) {
        fprintf(stderr, "FATAL: transport-native-epoll JNI call to dladdr failed!\n");
        return JNI_ERR;
    }
    packagePrefix = netty_unix_util_parse_package_prefix(dlinfo.dli_fname, "netty_transport_native_epoll", &status);
    if (status == JNI_ERR) {
        fprintf(stderr, "FATAL: transport-native-epoll JNI encountered unexpected dlinfo.dli_fname: %s\n", dlinfo.dli_fname);
        return JNI_ERR;
    }
#endif /* NETTY_BUILD_STATIC */
    jint ret = netty_epoll_native_JNI_OnLoad(env, packagePrefix);

    if (packagePrefix != NULL) {
      free(packagePrefix);
      packagePrefix = NULL;
    }

    return ret;
}

static void JNI_OnUnload_netty_transport_native_epoll0(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**) &env, NETTY_JNI_VERSION) != JNI_OK) {
        // Something is wrong but nothing we can do about this :(
        return;
    }
    netty_epoll_native_JNI_OnUnLoad(env);
}

// We build with -fvisibility=hidden so ensure we mark everything that needs to be visible with JNIEXPORT
// http://mail.openjdk.java.net/pipermail/core-libs-dev/2013-February/014549.html

// Invoked by the JVM when statically linked
JNIEXPORT jint JNI_OnLoad_netty_transport_native_epoll(JavaVM* vm, void* reserved) {
    return JNI_OnLoad_netty_transport_native_epoll0(vm, reserved);
}

// Invoked by the JVM when statically linked
JNIEXPORT void JNI_OnUnload_netty_transport_native_epoll(JavaVM* vm, void* reserved) {
    JNI_OnUnload_netty_transport_native_epoll0(vm, reserved);
}

#ifndef NETTY_BUILD_STATIC
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    return JNI_OnLoad_netty_transport_native_epoll0(vm, reserved);
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    JNI_OnUnload_netty_transport_native_epoll0(vm, reserved);
}
#endif /* NETTY_BUILD_STATIC */
