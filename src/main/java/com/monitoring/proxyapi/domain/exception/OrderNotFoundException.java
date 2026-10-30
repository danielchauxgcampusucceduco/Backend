package com.monitoring.proxyapi.domain.exception;

public class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(String orderId) {
        super("ORDER_NOT_FOUND", ErrorCategory.NOT_FOUND, "Order '" + orderId + "' does not exist");
    }
}
