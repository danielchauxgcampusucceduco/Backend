package com.monitoring.proxyapi.domain.service.inventory;

import com.monitoring.proxyapi.domain.exception.InsufficientStockException;
import com.monitoring.proxyapi.domain.exception.InvalidRequestParameterException;
import com.monitoring.proxyapi.domain.exception.OperationNotPermittedException;
import com.monitoring.proxyapi.domain.model.OperationParameters;
import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.Product;
import com.monitoring.proxyapi.domain.model.ProductCatalog;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.MicroserviceProxy;
import com.monitoring.proxyapi.domain.service.support.LatencySimulator;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real subject: warehouse stock management for the technology catalog.
 */
public class InventoryService implements MicroserviceProxy<OperationResult> {

    public static final Set<String> SUPPORTED_OPERATIONS = Set.of("checkStock", "reserveStock", "restockItem");

    private static final int LOW_STOCK_THRESHOLD = 10;
    private static final int MAX_UNITS_PER_INBOUND_SHIPMENT = 1_000;
    private static final Duration RESERVATION_HOLD = Duration.ofMinutes(15);
    private static final List<String> WAREHOUSES = List.of("BOG-CEDI-01", "MDE-HUB-02", "CLO-HUB-03");

    private final ProductCatalog catalog;
    private final LatencySimulator latency;
    private final Clock clock;
    private final Map<String, StockLevel> stockBySku = new ConcurrentHashMap<>();
    private final AtomicLong reservationSequence = new AtomicLong(48_200);
    private final AtomicLong purchaseOrderSequence = new AtomicLong(7_310);

    public InventoryService(ProductCatalog catalog, LatencySimulator latency, Clock clock) {
        this.catalog = catalog;
        this.latency = latency;
        this.clock = clock;
        seedInitialStock();
    }

    private void seedInitialStock() {
        List<Product> products = catalog.products();
        for (int index = 0; index < products.size(); index++) {
            int units = Math.max(18, 140 - index * 11);
            stockBySku.put(products.get(index).sku(), new StockLevel(WAREHOUSES.get(index % WAREHOUSES.size()), units));
        }
    }

    @Override
    public OperationResult execute(String operation, Object... params) {
        OperationParameters parameters = OperationParameters.from(params);
        latency.pause();
        Map<String, Object> data = switch (operation) {
            case "checkStock" -> checkStock(parameters);
            case "reserveStock" -> reserveStock(parameters);
            case "restockItem" -> restockItem(parameters);
            default -> throw new OperationNotPermittedException(ServiceId.INVENTORY, operation, SUPPORTED_OPERATIONS);
        };
        return OperationResult.of(ServiceId.INVENTORY, operation, data, clock.instant());
    }

    private Map<String, Object> checkStock(OperationParameters parameters) {
        Product product = catalog.require(parameters.requireText("sku"));
        StockLevel level = stockBySku.get(product.sku());
        StockSnapshot snapshot = level.snapshot();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sku", product.sku());
        data.put("productName", product.name());
        data.put("category", product.category());
        data.put("warehouse", level.warehouse());
        data.put("available", snapshot.available());
        data.put("reserved", snapshot.reserved());
        data.put("lowStock", snapshot.available() < LOW_STOCK_THRESHOLD);
        return data;
    }

    private Map<String, Object> reserveStock(OperationParameters parameters) {
        Product product = catalog.require(parameters.requireText("sku"));
        int quantity = parameters.requirePositiveInt("quantity");
        StockLevel level = stockBySku.get(product.sku());
        StockSnapshot after = level.reserve(product.sku(), quantity);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reservationId", "RSV-%06d".formatted(reservationSequence.incrementAndGet()));
        data.put("sku", product.sku());
        data.put("orderId", parameters.optionalText("orderId", "UNASSIGNED"));
        data.put("reservedQuantity", quantity);
        data.put("remainingAvailable", after.available());
        data.put("warehouse", level.warehouse());
        data.put("holdExpiresAt", clock.instant().plus(RESERVATION_HOLD).toString());
        return data;
    }

    private Map<String, Object> restockItem(OperationParameters parameters) {
        Product product = catalog.require(parameters.requireText("sku"));
        int quantity = parameters.requirePositiveInt("quantity");
        if (quantity > MAX_UNITS_PER_INBOUND_SHIPMENT) {
            throw new InvalidRequestParameterException("quantity",
                    "cannot exceed " + MAX_UNITS_PER_INBOUND_SHIPMENT + " units per inbound shipment");
        }
        StockLevel level = stockBySku.get(product.sku());
        StockSnapshot after = level.restock(quantity);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sku", product.sku());
        data.put("purchaseOrder", parameters.optionalText("purchaseOrder", "PO-%05d".formatted(purchaseOrderSequence.incrementAndGet())));
        data.put("supplier", parameters.optionalText("supplier", "Ingram Micro Colombia"));
        data.put("receivedQuantity", quantity);
        data.put("previousAvailable", after.available() - quantity);
        data.put("newAvailable", after.available());
        data.put("warehouse", level.warehouse());
        data.put("receivedAt", clock.instant().toString());
        return data;
    }

    private record StockSnapshot(int available, int reserved) {
    }

    /**
     * Per-SKU mutable counters. The critical sections are a couple of integer operations and never
     * block, so intrinsic locks are fine here even under virtual threads (no pinning-prone I/O inside).
     */
    private static final class StockLevel {

        private final String warehouse;
        private int available;
        private int reserved;

        private StockLevel(String warehouse, int available) {
            this.warehouse = warehouse;
            this.available = available;
        }

        String warehouse() {
            return warehouse;
        }

        synchronized StockSnapshot snapshot() {
            return new StockSnapshot(available, reserved);
        }

        synchronized StockSnapshot reserve(String sku, int quantity) {
            if (quantity > available) {
                throw new InsufficientStockException(sku, quantity, available);
            }
            available -= quantity;
            reserved += quantity;
            return new StockSnapshot(available, reserved);
        }

        synchronized StockSnapshot restock(int quantity) {
            available += quantity;
            return new StockSnapshot(available, reserved);
        }
    }
}
