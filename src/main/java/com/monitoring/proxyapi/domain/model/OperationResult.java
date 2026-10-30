package com.monitoring.proxyapi.domain.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Value returned by the three simulated microservices. The payload is kept as an ordered map
 * because each operation exposes a different shape, exactly as heterogeneous downstream APIs do.
 */
public record OperationResult(
        ServiceId serviceId,
        String operation,
        Map<String, Object> data,
        Instant processedAt) {

    public static OperationResult of(ServiceId serviceId, String operation, Map<String, Object> data, Instant processedAt) {
        return new OperationResult(serviceId, operation, Collections.unmodifiableMap(new LinkedHashMap<>(data)), processedAt);
    }

    public Object get(String key) {
        return data.get(key);
    }
}
