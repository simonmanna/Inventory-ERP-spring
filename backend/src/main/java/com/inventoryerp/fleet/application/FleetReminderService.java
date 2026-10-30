package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetReportCommands.ReminderQuery;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.PageResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expiry and service-due reminders (fleet 1.9, ADR-S009 §9), raised by the daily {@code fleet-reminders} job or on
 * demand. Items: vehicle compliance documents expired or within their own lead days
 * ({@link FleetComplianceService#dueForOrganization}), driver licences / medicals / permits within the driver's lead
 * days ({@link FleetDriverService#documentsDueForOrganization}), active stored weighbridge tares expiring within
 * {@code fleet.tareReminderDays} (default 3), and services due or overdue
 * ({@link FleetServiceScheduleService#dueForOrganization}).
 *
 * <p>Each item is reminded at most once per business day: the {@code FleetReminderLog} row (unique per organization,
 * item and day) is claimed first and only a claimed row sends the in-app notification (kernel
 * {@link NotificationsService}, category {@code fleet}) and publishes {@code fleet.reminder.due}, which communication
 * rules can route to email / WhatsApp. Metrics: {@code fleet.reminders.sent} / {@code fleet.reminders.skipped}
 * (tag {@code type}).
 */
@Service
public class FleetReminderService {

    static final String SETTING_TARE_DAYS = "fleet.tareReminderDays";

    private static final Logger log = LoggerFactory.getLogger(FleetReminderService.class);

    private final FleetDb db;
    private final FleetComplianceService compliance;
    private final FleetDriverService drivers;
    private final FleetServiceScheduleService schedules;
    private final NotificationsService notifications;
    private final SettingResolverService settings;
    private final EventBus events;
    private final ObjectProvider<MeterRegistry> meters;

    public FleetReminderService(FleetDb db, FleetComplianceService compliance, FleetDriverService drivers,
            FleetServiceScheduleService schedules, NotificationsService notifications, SettingResolverService settings,
            EventBus events, ObjectProvider<MeterRegistry> meters) {
        this.db = db;
        this.compliance = compliance;
        this.drivers = drivers;
        this.schedules = schedules;
        this.notifications = notifications;
        this.settings = settings;
        this.events = events;
        this.meters = meters;
    }

    /** One reminder candidate. */
    record Item(String type, String itemId, String key, String state, String vehicleId, String driverId,
            LocalDate dueOn, String title, String body, String link) {}

    /**
     * Remind every due item of the caller's organization for {@code asOf} (default today). Returns the counts sent per
     * item type and the number skipped because the item was already reminded that day.
     */
    @Transactional
    public Map<String, Object> run(LocalDate asOf) {
        LocalDate day = asOf == null ? LocalDate.now(BusinessZone.get()) : asOf;
        String org = FleetDb.org();
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> d : compliance.dueForOrganization(org, day)) {
            String plate = d.get("vehicleRegistrationPlate") == null ? "" : " (" + d.get("vehicleRegistrationPlate")
                    + ")";
            String what = IncidentPolicy.label(String.valueOf(d.get("docType")));
            items.add(new Item("compliance_document", (String) d.get("id"), (String) d.get("id"),
                    (String) d.get("state"), (String) d.get("vehicleId"), null, (LocalDate) d.get("expiresOn"),
                    cap(what) + " of " + d.get("vehicleCode") + plate + " " + verb((String) d.get("state")) + " "
                            + d.get("expiresOn"),
                    "Renew the " + what + (d.get("documentNumber") == null ? "" : " " + d.get("documentNumber"))
                            + " of vehicle " + d.get("vehicleCode") + ".", "/fleet/vehicles/" + d.get("vehicleId")));
        }
        for (Map<String, Object> d : drivers.documentsDueForOrganization(org, day)) {
            String doc = (String) d.get("document");
            items.add(new Item("driver_document", (String) d.get("driverId"), d.get("driverId") + ":" + doc,
                    (String) d.get("state"), null, (String) d.get("driverId"), (LocalDate) d.get("expiresOn"),
                    "Driver " + d.get("driverCode") + " " + d.get("driverName") + ": " + documentLabel(doc) + " "
                            + verb((String) d.get("state")) + " " + d.get("expiresOn"),
                    "Renew the " + documentLabel(doc) + " of driver " + d.get("driverCode") + ".",
                    "/fleet/drivers/" + d.get("driverId")));
        }
        int tareDays = tareLeadDays();
        Instant until = day.plusDays(tareDays + 1L).atStartOfDay(BusinessZone.get()).toInstant();
        Instant today = day.atStartOfDay(BusinessZone.get()).toInstant();
        for (Map<String, Object> t : SqlRows.list(db.jdbc().sql("SELECT t.id, t.\"registrationPlate\","
                + " t.\"expiresAt\", t.\"vehicleId\", v.code FROM \"FleetVehicleTare\" t LEFT JOIN \"FleetVehicle\" v"
                + " ON v.id = t.\"vehicleId\" WHERE t.\"organizationId\" = ? AND t.status = 'active' AND"
                + " t.\"expiresAt\" < ? ORDER BY t.\"expiresAt\"").params(org, SqlRows.ts(until)))) {
            Instant exp = (Instant) t.get("expiresAt");
            LocalDate expDay = LocalDate.ofInstant(exp, BusinessZone.get());
            String state = exp.isBefore(today) ? "expired" : "expiring";
            items.add(new Item("vehicle_tare", (String) t.get("id"), (String) t.get("id"), state,
                    (String) t.get("vehicleId"), null, expDay,
                    "Stored tare of " + t.get("registrationPlate") + " " + verb(state) + " " + expDay,
                    "Weigh the empty truck " + t.get("registrationPlate") + " again (re-tare).",
                    "/fleet/weighbridge/tares"));
        }
        for (Map<String, Object> s : schedules.dueForOrganization(org, day)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> v = (Map<String, Object>) s.get("vehicle");
            String state = (String) s.get("dueState");
            LocalDate dueOn = null;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dims = (List<Map<String, Object>>) s.get("due");
            for (Map<String, Object> dim : dims) {
                if (dim.get("nextDueOn") instanceof LocalDate ld) {
                    dueOn = ld;
                }
            }
            items.add(new Item("service_due", (String) s.get("id"), (String) s.get("id"), state, (String) v.get("id"),
                    null, dueOn, "Service " + s.get("name") + " of " + v.get("code") + " is " + state,
                    "Plan the " + s.get("name") + " service of " + v.get("code") + (s.get("liveWorkOrder") == null
                            ? "." : " (work order raised)."), "/fleet/maintenance"));
        }
        Map<String, Object> sent = new LinkedHashMap<>();
        for (String t : List.of("compliance_document", "driver_document", "vehicle_tare", "service_due")) {
            sent.put(t, 0);
        }
        int skipped = 0;
        for (Item it : items) {
            if (remind(org, day, it)) {
                sent.put(it.type(), (Integer) sent.get(it.type()) + 1);
                count("fleet.reminders.sent", it.type());
            } else {
                skipped++;
                count("fleet.reminders.skipped", it.type());
            }
        }
        return Obj.of("asOf", day, "sent", sent, "skipped", skipped, "items", items.size());
    }

    /** Claim the item's row for the day; only the first claim notifies. */
    private boolean remind(String org, LocalDate day, Item it) {
        String id = UUID.randomUUID().toString();
        int claimed = db.jdbc().sql("INSERT INTO \"FleetReminderLog\" (id, \"organizationId\", \"itemType\","
                + " \"itemId\", \"itemKey\", \"reminderDate\", state, \"vehicleId\", \"driverId\", \"dueOn\", title,"
                + " body, \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + FleetDb.NOW + ") ON CONFLICT"
                + " (\"organizationId\", \"itemType\", \"itemKey\", \"reminderDate\") DO NOTHING")
                .params(id, org, it.type(), it.itemId(), it.key(), java.sql.Date.valueOf(day), it.state(),
                        it.vehicleId(), it.driverId(), it.dueOn() == null ? null : java.sql.Date.valueOf(it.dueOn()),
                        it.title(), it.body()).update();
        if (claimed == 0) {
            return false;
        }
        Map<String, Object> payload = Obj.of("kind", "fleet.reminder", "itemType", it.type(), "itemId", it.itemId(),
                "state", it.state(), "dueOn", it.dueOn() == null ? null : it.dueOn().toString(), "link", it.link());
        try {
            Map<String, Object> n = notifications.send(new SendInput(org, null, "in_app", "fleet", it.title(),
                    it.body(), payload));
            db.jdbc().sql("UPDATE \"FleetReminderLog\" SET \"notificationId\" = ? WHERE id = ?").params(n.get("id"), id)
                    .update();
        } catch (RuntimeException e) {
            log.warn("fleet reminder {} not delivered: {}", it.key(), e.toString());
        }
        events.publish(DomainEvents.FLEET_REMINDER_DUE, Obj.of("organizationId", org, "itemType", it.type(),
                "itemId", it.itemId(), "state", it.state(), "dueOn", it.dueOn() == null ? null : it.dueOn().toString(),
                "vehicleId", it.vehicleId(), "driverId", it.driverId(), "title", it.title(), "body", it.body()));
        return true;
    }

    /** Reminders sent (newest first): {@code itemType}, reminder days {@code from} .. {@code to}. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> log(ReminderQuery q) {
        int page = q.page() == null ? 1 : q.page();
        int size = q.pageSize() == null ? 50 : q.pageSize();
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        FleetIncidentService.eq(where, params, "r.\"itemType\"", q.itemType());
        if (q.from() != null) {
            where.append(" AND r.\"reminderDate\" >= ?");
            params.add(java.sql.Date.valueOf(q.from()));
        }
        if (q.to() != null) {
            where.append(" AND r.\"reminderDate\" <= ?");
            params.add(java.sql.Date.valueOf(q.to()));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetReminderLog\" r" + where).params(params)
                .query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT r.*, v.code AS \"vehicleCode\","
                + " d.code AS \"driverCode\" FROM \"FleetReminderLog\" r LEFT JOIN \"FleetVehicle\" v ON v.id ="
                + " r.\"vehicleId\" LEFT JOIN \"FleetDriver\" d ON d.id = r.\"driverId\"" + where
                + " ORDER BY r.\"reminderDate\" DESC, r.\"createdAt\" DESC LIMIT ? OFFSET ?").params(pageParams));
        return PageResult.of(rows, page, size, total);
    }

    int tareLeadDays() {
        Object v = settings.resolve(SETTING_TARE_DAYS, SettingResolverService.SettingContext.EMPTY);
        try {
            int d = v == null ? 3 : new java.math.BigDecimal(String.valueOf(v)).intValue();
            return Math.max(0, Math.min(d, 365));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private void count(String name, String type) {
        MeterRegistry r = meters.getIfAvailable();
        if (r != null) {
            r.counter(name, "type", type).increment();
        }
    }

    private static String verb(String state) {
        return "expired".equals(state) ? "expired on" : "expires on";
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String documentLabel(String doc) {
        return switch (doc) {
            case "licence" -> "driving licence";
            case "medical" -> "medical certificate";
            default -> "operator permit";
        };
    }
}
