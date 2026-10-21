package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.pos.domain.PosTable;
import com.inventoryerp.pos.domain.PosTableReservation;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Table reservations (port of {@code pos-reservations.service.ts}): pending → seated → completed, pending →
 * cancelled / no_show; plus the no-show sweep and the RESERVED-flag refresh the reservation worker runs.
 */
@Service
public class PosReservationsService {

    private static final Logger log = LoggerFactory.getLogger(PosReservationsService.class);
    private static final long SEATABLE_GRACE_MS = 30 * 60 * 1000L;

    private final PosDb db;
    private final AuditService audit;
    private final EventBus events;
    private final PosTablesService tables;
    private final TransactionTemplate tx;

    public PosReservationsService(PosDb db, AuditService audit, EventBus events, PosTablesService tables,
            PlatformTransactionManager txm) {
        this.db = db;
        this.audit = audit;
        this.events = events;
        this.tables = tables;
        this.tx = new TransactionTemplate(txm);
    }

    private static String iso(Object instant) {
        return JsonConfig.toJsIso((Instant) instant);
    }

    public List<Map<String, Object>> list(String date, String status, String tableId) {
        String orgId = org();
        StringBuilder where = new StringBuilder("\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(orgId));
        if (status != null && !status.isEmpty() && !"all".equals(status)) {
            where.append(" AND status = ?");
            params.add(status);
        }
        if (tableId != null && !tableId.isEmpty()) {
            where.append(" AND \"tableId\" = ?");
            params.add(tableId);
        }
        if (date != null && !date.isEmpty()) {
            Instant d = parseDate(date);
            if (d != null) {
                Instant start = d.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant end = start.plus(1, ChronoUnit.DAYS);
                where.append(" AND \"startAt\" >= ? AND \"startAt\" < ?");
                params.add(Timestamp.from(start));
                params.add(Timestamp.from(end));
            }
        }
        where.append(" ORDER BY \"startAt\" ASC LIMIT 200");
        List<Map<String, Object>> rows = db.list(PosTableReservation.class, where.toString(), params.toArray());
        for (Map<String, Object> r : rows) {
            Map<String, Object> t = db.first(PosTable.class, "id = ?", r.get("tableId"));
            r.put("table", t == null ? null : Obj.of("id", t.get("id"), "number", t.get("number"), "name", t.get("name"),
                    "status", t.get("status")));
        }
        return rows;
    }

    /** JS {@code new Date(s)}; {@code null} when it is an Invalid Date. */
    private static Instant parseDate(String s) {
        try {
            return PosSql.date(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Map<String, Object> get(String id) {
        Map<String, Object> r = db.first(PosTableReservation.class, "id = ? AND \"organizationId\" = ?", id, org());
        if (r == null) {
            throw ApiException.notFound("Reservation not found");
        }
        r.put("table", db.first(PosTable.class, "id = ?", r.get("tableId")));
        return r;
    }

    private Map<String, Object> existing(String id) {
        Map<String, Object> r = db.first(PosTableReservation.class, "id = ? AND \"organizationId\" = ?", id, org());
        if (r == null) {
            throw ApiException.notFound("Reservation not found");
        }
        return r;
    }

    public Map<String, Object> create(Map<String, Object> dto) {
        String orgId = org();
        String customerName = PosSql.str(dto, "customerName");
        if (customerName == null || customerName.trim().isEmpty()) {
            throw ApiException.badRequest("Customer name is required");
        }
        Instant startAt = parseDate(PosSql.str(dto, "startAt"));
        Instant endAt = parseDate(PosSql.str(dto, "endAt"));
        if (startAt == null || endAt == null) {
            throw ApiException.badRequest("Invalid startAt / endAt");
        }
        if (!endAt.isAfter(startAt)) {
            throw ApiException.badRequest("endAt must be after startAt");
        }
        String tableId = PosSql.str(dto, "tableId");
        Map<String, Object> created = tx.execute(st -> {
            db.jdbc().sql("SELECT id FROM \"PosTable\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(tableId, orgId).query().listOfRows();
            Map<String, Object> table = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", tableId, orgId);
            if (table == null) {
                throw ApiException.notFound("Table not found");
            }
            if (!Boolean.TRUE.equals(table.get("active"))) {
                throw ApiException.conflict("Cannot reserve an archived table");
            }
            Map<String, Object> overlap = db.first(PosTableReservation.class, "\"organizationId\" = ? AND \"tableId\" = ?"
                    + " AND status IN ('pending', 'seated') AND \"startAt\" < ? AND \"endAt\" > ?", orgId, tableId,
                    Timestamp.from(endAt), Timestamp.from(startAt));
            if (overlap != null) {
                throw ApiException.conflict("Table T" + table.get("number") + " is already reserved from "
                        + iso(overlap.get("startAt")) + " to " + iso(overlap.get("endAt")));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("organizationId", orgId);
            data.put("tableId", tableId);
            data.put("customerName", customerName.trim());
            data.put("phone", dto.get("phone"));
            data.put("email", dto.get("email"));
            data.put("partySize", dto.get("partySize") == null ? 2 : dto.get("partySize"));
            data.put("startAt", startAt);
            data.put("endAt", endAt);
            data.put("notes", dto.get("notes"));
            data.put("status", "pending");
            Map<String, Object> row = db.insert(PosTableReservation.class, data);
            boolean soon = startAt.toEpochMilli() - System.currentTimeMillis() < 60 * 60 * 1000L;
            if (soon && "available".equals(table.get("status"))) {
                db.update(PosTable.class, Obj.of("status", "reserved"), "id = ? AND \"organizationId\" = ?", tableId,
                        orgId);
            }
            audit.recordInTx(AuditInput.of("PosTableReservation", (String) row.get("id"), "reserve", null,
                    Obj.of("tableId", tableId, "startAt", JsonConfig.toJsIso(startAt), "endAt",
                            JsonConfig.toJsIso(endAt), "customerName", row.get("customerName"))));
            return row;
        });
        events.publish(DomainEvents.POS_TABLE_RESERVATION_CREATED, Obj.of("organizationId", orgId,
                "reservationId", created.get("id"), "tableId", created.get("tableId"),
                "startAt", iso(created.get("startAt")), "endAt", iso(created.get("endAt"))));
        return created;
    }

    public Map<String, Object> update(String id, Map<String, Object> dto) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> existing = existing(id);
            if (!"pending".equals(existing.get("status"))) {
                throw ApiException.badRequest("Cannot edit a " + existing.get("status") + " reservation");
            }
            Instant startAt = PosSql.truthy(dto.get("startAt")) ? parseDate(PosSql.str(dto, "startAt"))
                    : (Instant) existing.get("startAt");
            Instant endAt = PosSql.truthy(dto.get("endAt")) ? parseDate(PosSql.str(dto, "endAt"))
                    : (Instant) existing.get("endAt");
            if (!endAt.isAfter(startAt)) {
                throw ApiException.badRequest("endAt must be after startAt");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            for (String k : List.of("customerName", "phone", "email", "partySize")) {
                data.put(k, dto.get(k) != null ? dto.get(k) : existing.get(k));
            }
            data.put("startAt", startAt);
            data.put("endAt", endAt);
            data.put("notes", dto.get("notes") != null ? dto.get("notes") : existing.get("notes"));
            Map<String, Object> updated = db.update(PosTableReservation.class, data,
                    "id = ? AND \"organizationId\" = ?", id, orgId);
            audit.recordInTx(AuditInput.of("PosTableReservation", id, "update",
                    Obj.of("startAt", existing.get("startAt"), "endAt", existing.get("endAt"), "partySize",
                            existing.get("partySize")),
                    Obj.of("startAt", startAt, "endAt", endAt, "partySize", updated.get("partySize"))));
            return updated;
        });
    }

    /** Seat a pending reservation; the table turns OCCUPIED, optionally linked to an open order. */
    public Map<String, Object> seat(String id, String orderId) {
        String orgId = org();
        Map<String, Object> updated = tx.execute(st -> {
            Map<String, Object> existing = existing(id);
            if (!"pending".equals(existing.get("status"))) {
                throw ApiException.badRequest("Reservation is already " + existing.get("status"));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", "seated");
            data.put("seatedAt", PosDb.raw(NOW));
            data.put("seatedOrderId", orderId);
            Map<String, Object> row = db.update(PosTableReservation.class, data, "id = ? AND \"organizationId\" = ?",
                    id, orgId);
            String tableId = (String) existing.get("tableId");
            tables.syncTableStatus(tableId);
            Map<String, Object> table = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", tableId, orgId);
            if (table != null && "available".equals(table.get("status"))) {
                db.update(PosTable.class, Obj.of("status", "occupied"), "id = ? AND \"organizationId\" = ?", tableId,
                        orgId);
            }
            Map<String, Object> newValues = new LinkedHashMap<>();
            newValues.put("status", "seated");
            newValues.put("seatedOrderId", orderId);
            audit.recordInTx(AuditInput.of("PosTableReservation", id, "update", Obj.of("status", "pending"), newValues));
            return row;
        });
        if (orderId != null) {
            tables.attachSaleToTable((String) updated.get("tableId"), orderId, updated.get("customerName"),
                    updated.get("partySize"));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", orgId);
        payload.put("reservationId", id);
        payload.put("tableId", updated.get("tableId"));
        if (orderId != null) {
            payload.put("orderId", orderId);
        }
        events.publish(DomainEvents.POS_TABLE_RESERVATION_SEATED, payload);
        return updated;
    }

    public Map<String, Object> cancel(String id) {
        String orgId = org();
        Map<String, Object> r = tx.execute(st -> {
            Map<String, Object> existing = existing(id);
            if (!"pending".equals(existing.get("status"))) {
                throw ApiException.badRequest("Reservation is already " + existing.get("status"));
            }
            Map<String, Object> updated = db.update(PosTableReservation.class,
                    Obj.of("status", "cancelled", "cancelledAt", PosDb.raw(NOW)), "id = ? AND \"organizationId\" = ?",
                    id, orgId);
            audit.recordInTx(AuditInput.of("PosTableReservation", id, "cancel", Obj.of("status", "pending"),
                    Obj.of("status", "cancelled")));
            tables.syncTableStatus((String) existing.get("tableId"));
            return updated;
        });
        events.publish(DomainEvents.POS_TABLE_RESERVATION_CANCELLED, Obj.of("organizationId", orgId,
                "reservationId", id, "tableId", r.get("tableId")));
        return r;
    }

    /** Lapsed pending booking → no_show (worker and {@code POST :id/no-show}). Non-pending rows are returned as is. */
    public Map<String, Object> markNoShow(String id) {
        String orgId = org();
        Map<String, Object> r = tx.execute(st -> {
            Map<String, Object> existing = existing(id);
            if (!"pending".equals(existing.get("status"))) {
                return existing;
            }
            Map<String, Object> updated = db.update(PosTableReservation.class,
                    Obj.of("status", "no_show", "noShowAt", PosDb.raw(NOW)), "id = ? AND \"organizationId\" = ?", id,
                    orgId);
            audit.recordInTx(AuditInput.of("PosTableReservation", id, "update", Obj.of("status", "pending"),
                    Obj.of("status", "no_show")));
            tables.syncTableStatus((String) existing.get("tableId"));
            return updated;
        });
        events.publish(DomainEvents.POS_TABLE_RESERVATION_NO_SHOW, Obj.of("organizationId", orgId,
                "reservationId", id, "tableId", r.get("tableId")));
        return r;
    }

    public Map<String, Object> complete(String id) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> existing = existing(id);
            if (!"seated".equals(existing.get("status"))) {
                throw ApiException.badRequest("Reservation is " + existing.get("status") + ", not seated");
            }
            Map<String, Object> updated = db.update(PosTableReservation.class, Obj.of("status", "completed"),
                    "id = ? AND \"organizationId\" = ?", id, orgId);
            tables.syncTableStatus((String) existing.get("tableId"));
            return updated;
        });
    }

    /** Worker: pending reservations past the 30-minute grace → no_show (tenant scope set by the caller). */
    public int sweepNoShows() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusMillis(SEATABLE_GRACE_MS));
        List<String> stale = db.jdbc().sql("SELECT id FROM \"PosTableReservation\" WHERE \"organizationId\" = ?"
                + " AND status = 'pending' AND \"startAt\" < ?").params(org(), cutoff).query(String.class).list();
        for (String id : stale) {
            try {
                markNoShow(id);
            } catch (RuntimeException e) {
                log.warn("No-show mark failed: {}", e.getMessage());
            }
        }
        return stale.size();
    }

    /** Worker: tables whose next booking starts within 60 minutes flip AVAILABLE → RESERVED (unless occupied). */
    public int refreshReservedFlags() {
        String orgId = org();
        Timestamp soon = Timestamp.from(Instant.now().plusMillis(60 * 60 * 1000L));
        List<String> upcoming = db.jdbc().sql("SELECT \"tableId\" FROM \"PosTableReservation\" WHERE \"organizationId\" = ?"
                + " AND status = 'pending' AND \"startAt\" < ?").params(orgId, soon).query(String.class).list();
        int flipped = 0;
        for (String tableId : upcoming) {
            long open = db.jdbc().sql("SELECT count(*) FROM \"PosTableOrder\" WHERE \"organizationId\" = ?"
                    + " AND \"tableId\" = ? AND \"closedAt\" IS NULL").params(orgId, tableId).query(Long.class).single();
            if (open > 0) {
                continue;
            }
            Map<String, Object> table = db.first(PosTable.class, "id = ? AND \"organizationId\" = ?", tableId, orgId);
            if (table != null && "available".equals(table.get("status"))) {
                db.update(PosTable.class, Obj.of("status", "reserved"), "id = ? AND \"organizationId\" = ?", tableId,
                        orgId);
                flipped += 1;
            }
        }
        return flipped;
    }
}
