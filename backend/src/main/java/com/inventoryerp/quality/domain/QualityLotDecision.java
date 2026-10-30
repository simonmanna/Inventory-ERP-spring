package com.inventoryerp.quality.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QualityLotDecision"}: Hold / quarantine / release / reject / downgrade / cancel decision of a lot (ADR-S013).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QualityLotDecision")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QualityLotDecision {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "lotId", nullable = false)
    private String lotId;

    @Column(name = "decision", nullable = false)
    private String decision;

    @Column(name = "quantity", precision = 20, scale = 6)
    private BigDecimal quantity;

    @Column(name = "fromLocationId")
    private String fromLocationId;

    @Column(name = "toLocationId")
    private String toLocationId;

    @Column(name = "disposition")
    private String disposition;

    @Column(name = "downgradeProductId")
    private String downgradeProductId;

    @Column(name = "downgradeBatchNumber")
    private String downgradeBatchNumber;

    @Column(name = "downgradeQty", precision = 20, scale = 6)
    private BigDecimal downgradeQty;

    @Column(name = "unitCost", precision = 20, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "stockValue", precision = 20, scale = 6)
    private BigDecimal stockValue;

    @Column(name = "writeDownValue", precision = 20, scale = 6)
    private BigDecimal writeDownValue;

    @Column(name = "ledgerCodes")
    private String ledgerCodes;

    @Column(name = "journalEntryId")
    private String journalEntryId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "concession", nullable = false)
    private Boolean concession;

    @Column(name = "decidedBy")
    private String decidedBy;

    @Column(name = "decidedAt", nullable = false)
    private Instant decidedAt;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
