package com.inventoryerp.quarry.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QuarryExplosiveIssueLine"}: Line of an explosive issue (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryExplosiveIssueLine")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryExplosiveIssueLine {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "issueId", nullable = false)
    private String issueId;

    @Column(name = "lineNumber", nullable = false)
    private Integer lineNumber;

    @Column(name = "explosiveId", nullable = false)
    private String explosiveId;

    @Column(name = "productId", nullable = false)
    private String productId;

    @Column(name = "requestedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal requestedQty;

    @Column(name = "issuedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal issuedQty;

    @Column(name = "returnedQty", nullable = false, precision = 14, scale = 3)
    private BigDecimal returnedQty;

    @Column(name = "unitCost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    @Column(name = "issuedValue", nullable = false, precision = 18, scale = 2)
    private BigDecimal issuedValue;

    @Column(name = "returnedValue", nullable = false, precision = 18, scale = 2)
    private BigDecimal returnedValue;

    @Column(name = "stockLedgerCode")
    private String stockLedgerCode;

    @Column(name = "returnLedgerCode")
    private String returnLedgerCode;

    public String getId() {
        return id;
    }
}
