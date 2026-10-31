package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetFreightBillLine"}: One trip on a transporter freight bill (fleet 1.9); a trip is on one live
 * bill only.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetFreightBillLine")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetFreightBillLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "freightBillId", nullable = false)
    private String freightBillId;

    @Column(name = "tripId")
    private String tripId;

    @Column(name = "deliveryOrderId")
    private String deliveryOrderId;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "freightBasis")
    private String freightBasis;

    @Column(name = "freightRate", precision = 14, scale = 2)
    private BigDecimal freightRate;

    @Column(name = "tonnes", precision = 14, scale = 3)
    private BigDecimal tonnes;

    @Column(name = "distanceKm", precision = 10, scale = 1)
    private BigDecimal distanceKm;

    @Column(name = "amount", nullable = false, precision = 16, scale = 2)
    private BigDecimal amount;

    @Column(name = "costCenterId")
    private String costCenterId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
