package com.monitoring.proxyapi.application.facade;

import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.Product;
import com.monitoring.proxyapi.domain.model.ProductCatalog;
import com.monitoring.proxyapi.domain.model.ServiceId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Produces realistic, varied traffic for the load simulation.
 * <p>
 * Traffic is generated in two waves. The primary wave creates entities (orders, captured payments,
 * reservations); the follow-up wave queries, cancels and refunds the entities that actually exist,
 * the way real user journeys do. A small share of requests is intentionally invalid (oversized
 * reservations, double cancellations) so error rates are not driven only by the payment gateway.
 */
public class LoadScenarioGenerator {

    private static final int TRACKED_IDS = 200;
    private static final List<String> SUPPLIERS = List.of("Ingram Micro Colombia", "Intcomex Colombia", "Nexsys de Colombia");
    private static final List<String> CITIES = List.of("Bogota D.C.", "Medellin", "Cali", "Barranquilla", "Bucaramanga", "Pereira");
    private static final List<String> CANCEL_REASONS = List.of("CUSTOMER_REQUEST", "PAYMENT_TIMEOUT", "DUPLICATE_ORDER", "ADDRESS_NOT_SERVICEABLE");
    private static final Map<String, String> TEST_CARDS = Map.of(
            "VISA", "4111111111111111",
            "MASTERCARD", "5500005555555559",
            "AMEX", "378282246310005");
    private static final List<String> PSE_BANK_CODES = List.of("1007", "1051", "1013", "1001");

    private final ProductCatalog catalog;
    private final Clock clock;
    private final RecentIds orderIds = new RecentIds(TRACKED_IDS);
    private final RecentIds transactionIds = new RecentIds(TRACKED_IDS);
    private final Map<String, BigDecimal> capturedAmounts = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BigDecimal> eldest) {
            return size() > TRACKED_IDS;
        }
    });

    public LoadScenarioGenerator(ProductCatalog catalog, Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    public List<ScenarioCall> primaryWave(int size) {
        List<ScenarioCall> calls = new ArrayList<>(size);
        for (ServiceId serviceId : balancedServices(size)) {
            calls.add(switch (serviceId) {
                case INVENTORY -> primaryInventoryCall();
                case ORDERS -> createOrder();
                case PAYMENTS -> primaryPaymentCall();
            });
        }
        return calls;
    }

    public List<ScenarioCall> followUpWave(int size) {
        List<ScenarioCall> calls = new ArrayList<>(size);
        for (ServiceId serviceId : balancedServices(size)) {
            calls.add(switch (serviceId) {
                case INVENTORY -> checkStock();
                case ORDERS -> followUpOrderCall();
                case PAYMENTS -> followUpPaymentCall();
            });
        }
        return calls;
    }

    /**
     * Feeds successful results back so the follow-up wave targets real orders and transactions.
     */
    public void registerOutcome(OperationResult result) {
        if (result.serviceId() == ServiceId.ORDERS && "createOrder".equals(result.operation())) {
            orderIds.add(String.valueOf(result.get("orderId")));
        } else if (result.serviceId() == ServiceId.PAYMENTS && "processPayment".equals(result.operation())) {
            String transactionId = String.valueOf(result.get("transactionId"));
            transactionIds.add(transactionId);
            capturedAmounts.put(transactionId, new BigDecimal(String.valueOf(result.get("amount"))));
        }
    }

    private List<ServiceId> balancedServices(int size) {
        List<ServiceId> services = new ArrayList<>(size);
        ServiceId[] all = ServiceId.values();
        int offset = random().nextInt(all.length);
        for (int index = 0; index < size; index++) {
            services.add(all[(index + offset) % all.length]);
        }
        Collections.shuffle(services, random());
        return services;
    }

    private ScenarioCall primaryInventoryCall() {
        int roll = random().nextInt(100);
        if (roll < 50) {
            return checkStock();
        }
        if (roll < 85) {
            // Roughly one reservation in fifteen asks for far more than any warehouse holds.
            int quantity = random().nextInt(15) == 0 ? 400 + random().nextInt(200) : 1 + random().nextInt(4);
            return new ScenarioCall(ServiceId.INVENTORY, "reserveStock", Map.of(
                    "sku", randomProduct().sku(),
                    "quantity", quantity,
                    "orderId", knownOrSyntheticOrderId()));
        }
        return new ScenarioCall(ServiceId.INVENTORY, "restockItem", Map.of(
                "sku", randomProduct().sku(),
                "quantity", 10 + random().nextInt(51),
                "supplier", pick(SUPPLIERS)));
    }

    private ScenarioCall checkStock() {
        return new ScenarioCall(ServiceId.INVENTORY, "checkStock", Map.of("sku", randomProduct().sku()));
    }

    private ScenarioCall createOrder() {
        int lineCount = 1 + random().nextInt(3);
        List<Product> products = new ArrayList<>(catalog.products());
        Collections.shuffle(products, random());
        List<Map<String, Object>> items = products.stream()
                .limit(lineCount)
                .map(product -> Map.<String, Object>of("sku", product.sku(), "quantity", 1 + random().nextInt(3)))
                .toList();
        return new ScenarioCall(ServiceId.ORDERS, "createOrder", Map.of(
                "customerId", "CUS-%05d".formatted(10_000 + random().nextInt(90_000)),
                "shippingCity", pick(CITIES),
                "items", items));
    }

    private ScenarioCall followUpOrderCall() {
        Optional<String> orderId = orderIds.pick();
        if (orderId.isEmpty()) {
            return createOrder();
        }
        if (random().nextInt(100) < 70) {
            return new ScenarioCall(ServiceId.ORDERS, "getOrderStatus", Map.of("orderId", orderId.get()));
        }
        return new ScenarioCall(ServiceId.ORDERS, "cancelOrder", Map.of(
                "orderId", orderId.get(),
                "reason", pick(CANCEL_REASONS)));
    }

    private ScenarioCall primaryPaymentCall() {
        if (random().nextInt(100) < 70) {
            return processPayment();
        }
        String method = pick(List.of("VISA", "MASTERCARD", "AMEX", "NEQUI", "PSE"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("method", method);
        switch (method) {
            // One validation in eight carries a mistyped PAN so the Luhn check visibly rejects it.
            case "VISA", "MASTERCARD", "AMEX" -> params.put("cardNumber",
                    random().nextInt(8) == 0 ? mistype(TEST_CARDS.get(method)) : TEST_CARDS.get(method));
            case "NEQUI" -> params.put("walletPhone", "3" + (100_000_000 + random().nextInt(900_000_000)));
            default -> params.put("bankCode", pick(PSE_BANK_CODES));
        }
        return new ScenarioCall(ServiceId.PAYMENTS, "validatePaymentMethod", params);
    }

    private ScenarioCall processPayment() {
        Product product = randomProduct();
        BigDecimal amount = product.unitPrice()
                .multiply(BigDecimal.valueOf(1 + random().nextInt(2)))
                .multiply(new BigDecimal("1.19"))
                .setScale(2, RoundingMode.HALF_UP);
        String method = pick(List.of("VISA", "MASTERCARD", "AMEX", "NEQUI", "PSE"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("orderId", knownOrSyntheticOrderId());
        params.put("amount", amount);
        params.put("currency", "COP");
        params.put("method", method);
        if (TEST_CARDS.containsKey(method)) {
            params.put("cardNumber", TEST_CARDS.get(method));
        }
        return new ScenarioCall(ServiceId.PAYMENTS, "processPayment", params);
    }

    private ScenarioCall followUpPaymentCall() {
        Optional<String> transactionId = transactionIds.pick();
        if (transactionId.isEmpty()) {
            return processPayment();
        }
        BigDecimal captured = capturedAmounts.getOrDefault(transactionId.get(), new BigDecimal("100000.00"));
        BigDecimal partial = captured.multiply(new BigDecimal("0.20")).setScale(2, RoundingMode.HALF_UP);
        return new ScenarioCall(ServiceId.PAYMENTS, "refundPayment", Map.of(
                "transactionId", transactionId.get(),
                "amount", partial,
                "reason", pick(List.of("CUSTOMER_RETURN", "DEFECTIVE_ITEM", "PRICE_ADJUSTMENT"))));
    }

    private String knownOrSyntheticOrderId() {
        return orderIds.pick().orElseGet(() -> "ORD-%d-%06d".formatted(Year.now(clock).getValue(), 1_000 + random().nextInt(40)));
    }

    private Product randomProduct() {
        return pick(catalog.products());
    }

    private static String mistype(String cardNumber) {
        char last = cardNumber.charAt(cardNumber.length() - 1);
        char replacement = last == '9' ? '0' : (char) (last + 1);
        return cardNumber.substring(0, cardNumber.length() - 1) + replacement;
    }

    private static <E> E pick(List<E> options) {
        return options.get(random().nextInt(options.size()));
    }

    private static ThreadLocalRandom random() {
        return ThreadLocalRandom.current();
    }

    /**
     * Bounded pool of recently created ids that supports random picks.
     */
    private static final class RecentIds {

        private final int capacity;
        private final List<String> ids = new ArrayList<>();

        private RecentIds(int capacity) {
            this.capacity = capacity;
        }

        synchronized void add(String id) {
            if (ids.size() == capacity) {
                ids.removeFirst();
            }
            ids.add(id);
        }

        synchronized Optional<String> pick() {
            return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(ThreadLocalRandom.current().nextInt(ids.size())));
        }
    }
}
