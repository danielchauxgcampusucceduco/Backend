package com.monitoring.proxyapi.domain.exception;

public class IllegalOrderStateException extends DomainException {

    public IllegalOrderStateException(String orderId, String currentStatus, String attemptedAction) {
        super("ILLEGAL_ORDER_STATE", ErrorCategory.CONFLICT,
                "Order " + orderId + " is " + currentStatus + " and cannot be " + attemptedAction);
    }
}
