package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetCheckoutPhoto"}: Photo (vehicle attachment) taken at check-out or check-in (ADR-S009, fleet
 * 1.2).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetCheckoutPhoto")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetCheckoutPhoto {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "checkoutId", nullable = false)
    private String checkoutId;

    @Column(name = "attachmentId", nullable = false)
    private String attachmentId;

    @Column(name = "phase", nullable = false)
    private String phase;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
