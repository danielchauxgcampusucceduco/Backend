package com.monitoring.proxyapi.application.proxy;

import com.monitoring.proxyapi.domain.port.AuditLogRepository;

import java.time.Clock;

/**
 * Infrastructure every logging proxy shares, grouped to keep the proxy constructor readable.
 */
public record ProxyCollaborators(
        AuditLogRepository auditLog,
        AuditPayloadSerializer serializer,
        StackTraceSummarizer stackTraceSummarizer,
        Clock clock) {
}
