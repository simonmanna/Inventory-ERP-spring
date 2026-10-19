package com.inventoryerp.shared.web;

import java.util.List;

/** Wire shape {@code {data: T[], meta: {page, pageSize, total, totalPages}}} (packages/shared/src/pagination.ts). */
public record PageResult<T>(List<T> data, Meta meta) {

    public record Meta(int page, int pageSize, long total, int totalPages) {}

    public static <T> PageResult<T> of(List<T> data, PageQuery query, long total) {
        int size = query.pageSizeOrDefault();
        int totalPages = (int) Math.ceil(total / (double) size);
        return new PageResult<>(data, new Meta(query.pageOrDefault(), size, total, totalPages));
    }
}
