package com.monitoring.proxyapi.domain.service.payments;

import com.monitoring.proxyapi.domain.exception.InvalidRequestParameterException;
import com.monitoring.proxyapi.domain.exception.OperationNotPermittedException;
import com.monitoring.proxyapi.domain.exception.PaymentDeclinedException;
import com.monitoring.proxyapi.domain.exception.PaymentGatewayException;
import com.monitoring.proxyapi.domain.exception.TransactionNotFoundException;
import com.monitoring.proxyapi.domain.model.OperationParameters;
import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.MicroserviceProxy;
import com.monitoring.proxyapi.domain.service.support.LatencySimulator;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real subject: payment capture, refunds and method validation against an external acquirer.
 * <p>
 * The acquirer is deliberately unreliable: {@code baseFailureRate} of all calls (10% by default)
 * fail with a {@link PaymentGatewayException}. Clients can push the error rate further to demo the
 * dashboard alert, either with {@code "forceFail": true} (deterministic) or with
 * {@code "stressFactor": 1..5}, which multiplies the base rate (capped at 60%).
 */
public class PaymentService implements MicroserviceProxy<OperationResult> {

    public static final Set<String> SUPPORTED_OPERATIONS = Set.of("processPayment", "refundPayment", "validatePaymentMethod");

    private static final String GATEWAY = "Redeban-Switch";
    private static final BigDecimal SINGLE_PAYMENT_LIMIT = new BigDecimal("40000000.00");
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("COP", "USD");
    private static final int MAX_STRESS_FACTOR = 5;
    private static final double MAX_INJECTED_FAILURE_RATE = 0.60;
    private static final List<String> GATEWAY_FAULTS = List.of(
            "Acquirer did not answer within 3000 ms",
            "Issuer unavailable - ISO 8583 response code 91",
            "Connection reset by gateway during TLS handshake",
            "Gateway throttled the request - provider returned HTTP 429",
            "Fraud screening service timed out (risk score unavailable)");

    private final LatencySimulator latency;
    private final Clock clock;
    private final double baseFailureRate;
    private final Map<String, PaymentTransaction> transactions = new ConcurrentHashMap<>();

    public PaymentService(LatencySimulator latency, Clock clock, double baseFailureRate) {
        if (baseFailureRate < 0 || baseFailureRate > 1) {
            throw new IllegalArgumentException("baseFailureRate must be within [0, 1]");
        }
        this.latency = latency;
        this.clock = clock;
        this.baseFailureRate = baseFailureRate;
    }

    @Override
    public OperationResult execute(String operation, Object... params) {
        OperationParameters parameters = OperationParameters.from(params);
        latency.pause();
        simulateGatewayInstability(parameters);
        Map<String, Object> data = switch (operation) {
            case "processPayment" -> processPayment(parameters);
            case "refundPayment" -> refundPayment(parameters);
            case "validatePaymentMethod" -> validatePaymentMethod(parameters);
            default -> throw new OperationNotPermittedException(ServiceId.PAYMENTS, operation, SUPPORTED_OPERATIONS);
        };
        return OperationResult.of(ServiceId.PAYMENTS, operation, data, clock.instant());
    }

    private void simulateGatewayInstability(OperationParameters parameters) {
        if (parameters.flag("forceFail")) {
            throw new PaymentGatewayException(GATEWAY,
                    "Forced outage requested by client (forceFail=true): acquirer unreachable", true);
        }
        int stressFactor = Math.clamp(parameters.optionalInt("stressFactor", 1), 1, MAX_STRESS_FACTOR);
        double failureRate = Math.min(baseFailureRate * stressFactor, MAX_INJECTED_FAILURE_RATE);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < failureRate) {
            throw new PaymentGatewayException(GATEWAY, GATEWAY_FAULTS.get(random.nextInt(GATEWAY_FAULTS.size())), false);
        }
    }

    private Map<String, Object> processPayment(OperationParameters parameters) {
        String orderId = parameters.requireText("orderId");
        BigDecimal amount = parameters.requireAmount("amount");
        String currency = parameters.optionalText("currency", "COP").toUpperCase(Locale.ROOT);
        String method = parameters.requireText("method").toUpperCase(Locale.ROOT);

        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new InvalidRequestParameterException("currency", "must be one of " + SUPPORTED_CURRENCIES);
        }
        if (!PaymentMethodValidator.SUPPORTED_METHODS.contains(method)) {
            throw new InvalidRequestParameterException("method", "must be one of " + PaymentMethodValidator.SUPPORTED_METHODS);
        }
        if (amount.compareTo(SINGLE_PAYMENT_LIMIT) > 0) {
            throw new PaymentDeclinedException("AMOUNT_LIMIT_EXCEEDED",
                    "Amount " + amount.toPlainString() + " exceeds the single payment limit of " + SINGLE_PAYMENT_LIMIT.toPlainString());
        }

        String transactionId = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String authorizationCode = "%06d".formatted(ThreadLocalRandom.current().nextInt(1_000_000));
        PaymentTransaction transaction = new PaymentTransaction(transactionId, orderId, method, currency, amount,
                BigDecimal.ZERO.setScale(2), authorizationCode, clock.instant());
        transactions.put(transactionId, transaction);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transactionId", transactionId);
        data.put("orderId", orderId);
        data.put("status", "APPROVED");
        data.put("authorizationCode", authorizationCode);
        data.put("amount", amount);
        data.put("currency", currency);
        data.put("method", method);
        data.put("gateway", GATEWAY);
        data.put("capturedAt", transaction.capturedAt().toString());
        return data;
    }

    private Map<String, Object> refundPayment(OperationParameters parameters) {
        String transactionId = parameters.requireText("transactionId");
        Optional<BigDecimal> requestedAmount = parameters.optionalAmount("amount");
        String reason = parameters.optionalText("reason", "CUSTOMER_RETURN");
        AtomicReference<BigDecimal> refundedNow = new AtomicReference<>();

        PaymentTransaction updated = transactions.compute(transactionId, (id, current) -> {
            if (current == null) {
                throw new TransactionNotFoundException(id);
            }
            if (current.fullyRefunded()) {
                throw new PaymentDeclinedException("ALREADY_REFUNDED", "Transaction " + id + " was already fully refunded");
            }
            BigDecimal amount = requestedAmount.orElse(current.refundableAmount());
            if (amount.compareTo(current.refundableAmount()) > 0) {
                throw new PaymentDeclinedException("REFUND_EXCEEDS_CAPTURE",
                        "Refund of " + amount.toPlainString() + " exceeds the refundable balance of "
                                + current.refundableAmount().toPlainString());
            }
            refundedNow.set(amount);
            return current.withRefund(amount);
        });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundId", "RFD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        data.put("transactionId", transactionId);
        data.put("orderId", updated.orderId());
        data.put("status", updated.fullyRefunded() ? "REFUNDED" : "PARTIALLY_REFUNDED");
        data.put("refundedAmount", refundedNow.get());
        data.put("totalRefunded", updated.refundedAmount());
        data.put("remainingRefundable", updated.refundableAmount());
        data.put("currency", updated.currency());
        data.put("reason", reason);
        data.put("processedAt", clock.instant().toString());
        return data;
    }

    private Map<String, Object> validatePaymentMethod(OperationParameters parameters) {
        String method = parameters.requireText("method").toUpperCase(Locale.ROOT);
        PaymentMethodValidator.Verdict verdict;
        if (!PaymentMethodValidator.SUPPORTED_METHODS.contains(method)) {
            verdict = new PaymentMethodValidator.Verdict(false, "-", List.of("Payment method " + method + " is not supported"));
        } else if (PaymentMethodValidator.CARD_METHODS.contains(method)) {
            verdict = PaymentMethodValidator.validateCard(method, parameters.requireText("cardNumber"));
        } else if (method.equals("NEQUI")) {
            verdict = PaymentMethodValidator.validateWallet(parameters.optionalText("walletPhone", null));
        } else {
            String bankCode = parameters.optionalText("bankCode", "");
            verdict = bankCode.matches("\\d{4}")
                    ? new PaymentMethodValidator.Verdict(true, "PSE-" + bankCode, List.of())
                    : new PaymentMethodValidator.Verdict(false, "PSE", List.of("PSE requires a 4-digit ACH bank code"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("method", method);
        data.put("valid", verdict.valid());
        data.put("maskedReference", verdict.maskedReference());
        data.put("rejectionReasons", verdict.rejectionReasons());
        data.put("redirectRequired", method.equals("PSE") && verdict.valid());
        data.put("checkedAt", clock.instant().toString());
        return data;
    }
}
