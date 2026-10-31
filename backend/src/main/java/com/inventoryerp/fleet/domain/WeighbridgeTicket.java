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
 * Table {@code "WeighbridgeTicket"}: Weighbridge ticket: truck, reference documents, first / second weighing, net, tolerance, print / void / reissue (fleet 1.4).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "WeighbridgeTicket")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class WeighbridgeTicket {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "ticketNumber", nullable = false)
    private String ticketNumber;

    @Column(name = "stationId", nullable = false)
    private String stationId;

    @Column(name = "subjectType", nullable = false)
    private String subjectType;

    @Column(name = "direction", nullable = false)
    private String direction;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "registrationPlate", nullable = false)
    private String registrationPlate;

    @Column(name = "trailerPlate")
    private String trailerPlate;

    @Column(name = "driverId")
    private String driverId;

    @Column(name = "driverName")
    private String driverName;

    @Column(name = "transporterPartnerId")
    private String transporterPartnerId;

    @Column(name = "partnerId")
    private String partnerId;

    @Column(name = "salesOrderId")
    private String salesOrderId;

    @Column(name = "deliveryOrderId")
    private String deliveryOrderId;

    @Column(name = "tripId")
    private String tripId;

    @Column(name = "purchaseOrderId")
    private String purchaseOrderId;

    @Column(name = "purchaseOrderLineId")
    private String purchaseOrderLineId;

    @Column(name = "referenceNumber")
    private String referenceNumber;

    @Column(name = "productId")
    private String productId;

    @Column(name = "orderedQty", precision = 18, scale = 4)
    private BigDecimal orderedQty;

    @Column(name = "orderedUomId")
    private String orderedUomId;

    @Column(name = "expectedKg", precision = 14, scale = 2)
    private BigDecimal expectedKg;

    @Column(name = "locationId")
    private String locationId;

    @Column(name = "firstWeightKg", precision = 14, scale = 2)
    private BigDecimal firstWeightKg;

    @Column(name = "firstWeighedAt")
    private Instant firstWeighedAt;

    @Column(name = "firstSource")
    private String firstSource;

    @Column(name = "firstDeviceId")
    private String firstDeviceId;

    @Column(name = "firstRawFrame")
    private String firstRawFrame;

    @Column(name = "firstManualReason")
    private String firstManualReason;

    @Column(name = "firstBy")
    private String firstBy;

    @Column(name = "firstTareId")
    private String firstTareId;

    @Column(name = "secondWeightKg", precision = 14, scale = 2)
    private BigDecimal secondWeightKg;

    @Column(name = "secondWeighedAt")
    private Instant secondWeighedAt;

    @Column(name = "secondSource")
    private String secondSource;

    @Column(name = "secondDeviceId")
    private String secondDeviceId;

    @Column(name = "secondRawFrame")
    private String secondRawFrame;

    @Column(name = "secondManualReason")
    private String secondManualReason;

    @Column(name = "secondBy")
    private String secondBy;

    @Column(name = "secondTareId")
    private String secondTareId;

    @Column(name = "grossKg", precision = 14, scale = 2)
    private BigDecimal grossKg;

    @Column(name = "tareKg", precision = 14, scale = 2)
    private BigDecimal tareKg;

    @Column(name = "netKg", precision = 14, scale = 2)
    private BigDecimal netKg;

    @Column(name = "netQty", precision = 18, scale = 4)
    private BigDecimal netQty;

    @Column(name = "netUomId")
    private String netUomId;

    @Column(name = "varianceKg", precision = 14, scale = 2)
    private BigDecimal varianceKg;

    @Column(name = "variancePct", precision = 9, scale = 3)
    private BigDecimal variancePct;

    @Column(name = "allowedVarianceKg", precision = 14, scale = 2)
    private BigDecimal allowedVarianceKg;

    @Column(name = "tolerancePct", precision = 7, scale = 3)
    private BigDecimal tolerancePct;

    @Column(name = "toleranceKg", precision = 14, scale = 2)
    private BigDecimal toleranceKg;

    @Column(name = "withinTolerance")
    private Boolean withinTolerance;

    @Column(name = "isManual", nullable = false)
    private Boolean isManual;

    @Column(name = "usedStoredTare", nullable = false)
    private Boolean usedStoredTare;

    @Column(name = "completedAt")
    private Instant completedAt;

    @Column(name = "completedBy")
    private String completedBy;

    @Column(name = "printCount", nullable = false)
    private Integer printCount;

    @Column(name = "firstPrintedAt")
    private Instant firstPrintedAt;

    @Column(name = "lastPrintedAt")
    private Instant lastPrintedAt;

    @Column(name = "lastPrintedBy")
    private String lastPrintedBy;

    @Column(name = "cancelledAt")
    private Instant cancelledAt;

    @Column(name = "cancelledBy")
    private String cancelledBy;

    @Column(name = "cancelReason")
    private String cancelReason;

    @Column(name = "voidedAt")
    private Instant voidedAt;

    @Column(name = "voidedBy")
    private String voidedBy;

    @Column(name = "voidReason")
    private String voidReason;

    @Column(name = "reissuedFromId")
    private String reissuedFromId;

    @Column(name = "replacedById")
    private String replacedById;

    @Column(name = "goodsReceiptId")
    private String goodsReceiptId;

    @Column(name = "goodsReceiptLineId")
    private String goodsReceiptLineId;

    @Column(name = "tripFeedStatus")
    private String tripFeedStatus;

    @Column(name = "tripFeedMessage")
    private String tripFeedMessage;

    @Column(name = "tripFedAt")
    private Instant tripFedAt;

    @Column(name = "notes")
    private String notes;

    @Column(name = "createdBy")
    private String createdBy;

    @Column(name = "updatedBy")
    private String updatedBy;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
