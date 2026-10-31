package com.inventoryerp.fleet.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.fleet.application.FleetReminderService.Item;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What fleet tells the Phase 5 alerts. Documents and services come from the 1.9 reminder collection
 * ({@link FleetReminderService#dueItems}), so each document keeps its own lead days and the fleet reminder job and
 * these alerts can never disagree on what is due.
 */
public final class FleetAlertSources {

    private static final Set<String> DOCUMENTS = Set.of("compliance_document", "driver_document", "vehicle_tare");

    private FleetAlertSources() {}

    private static AlertItem item(Item it, String severity) {
        return new AlertItem(it.type() + ":" + it.key() + ":" + it.state(), severity, it.title(), it.body(), it.link(),
                Obj.of("itemType", it.type(), "state", it.state(), "dueOn", it.dueOn() == null ? null
                        : it.dueOn().toString()));
    }

    /** Vehicle documents, driver documents and stored tares that are expired or inside their lead days. */
    @Component
    public static class Documents implements AlertSource {

        private final FleetReminderService reminders;

        public Documents(FleetReminderService reminders) {
            this.reminders = reminders;
        }

        @Override
        public String alertType() {
            return AlertTypes.EXPIRING_DOCUMENT;
        }

        @Override
        @Transactional(readOnly = true)
        public List<AlertItem> collect(AlertScan scan) {
            List<AlertItem> out = new ArrayList<>();
            for (Item it : reminders.dueItems(scan.organizationId(), scan.asOf())) {
                if (DOCUMENTS.contains(it.type())) {
                    out.add(item(it, "expired".equals(it.state()) ? AlertItem.CRITICAL : AlertItem.WARNING));
                }
            }
            return out;
        }
    }

    /** Service schedules past due; a service merely due is the fleet reminder's business. */
    @Component
    public static class Services implements AlertSource {

        private final FleetReminderService reminders;

        public Services(FleetReminderService reminders) {
            this.reminders = reminders;
        }

        @Override
        public String alertType() {
            return AlertTypes.OVERDUE_MAINTENANCE;
        }

        @Override
        @Transactional(readOnly = true)
        public List<AlertItem> collect(AlertScan scan) {
            List<AlertItem> out = new ArrayList<>();
            for (Item it : reminders.dueItems(scan.organizationId(), scan.asOf())) {
                if ("service_due".equals(it.type()) && ServiceDuePolicy.OVERDUE.equals(it.state())) {
                    out.add(item(it, AlertItem.WARNING));
                }
            }
            return out;
        }
    }

    /** Out-of-tolerance tickets that have waited for a decision for at least the threshold, in hours. */
    @Component
    public static class WeighbridgeExceptions implements AlertSource {

        private final FleetDb db;

        public WeighbridgeExceptions(FleetDb db) {
            this.db = db;
        }

        @Override
        public String alertType() {
            return AlertTypes.WEIGHBRIDGE_EXCEPTION;
        }

        @Override
        @Transactional(readOnly = true)
        public List<AlertItem> collect(AlertScan scan) {
            long hours = scan.threshold() == null ? 0 : scan.threshold().longValue();
            Instant cutoff = Instant.now().minus(Duration.ofHours(hours));
            List<AlertItem> out = new ArrayList<>();
            for (Map<String, Object> e : SqlRows.list(db.jdbc().sql("SELECT e.id, e.\"varianceKg\", e.\"variancePct\","
                    + " e.\"allowedVarianceKg\", t.\"ticketNumber\", t.\"registrationPlate\" FROM"
                    + " \"WeighbridgeException\" e JOIN \"WeighbridgeTicket\" t ON t.id = e.\"ticketId\" WHERE"
                    + " e.\"organizationId\" = ? AND"
                    + " e.status = 'pending' AND e.\"raisedAt\" <= ? ORDER BY e.\"raisedAt\" LIMIT 200")
                    .params(scan.organizationId(), SqlRows.ts(cutoff)))) {
                out.add(new AlertItem((String) e.get("id"), AlertItem.WARNING, "Weighbridge ticket "
                        + e.get("ticketNumber") + " (" + e.get("registrationPlate") + ") is out of tolerance by "
                        + SqlRows.dec(e, "varianceKg").stripTrailingZeros().toPlainString() + " kg",
                        "Approve or reject the exception so the ticket can complete.", "/fleet/weighbridge/exceptions",
                        Obj.of("varianceKg", e.get("varianceKg"), "variancePct", e.get("variancePct"),
                                "allowedVarianceKg", e.get("allowedVarianceKg"))));
            }
            return out;
        }
    }
}
