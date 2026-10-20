package com.inventoryerp.procurement.web;

import com.inventoryerp.shared.web.ApiException;

/** Validation messages and helpers shared by the procurement controllers (class-validator wording). */
final class ProcurementDtos {

    private ProcurementDtos() {}

    /** class-validator {@code @IsDateString()} (validator.js isISO8601, non-strict). */
    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";

    static final String DATE_MSG = "must be a valid ISO 8601 date string";
    static final String STR = "must be a string";
    static final String NUM = "must be a number conforming to the specified constraints";
    static final String ARR = "must be an array";
    static final String MIN1 = "must contain at least 1 elements";
    static final String NOT_EMPTY = "should not be empty";
    static final String POSITIVE = "must be a positive number";

    /** Nest {@code ParseIntPipe} after {@code DefaultValuePipe(def)}. */
    static int parseInt(String raw, int def) {
        if (raw == null) {
            return def;
        }
        if (!raw.matches("-?\\d+")) {
            throw ApiException.badRequest("Validation failed (numeric string is expected)");
        }
        return Integer.parseInt(raw);
    }
}
