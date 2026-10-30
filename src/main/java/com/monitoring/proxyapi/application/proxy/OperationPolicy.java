package com.monitoring.proxyapi.application.proxy;

import com.monitoring.proxyapi.domain.exception.InvalidRequestParameterException;
import com.monitoring.proxyapi.domain.exception.OperationNotPermittedException;
import com.monitoring.proxyapi.domain.model.OperationParameters;
import com.monitoring.proxyapi.domain.model.ServiceId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Access-control rules enforced by the proxy before delegating.
 * <p>
 * Rejecting unknown operations and incomplete requests at this point has two benefits: the real
 * service is never instantiated for a request that would fail anyway (lazy loading stays lazy), and
 * every rejection is still audited, which is precisely what a protection proxy is for.
 */
public final class OperationPolicy {

    private final ServiceId serviceId;
    private final Map<String, OperationSpec> specsByName;

    private OperationPolicy(ServiceId serviceId, Map<String, OperationSpec> specsByName) {
        this.serviceId = serviceId;
        this.specsByName = Collections.unmodifiableMap(specsByName);
    }

    public static Builder forService(ServiceId serviceId) {
        return new Builder(serviceId);
    }

    public void authorize(String operation, OperationParameters parameters) {
        OperationSpec spec = specsByName.get(operation);
        if (spec == null) {
            throw new OperationNotPermittedException(serviceId, operation, specsByName.keySet());
        }
        for (String required : spec.requiredParameters()) {
            if (!parameters.has(required)) {
                throw InvalidRequestParameterException.missing(required);
            }
        }
    }

    public boolean isCacheable(String operation) {
        OperationSpec spec = specsByName.get(operation);
        return spec != null && spec.cacheable();
    }

    public boolean isReadOnly(String operation) {
        OperationSpec spec = specsByName.get(operation);
        return spec != null && spec.readOnly();
    }

    public List<OperationSpec> operations() {
        return List.copyOf(specsByName.values());
    }

    public static final class Builder {

        private final ServiceId serviceId;
        private final Map<String, OperationSpec> specs = new LinkedHashMap<>();

        private Builder(ServiceId serviceId) {
            this.serviceId = serviceId;
        }

        public Builder query(String name, boolean cacheable, Map<String, Object> sample, String... requiredParameters) {
            specs.put(name, new OperationSpec(name, List.of(requiredParameters), true, cacheable, sample));
            return this;
        }

        public Builder command(String name, Map<String, Object> sample, String... requiredParameters) {
            specs.put(name, new OperationSpec(name, List.of(requiredParameters), false, false, sample));
            return this;
        }

        public OperationPolicy build() {
            if (specs.isEmpty()) {
                throw new IllegalStateException("A policy for " + serviceId + " must expose at least one operation");
            }
            return new OperationPolicy(serviceId, new LinkedHashMap<>(specs));
        }
    }
}
