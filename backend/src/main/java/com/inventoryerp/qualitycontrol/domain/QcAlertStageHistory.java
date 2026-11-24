package com.inventoryerp.qualitycontrol.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QcAlertStageHistory"}: Append-only stage move of a quality alert (ADR-S087 §8).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcAlertStageHistory")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcAlertStageHistory {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "alertId", nullable = false)
    private String alertId;

    @Column(name = "fromStageId")
    private String fromStageId;

    @Column(name = "toStageId", nullable = false)
    private String toStageId;

    @Column(name = "comment")
    private String comment;

    @Column(name = "userId")
    private String userId;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
