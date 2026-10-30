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
 * Table {@code "QualityCapa"}: Corrective / preventive action record (ADR-S013).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QualityCapa")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class QualityCapa {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "capaNumber", nullable = false)
    private String capaNumber;

    @Column(name = "ncrId")
    private String ncrId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "rootCause")
    private String rootCause;

    @Column(name = "rootCauseMethod")
    private String rootCauseMethod;

    @Column(name = "ownerUserId")
    private String ownerUserId;

    @Column(name = "dueDate")
    private LocalDate dueDate;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "effectivenessNotes")
    private String effectivenessNotes;

    @Column(name = "verifiedBy")
    private String verifiedBy;

    @Column(name = "verifiedAt")
    private Instant verifiedAt;

    @Column(name = "closedAt")
    private Instant closedAt;

    @Column(name = "closedBy")
    private String closedBy;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "updatedBy")
    private String updatedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    @Column(name = "deletedAt")
    private Instant deletedAt;

    public String getId() {
        return id;
    }
}
