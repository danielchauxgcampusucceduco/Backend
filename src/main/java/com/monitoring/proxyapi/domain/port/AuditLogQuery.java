package com.monitoring.proxyapi.domain.port;

import com.monitoring.proxyapi.domain.model.AuditLogEntry;
import com.monitoring.proxyapi.domain.model.CallStatus;
import com.monitoring.proxyapi.domain.model.ServiceId;

import java.time.Instant;

/**
 * Optional filters for the audit log. A {@code null} criterion means "do not filter by it".
 * The date range is inclusive on both ends.
 */
public record AuditLogQuery(ServiceId serviceId, CallStatus status, Instant from, Instant to) {

    public static AuditLogQuery unfiltered() {
        return new AuditLogQuery(null, null, null, null);
    }

    public boolean matches(AuditLogEntry entry) {
        return (serviceId == null || entry.serviceId() == serviceId)
                && (status == null || entry.status() == status)
                && (from == null || !entry.timestamp().isBefore(from))
                && (to == null || !entry.timestamp().isAfter(to));
    }
}
