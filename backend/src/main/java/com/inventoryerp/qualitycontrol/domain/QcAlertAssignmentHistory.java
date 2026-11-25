package com.inventoryerp.qualitycontrol.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "QcAlertAssignmentHistory"}: Append-only team / responsible change of a quality alert (ADR-S087 §8).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filter and
 * schema validation.
 */
@Entity
@Table(name = "QcAlertAssignmentHistory")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class QcAlertAssignmentHistory {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "alertId", nullable = false)
    private String alertId;

    @Column(name = "fromTeamId")
    private String fromTeamId;

    @Column(name = "toTeamId")
    private String toTeamId;

    @Column(name = "fromResponsibleUserId")
    private String fromResponsibleUserId;

    @Column(name = "toResponsibleUserId")
    private String toResponsibleUserId;

    @Column(name = "userId")
    private String userId;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
