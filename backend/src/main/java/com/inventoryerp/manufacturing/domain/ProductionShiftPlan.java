package com.inventoryerp.manufacturing.domain;

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
 * Table {@code "ProductionShiftPlan"}: What one work centre should make in one shift of one day (ADR-S019).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ProductionShiftPlan")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ProductionShiftPlan {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "shiftDate", nullable = false)
    private LocalDate shiftDate;

    @Column(name = "shiftId", nullable = false)
    private String shiftId;

    @Column(name = "workCenterId", nullable = false)
    private String workCenterId;

    @Column(name = "productionOrderId")
    private String productionOrderId;

    @Column(name = "workOrderId")
    private String workOrderId;

    @Column(name = "productId")
    private String productId;

    @Column(name = "targetQty", nullable = false, precision = 20, scale = 6)
    private BigDecimal targetQty;

    @Column(name = "crewSize")
    private Integer crewSize;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "notes")
    private String notes;

    @Column(name = "publishedAt")
    private Instant publishedAt;

    @Column(name = "publishedBy")
    private String publishedBy;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
