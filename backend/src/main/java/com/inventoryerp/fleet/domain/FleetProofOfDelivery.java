package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
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

    public String getId() {
        return id;
    }
}
