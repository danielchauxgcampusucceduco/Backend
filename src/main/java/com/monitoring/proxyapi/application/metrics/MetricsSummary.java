package com.monitoring.proxyapi.application.metrics;

import com.monitoring.proxyapi.domain.model.ServiceId;

import java.time.Instant;
import java.util.Map;

/**
 * Response of {@code GET /api/metrics/summary}: one {@link ServiceMetrics} per monitored service.
 */
public record MetricsSummary(
        Instant generatedAt,
        double degradationThresholdPercent,
        long totalCalls,
        Map<ServiceId, ServiceMetrics> services) {
}
