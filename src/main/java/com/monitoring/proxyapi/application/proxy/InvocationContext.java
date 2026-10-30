package com.monitoring.proxyapi.application.proxy;

import java.util.Optional;

/**
 * Hands the proxy-generated request id back to the caller on the same thread.
 * <p>
 * The shared {@code MicroserviceProxy<T>} signature cannot carry extra metadata without leaking the
 * proxy into the contract (which would break transparency). A thread-local side channel keeps the
 * interface untouched: the proxy binds the id, the REST adapter reads it right after the call to
 * echo it in the {@code X-Request-Id} header, and then clears it.
 */
public final class InvocationContext {

    private static final ThreadLocal<String> LAST_REQUEST_ID = new ThreadLocal<>();

    private InvocationContext() {
    }

    static void bind(String requestId) {
        LAST_REQUEST_ID.set(requestId);
    }

    public static Optional<String> lastRequestId() {
        return Optional.ofNullable(LAST_REQUEST_ID.get());
    }

    public static void clear() {
        LAST_REQUEST_ID.remove();
    }
}
