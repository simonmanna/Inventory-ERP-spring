package com.inventoryerp.analytics.application;

import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * The filters every dashboard accepts, all optional. {@code siteId} is a quarry site, {@code deliverySiteId} a customer
 * delivery site, {@code partnerId} the customer (the supplier on the procurement dashboard).
 */
public record AnalyticsFilter(LocalDate from, LocalDate to, String branchId, String siteId, String deliverySiteId,
        String productId, String partnerId) {

    /** From the query string: {@code from}, {@code to} (ISO dates), {@code branchId}, {@code siteId} and so on. */
    public static AnalyticsFilter of(Map<String, String> q) {
        return new AnalyticsFilter(date(q.get("from")), date(q.get("to")), text(q.get("branchId")),
                text(q.get("siteId")), text(q.get("deliverySiteId")), text(q.get("productId")),
                text(q.get("partnerId")));
    }

    private static String text(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static LocalDate date(String raw) {
        if (text(raw) == null) {
            return null;
        }
        String d = raw.trim();
        try {
            return LocalDate.parse(d.length() > 10 ? d.substring(0, 10) : d);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("from / to must be ISO dates (YYYY-MM-DD)");
        }
    }
}
