package com.monitoring.proxyapi.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitoring.proxyapi.application.facade.LoadScenarioGenerator;
import com.monitoring.proxyapi.application.facade.ObservabilityMetricsFacade;
import com.monitoring.proxyapi.application.proxy.AuditPayloadSerializer;
import com.monitoring.proxyapi.application.proxy.LoggingProxy;
import com.monitoring.proxyapi.application.proxy.ProxyCollaborators;
import com.monitoring.proxyapi.application.proxy.ProxyRegistry;
import com.monitoring.proxyapi.application.proxy.ServicePolicies;
import com.monitoring.proxyapi.application.proxy.StackTraceSummarizer;
import com.monitoring.proxyapi.application.proxy.TtlResponseCache;
import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.ProductCatalog;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.AuditLogRepository;
import com.monitoring.proxyapi.domain.service.inventory.InventoryService;
import com.monitoring.proxyapi.domain.service.orders.OrderService;
import com.monitoring.proxyapi.domain.service.payments.PaymentService;
import com.monitoring.proxyapi.domain.service.support.LatencySimulator;
import com.monitoring.proxyapi.infrastructure.persistence.InMemoryAuditLogRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

/**
 * Composition root of the hexagon.
 * <p>
 * The real services are deliberately <b>not</b> Spring beans. If they were, the container would
 * instantiate them eagerly at startup and the proxy's lazy initialization would be meaningless.
 * Instead each proxy receives a {@code Supplier} and decides itself when the real subject is built.
 */
@Configuration
@EnableConfigurationProperties({ProxyProperties.class, CorsProperties.class})
public class ProxyConfiguration {

    private static final String APPLICATION_PACKAGE = "com.monitoring.proxyapi";
    private static final int STACK_TRACE_FRAMES = 4;

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ProductCatalog productCatalog() {
        return ProductCatalog.technologyStore();
    }

    @Bean
    AuditLogRepository auditLogRepository(ProxyProperties properties) {
        return new InMemoryAuditLogRepository(properties.auditCapacity());
    }

    @Bean
    ProxyCollaborators proxyCollaborators(AuditLogRepository auditLog, ObjectMapper objectMapper, Clock clock) {
        return new ProxyCollaborators(
                auditLog,
                new AuditPayloadSerializer(objectMapper),
                new StackTraceSummarizer(APPLICATION_PACKAGE, STACK_TRACE_FRAMES),
                clock);
    }

    @Bean
    ProxyRegistry proxyRegistry(ProxyProperties properties, ProductCatalog catalog, ProxyCollaborators collaborators, Clock clock) {
        LatencySimulator latency = new LatencySimulator(properties.latencyMinMs(), properties.latencyMaxMs());

        LoggingProxy<OperationResult> inventory = new LoggingProxy<>(ServiceId.INVENTORY,
                () -> new InventoryService(catalog, latency, clock),
                ServicePolicies.inventory(), cache(properties, clock), collaborators);

        LoggingProxy<OperationResult> orders = new LoggingProxy<>(ServiceId.ORDERS,
                () -> new OrderService(catalog, latency, clock),
                ServicePolicies.orders(), cache(properties, clock), collaborators);

        LoggingProxy<OperationResult> payments = new LoggingProxy<>(ServiceId.PAYMENTS,
                () -> new PaymentService(latency, clock, properties.paymentFailureRate()),
                ServicePolicies.payments(), cache(properties, clock), collaborators);

        return new ProxyRegistry(List.of(inventory, orders, payments));
    }

    @Bean
    ObservabilityMetricsFacade observabilityMetricsFacade(ProxyRegistry proxyRegistry, AuditLogRepository auditLog,
                                                          ProductCatalog catalog, Clock clock) {
        return new ObservabilityMetricsFacade(proxyRegistry, auditLog, new LoadScenarioGenerator(catalog, clock), clock);
    }

    private static TtlResponseCache<OperationResult> cache(ProxyProperties properties, Clock clock) {
        return properties.cacheEnabled()
                ? new TtlResponseCache<>(properties.cacheTtl(), clock)
                : TtlResponseCache.disabled(clock);
    }
}
