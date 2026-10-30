package com.monitoring.proxyapi.domain.port;

/**
 * Common contract shared by every real microservice and by the proxy that stands in front of it.
 * <p>
 * Keeping one single signature is what makes the Proxy pattern transparent: REST controllers and
 * the metrics facade depend on this abstraction only, so they never know (nor need to know) whether
 * they hold the real subject or the auditing surrogate wrapping it.
 *
 * @param <T> type of the value produced by an operation
 */
public interface MicroserviceProxy<T> {

    /**
     * Executes a named business operation.
     *
     * @param operation operation name exposed by the service (e.g. {@code checkStock})
     * @param params    either a single JSON-like {@code Map<String, Object>} or alternating
     *                  key/value pairs ({@code "sku", "LEN-TPX1C-G12", "quantity", 2})
     * @return the operation result
     */
    T execute(String operation, Object... params);
}
