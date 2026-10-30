package com.monitoring.proxyapi.domain.service.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Immutable order snapshot; state transitions return a new instance so the map holding orders can
 * swap them atomically with {@code compute}.
 */
public record Order(
        String orderId,
        String customerId,
        String shippingCity,
        List<OrderLine> lines,
        BigDecimal subtotal,
        BigDecimal vat,
        BigDecimal total,
        String currency,
        OrderStatus status,
        Instant createdAt,
        Instant updatedAt,
        String cancellationReason) {

    public Order cancel(String reason, Instant at) {
        return new Order(orderId, customerId, shippingCity, lines, subtotal, vat, total, currency,
                OrderStatus.CANCELLED, createdAt, at, reason);
    }

    public int totalUnits() {
        return lines.stream().mapToInt(OrderLine::quantity).sum();
    }
}
