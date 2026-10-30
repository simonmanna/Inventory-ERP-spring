package com.inventoryerp.quarry.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QuarryExtractionResource"}: Equipment or labour line of an extraction log (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryExtractionResource")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryExtractionResource {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "logId", nullable = false)
    private String logId;

    @Column(name = "lineNumber", nullable = false)
    private Integer lineNumber;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "employeeId")
    private String employeeId;

    @Column(name = "role")
    private String role;

    @Column(name = "hours", nullable = false, precision = 10, scale = 2)
    private BigDecimal hours;

    @Column(name = "meterStart", precision = 14, scale = 1)
    private BigDecimal meterStart;

    @Column(name = "meterEnd", precision = 14, scale = 1)
    private BigDecimal meterEnd;

    @Column(name = "fuelLitres", precision = 12, scale = 3)
    private BigDecimal fuelLitres;

    @Column(name = "ratePerHour", precision = 14, scale = 4)
    private BigDecimal ratePerHour;

    @Column(name = "fuelCostPerLitre", precision = 14, scale = 4)
    private BigDecimal fuelCostPerLitre;

    @Column(name = "appliedRate", precision = 14, scale = 4)
    private BigDecimal appliedRate;

    @Column(name = "appliedFuelRate", precision = 14, scale = 4)
    private BigDecimal appliedFuelRate;

    @Column(name = "hoursCost", precision = 18, scale = 2)
    private BigDecimal hoursCost;

    @Column(name = "fuelCost", precision = 18, scale = 2)
    private BigDecimal fuelCost;

    @Column(name = "notes")
    private String notes;

    public String getId() {
        return id;
    }
}
