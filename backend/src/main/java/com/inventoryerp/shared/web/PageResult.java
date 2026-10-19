package com.inventoryerp.shared.web;

import java.util.List;

/** Wire shape {@code {data: T[], meta: {page, pageSize, total, totalPages}}} (packages/shared/src/pagination.ts). */
public record PageResult<T>(List<T> data, Meta meta) {

    public record Meta(int page, int pageSize, long total, int totalPages) {}

    public static <T> PageResult<T> of(List<T> data, PageQuery query, long total) {
        int size = query.pageSizeOrDefault();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) size));
        return new PageResult<>(data, new Meta(query.pageOrDefault(), size, total, totalPages));
    }

    /** Nest {@code meta} with explicit page/pageSize ({@code totalPages = max(1, ceil(total / pageSize))}). */
    public static <T> PageResult<T> of(List<T> data, int page, int pageSize, long total) {
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        return new PageResult<>(data, new Meta(page, pageSize, total, totalPages));
    }
}
