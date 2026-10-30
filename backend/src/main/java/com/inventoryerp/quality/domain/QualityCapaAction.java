package com.inventoryerp.quality.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QualityCapaAction"}: Action of a CAPA with owner and due date (ADR-S013).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QualityCapaAction")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QualityCapaAction {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "capaId", nullable = false)
    private String capaId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "ownerUserId")
    private String ownerUserId;

    @Column(name = "dueDate")
    private LocalDate dueDate;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "completedAt")
    private Instant completedAt;

    @Column(name = "completedBy")
    private String completedBy;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
