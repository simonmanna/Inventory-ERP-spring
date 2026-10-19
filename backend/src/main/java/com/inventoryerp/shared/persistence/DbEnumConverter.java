package com.inventoryerp.shared.persistence;

import jakarta.persistence.AttributeConverter;

/**
 * Persists a {@link DbEnum} by its value. The JDBC URL must carry {@code stringtype=unspecified}
 * so Postgres casts the bound varchar to the column's enum type.
 */
public abstract class DbEnumConverter<E extends Enum<E> & DbEnum> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected DbEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public E convertToEntityAttribute(String dbData) {
        return DbEnum.fromValue(type, dbData);
    }
}
