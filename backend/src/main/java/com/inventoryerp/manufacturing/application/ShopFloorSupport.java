package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.ShiftClock.ShiftDef;
import com.inventoryerp.manufacturing.application.ShiftClock.Window;
import com.inventoryerp.manufacturing.domain.ShopFloorDowntimeReason;
import com.inventoryerp.manufacturing.domain.ShopFloorShift;
import com.inventoryerp.manufacturing.domain.ShopFloorShiftReport;
import com.inventoryerp.manufacturing.domain.ShopFloorWorkCenterProfile;
import com.inventoryerp.manufacturing.domain.WorkCenter;
import com.inventoryerp.manufacturing.domain.WorkOrder;
import com.inventoryerp.shared.web.ApiException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Shared lookups of the shop-floor services (ADR-S015): organization time zone, shifts, reasons, work centres,
 * work orders (locked), profiles, and the find-or-create of the shift report an instant belongs to.
 */
@Component
class ShopFloorSupport {

    private final MfgDb db;
    private final SequenceService seq;

    ShopFloorSupport(MfgDb db, SequenceService seq) {
        this.db = db;
        this.seq = seq;
    }

    MfgDb db() {
        return db;
    }

    /** The organization's IANA time zone (UTC when unset / invalid): shift windows are local. */
    ZoneId zone() {
        String tz = db.jdbc().sql("SELECT timezone FROM \"Organization\" WHERE id = ?").param(MfgDb.org())
                .query(String.class).optional().orElse(null);
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    List<Map<String, Object>> shiftRows(boolean activeOnly) {
        return db.list(ShopFloorShift.class, (activeOnly ? " AND \"isActive\" = true" : "")
                + " ORDER BY \"sortOrder\", \"startMinute\", code");
    }

    static ShiftDef def(Map<String, Object> s) {
        return new ShiftDef(str(s, "id"), str(s, "code"), ((Number) s.get("startMinute")).intValue(),
                ((Number) s.get("endMinute")).intValue(), ((Number) s.get("breakMinutes")).intValue());
    }

    List<ShiftDef> activeShifts() {
        List<ShiftDef> out = new ArrayList<>();
        for (Map<String, Object> s : shiftRows(true)) {
            out.add(def(s));
        }
        return out;
    }

    Map<String, Object> requireShift(String id) {
        Map<String, Object> s = db.byId(ShopFloorShift.class, id);
        if (s == null) {
            throw ApiException.notFound("Shift not found");
        }
        return s;
    }

    Map<String, Object> requireWorkCenter(String id) {
        Map<String, Object> wc = id == null ? null : db.byId(WorkCenter.class, id);
        if (wc == null) {
            throw ApiException.notFound("Work centre not found");
        }
        return wc;
    }

    /** The work order row, locked for the rest of the transaction. */
    Map<String, Object> lockWorkOrder(String id) {
        Map<String, Object> wo = id == null ? null : SqlRows.one(WorkOrder.class, db.jdbc().sql("SELECT * FROM"
                + " \"WorkOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, MfgDb.org()));
        if (wo == null) {
            throw ApiException.notFound("Work order not found");
        }
        return wo;
    }

    /** Row of {@code table} by id under the tenant, locked; 404 with {@code missing} when absent. */
    Map<String, Object> lock(Class<?> entity, String id, String missing) {
        Map<String, Object> row = id == null ? null : SqlRows.one(entity, db.jdbc().sql("SELECT * FROM \""
                + MfgDb.table(entity) + "\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, MfgDb.org()));
        if (row == null) {
            throw ApiException.notFound(missing);
        }
        return row;
    }

    /** Active reason by code; 400 when unknown. */
    Map<String, Object> reason(String code) {
        List<Map<String, Object>> rows = db.list(ShopFloorDowntimeReason.class, " AND code = ? AND \"isActive\" = true",
                code);
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Unknown downtime reason " + code + ".");
        }
        return rows.getFirst();
    }

    Optional<Map<String, Object>> reasonIfKnown(String code) {
        if (code == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = db.list(ShopFloorDowntimeReason.class, " AND code = ? AND \"isActive\" = true",
                code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    Map<String, Object> profile(String workCenterId) {
        List<Map<String, Object>> rows = db.list(ShopFloorWorkCenterProfile.class, " AND \"workCenterId\" = ?",
                workCenterId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The shift occurrence {@code at} falls in, if shifts are defined and one covers it. */
    Optional<Window> window(Instant at) {
        return ShiftClock.resolve(activeShifts(), at, zone());
    }

    /** The existing shift report of the work centre for {@code at} (no create), or null. */
    Map<String, Object> findReport(String workCenterId, Instant at) {
        Optional<Window> w = window(at);
        if (w.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = db.list(ShopFloorShiftReport.class, " AND \"workCenterId\" = ? AND"
                + " \"shiftId\" = ? AND \"shiftDate\" = ?", workCenterId, w.get().shiftId(),
                java.sql.Date.valueOf(w.get().shiftDate()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Find or create the shift report of the work centre for {@code at}; null when no shift covers it. */
    Map<String, Object> ensureReport(String workCenterId, Instant at) {
        Optional<Window> w = window(at);
        return w.isEmpty() ? null : ensureReport(workCenterId, w.get());
    }

    Map<String, Object> ensureReport(String workCenterId, Window w) {
        String sql = " AND \"workCenterId\" = ? AND \"shiftId\" = ? AND \"shiftDate\" = ?";
        java.sql.Date day = java.sql.Date.valueOf(w.shiftDate());
        List<Map<String, Object>> rows = db.list(ShopFloorShiftReport.class, sql, workCenterId, w.shiftId(), day);
        if (!rows.isEmpty()) {
            return rows.getFirst();
        }
        String code = seq.next("shop_floor_shift_report", "SFR-", 5);
        db.jdbc().sql("INSERT INTO \"ShopFloorShiftReport\" (id, \"organizationId\", code, \"workCenterId\", \"shiftId\","
                + " \"shiftDate\", \"startsAt\", \"endsAt\", status, \"createdBy\", \"createdAt\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, ?, ?, ?, ?, 'open', ?, " + MfgDb.NOW + ", " + MfgDb.NOW + ")"
                + " ON CONFLICT (\"organizationId\", \"workCenterId\", \"shiftId\", \"shiftDate\") DO NOTHING")
                .params(java.util.UUID.randomUUID().toString(), MfgDb.org(), code, workCenterId, w.shiftId(), day,
                        MfgDb.param(w.startsAt()), MfgDb.param(w.endsAt()), TenantContext.userId()).update();
        return db.list(ShopFloorShiftReport.class, sql, workCenterId, w.shiftId(), day).getFirst();
    }

    /** Records may only be added to / changed in an open shift report. */
    static void assertEditable(Map<String, Object> report) {
        if (report == null) {
            return;
        }
        String status = str(report, "status");
        if ("approved".equals(status)) {
            throw ApiException.badRequest("Shift report " + report.get("code") + " is approved and locked.");
        }
        if ("submitted".equals(status)) {
            throw ApiException.badRequest("Shift report " + report.get("code") + " is submitted; reject it to reopen"
                    + " before changing its records.");
        }
    }

    /** {@code {id, name}} of a user of the organization, or null. */
    Map<String, Object> user(String userId) {
        if (userId == null) {
            return null;
        }
        return SqlRows.one(db.jdbc().sql("SELECT id, trim(concat(\"firstName\", ' ', coalesce(\"lastName\", ''))) AS name,"
                + " email FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(userId, MfgDb.org()));
    }

    static LocalDate date(String iso) {
        return LocalDate.parse(iso.substring(0, 10));
    }
}
