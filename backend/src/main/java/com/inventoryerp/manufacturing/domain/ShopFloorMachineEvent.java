package com.inventoryerp.manufacturing.domain;

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
 * Table {@code "ShopFloorMachineEvent"} (ADR-S015):
 * Raw machine-data event (count / downtime) from a MachineDataAdapter.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "ShopFloorMachineEvent")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class ShopFloorMachineEvent {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "adapter", nullable = false)
    private String adapter;

    @Column(name = "externalId", nullable = false)
    private String externalId;

    @Column(name = "machineCode", nullable = false)
    private String machineCode;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "eventType", nullable = false)
    private String eventType;

    @Column(name = "occurredAt", nullable = false)
    private Instant occurredAt;

    @Column(name = "endedAt")
    private Instant endedAt;

    @Column(name = "goodQty", precision = 20, scale = 6)
    private BigDecimal goodQty;

    @Column(name = "scrapQty", precision = 20, scale = 6)
    private BigDecimal scrapQty;

    @Column(name = "reasonCode")
    private String reasonCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload")
    private String payload;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "draftType")
    private String draftType;

    @Column(name = "draftId")
    private String draftId;

    @Column(name = "error")
    private String error;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
