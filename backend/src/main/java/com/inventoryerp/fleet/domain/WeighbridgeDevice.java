package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "WeighbridgeDevice"}: Weighbridge indicator of a station read through a WeighbridgeAdapter (simulator, ascii-tcp, ascii-serial) (fleet 1.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "WeighbridgeDevice")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class WeighbridgeDevice {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "stationId", nullable = false)
    private String stationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "adapterType", nullable = false)
    private String adapterType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private String config;

    @Column(name = "readTimeoutMs", nullable = false)
    private Integer readTimeoutMs;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "lastReadAt")
    private Instant lastReadAt;

    @Column(name = "lastReadKg", precision = 14, scale = 2)
    private BigDecimal lastReadKg;

    @Column(name = "lastStatus")
    private String lastStatus;

    @Column(name = "lastError")
    private String lastError;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "updatedBy")
    private String updatedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
