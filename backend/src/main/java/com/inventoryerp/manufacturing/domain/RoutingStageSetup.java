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
 * Table {@code "RoutingStageSetup"}: Stage behaviour of a routing operation (ADR-S011).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "RoutingStageSetup")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class RoutingStageSetup {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "routingOperationId", nullable = false)
    private String routingOperationId;

    @Column(name = "issueMode", nullable = false)
    private String issueMode;

    @Column(name = "outputProductId")
    private String outputProductId;

    @Column(name = "outputPerUnit", nullable = false, precision = 20, scale = 6)
    private BigDecimal outputPerUnit;

    @Column(name = "stageLocationId")
    private String stageLocationId;

    @Column(name = "expectedYieldPct", nullable = false, precision = 20, scale = 6)
    private BigDecimal expectedYieldPct;

    @Column(name = "qcGate", nullable = false)
    private Boolean qcGate;

    @Column(name = "qcCheckpointCode")
    private String qcCheckpointCode;

    /** ADR-S014 (2.4): days the gated output must cure before the gate can pass. */
    @Column(name = "minCuringDays", nullable = false)
    private Integer minCuringDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "materialProductIds", nullable = false)
    private String materialProductIds;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
