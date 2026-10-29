package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "FleetIncident"}: Incident / accident of a vehicle with damage, third parties and police report (fleet 1.7).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetIncident")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetIncident {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "incidentNumber", nullable = false)
    private String incidentNumber;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "driverEmployeeId")
    private String driverEmployeeId;

    @Column(name = "occurredAt", nullable = false)
    private Instant occurredAt;

    @Column(name = "location")
    private String location;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "incidentType", nullable = false)
    private String incidentType;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "damageDescription")
    private String damageDescription;

    @Column(name = "estimatedCost", precision = 18, scale = 2)
    private BigDecimal estimatedCost;

    @Column(name = "atFault", nullable = false)
    private String atFault;

    @Column(name = "injuries")
    private String injuries;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "thirdParties", nullable = false)
    private String thirdParties;

    @Column(name = "policeReportNumber")
    private String policeReportNumber;

    @Column(name = "policeStation")
    private String policeStation;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "statusReason")
    private String statusReason;

    @Column(name = "workOrderId")
    private String workOrderId;

    @Column(name = "repairReference")
    private String repairReference;

    @Column(name = "repairCost", precision = 18, scale = 2)
    private BigDecimal repairCost;

    @Column(name = "closedAt")
    private Instant closedAt;

    @Column(name = "closedBy")
    private String closedBy;

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
