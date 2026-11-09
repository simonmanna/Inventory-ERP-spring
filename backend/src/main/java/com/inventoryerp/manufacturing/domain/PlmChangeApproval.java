package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "PlmChangeApproval"}: An approval decision on an ECO at one stage; voided when the ECO moves back (ADR-S063).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "PlmChangeApproval")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PlmChangeApproval {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "changeId", nullable = false)
    private String changeId;

    @Column(name = "stageId", nullable = false)
    private String stageId;

    @Column(name = "userId", nullable = false)
    private String userId;

    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "comment")
    private String comment;

    @Column(name = "voided", nullable = false)
    private Boolean voided;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
