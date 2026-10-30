package com.monitoring.proxyapi.application.proxy;

import com.monitoring.proxyapi.domain.exception.DomainException;
import com.monitoring.proxyapi.domain.model.AuditLogEntry;
import com.monitoring.proxyapi.domain.model.CallStatus;
import com.monitoring.proxyapi.domain.model.OperationParameters;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.AuditLogRepository;
import com.monitoring.proxyapi.domain.port.MicroserviceProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Auditing proxy that stands in front of any {@link MicroserviceProxy} implementation.
 * <p>
 * Responsibilities, in the order they are applied on every call:
 * <ol>
 *   <li><b>Audit</b>: generate a {@code requestId}, timestamp the call and serialise its input.</li>
 *   <li><b>Access control</b>: reject unknown operations or incomplete requests via {@link OperationPolicy}.</li>
 *   <li><b>Cache</b>: answer pure reads from a short-lived TTL cache.</li>
 *   <li><b>Lazy loading</b>: build the real service only when a request actually needs it.</li>
 *   <li><b>Delegation</b>: forward the call unchanged and record the outcome, success or failure.</li>
 * </ol>
 * The proxy implements exactly the same interface as the real subject, so callers cannot tell them apart.
 *
 * @param <T> result type of the wrapped service
 */
public class LoggingProxy<T> implements MicroserviceProxy<T>, ProxyDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(LoggingProxy.class);
    private static final String MDC_REQUEST_ID = "requestId";

    private final ServiceId serviceId;
    private final Supplier<? extends MicroserviceProxy<T>> realServiceFactory;
    private final OperationPolicy policy;
    private final TtlResponseCache<T> cache;
    private final AuditLogRepository auditLog;
    private final AuditPayloadSerializer serializer;
    private final StackTraceSummarizer stackTraceSummarizer;
    private final Clock clock;

    // ReentrantLock rather than synchronized: on JDK 21 a virtual thread that blocks inside a
    // synchronized block pins its carrier thread, and the real service constructor may be slow.
    private final Lock initializationLock = new ReentrantLock();
    private volatile MicroserviceProxy<T> realService;
    private volatile Instant realServiceInitializedAt;
    private volatile String initializedByRequestId;

    public LoggingProxy(ServiceId serviceId,
                        Supplier<? extends MicroserviceProxy<T>> realServiceFactory,
                        OperationPolicy policy,
                        TtlResponseCache<T> cache,
                        ProxyCollaborators collaborators) {
        this.serviceId = Objects.requireNonNull(serviceId);
        this.realServiceFactory = Objects.requireNonNull(realServiceFactory);
        this.policy = Objects.requireNonNull(policy);
        this.cache = Objects.requireNonNull(cache);
        this.auditLog = collaborators.auditLog();
        this.serializer = collaborators.serializer();
        this.stackTraceSummarizer = collaborators.stackTraceSummarizer();
        this.clock = collaborators.clock();
    }

    @Override
    public T execute(String operation, Object... params) {
        String requestId = UUID.randomUUID().toString();
        Instant timestamp = clock.instant();
        // nanoTime is monotonic; wall-clock differences can go negative after an NTP adjustment.
        long startedAt = System.nanoTime();
        String inputParams = serializer.serializeParams(params);

        InvocationContext.bind(requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        try {
            policy.authorize(operation, OperationParameters.from(params));

            String cacheKey = policy.isCacheable(operation) ? operation + '|' + inputParams : null;
            if (cacheKey != null) {
                Optional<T> cached = cache.get(cacheKey);
                if (cached.isPresent()) {
                    recordSuccess(requestId, operation, timestamp, startedAt, inputParams, cached.get(), true);
                    return cached.get();
                }
            }

            T result = resolveRealService(requestId).execute(operation, params);

            if (cacheKey != null) {
                cache.put(cacheKey, result);
            } else if (!policy.isReadOnly(operation)) {
                // Any write may change what a cached read would return; with a dozen keys at most,
                // dropping everything is cheaper and safer than tracking per-SKU dependencies.
                cache.invalidateAll();
            }

            recordSuccess(requestId, operation, timestamp, startedAt, inputParams, result, false);
            return result;
        } catch (RuntimeException failure) {
            long durationMs = recordFailure(requestId, operation, timestamp, startedAt, inputParams, failure);
            throw new AuditedInvocationException(requestId, serviceId, operation, durationMs, failure);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
        }
    }

    /**
     * Virtual-proxy behaviour: the real subject is created on first demand, mirroring the classic
     * {@code if (realProfile == null) realProfile = new RealProfile()} example, but made thread-safe
     * with double-checked locking because concurrent load simulations can race on the first call.
     */
    private MicroserviceProxy<T> resolveRealService(String requestId) {
        MicroserviceProxy<T> current = realService;
        if (current != null) {
            return current;
        }
        initializationLock.lock();
        try {
            if (realService == null) {
                long started = System.nanoTime();
                MicroserviceProxy<T> created = Objects.requireNonNull(realServiceFactory.get(),
                        "Real service factory for " + serviceId + " returned null");
                realServiceInitializedAt = clock.instant();
                initializedByRequestId = requestId;
                realService = created;
                log.info("Lazy initialization: real {} service instantiated on first demand by request {} in {} ms",
                        serviceId, requestId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
            return realService;
        } finally {
            initializationLock.unlock();
        }
    }

    private void recordSuccess(String requestId, String operation, Instant timestamp, long startedAt,
                               String inputParams, T result, boolean cacheHit) {
        long durationMs = elapsedMillis(startedAt);
        auditLog.append(new AuditLogEntry(requestId, serviceId, operation, durationMs, CallStatus.SUCCESS,
                timestamp, inputParams, serializer.serializeResult(result), null, cacheHit));
        log.info("{} {}.{} completed in {} ms{}", CallStatus.SUCCESS, serviceId, operation, durationMs,
                cacheHit ? " (cache hit)" : "");
    }

    private long recordFailure(String requestId, String operation, Instant timestamp, long startedAt,
                               String inputParams, RuntimeException failure) {
        long durationMs = elapsedMillis(startedAt);
        List<String> summary = stackTraceSummarizer.summarize(failure);
        auditLog.append(new AuditLogEntry(requestId, serviceId, operation, durationMs, CallStatus.ERROR,
                timestamp, inputParams, serializer.serializeError(failure, summary), String.join("\n", summary), false));

        if (failure instanceof DomainException) {
            log.warn("{} {}.{} failed after {} ms: {}", CallStatus.ERROR, serviceId, operation, durationMs, failure.getMessage());
        } else {
            log.error("{} {}.{} failed unexpectedly after {} ms", CallStatus.ERROR, serviceId, operation, durationMs, failure);
        }
        return durationMs;
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    @Override
    public ServiceId serviceId() {
        return serviceId;
    }

    @Override
    public boolean isRealServiceInitialized() {
        return realService != null;
    }

    @Override
    public Optional<Instant> realServiceInitializedAt() {
        return Optional.ofNullable(realServiceInitializedAt);
    }

    @Override
    public Optional<String> initializedByRequestId() {
        return Optional.ofNullable(initializedByRequestId);
    }

    @Override
    public int cachedEntries() {
        return cache.size();
    }

    @Override
    public List<OperationSpec> exposedOperations() {
        return policy.operations();
    }
}
