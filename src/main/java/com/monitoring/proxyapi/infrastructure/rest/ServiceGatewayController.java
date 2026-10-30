package com.monitoring.proxyapi.infrastructure.rest;

import com.monitoring.proxyapi.application.proxy.InvocationContext;
import com.monitoring.proxyapi.application.proxy.ProxyDiagnostics;
import com.monitoring.proxyapi.application.proxy.ProxyRegistry;
import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.ServiceId;
import com.monitoring.proxyapi.domain.port.MicroserviceProxy;
import com.monitoring.proxyapi.infrastructure.rest.dto.OperationResponse;
import com.monitoring.proxyapi.infrastructure.rest.dto.ServiceCatalogEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Entry point for business traffic. This controller is the <i>client</i> of the Proxy pattern:
 * it only knows {@link MicroserviceProxy} and is unaware that every call is audited, cached or
 * lazily initialised behind that interface.
 */
@RestController
@RequestMapping("/api/services")
public class ServiceGatewayController {

    static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final ProxyRegistry proxies;

    public ServiceGatewayController(ProxyRegistry proxies) {
        this.proxies = proxies;
    }

    @PostMapping("/inventory/{operation}")
    public ResponseEntity<OperationResponse> inventory(@PathVariable String operation,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return dispatch(ServiceId.INVENTORY, operation, body);
    }

    @PostMapping("/orders/{operation}")
    public ResponseEntity<OperationResponse> orders(@PathVariable String operation,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        return dispatch(ServiceId.ORDERS, operation, body);
    }

    @PostMapping("/payments/{operation}")
    public ResponseEntity<OperationResponse> payments(@PathVariable String operation,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        return dispatch(ServiceId.PAYMENTS, operation, body);
    }

    @GetMapping("/catalog")
    public List<ServiceCatalogEntry> catalog() {
        return Arrays.stream(ServiceId.values())
                .map(serviceId -> {
                    ProxyDiagnostics diagnostics = proxies.diagnosticsFor(serviceId);
                    return new ServiceCatalogEntry(serviceId, "/api/services/" + serviceId.pathSegment() + "/{operation}",
                            diagnostics.isRealServiceInitialized(), diagnostics.exposedOperations());
                })
                .toList();
    }

    private ResponseEntity<OperationResponse> dispatch(ServiceId serviceId, String operation, Map<String, Object> body) {
        MicroserviceProxy<OperationResult> service = proxies.proxyFor(serviceId);
        try {
            OperationResult result = service.execute(operation, body == null ? Map.of() : body);
            String requestId = InvocationContext.lastRequestId().orElse("unavailable");
            return ResponseEntity.ok()
                    .header(REQUEST_ID_HEADER, requestId)
                    .body(OperationResponse.success(requestId, result));
        } finally {
            InvocationContext.clear();
        }
    }
}
