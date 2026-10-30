package com.monitoring.proxyapi.infrastructure.rest;

import com.monitoring.proxyapi.application.facade.ObservabilityMetricsFacade;
import com.monitoring.proxyapi.application.metrics.MetricsSummary;
import com.monitoring.proxyapi.application.metrics.SimulationReport;
import com.monitoring.proxyapi.domain.exception.InvalidRequestParameterException;
import com.monitoring.proxyapi.domain.model.AuditLogEntry;
import com.monitoring.proxyapi.domain.model.CallStatus;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.AuditLogQuery;
import com.monitoring.proxyapi.domain.port.AuditLogRepository;
import com.monitoring.proxyapi.infrastructure.rest.dto.PageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Read side of the observability platform plus the load simulation trigger.
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ObservabilityMetricsFacade metricsFacade;
    private final AuditLogRepository auditLog;

    public MetricsController(ObservabilityMetricsFacade metricsFacade, AuditLogRepository auditLog) {
        this.metricsFacade = metricsFacade;
        this.auditLog = auditLog;
    }

    @GetMapping("/summary")
    public MetricsSummary summary() {
        return metricsFacade.summary();
    }

    @GetMapping("/logs")
    public PageResponse<AuditLogEntry> logs(
            @RequestParam(required = false) ServiceId service,
            @RequestParam(required = false) CallStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (page < 0) {
            throw new InvalidRequestParameterException("page", "must be zero or positive");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidRequestParameterException("size", "must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestParameterException("from", "must not be later than 'to'");
        }
        AuditLogQuery query = new AuditLogQuery(service, status, from, to);
        return PageResponse.of(auditLog.search(query, page, size), page, size);
    }

    @GetMapping("/logs/{requestId}")
    public ResponseEntity<AuditLogEntry> logByRequestId(@PathVariable String requestId) {
        return ResponseEntity.of(auditLog.findByRequestId(requestId));
    }

    @PostMapping("/simulate-load")
    public SimulationReport simulateLoad(@RequestParam(defaultValue = "50") int calls) {
        if (calls < 1 || calls > ObservabilityMetricsFacade.MAX_SIMULATED_CALLS) {
            throw new InvalidRequestParameterException("calls",
                    "must be between 1 and " + ObservabilityMetricsFacade.MAX_SIMULATED_CALLS);
        }
        return metricsFacade.simulateLoad(calls);
    }

    @DeleteMapping("/logs")
    public ResponseEntity<Void> reset() {
        metricsFacade.reset();
        return ResponseEntity.noContent().build();
    }
}
