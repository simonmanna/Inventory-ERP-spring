package com.inventoryerp.shared.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Standard list query, identical to the Nest {@code PaginationDto}: page (1-based, default 1),
 * pageSize (default 25, max 1000), search, sortBy, sortOrder (asc|desc).
 */
public record PageQuery(
        @Min(1) Integer page,
        @Min(1) @Max(1000) Integer pageSize,
        String search,
        String sortBy,
        @Pattern(regexp = "asc|desc") String sortOrder) {

    public int pageOrDefault() {
        return page == null ? 1 : page;
    }

    public int pageSizeOrDefault() {
        return pageSize == null ? 25 : pageSize;
    }

    public int offset() {
        return (pageOrDefault() - 1) * pageSizeOrDefault();
    }
}
