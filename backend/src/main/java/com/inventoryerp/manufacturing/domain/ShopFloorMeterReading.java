package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "ShopFloorMeterReading"} (ADR-S015): Meter reading of a work centre (hours, kWh, tonnes).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ShopFloorMeterReading")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ShopFloorMeterReading {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "workCenterId", nullable = false)
    private String workCenterId;

    @Column(name = "meterCode", nullable = false)
    private String meterCode;

    @Column(name = "reading", nullable = false, precision = 20, scale = 6)
    private BigDecimal reading;

    @Column(name = "delta", precision = 20, scale = 6)
    private BigDecimal delta;

    @Column(name = "isReset", nullable = false)
    private Boolean isReset;

    @Column(name = "readAt", nullable = false)
    private Instant readAt;

    @Column(name = "shiftReportId")
    private String shiftReportId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "recordedBy")
    private String recordedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
