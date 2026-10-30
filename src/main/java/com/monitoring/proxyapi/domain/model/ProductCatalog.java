package com.monitoring.proxyapi.domain.model;

import com.monitoring.proxyapi.domain.exception.ProductNotFoundException;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reference data of a technology retailer. It is immutable and therefore safe to share between
 * services and threads without any synchronisation.
 */
public final class ProductCatalog {

    private final Map<String, Product> productsBySku;

    public ProductCatalog(List<Product> products) {
        Map<String, Product> index = new LinkedHashMap<>();
        products.forEach(product -> index.put(product.sku(), product));
        this.productsBySku = Collections.unmodifiableMap(index);
    }

    public static ProductCatalog technologyStore() {
        return new ProductCatalog(List.of(
                new Product("LEN-TPX1C-G12", "Lenovo ThinkPad X1 Carbon Gen 12 (Core Ultra 7, 32 GB)", "LAPTOPS", cop("9899000")),
                new Product("APL-MBP14-M4P", "Apple MacBook Pro 14\" M4 Pro 24 GB / 512 GB", "LAPTOPS", cop("11499000")),
                new Product("DEL-U2724D", "Dell UltraSharp 27 QHD Monitor U2724D", "MONITORS", cop("2349000")),
                new Product("LOG-MXM3S-GRF", "Logitech MX Master 3S Mouse - Graphite", "PERIPHERALS", cop("459900")),
                new Product("SAM-MZV9P2T0", "Samsung 990 PRO 2 TB NVMe M.2 SSD", "STORAGE", cop("899900")),
                new Product("KCH-K8PRO-RGB", "Keychron K8 Pro Wireless Mechanical Keyboard", "PERIPHERALS", cop("519000")),
                new Product("SNY-WH1000XM5-B", "Sony WH-1000XM5 Noise Cancelling Headphones", "AUDIO", cop("1649000")),
                new Product("RPI-5-8GB", "Raspberry Pi 5 Model B 8 GB", "COMPONENTS", cop("489000")),
                new Product("ASU-RTX4070S-12G", "ASUS Dual GeForce RTX 4070 SUPER 12 GB OC", "COMPONENTS", cop("3899000")),
                new Product("ANK-A1340-737", "Anker 737 Power Bank 24,000 mAh 140 W", "ACCESSORIES", cop("529900")),
                new Product("UBQ-U6-PRO", "Ubiquiti UniFi U6 Pro Access Point", "NETWORKING", cop("899000")),
                new Product("KNG-FURY-32G-D5", "Kingston FURY Beast 32 GB DDR5 5600 MT/s", "COMPONENTS", cop("529000"))));
    }

    private static BigDecimal cop(String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    public Optional<Product> find(String sku) {
        return sku == null ? Optional.empty() : Optional.ofNullable(productsBySku.get(sku.trim().toUpperCase(Locale.ROOT)));
    }

    public Product require(String sku) {
        return find(sku).orElseThrow(() -> new ProductNotFoundException(sku));
    }

    public List<Product> products() {
        return List.copyOf(productsBySku.values());
    }

    public List<String> skus() {
        return List.copyOf(productsBySku.keySet());
    }
}
