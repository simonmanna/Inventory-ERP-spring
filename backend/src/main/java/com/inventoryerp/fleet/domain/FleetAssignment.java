package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetAssignment"}: Vehicle ↔ driver assignment, primary (open-ended) or temporary (ADR-S009, fleet
 * 1.2).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetAssignment")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetAssignment {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "driverId", nullable = false)
    private String driverId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "startsAt", nullable = false)
    private Instant startsAt;

    @Column(name = "endsAt")
    private Instant endsAt;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "notes")
    private String notes;

    @Column(name = "endReason")
    private String endReason;

    @Column(name = "createdBy")
    private String createdBy;

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
