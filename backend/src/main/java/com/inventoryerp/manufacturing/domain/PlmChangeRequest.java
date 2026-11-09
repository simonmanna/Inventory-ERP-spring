package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "PlmChangeRequest"}: Engineering change request (ECR) that can be converted into an ECO (ADR-S063).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "PlmChangeRequest")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PlmChangeRequest {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "number", nullable = false)
    private String number;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    @Column(name = "productId")
    private String productId;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "reasonCode", nullable = false)
    private String reasonCode;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "dueDate")
    private LocalDate dueDate;

    @Column(name = "requestedById")
    private String requestedById;

    @Column(name = "reviewedById")
    private String reviewedById;

    @Column(name = "reviewedAt")
    private Instant reviewedAt;

    @Column(name = "decisionNote")
    private String decisionNote;

    @Column(name = "changeId")
    private String changeId;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
