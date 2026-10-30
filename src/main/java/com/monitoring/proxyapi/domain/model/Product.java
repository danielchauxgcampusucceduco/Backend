package com.monitoring.proxyapi.domain.model;

import java.math.BigDecimal;

/**
 * Catalog entry shared by the inventory and order services. Prices are expressed in Colombian pesos (COP).
 */
public record Product(String sku, String name, String category, BigDecimal unitPrice) {
}
