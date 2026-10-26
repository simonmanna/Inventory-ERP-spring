package com.inventoryerp.license.shared;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * List envelope, identical in shape to the ERP's {@code PageResult}, so the shared TypeScript types
 * cover both services.
 */
public record PageResult<T>(List<T> items, long total, int page, int pageSize) {

    public static <E, T> PageResult<T> of(Page<E> page, java.util.function.Function<E, T> mapper) {
        return new PageResult<>(
                page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(),
                page.getNumber() + 1,
                page.getSize());
    }

    public static <T> PageResult<T> of(List<T> items) {
        return new PageResult<>(items, items.size(), 1, items.size());
    }
}
