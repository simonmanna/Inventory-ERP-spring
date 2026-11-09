package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "PlmLifecycleEvent"}: Lifecycle history of a PLM item: registration, state transitions, revision bumps (ADR-S063).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "PlmLifecycleEvent")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class PlmLifecycleEvent {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "itemId", nullable = false)
    private String itemId;

    @Column(name = "kind", nullable = false)
    private String kind;

    @Column(name = "fromState")
    private String fromState;

    @Column(name = "toState")
    private String toState;

    @Column(name = "fromRevision")
    private String fromRevision;

    @Column(name = "toRevision")
    private String toRevision;

    @Column(name = "changeId")
    private String changeId;

    @Column(name = "note")
    private String note;

    @Column(name = "userId")
    private String userId;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
