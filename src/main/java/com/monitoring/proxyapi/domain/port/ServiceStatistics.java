package com.monitoring.proxyapi.domain.port;

import com.monitoring.proxyapi.domain.model.ServiceId;

/**
 * Running counters for one service since startup (or since the last reset).
 */
public record ServiceStatistics(ServiceId serviceId, long totalCalls, long errorCalls, long totalDurationMs, long cacheHits) {
}
