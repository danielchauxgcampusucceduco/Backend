package com.monitoring.proxyapi.application.metrics;

import com.monitoring.proxyapi.domain.model.AuditLogEntry;
import com.monitoring.proxyapi.domain.model.CallStatus;

import java.time.Instant;

/**
 * Lightweight point used by the dashboard line chart.
 */
public record CallSample(String requestId, String operation, long durationMs, CallStatus status, Instant timestamp, boolean cacheHit) {

    public static CallSample from(AuditLogEntry entry) {
        return new CallSample(entry.requestId(), entry.operation(), entry.durationMs(), entry.status(), entry.timestamp(), entry.cacheHit());
    }
}
