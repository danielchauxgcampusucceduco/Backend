package com.monitoring.proxyapi.infrastructure.persistence;

import com.monitoring.proxyapi.domain.model.AuditLogEntry;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.AuditLogQuery;
import com.monitoring.proxyapi.domain.port.AuditLogRepository;
import com.monitoring.proxyapi.domain.port.PageSlice;
import com.monitoring.proxyapi.domain.port.ServiceStatistics;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, lock-free, in-memory audit store.
 * <p>
 * Why not H2 + JPA: the dashboard polls every 3 seconds and the proxies append from hundreds of
 * virtual threads at once. A concurrent deque gives O(1) appends without a connection pool, starts
 * instantly on Railway and keeps the container well inside the free-tier memory budget.
 * <ul>
 *   <li>Entries are kept newest-first, so "latest N" queries stop after N matches.</li>
 *   <li>Aggregates use {@link LongAdder}, which stripes contended updates across cells instead of
 *       retrying a single CAS, and are never recomputed by scanning the log.</li>
 *   <li>The log is capped; the oldest entries are evicted first. Aggregates intentionally keep
 *       counting since startup, so success/error rates stay meaningful after eviction.</li>
 * </ul>
 */
public class InMemoryAuditLogRepository implements AuditLogRepository {

    private final int capacity;
    private final ConcurrentLinkedDeque<AuditLogEntry> entries = new ConcurrentLinkedDeque<>();
    private final Map<String, AuditLogEntry> entriesByRequestId = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private final Map<ServiceId, ServiceCounters> counters = new EnumMap<>(ServiceId.class);

    public InMemoryAuditLogRepository(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        for (ServiceId serviceId : ServiceId.values()) {
            counters.put(serviceId, new ServiceCounters());
        }
    }

    @Override
    public void append(AuditLogEntry entry) {
        entries.addFirst(entry);
        entriesByRequestId.put(entry.requestId(), entry);
        counters.get(entry.serviceId()).record(entry);

        if (size.incrementAndGet() > capacity) {
            AuditLogEntry evicted = entries.pollLast();
            if (evicted != null) {
                size.decrementAndGet();
                entriesByRequestId.remove(evicted.requestId(), evicted);
            }
        }
    }

    @Override
    public List<AuditLogEntry> findLatest(ServiceId serviceId, int limit) {
        return entries.stream()
                .filter(entry -> entry.serviceId() == serviceId)
                .limit(limit)
                .toList();
    }

    @Override
    public PageSlice<AuditLogEntry> search(AuditLogQuery query, int page, int size) {
        List<AuditLogEntry> matches = entries.stream().filter(query::matches).toList();
        long offset = (long) page * size;
        if (offset >= matches.size()) {
            return new PageSlice<>(List.of(), matches.size());
        }
        int end = (int) Math.min(offset + size, matches.size());
        return new PageSlice<>(List.copyOf(matches.subList((int) offset, end)), matches.size());
    }

    @Override
    public Optional<AuditLogEntry> findByRequestId(String requestId) {
        return Optional.ofNullable(entriesByRequestId.get(requestId));
    }

    @Override
    public ServiceStatistics statisticsFor(ServiceId serviceId) {
        return counters.get(serviceId).snapshot(serviceId);
    }

    @Override
    public void clear() {
        entries.clear();
        entriesByRequestId.clear();
        size.set(0);
        counters.values().forEach(ServiceCounters::reset);
    }

    private static final class ServiceCounters {

        private final LongAdder total = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder durationMs = new LongAdder();
        private final LongAdder cacheHits = new LongAdder();

        void record(AuditLogEntry entry) {
            total.increment();
            durationMs.add(entry.durationMs());
            if (entry.failed()) {
                errors.increment();
            }
            if (entry.cacheHit()) {
                cacheHits.increment();
            }
        }

        ServiceStatistics snapshot(ServiceId serviceId) {
            return new ServiceStatistics(serviceId, total.sum(), errors.sum(), durationMs.sum(), cacheHits.sum());
        }

        void reset() {
            total.reset();
            errors.reset();
            durationMs.reset();
            cacheHits.reset();
        }
    }
}
