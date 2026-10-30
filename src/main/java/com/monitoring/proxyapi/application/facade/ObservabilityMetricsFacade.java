package com.monitoring.proxyapi.application.facade;

import com.monitoring.proxyapi.application.metrics.CallSample;
import com.monitoring.proxyapi.application.metrics.MetricsSummary;
import com.monitoring.proxyapi.application.metrics.ServiceMetrics;
import com.monitoring.proxyapi.application.metrics.SimulationReport;
import com.monitoring.proxyapi.application.proxy.AuditedInvocationException;
import com.monitoring.proxyapi.application.proxy.ProxyDiagnostics;
import com.monitoring.proxyapi.application.proxy.ProxyRegistry;
import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.AuditLogRepository;
import com.monitoring.proxyapi.domain.port.ServiceStatistics;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Facade over the observability subsystems: the three service proxies, the load generator and the
 * audit log repository.
 * <p>
 * <b>Facade vs Proxy.</b> A proxy exposes <i>the same interface</i> as one object in order to control
 * access to it (auditing, caching, lazy loading); the client cannot tell the two apart. This facade
 * exposes a <i>new, simplified interface</i> ({@code simulateLoad}, {@code summary}, {@code reset})
 * that orchestrates several subsystems the REST controller would otherwise have to coordinate
 * itself. Note that the facade talks to the proxies, never to the real services: the two patterns
 * compose rather than compete.
 */
public class ObservabilityMetricsFacade {

    public static final double DEGRADATION_THRESHOLD_PERCENT = 15.0;
    public static final int MAX_SIMULATED_CALLS = 500;
    private static final int LAST_CALLS_WINDOW = 20;
    private static final double PRIMARY_WAVE_SHARE = 0.6;

    private final ProxyRegistry proxies;
    private final AuditLogRepository auditLog;
    private final LoadScenarioGenerator scenarioGenerator;
    private final Clock clock;

    public ObservabilityMetricsFacade(ProxyRegistry proxies, AuditLogRepository auditLog,
                                      LoadScenarioGenerator scenarioGenerator, Clock clock) {
        this.proxies = proxies;
        this.auditLog = auditLog;
        this.scenarioGenerator = scenarioGenerator;
        this.clock = clock;
    }

    public SimulationReport simulateLoad(int requestedCalls) {
        if (requestedCalls < 1 || requestedCalls > MAX_SIMULATED_CALLS) {
            throw new IllegalArgumentException("calls must be between 1 and " + MAX_SIMULATED_CALLS);
        }
        Instant startedAt = clock.instant();
        long started = System.nanoTime();

        int primarySize = (int) Math.ceil(requestedCalls * PRIMARY_WAVE_SHARE);
        List<CallOutcome> outcomes = new ArrayList<>(requestedCalls);
        outcomes.addAll(dispatch(scenarioGenerator.primaryWave(primarySize)));
        outcomes.addAll(dispatch(scenarioGenerator.followUpWave(requestedCalls - primarySize)));

        int failed = (int) outcomes.stream().filter(outcome -> !outcome.succeeded()).count();
        return new SimulationReport(
                requestedCalls,
                requestedCalls - failed,
                failed,
                countByService(outcomes, outcome -> true),
                countByService(outcomes, outcome -> !outcome.succeeded()),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                startedAt);
    }

    public MetricsSummary summary() {
        Map<ServiceId, ServiceMetrics> services = new EnumMap<>(ServiceId.class);
        long totalCalls = 0;
        for (ServiceId serviceId : ServiceId.values()) {
            ServiceMetrics metrics = metricsFor(serviceId);
            services.put(serviceId, metrics);
            totalCalls += metrics.totalCalls();
        }
        return new MetricsSummary(clock.instant(), DEGRADATION_THRESHOLD_PERCENT, totalCalls, services);
    }

    public void reset() {
        auditLog.clear();
    }

    /**
     * Runs one wave concurrently on virtual threads. Each simulated call sleeps 25-320 ms; on a
     * platform thread pool those sleeps would park real OS threads, whereas virtual threads simply
     * unmount, so a wave of hundreds of calls finishes in roughly the time of the slowest call.
     */
    private List<CallOutcome> dispatch(List<ScenarioCall> calls) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CallOutcome>> futures = calls.stream()
                    .map(call -> executor.submit(() -> invoke(call)))
                    .toList();
            List<CallOutcome> outcomes = new ArrayList<>(futures.size());
            for (Future<CallOutcome> future : futures) {
                outcomes.add(await(future));
            }
            return outcomes;
        }
    }

    private CallOutcome invoke(ScenarioCall call) {
        try {
            OperationResult result = proxies.proxyFor(call.serviceId()).execute(call.operation(), call.params());
            scenarioGenerator.registerOutcome(result);
            return new CallOutcome(call.serviceId(), true);
        } catch (AuditedInvocationException alreadyAudited) {
            return new CallOutcome(call.serviceId(), false);
        }
    }

    private static CallOutcome await(Future<CallOutcome> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Load simulation interrupted", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Simulated call failed outside the proxy", ex.getCause());
        }
    }

    private ServiceMetrics metricsFor(ServiceId serviceId) {
        ServiceStatistics stats = auditLog.statisticsFor(serviceId);
        ProxyDiagnostics diagnostics = proxies.diagnosticsFor(serviceId);

        List<CallSample> lastCalls = new ArrayList<>(auditLog.findLatest(serviceId, LAST_CALLS_WINDOW).stream()
                .map(CallSample::from)
                .toList());
        lastCalls.sort((left, right) -> left.timestamp().compareTo(right.timestamp()));

        long total = stats.totalCalls();
        double errorRate = percentage(stats.errorCalls(), total);
        double successRate = total == 0 ? 0.0 : round(100.0 - errorRate, 2);
        double avgResponse = total == 0 ? 0.0 : round((double) stats.totalDurationMs() / total, 1);

        return new ServiceMetrics(
                serviceId,
                total,
                total - stats.errorCalls(),
                stats.errorCalls(),
                successRate,
                errorRate,
                avgResponse,
                errorRate > DEGRADATION_THRESHOLD_PERCENT,
                stats.cacheHits(),
                diagnostics.isRealServiceInitialized(),
                diagnostics.realServiceInitializedAt().orElse(null),
                List.copyOf(lastCalls));
    }

    private static Map<ServiceId, Long> countByService(List<CallOutcome> outcomes, Predicate<CallOutcome> filter) {
        Map<ServiceId, Long> counts = new EnumMap<>(ServiceId.class);
        for (ServiceId serviceId : ServiceId.values()) {
            counts.put(serviceId, 0L);
        }
        counts.putAll(outcomes.stream()
                .filter(filter)
                .collect(Collectors.groupingBy(CallOutcome::serviceId, Collectors.counting())));
        return counts;
    }

    private static double percentage(long part, long total) {
        return total == 0 ? 0.0 : round(part * 100.0 / total, 2);
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private record CallOutcome(ServiceId serviceId, boolean succeeded) {
    }
}
