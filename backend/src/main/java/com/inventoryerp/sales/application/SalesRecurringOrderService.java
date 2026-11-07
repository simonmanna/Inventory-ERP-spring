package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Repeat a sales order on a schedule (SG-9). Each run makes a draft copy that someone still confirms. */
@Service
public class SalesRecurringOrderService {

    private static final Set<String> FREQUENCIES = Set.of("weekly", "monthly", "quarterly", "yearly");

    private final SalesDb db;
    private final SalesOrderService orders;
    private final SalesAlerts alerts;

    public SalesRecurringOrderService(SalesDb db, SalesOrderService orders, SalesAlerts alerts) {
        this.db = db;
        this.orders = orders;
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return db.rows("SELECT r.*, o.number AS \"sourceNumber\", p.name AS \"partnerName\", l.number AS \"lastNumber\" FROM \"SalesRecurringOrder\" r"
                + " JOIN \"SalesOrder\" o ON o.id = r.\"sourceOrderId\" LEFT JOIN \"Partner\" p ON p.id = o.\"partnerId\" LEFT JOIN"
                + " \"SalesOrder\" l ON l.id = r.\"lastOrderId\" WHERE r.\"organizationId\" = ? ORDER BY r.\"nextRunDate\"", org());
    }

    @Transactional
    public Map<String, Object> create(String orderId, Map<String, Object> in) {
        orders.require(orderId);
        String f = str(in.get("frequency"));
        if (f == null || !FREQUENCIES.contains(f)) {
            throw ApiException.badRequest("frequency must be one of: monthly, quarterly, weekly, yearly");
        }
        LocalDate next = in.get("nextRunDate") == null ? advance(LocalDate.now(), f) : SalesDb.date(str(in.get("nextRunDate")));
        return db.insert("SalesRecurringOrder", Obj.of("sourceOrderId", orderId, "frequency", f, "nextRunDate", next, "endDate",
                SalesDb.date(str(in.get("endDate"))), "createdById", user()));
    }

    @Transactional
    public Map<String, Object> stop(String id) {
        if (db.update("UPDATE \"SalesRecurringOrder\" SET active = false, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?",
                id, org()) == 0) {
            throw ApiException.notFound("Recurring order not found");
        }
        return Obj.of("ok", true);
    }

    static LocalDate advance(LocalDate d, String f) {
        return switch (f) {
            case "weekly" -> d.plusWeeks(1);
            case "quarterly" -> d.plusMonths(3);
            case "yearly" -> d.plusYears(1);
            default -> d.plusMonths(1);
        };
    }

    /** Job body: draft a copy of every order due. A customer no longer active stops its schedule instead of failing. */
    @Transactional
    public int runDue(LocalDate today) {
        int made = 0;
        for (Map<String, Object> r : db.rows("SELECT * FROM \"SalesRecurringOrder\" WHERE \"organizationId\" = ? AND active AND"
                + " \"nextRunDate\" <= ? FOR UPDATE SKIP LOCKED", org(), today)) {
            String id = str(r.get("id"));
            LocalDate end = r.get("endDate") instanceof LocalDate d ? d : null;
            if (end != null && end.isBefore(today)) {
                db.update("UPDATE \"SalesRecurringOrder\" SET active = false, \"updatedAt\" = " + NOW + " WHERE id = ?", id);
                continue;
            }
            Map<String, Object> source = db.row("SELECT \"partnerId\" FROM \"SalesOrder\" WHERE id = ?", r.get("sourceOrderId"));
            try {
                db.requireActivePartner(str(source.get("partnerId")));
            } catch (com.inventoryerp.shared.web.ApiException e) {
                db.update("UPDATE \"SalesRecurringOrder\" SET active = false, \"updatedAt\" = " + NOW + " WHERE id = ?", id);
                alerts.send(str(r.get("createdById")), "sales_recurring_stopped", "A recurring sales order stopped", e.getMessage(),
                        Obj.of("entityType", "sales_order", "entityId", r.get("sourceOrderId")));
                continue;
            }
            Map<String, Object> copy = orders.duplicate(str(r.get("sourceOrderId")));
            db.update("UPDATE \"SalesRecurringOrder\" SET \"lastOrderId\" = ?, \"runCount\" = \"runCount\" + 1, \"nextRunDate\" = ?, \"updatedAt\" = "
                    + NOW + " WHERE id = ?", copy.get("id"), advance((LocalDate) r.get("nextRunDate"), str(r.get("frequency"))), id);
            alerts.send(str(r.get("createdById")), "sales_recurring_created", "Recurring order " + copy.get("number") + " is ready",
                    "Review and confirm the draft.", Obj.of("entityType", "sales_order", "entityId", copy.get("id")));
            made++;
        }
        return made;
    }
}
