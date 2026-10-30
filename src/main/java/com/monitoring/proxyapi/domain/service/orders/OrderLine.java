package com.monitoring.proxyapi.domain.service.orders;

import com.monitoring.proxyapi.domain.model.Product;

import java.math.BigDecimal;

public record OrderLine(String sku, String productName, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {

    public static OrderLine of(Product product, int quantity) {
        return new OrderLine(product.sku(), product.name(), quantity, product.unitPrice(),
                product.unitPrice().multiply(BigDecimal.valueOf(quantity)));
    }
}
