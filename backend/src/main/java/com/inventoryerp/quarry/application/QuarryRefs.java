package com.inventoryerp.quarry.application;

import com.inventoryerp.fleet.application.FleetVehicleService;
import com.inventoryerp.fleet.application.WeighbridgeTicketService;
import com.inventoryerp.fleet.application.WeighbridgeTicketService.CompletedTicket;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Read-only references into fleet and hr for the quarry services: vehicles and their class through
 * {@link FleetVehicleService}, completed weighbridge tickets through {@link WeighbridgeTicketService#completedTicket},
 * fleet trips and hr employees / shifts by id. Quarry never writes fleet or hr.
 */
@Component
public class QuarryRefs {

    /** Ticket subjects that may evidence internal stone movement (not customer deliveries or purchases). */
    static final Set<String> HAULAGE_SUBJECTS = Set.of("internal_haulage", "other");

    private final QuarryDb db;
    private final FleetVehicleService vehicles;
    private final WeighbridgeTicketService tickets;

    public QuarryRefs(QuarryDb db, FleetVehicleService vehicles, WeighbridgeTicketService tickets) {
        this.db = db;
        this.vehicles = vehicles;
        this.tickets = tickets;
    }

    /** Vehicle detail (with {@code category.kind}); 400 when unknown or disposed. */
    Map<String, Object> vehicle(String id) {
        // Checked first: an exception thrown through fleet's transactional proxy would mark our transaction
        // rollback-only.
        if (id == null || !db.exists("FleetVehicle", id, true)) {
            throw ApiException.badRequest("Vehicle not found");
        }
        Map<String, Object> v = vehicles.get(id);
        if ("disposed".equals(v.get("status"))) {
            throw ApiException.badRequest("Vehicle " + v.get("code") + " is disposed");
        }
        return v;
    }

    /** A mobile-plant vehicle (excavator, loader, dozer, ...): equipment of an extraction log or a drill rig. */
    @SuppressWarnings("unchecked")
    Map<String, Object> plant(String id) {
        Map<String, Object> v = vehicle(id);
        Map<String, Object> c = (Map<String, Object>) v.get("category");
        String kind = c == null ? null : (String) c.get("kind");
        if (!"mobile_plant".equals(kind)) {
            throw ApiException.badRequest("Vehicle " + v.get("code") + " is not mobile plant (class "
                    + (c == null ? "?" : c.get("name")) + "); log excavators, loaders and other mobile plant");
        }
        return v;
    }

    static String categoryId(Map<String, Object> v) {
        return (String) v.get("categoryId");
    }

    /** Capacity per load: the given one, else the truck's payload capacity, else 400. */
    BigDecimal capacity(String truckId, BigDecimal given) {
        if (given != null) {
            if (truckId != null) {
                vehicle(truckId);
            }
            return given;
        }
        if (truckId == null) {
            throw ApiException.badRequest("Give the truck or the capacity per load");
        }
        Map<String, Object> v = vehicle(truckId);
        if (v.get("payloadCapacityT") == null || SqlRows.dec(v, "payloadCapacityT").signum() <= 0) {
            throw ApiException.badRequest("Truck " + v.get("code") + " has no payload capacity; enter the capacity per"
                    + " load");
        }
        return SqlRows.dec(v, "payloadCapacityT");
    }

    /** A completed weighbridge ticket of internal haulage (or other) — never a customer / supplier weighing. */
    CompletedTicket ticket(String id) {
        Map<String, Object> row = SqlRows.one(db.jdbc().sql("SELECT \"ticketNumber\", status FROM \"WeighbridgeTicket\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(id, QuarryDb.org()));
        if (row == null) {
            throw ApiException.badRequest("Weighbridge ticket not found");
        }
        if (!"completed".equals(row.get("status"))) {
            throw ApiException.badRequest("Weighbridge ticket " + row.get("ticketNumber") + " is not completed (it is "
                    + row.get("status") + ")");
        }
        CompletedTicket t = tickets.completedTicket(id);
        if (!HAULAGE_SUBJECTS.contains(t.subjectType())) {
            throw ApiException.badRequest("Weighbridge ticket " + t.ticketNumber() + " is a "
                    + t.subjectType().replace('_', ' ') + " weighing, not internal haulage");
        }
        if (t.netKg() == null || t.netKg().signum() <= 0) {
            throw ApiException.badRequest("Weighbridge ticket " + t.ticketNumber() + " has no net weight");
        }
        return t;
    }

    void trip(String id) {
        if (id != null && !db.exists("FleetLoad", id, false)) {
            throw ApiException.badRequest("Fleet trip not found");
        }
    }

    void employee(String id) {
        db.requireRef("HrEmployee", id, true, "Employee not found");
    }

    void hrShift(String id) {
        db.requireRef("HrShift", id, true, "Shift not found");
    }
}
