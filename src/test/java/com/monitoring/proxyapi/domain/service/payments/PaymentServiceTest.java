package com.monitoring.proxyapi.domain.service.payments;

import com.monitoring.proxyapi.domain.exception.PaymentDeclinedException;
import com.monitoring.proxyapi.domain.exception.PaymentGatewayException;
import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.service.support.LatencySimulator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentServiceTest {

    private final PaymentService reliableGateway = new PaymentService(LatencySimulator.none(), Clock.systemUTC(), 0.0);

    @Test
    void forceFailAlwaysSimulatesAGatewayOutage() {
        assertThatThrownBy(() -> reliableGateway.execute("processPayment",
                Map.of("orderId", "ORD-2026-001041", "amount", 459900, "method", "NEQUI", "forceFail", true)))
                .isInstanceOfSatisfying(PaymentGatewayException.class, ex -> assertThat(ex.isForced()).isTrue());
    }

    @Test
    void baseFailureRateIsAppliedToRegularTraffic() {
        PaymentService flakyGateway = new PaymentService(LatencySimulator.none(), Clock.systemUTC(), 0.10);

        long failures = IntStream.range(0, 4_000).filter(attempt -> {
            try {
                flakyGateway.execute("validatePaymentMethod", Map.of("method", "PSE", "bankCode", "1007"));
                return false;
            } catch (PaymentGatewayException expected) {
                return true;
            }
        }).count();

        assertThat(failures / 4_000.0).isBetween(0.07, 0.13);
    }

    @Test
    void cardValidationAppliesLuhnAndBrandChecks() {
        OperationResult valid = reliableGateway.execute("validatePaymentMethod", Map.of("method", "VISA", "cardNumber", "4111 1111 1111 1111"));
        OperationResult mistyped = reliableGateway.execute("validatePaymentMethod", Map.of("method", "VISA", "cardNumber", "4111111111111112"));
        OperationResult wrongBrand = reliableGateway.execute("validatePaymentMethod", Map.of("method", "AMEX", "cardNumber", "5500005555555559"));

        assertThat(valid.get("valid")).isEqualTo(true);
        assertThat(valid.get("maskedReference")).isEqualTo("**** **** **** 1111");
        assertThat(mistyped.get("valid")).isEqualTo(false);
        assertThat(wrongBrand.get("valid")).isEqualTo(false);
        assertThat((List<?>) wrongBrand.get("rejectionReasons")).singleElement().asString().contains("MASTERCARD");
    }

    @Test
    void refundsCannotExceedTheCapturedAmount() {
        OperationResult payment = reliableGateway.execute("processPayment",
                Map.of("orderId", "ORD-2026-001041", "amount", "1649000.00", "method", "MASTERCARD"));
        String transactionId = (String) payment.get("transactionId");

        OperationResult partial = reliableGateway.execute("refundPayment", Map.of("transactionId", transactionId, "amount", "649000.00"));
        assertThat(partial.get("status")).isEqualTo("PARTIALLY_REFUNDED");

        assertThatThrownBy(() -> reliableGateway.execute("refundPayment", Map.of("transactionId", transactionId, "amount", "1000000.01")))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("refundable balance");
    }
}
