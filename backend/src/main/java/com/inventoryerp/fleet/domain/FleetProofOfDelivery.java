package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetProofOfDelivery"}: Proof of delivery of a load: receiver, time, GPS, signature and photo (fleet 1.3).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetProofOfDelivery")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetProofOfDelivery {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "loadId", nullable = false)
    private String loadId;

    @Column(name = "receiverName", nullable = false)
    private String receiverName;

    @Column(name = "receiverPhone")
    private String receiverPhone;

    @Column(name = "receivedAt", nullable = false)
    private Instant receivedAt;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "locationSource", nullable = false)
    private String locationSource;

    @Column(name = "signatureFileId")
    private String signatureFileId;

    @Column(name = "photoFileId")
    private String photoFileId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "recordedBy")
    private String recordedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    // Phase A A2 (V496): customer weight evidence and its frozen comparison.

    @Column(name = "customerGrossKg", precision = 14, scale = 3)
    private BigDecimal customerGrossKg;

    @Column(name = "customerTareKg", precision = 14, scale = 3)
    private BigDecimal customerTareKg;

    @Column(name = "customerNetKg", precision = 14, scale = 3)
    private BigDecimal customerNetKg;

    @Column(name = "customerTicketRef")
    private String customerTicketRef;

    @Column(name = "customerWeighedAt")
    private Instant customerWeighedAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "customerWeightFileIds", nullable = false)
    private List<String> customerWeightFileIds;

    @Column(name = "customerWeightNotes")
    private String customerWeightNotes;

    @Column(name = "customerWeightBy")
    private String customerWeightBy;

    @Column(name = "customerWeightAt")
    private Instant customerWeightAt;

    @Column(name = "quarryNetKg", precision = 14, scale = 3)
    private BigDecimal quarryNetKg;

    @Column(name = "quarryWeightSource")
    private String quarryWeightSource;

    @Column(name = "quarryTicketId")
    private String quarryTicketId;

    @Column(name = "weightVarianceKg", precision = 14, scale = 3)
    private BigDecimal weightVarianceKg;

    @Column(name = "allowedVarianceKg", precision = 14, scale = 3)
    private BigDecimal allowedVarianceKg;

    @Column(name = "weightWithinTolerance")
    private Boolean weightWithinTolerance;

    @Column(name = "weightOutcome")
    private String weightOutcome;

    @Column(name = "unallocatedVarianceKg", precision = 14, scale = 3)
    private BigDecimal unallocatedVarianceKg;

    public String getId() {
        return id;
    }
    public BigDecimal getCustomerGrossKg() {
        return customerGrossKg;
    }

    public BigDecimal getCustomerTareKg() {
        return customerTareKg;
    }

    public BigDecimal getCustomerNetKg() {
        return customerNetKg;
    }

    public String getCustomerTicketRef() {
        return customerTicketRef;
    }

    public Instant getCustomerWeighedAt() {
        return customerWeighedAt;
    }

    public List<String> getCustomerWeightFileIds() {
        return customerWeightFileIds;
    }

    public String getCustomerWeightNotes() {
        return customerWeightNotes;
    }

    public String getCustomerWeightBy() {
        return customerWeightBy;
    }

    public Instant getCustomerWeightAt() {
        return customerWeightAt;
    }

    public BigDecimal getQuarryNetKg() {
        return quarryNetKg;
    }

    public String getQuarryWeightSource() {
        return quarryWeightSource;
    }

    public String getQuarryTicketId() {
        return quarryTicketId;
    }

    public BigDecimal getWeightVarianceKg() {
        return weightVarianceKg;
    }

    public BigDecimal getAllowedVarianceKg() {
        return allowedVarianceKg;
    }

    public Boolean getWeightWithinTolerance() {
        return weightWithinTolerance;
    }

    public String getWeightOutcome() {
        return weightOutcome;
    }

    public BigDecimal getUnallocatedVarianceKg() {
        return unallocatedVarianceKg;
    }
}
