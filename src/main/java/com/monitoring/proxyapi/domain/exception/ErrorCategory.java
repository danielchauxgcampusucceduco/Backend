package com.monitoring.proxyapi.domain.exception;

/**
 * Transport-agnostic classification of domain failures. The REST adapter translates each category
 * into an HTTP status, which keeps the domain free of any web dependency.
 */
public enum ErrorCategory {
    INVALID_REQUEST,
    ACCESS_DENIED,
    NOT_FOUND,
    CONFLICT,
    BUSINESS_RULE_VIOLATION,
    UPSTREAM_FAILURE
}
