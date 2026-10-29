package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetDowntime"}: Downtime period of a unit (breakdown or planned service) — source of availability,
 * MTBF and MTTR (fleet 1.6).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetDowntime")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetDowntime {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "workOrderId")
    private String workOrderId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "reasonCode", nullable = false)
    private String reasonCode;

    @Column(name = "description")
    private String description;

    @Column(name = "startedAt", nullable = false)
    private Instant startedAt;

    @Column(name = "endedAt")
    private Instant endedAt;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "odometer", precision = 14, scale = 1)
    private BigDecimal odometer;

    @Column(name = "hours", precision = 14, scale = 1)
    private BigDecimal hours;

    @Column(name = "reportedBy")
    private String reportedBy;

    @Column(name = "endedBy")
    private String endedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
