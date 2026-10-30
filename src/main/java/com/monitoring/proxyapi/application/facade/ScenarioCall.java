package com.monitoring.proxyapi.application.facade;

import com.monitoring.proxyapi.domain.model.ServiceId;

import java.util.Map;

/**
 * One synthetic request produced by the load generator.
 */
public record ScenarioCall(ServiceId serviceId, String operation, Map<String, Object> params) {
}
