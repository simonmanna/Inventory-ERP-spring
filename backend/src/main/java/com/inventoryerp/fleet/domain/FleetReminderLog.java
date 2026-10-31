package com.inventoryerp.fleet.domain;

import com.inventoryerp.shared.persistence.TenancyFilters;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.Filter;

/**
 * Table {@code "FleetReminderLog"}: Expiry / service-due reminder sent by the fleet reminder job (fleet 1.9); one
 * per item per day.
 *
 * <p>Rows are read and written through JDBC; the entity carries the column list, the tenancy filters and
 * schema validation.
 */
@Entity
@Table(name = "FleetReminderLog")
@Filter(name = TenancyFilters.TENANT, condition = TenancyFilters.TENANT_CONDITION, deduceAliasInjectionPoints = false)
public class FleetReminderLog {

    @Id
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "organizationId", nullable = false)
    private String organizationId;

    @Column(name = "itemType", nullable = false)
    private String itemType;

    @Column(name = "itemId", nullable = false)
    private String itemId;

    @Column(name = "itemKey", nullable = false)
    private String itemKey;

    @Column(name = "reminderDate", nullable = false)
    private LocalDate reminderDate;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "vehicleId")
    private String vehicleId;

    @Column(name = "driverId")
    private String driverId;

    @Column(name = "dueOn")
    private LocalDate dueOn;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body")
    private String body;

    @Column(name = "notificationId")
    private String notificationId;

    @Column(name = "createdAt", nullable = false)
    private Instant createdAt;

    public String getId() {
        return id;
    }
}
