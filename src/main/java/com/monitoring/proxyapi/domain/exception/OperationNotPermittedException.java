package com.monitoring.proxyapi.domain.exception;

import com.monitoring.proxyapi.domain.model.ServiceId;

import java.util.Collection;
import java.util.TreeSet;

public class OperationNotPermittedException extends DomainException {

    public OperationNotPermittedException(ServiceId serviceId, String operation, Collection<String> allowedOperations) {
        super("OPERATION_NOT_PERMITTED", ErrorCategory.ACCESS_DENIED,
                "Operation '" + operation + "' is not exposed by the " + serviceId
                        + " service. Allowed operations: " + new TreeSet<>(allowedOperations));
    }
}
