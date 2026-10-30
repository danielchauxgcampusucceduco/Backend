package com.monitoring.proxyapi.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tunables of the proxy layer, bound from {@code app.proxy.*}.
 */
@ConfigurationProperties(prefix = "app.proxy")
public record ProxyProperties(
        @DefaultValue("true") boolean cacheEnabled,
        @DefaultValue("5s") Duration cacheTtl,
        @DefaultValue("10000") int auditCapacity,
        @DefaultValue("25") int latencyMinMs,
        @DefaultValue("320") int latencyMaxMs,
        @DefaultValue("0.10") double paymentFailureRate) {
}
