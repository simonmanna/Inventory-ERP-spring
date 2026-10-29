package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetDefect"}: Defect found by an inspection or reported by a driver; an open critical defect
 * blocks dispatch (fleet 1.6).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetDefect")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetDefect {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "inspectionId")
    private String inspectionId;

    @Column(name = "itemCode")
    private String itemCode;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "workOrderId")
    private String workOrderId;

    @Column(name = "reportedByEmployeeId")
    private String reportedByEmployeeId;

    @Column(name = "reportedBy")
    private String reportedBy;

    @Column(name = "reportedAt", nullable = false)
    private Instant reportedAt;

    @Column(name = "resolvedAt")
    private Instant resolvedAt;

    @Column(name = "resolvedBy")
    private String resolvedBy;

    @Column(name = "resolution")
    private String resolution;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
