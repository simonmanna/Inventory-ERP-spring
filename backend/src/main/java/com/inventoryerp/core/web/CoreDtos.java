package com.inventoryerp.core.web;

/** Shared request-validation constants for the core controllers. */
final class CoreDtos {

    private CoreDtos() {}

    /** class-validator {@code @IsDateString()} (validator.js isISO8601, non-strict). */
    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";
}
