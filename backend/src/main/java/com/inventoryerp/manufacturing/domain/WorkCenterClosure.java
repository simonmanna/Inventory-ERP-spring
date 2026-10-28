package com.inventoryerp.manufacturing.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "WorkCenterClosure"}: Non-working day of one work centre or the whole plant (ADR-S006).
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "WorkCenterClosure")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class WorkCenterClosure {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "workCenterId")
    private String workCenterId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "reason")
    private String reason;

    public String getId() {
        return id;
    }
}
