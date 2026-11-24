package com.inventoryerp.qualitycontrol.domain;

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
 * Table {@code "QcControlPoint"}: Quality control point (ADR-S087 §1): generates checks on receipts, deliveries, MOs and work orders.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcControlPoint")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcControlPoint {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "reference", nullable = false)
    private String reference;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "operation", nullable = false)
    private String operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "productIds", nullable = false)
    private String productIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categoryIds", nullable = false)
    private String categoryIds;

    @Column(name = "supplierPartnerId")
    private String supplierPartnerId;

    @Column(name = "routingOperationId")
    private String routingOperationId;

    @Column(name = "teamId")
    private String teamId;

    @Column(name = "responsibleUserId")
    private String responsibleUserId;

    @Column(name = "checkType", nullable = false)
    private String checkType;

    @Column(name = "instructions")
    private String instructions;

    @Column(name = "failureMessage")
    private String failureMessage;

    @Column(name = "failureClassRequired", nullable = false)
    private Boolean failureClassRequired;

    @Column(name = "norm", precision = 20, scale = 6)
    private BigDecimal norm;

    @Column(name = "toleranceMin", precision = 20, scale = 6)
    private BigDecimal toleranceMin;

    @Column(name = "toleranceMax", precision = 20, scale = 6)
    private BigDecimal toleranceMax;

    @Column(name = "unit")
    private String unit;

    @Column(name = "decimals")
    private Integer decimals;

    @Column(name = "worksheetTemplateKey")
    private String worksheetTemplateKey;

    @Column(name = "controlPer", nullable = false)
    private String controlPer;

    @Column(name = "testQtyPct", precision = 9, scale = 4)
    private BigDecimal testQtyPct;

    @Column(name = "minSampleQty", precision = 20, scale = 6)
    private BigDecimal minSampleQty;

    @Column(name = "frequency", nullable = false)
    private String frequency;

    @Column(name = "randomPct", precision = 9, scale = 4)
    private BigDecimal randomPct;

    @Column(name = "periodValue")
    private Integer periodValue;

    @Column(name = "periodUnit")
    private String periodUnit;

    @Column(name = "blocking", nullable = false)
    private Boolean blocking;

    @Column(name = "blockOnFailure", nullable = false)
    private Boolean blockOnFailure;

    @Column(name = "autoAlert", nullable = false)
    private Boolean autoAlert;

    @Column(name = "receiptMode")
    private String receiptMode;

    @Column(name = "failureLocationId")
    private String failureLocationId;

    @Column(name = "spcSubgroup", nullable = false)
    private String spcSubgroup;

    @Column(name = "spcSubgroupSize")
    private Integer spcSubgroupSize;

    @Column(name = "spcWindowHours")
    private Integer spcWindowHours;

    @Column(name = "spcSplitByWorkCenter", nullable = false)
    private Boolean spcSplitByWorkCenter;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    @Column(name = "isActive", nullable = false)
    private Boolean isActive;

    @Column(name = "version", nullable = false)
    private Integer version;

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
