package com.monitoring.proxyapi.domain.exception;

/**
 * The external acquirer/gateway could not complete the request. This is the failure the payment
 * service injects randomly to emulate an unreliable third-party dependency.
 */
public class PaymentGatewayException extends DomainException {

    private final String gateway;
    private final boolean forced;

    public PaymentGatewayException(String gateway, String message, boolean forced) {
        super("PAYMENT_GATEWAY_UNAVAILABLE", ErrorCategory.UPSTREAM_FAILURE, "[" + gateway + "] " + message);
        this.gateway = gateway;
        this.forced = forced;
    }

    public String getGateway() {
        return gateway;
    }

    public boolean isForced() {
        return forced;
    }
}
