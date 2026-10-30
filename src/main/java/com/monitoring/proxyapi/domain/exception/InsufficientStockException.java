package com.monitoring.proxyapi.domain.exception;

public class InsufficientStockException extends DomainException {

    public InsufficientStockException(String sku, int requested, int available) {
        super("INSUFFICIENT_STOCK", ErrorCategory.CONFLICT,
                "Cannot reserve " + requested + " units of " + sku + ": only " + available + " available");
    }
}
