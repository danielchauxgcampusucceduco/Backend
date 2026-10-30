package com.monitoring.proxyapi.infrastructure.rest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.proxy.latency-min-ms=0",
        "app.proxy.latency-max-ms=2"
})
@AutoConfigureMockMvc
class MetricsApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetAuditLog() throws Exception {
        mockMvc.perform(delete("/api/metrics/logs")).andExpect(status().isNoContent());
    }

    @Test
    void simulatedLoadIsFullyAccountedForInTheSummary() throws Exception {
        mockMvc.perform(post("/api/metrics/simulate-load"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedCalls").value(50));

        mockMvc.perform(get("/api/metrics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCalls").value(50))
                .andExpect(jsonPath("$.degradationThresholdPercent").value(15.0))
                .andExpect(jsonPath("$.services.INVENTORY.totalCalls", notNullValue()))
                .andExpect(jsonPath("$.services.PAYMENTS.last20Calls.length()", lessThanOrEqualTo(20)))
                .andExpect(jsonPath("$.services.ORDERS.realServiceInitialized").value(true));
    }

    @Test
    void logsCanBeFilteredByStatusAndPaginated() throws Exception {
        mockMvc.perform(post("/api/metrics/simulate-load").param("calls", "60")).andExpect(status().isOk());

        mockMvc.perform(get("/api/metrics/logs").param("service", "INVENTORY").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.content[*].serviceId", everyItem(is("INVENTORY"))))
                .andExpect(jsonPath("$.content[0].inputParams", notNullValue()))
                .andExpect(jsonPath("$.content[0].responsePayload", notNullValue()));

        mockMvc.perform(get("/api/metrics/logs").param("status", "ERROR").param("service", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].status", everyItem(is("ERROR"))));

        mockMvc.perform(get("/api/metrics/logs").param("from", "2026-10-30T12:00:00Z").param("to", "2026-10-30T11:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forcedPaymentFailureReturnsBadGatewayCorrelatedWithItsAuditEntry() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/services/payments/processPayment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ORD-2026-001041","amount":10358810.00,"method":"VISA","forceFail":true}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("PAYMENT_GATEWAY_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId", notNullValue()))
                .andReturn();

        String requestId = result.getResponse().getHeader("X-Request-Id");
        mockMvc.perform(get("/api/metrics/logs/{requestId}", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.stackTraceSummary", notNullValue()));
    }

    @Test
    void unknownOperationsAreRejectedByTheProxy() throws Exception {
        mockMvc.perform(post("/api/services/inventory/deleteWarehouse"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("OPERATION_NOT_PERMITTED"));
    }

    @Test
    void successfulCallsExposeTheirRequestId() throws Exception {
        mockMvc.perform(post("/api/services/inventory/checkStock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"RPI-5-8GB\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.sku").value("RPI-5-8GB"));
    }

    @Test
    void vercelPreviewDeploymentsPassTheCorsPreflight() throws Exception {
        mockMvc.perform(options("/api/metrics/summary")
                        .header(HttpHeaders.ORIGIN, "https://observability-proxy-dashboard-git-main.vercel.app")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "https://observability-proxy-dashboard-git-main.vercel.app"));
    }
}
