package com.monitoring.proxyapi.domain.exception;

/**
 * Base type for every expected, business-meaningful failure raised by the microservices.
 * Anything that is not a {@code DomainException} is treated as an unexpected defect.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;
    private final ErrorCategory category;

    protected DomainException(String errorCode, ErrorCategory category, String message) {
        super(message);
        this.errorCode = errorCode;
        this.category = category;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public ErrorCategory getCategory() {
        return category;
    }
}
