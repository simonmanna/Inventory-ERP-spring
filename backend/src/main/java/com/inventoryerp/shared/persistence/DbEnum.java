package com.inventoryerp.shared.persistence;

/** Enum mapped to a Postgres enum type; {@link #value()} is the exact stored and serialized value. */
public interface DbEnum {

    String value();

    static <E extends Enum<E> & DbEnum> E fromValue(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.value().equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " value: " + value);
    }
}
