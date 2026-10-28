package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "EngineeringChange"}: Engineering change order between two BOM versions (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "EngineeringChange")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class EngineeringChange {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "number", nullable = false)
    private String number;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "reason")
    private String reason;

    @Column(name = "bomId", nullable = false)
    private String bomId;

    @Column(name = "newBomId", nullable = false)
    private String newBomId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "approvedById")
    private String approvedById;

    @Column(name = "approvedAt")
    private Instant approvedAt;

    @Column(name = "appliedAt")
    private Instant appliedAt;

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
