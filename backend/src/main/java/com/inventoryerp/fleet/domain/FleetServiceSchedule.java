package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "FleetServiceSchedule"}: Preventive-maintenance schedule of one maintainable unit, with the baseline
 * (last service) of its next due point (fleet 1.6).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetServiceSchedule")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetServiceSchedule {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "templateId")
    private String templateId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "intervalKm", precision = 14, scale = 1)
    private BigDecimal intervalKm;

    @Column(name = "intervalHours", precision = 14, scale = 1)
    private BigDecimal intervalHours;

    @Column(name = "intervalDays")
    private Integer intervalDays;

    @Column(name = "leadKm", nullable = false, precision = 14, scale = 1)
    private BigDecimal leadKm;

    @Column(name = "leadHours", nullable = false, precision = 14, scale = 1)
    private BigDecimal leadHours;

    @Column(name = "leadDays", nullable = false)
    private Integer leadDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tasks", nullable = false)
    private String tasks;

    @Column(name = "lastDoneOn", nullable = false)
    private LocalDate lastDoneOn;

    @Column(name = "lastDoneKm", precision = 14, scale = 1)
    private BigDecimal lastDoneKm;

    @Column(name = "lastDoneHours", precision = 14, scale = 1)
    private BigDecimal lastDoneHours;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "openWorkOrderId")
    private String openWorkOrderId;

    @Column(name = "lastWorkOrderId")
    private String lastWorkOrderId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
