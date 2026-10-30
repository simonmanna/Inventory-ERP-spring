package com.inventoryerp.quarry.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QuarryBlast"}: Drilling and blasting record with its cost pool and compliance fields (quarry 2.2, ADR-S012).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "QuarryBlast")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
@Filter(name = TenancyFilters.SOFT_DELETE, condition = TenancyFilters.SOFT_DELETE_CONDITION, deduceAliasInjectionPoints = false)
public class QuarryBlast {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "siteId", nullable = false)
    private String siteId;

    @Column(name = "zoneId")
    private String zoneId;

    @Column(name = "materialId")
    private String materialId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "plannedAt")
    private Instant plannedAt;

    @Column(name = "firedAt")
    private Instant firedAt;

    @Column(name = "closedAt")
    private Instant closedAt;

    @Column(name = "patternType")
    private String patternType;

    @Column(name = "holeCount")
    private Integer holeCount;

    @Column(name = "holeDiameterMm", precision = 8, scale = 1)
    private BigDecimal holeDiameterMm;

    @Column(name = "averageDepthM", precision = 8, scale = 2)
    private BigDecimal averageDepthM;

    @Column(name = "burdenM", precision = 8, scale = 2)
    private BigDecimal burdenM;

    @Column(name = "spacingM", precision = 8, scale = 2)
    private BigDecimal spacingM;

    @Column(name = "subDrillM", precision = 8, scale = 2)
    private BigDecimal subDrillM;

    @Column(name = "stemmingM", precision = 8, scale = 2)
    private BigDecimal stemmingM;

    @Column(name = "drilledMetres", precision = 12, scale = 2)
    private BigDecimal drilledMetres;

    @Column(name = "drillVehicleId")
    private String drillVehicleId;

    @Column(name = "drillHours", precision = 10, scale = 2)
    private BigDecimal drillHours;

    @Column(name = "estimatedVolumeM3", precision = 14, scale = 3)
    private BigDecimal estimatedVolumeM3;

    @Column(name = "estimatedTonnes", precision = 14, scale = 3)
    private BigDecimal estimatedTonnes;

    @Column(name = "explosivesKg", nullable = false, precision = 14, scale = 3)
    private BigDecimal explosivesKg;

    @Column(name = "explosivesCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal explosivesCost;

    @Column(name = "drillingCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal drillingCost;

    @Column(name = "otherCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal otherCost;

    @Column(name = "absorbedCost", nullable = false, precision = 18, scale = 2)
    private BigDecimal absorbedCost;

    @Column(name = "shotfirerEmployeeId")
    private String shotfirerEmployeeId;

    @Column(name = "shotfirerLicenceNo")
    private String shotfirerLicenceNo;

    @Column(name = "blastPermitNo")
    private String blastPermitNo;

    @Column(name = "permitExpiresOn")
    private LocalDate permitExpiresOn;

    @Column(name = "exclusionZoneM", precision = 8, scale = 1)
    private BigDecimal exclusionZoneM;

    @Column(name = "warningSignalAt")
    private Instant warningSignalAt;

    @Column(name = "allClearAt")
    private Instant allClearAt;

    @Column(name = "misfireCount", nullable = false)
    private Integer misfireCount;

    @Column(name = "misfireNotes")
    private String misfireNotes;

    @Column(name = "vibrationPpvMmS", precision = 8, scale = 2)
    private BigDecimal vibrationPpvMmS;

    @Column(name = "airblastDb", precision = 8, scale = 2)
    private BigDecimal airblastDb;

    @Column(name = "weather")
    private String weather;

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

    @Column(name = "deletedAt")
    private Instant deletedAt;

    public String getId() {
        return id;
    }
}
