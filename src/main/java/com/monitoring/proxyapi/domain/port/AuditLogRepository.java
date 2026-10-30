package com.monitoring.proxyapi.domain.port;

import com.monitoring.proxyapi.domain.model.AuditLogEntry;
import com.monitoring.proxyapi.domain.model.ServiceId;

import java.util.List;
import java.util.Optional;

/**
 * Output port where the logging proxy stores its audit trail. The application layer never
 * depends on a concrete storage technology.
 */
public interface AuditLogRepository {

    void append(AuditLogEntry entry);

    /**
     * @return up to {@code limit} entries of the given service, newest first
     */
    List<AuditLogEntry> findLatest(ServiceId serviceId, int limit);

    /**
     * @return the requested page of entries matching the query, newest first
     */
    PageSlice<AuditLogEntry> search(AuditLogQuery query, int page, int size);

    Optional<AuditLogEntry> findByRequestId(String requestId);

    ServiceStatistics statisticsFor(ServiceId serviceId);

    void clear();
}
