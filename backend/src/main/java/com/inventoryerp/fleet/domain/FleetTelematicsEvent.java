package com.inventoryerp.fleet.domain;

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
 * Table {@code "FleetTelematicsEvent"}: Telematics event of a vehicle: ignition on / off and alerts (speeding, idling, geofence) (fleet 1.8).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetTelematicsEvent")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetTelematicsEvent {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "deviceId", nullable = false)
    private String deviceId;

    @Column(name = "eventType", nullable = false)
    private String eventType;

    @Column(name = "occurredAt", nullable = false)
    private Instant occurredAt;

    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(name = "speedKmh", precision = 6, scale = 1)
    private BigDecimal speedKmh;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false)
    private String data;

    @Column(name = "externalId")
    private String externalId;

    @Column(name = "isAlert", nullable = false)
    private Boolean isAlert;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "acknowledgedAt")
    private Instant acknowledgedAt;

    @Column(name = "acknowledgedBy")
    private String acknowledgedBy;

    @Column(name = "acknowledgeNote")
    private String acknowledgeNote;

    @Column(name = "receivedAt", nullable = false)
    private Instant receivedAt;

    public String getId() {
        return id;
    }
}
