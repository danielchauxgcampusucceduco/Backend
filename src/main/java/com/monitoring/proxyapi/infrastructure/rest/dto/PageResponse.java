package com.monitoring.proxyapi.infrastructure.rest.dto;

import com.monitoring.proxyapi.domain.port.PageSlice;

import java.util.List;

/**
 * Pagination envelope with the field names the dashboard expects.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> of(PageSlice<T> slice, int page, int size) {
        int totalPages = (int) Math.ceil((double) slice.totalElements() / size);
        return new PageResponse<>(slice.content(), page, size, slice.totalElements(), totalPages,
                page == 0, page >= totalPages - 1);
    }
}
