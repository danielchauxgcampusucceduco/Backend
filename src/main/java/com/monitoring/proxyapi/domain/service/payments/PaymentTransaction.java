package com.monitoring.proxyapi.domain.service.payments;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentTransaction(
        String transactionId,
        String orderId,
        String method,
        String currency,
        BigDecimal capturedAmount,
        BigDecimal refundedAmount,
        String authorizationCode,
        Instant capturedAt) {

    public BigDecimal refundableAmount() {
        return capturedAmount.subtract(refundedAmount);
    }

    public PaymentTransaction withRefund(BigDecimal amount) {
        return new PaymentTransaction(transactionId, orderId, method, currency, capturedAmount,
                refundedAmount.add(amount), authorizationCode, capturedAt);
    }

    public boolean fullyRefunded() {
        return refundableAmount().signum() == 0;
    }
}
