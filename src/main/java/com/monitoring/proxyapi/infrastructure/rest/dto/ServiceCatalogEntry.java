package com.monitoring.proxyapi.infrastructure.rest.dto;

import com.monitoring.proxyapi.application.proxy.OperationSpec;
import com.monitoring.proxyapi.domain.model.ServiceId;

import java.util.List;

/**
 * Describes the operations exposed by one service, used by the dashboard's manual trigger panel.
 */
public record ServiceCatalogEntry(
        ServiceId serviceId,
        String endpoint,
        boolean realServiceInitialized,
        List<OperationSpec> operations) {
}
