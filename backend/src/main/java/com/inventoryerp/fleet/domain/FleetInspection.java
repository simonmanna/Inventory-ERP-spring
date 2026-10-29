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
 * Table {@code "FleetInspection"}: Recorded inspection / pre-trip check of a unit with per-item results and photos
 * (fleet 1.6).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetInspection")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetInspection {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "templateId")
    private String templateId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "inspectorEmployeeId")
    private String inspectorEmployeeId;

    @Column(name = "recordedBy")
    private String recordedBy;

    @Column(name = "inspectedAt", nullable = false)
    private Instant inspectedAt;

    @Column(name = "odometer", precision = 14, scale = 1)
    private BigDecimal odometer;

    @Column(name = "hours", precision = 14, scale = 1)
    private BigDecimal hours;

    @Column(name = "result", nullable = false)
    private String result;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "items", nullable = false)
    private String items;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "photos", nullable = false)
    private String photos;

    @Column(name = "defectCount", nullable = false)
    private Integer defectCount;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
