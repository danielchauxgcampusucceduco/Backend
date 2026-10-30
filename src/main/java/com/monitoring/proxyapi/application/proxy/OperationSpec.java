package com.monitoring.proxyapi.application.proxy;

import java.util.List;
import java.util.Map;

/**
 * Declarative description of one operation the proxy lets through.
 *
 * @param name               operation name
 * @param requiredParameters parameters that must be present before the real service is even touched
 * @param readOnly           {@code true} when the operation never mutates state
 * @param cacheable          {@code true} when the proxy may answer from its TTL cache
 * @param samplePayload      realistic request body, published in the service catalog for the dashboard
 */
public record OperationSpec(
        String name,
        List<String> requiredParameters,
        boolean readOnly,
        boolean cacheable,
        Map<String, Object> samplePayload) {
}
