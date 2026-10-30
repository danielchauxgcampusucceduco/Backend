package com.monitoring.proxyapi.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Identifies each monitored microservice. The path segment is the public name used in REST routes.
 */
public enum ServiceId {

    INVENTORY("inventory"),
    ORDERS("orders"),
    PAYMENTS("payments");

    private final String pathSegment;

    ServiceId(String pathSegment) {
        this.pathSegment = pathSegment;
    }

    public String pathSegment() {
        return pathSegment;
    }

    /**
     * Accepts both the enum constant ({@code PAYMENTS}) and the route segment ({@code payments}),
     * so query strings written by hand while debugging still resolve.
     */
    public static Optional<ServiceId> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim();
        return Arrays.stream(values())
                .filter(id -> id.name().equalsIgnoreCase(normalized) || id.pathSegment.equals(normalized.toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}
