package com.monitoring.proxyapi.application.proxy;

import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.MicroserviceProxy;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Single lookup point for the three proxies. Clients receive them typed as {@link MicroserviceProxy},
 * so nothing outside this package can bypass the proxy and reach a real service directly.
 */
public final class ProxyRegistry {

    private final Map<ServiceId, LoggingProxy<OperationResult>> proxies;

    public ProxyRegistry(List<LoggingProxy<OperationResult>> proxies) {
        Map<ServiceId, LoggingProxy<OperationResult>> index = new EnumMap<>(ServiceId.class);
        for (LoggingProxy<OperationResult> proxy : proxies) {
            if (index.put(proxy.serviceId(), proxy) != null) {
                throw new IllegalStateException("Duplicate proxy registered for " + proxy.serviceId());
            }
        }
        for (ServiceId serviceId : ServiceId.values()) {
            if (!index.containsKey(serviceId)) {
                throw new IllegalStateException("No proxy registered for " + serviceId);
            }
        }
        this.proxies = Collections.unmodifiableMap(index);
    }

    public MicroserviceProxy<OperationResult> proxyFor(ServiceId serviceId) {
        return proxies.get(serviceId);
    }

    public ProxyDiagnostics diagnosticsFor(ServiceId serviceId) {
        return proxies.get(serviceId);
    }
}
