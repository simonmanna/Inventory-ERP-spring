package com.inventoryerp.inventory.web;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.QueryParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared request-validation constants and query checks for the inventory controllers. */
final class InventoryDtos {

    private InventoryDtos() {}

    /** class-validator {@code @IsDateString()} (validator.js isISO8601, non-strict). */
    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";

    static final String[] LOCATION_TYPES = {"warehouse", "store", "virtual", "main_kitchen", "bar", "storage_room",
            "walkin_fridge", "freezer", "dry_storage", "front_counter", "branch", "production", "transit"};

    static final String USER_LOCATION_TYPE_MESSAGE =
            "type must be a user-managed location type (transit is system-managed)";

    static final String[] WASTE_CATEGORIES = {"expired", "spoiled", "burnt", "contaminated", "breakage", "other",
            "overmixed", "packaging_defect", "qc_rejection"};

    static final String[] DOC_STATUSES = {"draft", "pending", "approved", "rejected", "completed", "cancelled"};

    static final String[] COUNT_TYPES = {"opening", "closing", "cycle", "spot"};

    /** Whitelist + per-field checks of a query DTO; throws the Nest 400 with the collected messages. */
    static final class Check {
        private final Map<String, String> raw;
        private final List<String> errors = new ArrayList<>();

        Check(Map<String, String> raw, String... allowed) {
            this.raw = raw;
            List<String> ok = List.of(allowed);
            for (String k : raw.keySet()) {
                if (!ok.contains(k)) {
                    errors.add("property " + k + " should not exist");
                }
            }
        }

        Check oneOf(String key, String... values) {
            String v = raw.get(key);
            if (v != null && !List.of(values).contains(v)) {
                errors.add(key + " must be one of the following values: " + String.join(", ", values));
            }
            return this;
        }

        Check date(String key) {
            String v = raw.get(key);
            if (v != null && !v.matches(ISO_DATE)) {
                errors.add(key + " must be a valid ISO 8601 date string");
            }
            return this;
        }

        Check number(String key) {
            String v = raw.get(key);
            if (v != null) {
                double n = JsNumbers.number(v);
                if (Double.isNaN(n) || Double.isInfinite(n)) {
                    errors.add(key + " must be a number conforming to the specified constraints");
                }
            }
            return this;
        }

        Check required(String key) {
            String v = raw.get(key);
            if (v == null || v.isEmpty()) {
                errors.add(key + " should not be empty");
            }
            return this;
        }

        void done() {
            if (!errors.isEmpty()) {
                throw QueryParams.validation(errors);
            }
        }
    }

    /** {@code toIdList}: {@code a,b} or a repeated parameter → trimmed non-empty ids (null when absent). */
    static List<String> idList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String v : raw.split(",")) {
            if (!v.trim().isEmpty()) {
                out.add(v.trim());
            }
        }
        return out;
    }
}
