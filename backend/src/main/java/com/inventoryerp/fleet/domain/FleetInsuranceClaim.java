package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetInsuranceClaim"}: Insurance claim raised for an incident (fleet 1.7).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetInsuranceClaim")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetInsuranceClaim {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "incidentId", nullable = false)
    private String incidentId;

    @Column(name = "claimNumber")
    private String claimNumber;

    @Column(name = "insurerPartnerId")
    private String insurerPartnerId;

    @Column(name = "insurerName")
    private String insurerName;

    @Column(name = "policyNumber")
    private String policyNumber;

    @Column(name = "submittedOn")
    private LocalDate submittedOn;

    @Column(name = "amountClaimed", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountClaimed;

    @Column(name = "amountApproved", precision = 18, scale = 2)
    private BigDecimal amountApproved;

    @Column(name = "amountReceived", nullable = false, precision = 18, scale = 2)
    private BigDecimal amountReceived;

    @Column(name = "excessAmount", precision = 18, scale = 2)
    private BigDecimal excessAmount;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "statusReason")
    private String statusReason;

    @Column(name = "decidedOn")
    private LocalDate decidedOn;

    @Column(name = "settledOn")
    private LocalDate settledOn;

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

    public String getId() {
        return id;
    }
}
