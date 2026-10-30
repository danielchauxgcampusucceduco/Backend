package com.monitoring.proxyapi.infrastructure.rest.dto;

import com.monitoring.proxyapi.domain.model.CallStatus;
import com.monitoring.proxyapi.domain.model.OperationResult;
import com.monitoring.proxyapi.domain.model.ServiceId;

import java.time.Instant;
import java.util.Map;

/**
 * Body returned by {@code POST /api/services/{service}/{operation}} when the call succeeds.
 */
public record OperationResponse(
        String requestId,
        ServiceId serviceId,
        String operation,
        CallStatus status,
        Map<String, Object> data,
        Instant processedAt) {

    public static OperationResponse success(String requestId, OperationResult result) {
        return new OperationResponse(requestId, result.serviceId(), result.operation(), CallStatus.SUCCESS,
                result.data(), result.processedAt());
    }
}
