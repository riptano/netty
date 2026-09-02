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

import io.netty.util.internal.PlatformDependent;

/**
 * Tells if <a href="https://netty.io/wiki/native-transports.html">{@code netty-transport-native-epoll}</a>
 * is supported.
 */
public final class Aio {

    private static final Throwable UNAVAILABILITY_CAUSE;

    static  {
        Throwable cause = Epoll.unavailabilityCause();
        AIOContext aioContext = null;
        try {
            aioContext = Native.createAIOContext(new AIOContext.Config(1, 1));
        } catch (Throwable t) {
            cause = t;
        } finally {
            if (aioContext != null) {
                try {
                    aioContext.destroy();
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }

        if (cause != null) {
            UNAVAILABILITY_CAUSE = cause;
        } else {
            UNAVAILABILITY_CAUSE = PlatformDependent.hasUnsafe() ? null :
                                   new IllegalStateException("sun.misc.Unsafe not available");
        }
    }

    /**
     * Returns {@code true} if and only if the
     * <a href="https://netty.io/wiki/native-transports.html">{@code netty-transport-native-epoll}</a> is available.
     */
    public static boolean isAvailable() {
        return UNAVAILABILITY_CAUSE == null;
    }

    /**
     * Ensure that <a href="https://netty.io/wiki/native-transports.html">{@code netty-transport-native-epoll}</a> is
     * available.
     *
     * @throws UnsatisfiedLinkError if unavailable
     */
    public static void ensureAvailability() {
        if (UNAVAILABILITY_CAUSE != null) {
            throw (Error) new UnsatisfiedLinkError(
            "failed to load the required native library").initCause(UNAVAILABILITY_CAUSE);
        }
    }

    /**
     * Returns the cause of unavailability of
     * <a href="https://netty.io/wiki/native-transports.html">{@code netty-transport-native-epoll}</a>.
     *
     * @return the cause if unavailable. {@code null} if available.
     */
    public static Throwable unavailabilityCause() {
        return UNAVAILABILITY_CAUSE;
    }

    private Aio() { }
}
