package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Table {@code "FleetCheckout"}: Vehicle check-out / check-in by a driver: meters, fuel level, condition, damage
 * flags (ADR-S009, fleet 1.2).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetCheckout")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetCheckout {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "vehicleId", nullable = false)
    private String vehicleId;

    @Column(name = "driverId", nullable = false)
    private String driverId;

    @Column(name = "assignmentId")
    private String assignmentId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "purpose")
    private String purpose;

    @Column(name = "tripId")
    private String tripId;

    @Column(name = "checkedOutAt", nullable = false)
    private Instant checkedOutAt;

    @Column(name = "outOdometer", precision = 14, scale = 1)
    private BigDecimal outOdometer;

    @Column(name = "outHours", precision = 14, scale = 1)
    private BigDecimal outHours;

    @Column(name = "outFuelLevelPct", precision = 5, scale = 1)
    private BigDecimal outFuelLevelPct;

    @Column(name = "outCondition", nullable = false)
    private String outCondition;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "outDamageFlags", nullable = false)
    private List<String> outDamageFlags;

    @Column(name = "outNotes")
    private String outNotes;

    @Column(name = "checkedOutBy")
    private String checkedOutBy;

    @Column(name = "checkedInAt")
    private Instant checkedInAt;

    @Column(name = "inOdometer", precision = 14, scale = 1)
    private BigDecimal inOdometer;

    @Column(name = "inHours", precision = 14, scale = 1)
    private BigDecimal inHours;

    @Column(name = "inFuelLevelPct", precision = 5, scale = 1)
    private BigDecimal inFuelLevelPct;

    @Column(name = "inCondition")
    private String inCondition;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "inDamageFlags", nullable = false)
    private List<String> inDamageFlags;

    @Column(name = "inNotes")
    private String inNotes;

    @Column(name = "checkedInBy")
    private String checkedInBy;

    @Column(name = "distanceKm", precision = 14, scale = 1)
    private BigDecimal distanceKm;

    @Column(name = "hoursUsed", precision = 14, scale = 1)
    private BigDecimal hoursUsed;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    @Column(name = "updatedAt", nullable = false)
    private Instant updatedAt;

    public String getId() {
        return id;
    }
}
