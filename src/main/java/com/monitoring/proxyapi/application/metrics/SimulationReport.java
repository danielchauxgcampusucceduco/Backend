package com.monitoring.proxyapi.application.metrics;

import com.monitoring.proxyapi.domain.model.ServiceId;

import java.time.Instant;
import java.util.Map;

/**
 * Outcome of {@code POST /api/metrics/simulate-load}.
 */
public record SimulationReport(
        int requestedCalls,
        int succeeded,
        int failed,
        Map<ServiceId, Long> callsByService,
        Map<ServiceId, Long> failuresByService,
        long elapsedMs,
        Instant startedAt) {
}
