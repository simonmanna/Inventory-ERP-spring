package com.inventoryerp.kernel.crud;

import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * Validates a raw query string against the Nest {@code PaginationDto} (+ extra string fields) under the
 * global {@code ValidationPipe({whitelist, forbidNonWhitelisted, transform})}: unknown keys are rejected
 * ({@code property x should not exist}), numbers go through {@code Number()}, and class-validator messages
 * are reported per property in reverse decorator order.
 */
public final class QueryParams {

    private static final Set<String> PAGINATION = Set.of("page", "pageSize", "search", "sortBy", "sortOrder");

    private QueryParams() {}

    /** {@code PaginationDto} plus the given extra optional string fields. */
    public static PageQuery page(Map<String, String> raw, String... extraStringFields) {
        Set<String> allowed = new LinkedHashSet<>(PAGINATION);
        allowed.addAll(List.of(extraStringFields));
        List<String> errors = new ArrayList<>();
        for (String k : raw.keySet()) {
            if (!allowed.contains(k)) {
                errors.add("property " + k + " should not exist");
            }
        }
        Integer page = integer(raw, "page", 1, null, errors);
        Integer pageSize = integer(raw, "pageSize", 1, 1000, errors);
        String sortOrder = raw.get("sortOrder");
        if (sortOrder != null && !"asc".equals(sortOrder) && !"desc".equals(sortOrder)) {
            errors.add("sortOrder must be one of the following values: asc, desc");
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
        return new PageQuery(page, pageSize, raw.get("search"), raw.get("sortBy"), sortOrder);
    }

    /** Whitelist check for a query DTO of optional string fields ({@code property x should not exist}). */
    public static void only(Map<String, String> raw, String... allowed) {
        List<String> ok = List.of(allowed);
        List<String> errors = new ArrayList<>();
        for (String k : raw.keySet()) {
            if (!ok.contains(k)) {
                errors.add("property " + k + " should not exist");
            }
        }
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
    }

    /** A Nest-shaped 400 with a {@code message} array. */
    public static ApiException validation(List<String> messages) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("message", messages);
        body.put("error", "Bad Request");
        body.put("statusCode", 400);
        return ApiException.withBody(HttpStatus.BAD_REQUEST, body);
    }

    private static Integer integer(Map<String, String> raw, String key, int min, Integer max, List<String> errors) {
        String v = raw.get(key);
        if (v == null) {
            return null;
        }
        double n = com.inventoryerp.kernel.common.JsNumbers.number(v);
        boolean isInt = !Double.isNaN(n) && !Double.isInfinite(n) && n == Math.rint(n);
        if (max != null && !(n <= max)) {
            errors.add(key + " must not be greater than " + max);
        }
        if (!(n >= min)) {
            errors.add(key + " must not be less than " + min);
        }
        if (!isInt) {
            errors.add(key + " must be an integer number");
        }
        return isInt ? (int) n : null;
    }
}
