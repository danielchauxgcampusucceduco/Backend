package com.monitoring.proxyapi.domain.exception;

public class ProductNotFoundException extends DomainException {

    public ProductNotFoundException(String sku) {
        super("PRODUCT_NOT_FOUND", ErrorCategory.NOT_FOUND, "No product registered with SKU '" + sku + "'");
    }
}
