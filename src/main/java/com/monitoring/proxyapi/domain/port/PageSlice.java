package com.monitoring.proxyapi.domain.port;

import java.util.List;

/**
 * One page of results plus the total number of matching elements.
 */
public record PageSlice<T>(List<T> content, long totalElements) {
}
