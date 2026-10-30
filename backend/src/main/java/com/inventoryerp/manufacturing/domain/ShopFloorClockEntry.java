package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "ShopFloorClockEntry"} (ADR-S015): Operator clock-on / clock-off on a work centre or work order.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ShopFloorClockEntry")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ShopFloorClockEntry {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "userId", nullable = false)
    private String userId;

    @Column(name = "workCenterId", nullable = false)
    private String workCenterId;

    @Column(name = "workOrderId")
    private String workOrderId;

    @Column(name = "shiftReportId")
    private String shiftReportId;

    @Column(name = "crewCode")
    private String crewCode;

    @Column(name = "role")
    private String role;

    @Column(name = "clockOnAt", nullable = false)
    private Instant clockOnAt;

    @Column(name = "clockOffAt")
    private Instant clockOffAt;

    @Column(name = "minutes")
    private Integer minutes;

    @Column(name = "notes")
    private String notes;

    @Column(name = "recordedBy")
    private String recordedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
