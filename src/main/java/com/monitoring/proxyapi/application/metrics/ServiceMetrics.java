package com.monitoring.proxyapi.application.metrics;

import com.monitoring.proxyapi.domain.model.ServiceId;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated health indicators of one service.
 *
 * @param successRate       percentage in [0, 100] rounded to two decimals
 * @param errorRate         percentage in [0, 100] rounded to two decimals
 * @param degraded          {@code true} when {@code errorRate} exceeds the degradation threshold
 * @param last20Calls       most recent calls in chronological order (oldest first), ready to plot
 */
public record ServiceMetrics(
        ServiceId serviceId,
        long totalCalls,
        long successfulCalls,
        long failedCalls,
        double successRate,
        double errorRate,
        double avgResponseTimeMs,
        boolean degraded,
        long cacheHits,
        boolean realServiceInitialized,
        Instant realServiceInitializedAt,
        List<CallSample> last20Calls) {
}
