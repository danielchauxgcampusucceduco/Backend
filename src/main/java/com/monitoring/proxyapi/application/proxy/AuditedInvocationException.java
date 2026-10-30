package com.monitoring.proxyapi.application.proxy;

import com.monitoring.proxyapi.domain.model.ServiceId;

/**
 * Raised by the proxy after a failed call has already been audited. It wraps the original failure
 * and carries the correlation data the REST layer needs to build a controlled error response.
 */
public class AuditedInvocationException extends RuntimeException {

    private final String requestId;
    private final ServiceId serviceId;
    private final String operation;
    private final long durationMs;

    public AuditedInvocationException(String requestId, ServiceId serviceId, String operation, long durationMs, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.requestId = requestId;
        this.serviceId = serviceId;
        this.operation = operation;
        this.durationMs = durationMs;
    }

    public String getRequestId() {
        return requestId;
    }

    public ServiceId getServiceId() {
        return serviceId;
    }

    public String getOperation() {
        return operation;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
