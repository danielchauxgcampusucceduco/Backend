package com.monitoring.proxyapi.domain.service.orders;

import com.monitoring.proxyapi.domain.exception.IllegalOrderStateException;
import com.monitoring.proxyapi.domain.exception.InvalidRequestParameterException;
import com.monitoring.proxyapi.domain.exception.OperationNotPermittedException;
import com.monitoring.proxyapi.domain.exception.OrderNotFoundException;
import com.monitoring.proxyapi.domain.model.OperationParameters;
import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.Product;
import com.monitoring.proxyapi.domain.model.ProductCatalog;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.MicroserviceProxy;
import com.monitoring.proxyapi.domain.service.support.LatencySimulator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real subject: order lifecycle for the e-commerce checkout.
 */
public class OrderService implements MicroserviceProxy<OperationResult> {

    public static final Set<String> SUPPORTED_OPERATIONS = Set.of("createOrder", "cancelOrder", "getOrderStatus");

    private static final BigDecimal VAT_RATE = new BigDecimal("0.19");
    private static final int MAX_LINES_PER_ORDER = 10;
    private static final int MAX_UNITS_PER_LINE = 25;
    private static final String CURRENCY = "COP";

    private final ProductCatalog catalog;
    private final LatencySimulator latency;
    private final Clock clock;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong orderSequence = new AtomicLong(1_040);

    public OrderService(ProductCatalog catalog, LatencySimulator latency, Clock clock) {
        this.catalog = catalog;
        this.latency = latency;
        this.clock = clock;
    }

    @Override
    public OperationResult execute(String operation, Object... params) {
        OperationParameters parameters = OperationParameters.from(params);
        latency.pause();
        Order order = switch (operation) {
            case "createOrder" -> createOrder(parameters);
            case "cancelOrder" -> cancelOrder(parameters);
            case "getOrderStatus" -> findOrder(parameters.requireText("orderId"));
            default -> throw new OperationNotPermittedException(ServiceId.ORDERS, operation, SUPPORTED_OPERATIONS);
        };
        return OperationResult.of(ServiceId.ORDERS, operation, describe(order), clock.instant());
    }

    private Order createOrder(OperationParameters parameters) {
        String customerId = parameters.requireText("customerId");
        List<Map<String, Object>> items = parameters.requireObjectList("items");
        if (items.size() > MAX_LINES_PER_ORDER) {
            throw new InvalidRequestParameterException("items", "cannot contain more than " + MAX_LINES_PER_ORDER + " lines");
        }

        List<OrderLine> lines = new ArrayList<>(items.size());
        for (int index = 0; index < items.size(); index++) {
            OperationParameters item = OperationParameters.from(items.get(index));
            Product product = catalog.require(item.requireText("sku"));
            int quantity = item.requirePositiveInt("quantity");
            if (quantity > MAX_UNITS_PER_LINE) {
                throw new InvalidRequestParameterException("items[" + index + "].quantity",
                        "cannot exceed " + MAX_UNITS_PER_LINE + " units per line");
            }
            lines.add(OrderLine.of(product, quantity));
        }

        BigDecimal subtotal = lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vat = subtotal.multiply(VAT_RATE).setScale(2, RoundingMode.HALF_UP);
        Instant now = clock.instant();
        String orderId = "ORD-%d-%06d".formatted(Year.now(clock).getValue(), orderSequence.incrementAndGet());

        Order order = new Order(orderId, customerId, parameters.optionalText("shippingCity", "Bogota D.C."),
                List.copyOf(lines), subtotal, vat, subtotal.add(vat), CURRENCY, OrderStatus.PENDING_PAYMENT, now, now, null);
        orders.put(orderId, order);
        return order;
    }

    private Order cancelOrder(OperationParameters parameters) {
        String orderId = parameters.requireText("orderId");
        String reason = parameters.optionalText("reason", "CUSTOMER_REQUEST");
        return orders.compute(orderId, (id, current) -> {
            if (current == null) {
                throw new OrderNotFoundException(id);
            }
            if (current.status() == OrderStatus.CANCELLED) {
                throw new IllegalOrderStateException(id, current.status().name(), "cancelled again");
            }
            return current.cancel(reason, clock.instant());
        });
    }

    private Order findOrder(String orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    private Map<String, Object> describe(Order order) {
        List<Map<String, Object>> lines = order.lines().stream().map(line -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("sku", line.sku());
            view.put("productName", line.productName());
            view.put("quantity", line.quantity());
            view.put("unitPrice", line.unitPrice());
            view.put("lineTotal", line.lineTotal());
            return view;
        }).toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.orderId());
        data.put("customerId", order.customerId());
        data.put("status", order.status().name());
        data.put("shippingCity", order.shippingCity());
        data.put("lines", lines);
        data.put("totalUnits", order.totalUnits());
        data.put("subtotal", order.subtotal());
        data.put("vat", order.vat());
        data.put("total", order.total());
        data.put("currency", order.currency());
        data.put("createdAt", order.createdAt().toString());
        data.put("updatedAt", order.updatedAt().toString());
        if (order.cancellationReason() != null) {
            data.put("cancellationReason", order.cancellationReason());
        }
        return data;
    }
}
