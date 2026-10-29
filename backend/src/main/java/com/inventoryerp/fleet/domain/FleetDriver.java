package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "FleetDriver"}: Driver / operator profile linked to an hr employee: licence, medical / permit
 * expiries, status (ADR-S009, fleet 1.2).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetDriver")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class FleetDriver {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "employeeId", nullable = false)
    private String employeeId;

    @Column(name = "driverType", nullable = false)
    private String driverType;

    @Column(name = "licenceNumber")
    private String licenceNumber;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "licenceClasses", nullable = false)
    private List<String> licenceClasses;

    @Column(name = "licenceIssuer")
    private String licenceIssuer;

    @Column(name = "licenceIssuedOn")
    private LocalDate licenceIssuedOn;

    @Column(name = "licenceExpiresOn")
    private LocalDate licenceExpiresOn;

    @Column(name = "medicalExpiresOn")
    private LocalDate medicalExpiresOn;

    @Column(name = "permitNumber")
    private String permitNumber;

    @Column(name = "permitExpiresOn")
    private LocalDate permitExpiresOn;

    @Column(name = "reminderLeadDays", nullable = false)
    private Integer reminderLeadDays;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "statusReason")
    private String statusReason;

    @Column(name = "notes")
    private String notes;

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
