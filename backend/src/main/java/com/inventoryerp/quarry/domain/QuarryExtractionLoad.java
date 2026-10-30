package com.inventoryerp.quarry.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QuarryExtractionLoad"}: Quantity line of an extraction log (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryExtractionLoad")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryExtractionLoad {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "logId", nullable = false)
    private String logId;

    @Column(name = "lineNumber", nullable = false)
    private Integer lineNumber;

    @Column(name = "method", nullable = false)
    private String method;

    @Column(name = "truckVehicleId")
    private String truckVehicleId;

    @Column(name = "loadCount")
    private Integer loadCount;

    @Column(name = "capacityT", precision = 12, scale = 3)
    private BigDecimal capacityT;

    @Column(name = "weighbridgeTicketId")
    private String weighbridgeTicketId;

    @Column(name = "haulageId")
    private String haulageId;

    @Column(name = "fleetTripId")
    private String fleetTripId;

    @Column(name = "tonnes", nullable = false, precision = 14, scale = 3)
    private BigDecimal tonnes;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "notes")
    private String notes;

    public String getId() {
        return id;
    }
}
