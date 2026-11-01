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
 * Table {@code "MrpPlannedOrder"}: Planned make/buy order proposed by an MRP run (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "MrpPlannedOrder")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class MrpPlannedOrder {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "runId", nullable = false)
    private String runId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "quantity", nullable = false, precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "uomId")
    private String uomId;

    @Column(name = "bomId")
    private String bomId;

    @Column(name = "supplierId")
    private String supplierId;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "dueDate", nullable = false)
    private Instant dueDate;

    @Column(name = "releaseDate", nullable = false)
    private Instant releaseDate;

    @Column(name = "lowLevelCode", nullable = false)
    private Integer lowLevelCode;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "firmedDocType")
    private String firmedDocType;

    @Column(name = "firmedDocId")
    private String firmedDocId;

    /** PR-5: the service purchase request raised to the subcontractor when a subcontract order is firmed. */
    @Column(name = "subcontractRequestId")
    private String subcontractRequestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pegging", nullable = false)
    private String pegging;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
