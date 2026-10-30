package com.monitoring.proxyapi.application.proxy;

import com.monitoring.proxyapi.domain.model.ServiceId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read-only introspection of a proxy's internal state, kept separate from {@code MicroserviceProxy}
 * so that clients of the business contract never see it.
 */
public interface ProxyDiagnostics {

    ServiceId serviceId();

    boolean isRealServiceInitialized();

    Optional<Instant> realServiceInitializedAt();

    Optional<String> initializedByRequestId();

    int cachedEntries();

    List<OperationSpec> exposedOperations();
}
