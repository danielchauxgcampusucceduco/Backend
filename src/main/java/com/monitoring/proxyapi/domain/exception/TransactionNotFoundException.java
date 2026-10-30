package com.monitoring.proxyapi.domain.exception;

public class TransactionNotFoundException extends DomainException {

    public TransactionNotFoundException(String transactionId) {
        super("TRANSACTION_NOT_FOUND", ErrorCategory.NOT_FOUND, "Payment transaction '" + transactionId + "' does not exist");
    }
}
