package com.inventoryerp.quarry.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QuarryExtractionCost"}: Cost build-up line of a posted extraction log (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryExtractionCost")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryExtractionCost {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "logId", nullable = false)
    private String logId;

    @Column(name = "component", nullable = false)
    private String component;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "quantity", precision = 18, scale = 4)
    private BigDecimal quantity;

    @Column(name = "rate", precision = 18, scale = 6)
    private BigDecimal rate;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "refType")
    private String refType;

    @Column(name = "refId")
    private String refId;

    @Column(name = "partnerId")
    private String partnerId;

    public String getId() {
        return id;
    }
}
