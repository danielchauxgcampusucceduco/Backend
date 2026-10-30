package com.monitoring.proxyapi.application.proxy;

import com.monitoring.proxyapi.domain.model.ServiceId;

import java.util.List;
import java.util.Map;

/**
 * Operation whitelist of each microservice. Only {@code checkStock} is cacheable: it is a pure read
 * whose staleness is bounded by the cache TTL and by the invalidation triggered on every inventory write.
 * {@code getOrderStatus} is read-only too, but an order can be cancelled at any moment, so it is never cached.
 */
public final class ServicePolicies {

    private ServicePolicies() {
    }

    public static OperationPolicy inventory() {
        return OperationPolicy.forService(ServiceId.INVENTORY)
                .query("checkStock", true,
                        Map.of("sku", "LEN-TPX1C-G12"),
                        "sku")
                .command("reserveStock",
                        Map.of("sku", "DEL-U2724D", "quantity", 2, "orderId", "ORD-2026-001041"),
                        "sku", "quantity")
                .command("restockItem",
                        Map.of("sku", "SAM-MZV9P2T0", "quantity", 40, "supplier", "Intcomex Colombia"),
                        "sku", "quantity")
                .build();
    }

    public static OperationPolicy orders() {
        return OperationPolicy.forService(ServiceId.ORDERS)
                .command("createOrder",
                        Map.of("customerId", "CUS-48213",
                                "shippingCity", "Medellin",
                                "items", List.of(
                                        Map.of("sku", "APL-MBP14-M4P", "quantity", 1),
                                        Map.of("sku", "LOG-MXM3S-GRF", "quantity", 2))),
                        "customerId", "items")
                .command("cancelOrder",
                        Map.of("orderId", "ORD-2026-001041", "reason", "PAYMENT_TIMEOUT"),
                        "orderId")
                .query("getOrderStatus", false,
                        Map.of("orderId", "ORD-2026-001041"),
                        "orderId")
                .build();
    }

    public static OperationPolicy payments() {
        return OperationPolicy.forService(ServiceId.PAYMENTS)
                .command("processPayment",
                        Map.of("orderId", "ORD-2026-001041", "amount", 14774091.00, "currency", "COP",
                                "method", "VISA", "cardNumber", "4111111111111111"),
                        "orderId", "amount", "method")
                .command("refundPayment",
                        Map.of("transactionId", "TXN-9F2C41A07B3D", "amount", 459900.00, "reason", "DEFECTIVE_ITEM"),
                        "transactionId")
                .query("validatePaymentMethod", false,
                        Map.of("method", "MASTERCARD", "cardNumber", "5500005555555559"),
                        "method")
                .build();
    }
}
