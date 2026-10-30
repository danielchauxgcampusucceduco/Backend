package com.monitoring.proxyapi.domain.exception;

/**
 * The payment request reached the service but violates a business rule (limits, refund caps...).
 * The reason code doubles as the error code so the dashboard can group declines by cause.
 */
public class PaymentDeclinedException extends DomainException {

    public PaymentDeclinedException(String reasonCode, String message) {
        super(reasonCode, ErrorCategory.BUSINESS_RULE_VIOLATION, message);
    }
}
