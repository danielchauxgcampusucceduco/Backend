package com.monitoring.proxyapi.application.proxy;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.monitoring.proxyapi.domain.exception.InsufficientStockException;
import com.monitoring.proxyapi.domain.exception.OperationNotPermittedException;
import com.monitoring.proxyapi.domain.model.AuditLogEntry;
import com.monitoring.proxyapi.domain.model.CallStatus;
import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.ProductCatalog;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.AuditLogQuery;
import com.monitoring.proxyapi.domain.service.inventory.InventoryService;
import com.monitoring.proxyapi.domain.service.payments.PaymentService;
import com.monitoring.proxyapi.domain.service.support.LatencySimulator;
import com.monitoring.proxyapi.infrastructure.persistence.InMemoryAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingProxyTest {

    private static final String THINKPAD = "LEN-TPX1C-G12";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-30T15:00:00Z"), ZoneOffset.UTC);
    private final AtomicInteger instantiations = new AtomicInteger();
    private InMemoryAuditLogRepository auditLog;
    private ProxyCollaborators collaborators;

    @BeforeEach
    void setUp() {
        auditLog = new InMemoryAuditLogRepository(500);
        JsonMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        collaborators = new ProxyCollaborators(auditLog, new AuditPayloadSerializer(mapper),
                new StackTraceSummarizer("com.monitoring.proxyapi", 4), clock);
    }

    private LoggingProxy<OperationResult> inventoryProxy() {
        return new LoggingProxy<>(ServiceId.INVENTORY,
                () -> {
                    instantiations.incrementAndGet();
                    return new InventoryService(ProductCatalog.technologyStore(), LatencySimulator.none(), clock);
                },
                ServicePolicies.inventory(), new TtlResponseCache<>(Duration.ofSeconds(5), clock), collaborators);
    }

    @Test
    void realServiceIsBuiltOnlyWhenAnAuthorizedCallNeedsIt() {
        LoggingProxy<OperationResult> proxy = inventoryProxy();
        assertThat(proxy.isRealServiceInitialized()).isFalse();

        assertThatThrownBy(() -> proxy.execute("dropWarehouse", Map.of("sku", THINKPAD)))
                .isInstanceOf(AuditedInvocationException.class)
                .hasCauseInstanceOf(OperationNotPermittedException.class);
        assertThat(instantiations).hasValue(0);

        proxy.execute("checkStock", Map.of("sku", THINKPAD));
        proxy.execute("checkStock", Map.of("sku", "DEL-U2724D"));

        assertThat(instantiations).hasValue(1);
        assertThat(proxy.isRealServiceInitialized()).isTrue();
        assertThat(proxy.realServiceInitializedAt()).contains(clock.instant());
        assertThat(proxy.initializedByRequestId()).isPresent();
    }

    @Test
    void successfulCallIsAuditedWithInputAndFullResponse() {
        LoggingProxy<OperationResult> proxy = inventoryProxy();

        OperationResult result = proxy.execute("reserveStock", "sku", THINKPAD, "quantity", 2, "orderId", "ORD-2026-001041");

        AuditLogEntry entry = singleEntry();
        assertThat(entry.status()).isEqualTo(CallStatus.SUCCESS);
        assertThat(entry.requestId()).isEqualTo(InvocationContext.lastRequestId().orElseThrow());
        assertThat(entry.operation()).isEqualTo("reserveStock");
        assertThat(entry.timestamp()).isEqualTo(clock.instant());
        assertThat(entry.inputParams()).contains(THINKPAD).contains("\"quantity\":2");
        assertThat(entry.responsePayload()).contains(String.valueOf(result.get("reservationId")));
        assertThat(entry.stackTraceSummary()).isNull();
    }

    @Test
    void failedCallIsAuditedWithCondensedStackTraceAndRethrown() {
        LoggingProxy<OperationResult> proxy = inventoryProxy();

        assertThatThrownBy(() -> proxy.execute("reserveStock", Map.of("sku", THINKPAD, "quantity", 9_999)))
                .isInstanceOf(AuditedInvocationException.class)
                .hasCauseInstanceOf(InsufficientStockException.class);

        AuditLogEntry entry = singleEntry();
        assertThat(entry.status()).isEqualTo(CallStatus.ERROR);
        assertThat(entry.responsePayload()).contains("INSUFFICIENT_STOCK").contains("stackTrace");
        List<String> lines = entry.stackTraceSummary().lines().toList();
        assertThat(lines).hasSizeBetween(2, 5);
        assertThat(lines.getFirst()).contains("InsufficientStockException");
        assertThat(lines.subList(1, lines.size())).allMatch(line -> line.startsWith("at com.monitoring.proxyapi"));
    }

    @Test
    void stockQueriesAreServedFromCacheUntilAWriteInvalidatesThem() {
        LoggingProxy<OperationResult> proxy = inventoryProxy();

        OperationResult first = proxy.execute("checkStock", Map.of("sku", THINKPAD));
        OperationResult cached = proxy.execute("checkStock", Map.of("sku", THINKPAD));
        assertThat(cached).isSameAs(first);

        proxy.execute("reserveStock", Map.of("sku", THINKPAD, "quantity", 3));
        OperationResult refreshed = proxy.execute("checkStock", Map.of("sku", THINKPAD));

        assertThat((int) refreshed.get("available")).isEqualTo((int) first.get("available") - 3);
        assertThat(auditLog.statisticsFor(ServiceId.INVENTORY).cacheHits()).isEqualTo(1);
    }

    @Test
    void cardNumbersNeverReachTheAuditLogInClear() {
        LoggingProxy<OperationResult> proxy = new LoggingProxy<>(ServiceId.PAYMENTS,
                () -> new PaymentService(LatencySimulator.none(), clock, 0.0),
                ServicePolicies.payments(), TtlResponseCache.disabled(clock), collaborators);

        proxy.execute("validatePaymentMethod", Map.of("method", "VISA", "cardNumber", "4111111111111111"));

        AuditLogEntry entry = singleEntry();
        assertThat(entry.inputParams()).doesNotContain("4111111111111111").contains("************1111");
        assertThat(entry.responsePayload()).contains("\"valid\":true");
    }

    private AuditLogEntry singleEntry() {
        List<AuditLogEntry> entries = auditLog.search(AuditLogQuery.unfiltered(), 0, 10).content();
        assertThat(entries).hasSize(1);
        return entries.getFirst();
    }
}
