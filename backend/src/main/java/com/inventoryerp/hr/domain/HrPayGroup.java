package com.inventoryerp.hr.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "HrPayGroup"}: employees paid together on one calendar (monthly, bi-weekly or weekly) (HP-0).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and schema
 * validation.
 */
@Entity
@Table(name = "HrPayGroup")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION,
        deduceAliasInjectionPoints = false)
public class HrPayGroup {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "payFrequency", nullable = false)
    private String payFrequency;

    @Column(name = "anchorDate")
    private LocalDate anchorDate;

    @Column(name = "paymentDay", nullable = false)
    private Integer paymentDay;

    @Column(name = "isDefault", nullable = false)
    private Boolean isDefault;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "description")
    private String description;

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
}
