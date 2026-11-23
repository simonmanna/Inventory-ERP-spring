package com.inventoryerp.repair.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.persistence.DbEnumConverter;
import jakarta.persistence.Converter;

/** Postgres enum {@code "RepairPartStatus"}. {@link #value()} is the exact DB and JSON value. */
public enum RepairPartStatus implements DbEnum {
    RESERVED("reserved"),
    ISSUED("issued"),
    /** Hardening R5a (V615): an issued part taken back into stock (its issue reversed). */
    RETURNED("returned");

    private final String value;

    RepairPartStatus(String value) {
        this.value = value;
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RepairPartStatus fromValue(String value) {
        return DbEnum.fromValue(RepairPartStatus.class, value);
    }

    @Converter(autoApply = true)
    public static class JpaConverter extends DbEnumConverter<RepairPartStatus> {
        public JpaConverter() {
            super(RepairPartStatus.class);
        }
    }
}
