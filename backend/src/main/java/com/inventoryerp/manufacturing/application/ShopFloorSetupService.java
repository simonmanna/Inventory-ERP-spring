package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.application.ShiftClock.ShiftDef;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ProfileInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ReasonInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ReasonPatch;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ShiftInput;
import com.inventoryerp.manufacturing.application.ShopFloorCommands.ShiftPatch;
import com.inventoryerp.manufacturing.domain.ShopFloorDowntimeReason;
import com.inventoryerp.manufacturing.domain.ShopFloorShift;
import com.inventoryerp.manufacturing.domain.ShopFloorWorkCenterProfile;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shop-floor master data (ADR-S015): production shifts (no two active shifts may overlap), the downtime reason
 * taxonomy (category + planned flag; codes are immutable because events keep them) with a standard set, and the
 * per-work-centre profile (ideal rate for OEE, machine code for machine data, meters).
 */
@Service
public class ShopFloorSetupService {

    /** Standard reason taxonomy: code, name, category, planned. */
    static final List<String[]> DEFAULT_REASONS = List.of(
            new String[] {"BREAKDOWN", "Mechanical breakdown", "breakdown", "false"},
            new String[] {"ELECTRICAL", "Electrical fault", "breakdown", "false"},
            new String[] {"BLOCKAGE", "Blockage / jam", "breakdown", "false"},
            new String[] {"SETUP", "Set-up / changeover", "setup", "false"},
            new String[] {"NO_MATERIAL", "Waiting for material", "material", "false"},
            new String[] {"QUALITY_HOLD", "Quality hold", "quality", "false"},
            new String[] {"NO_OPERATOR", "No operator", "operator", "false"},
            new String[] {"POWER", "Power / utility outage", "utility", "false"},
            new String[] {"MINOR_STOP", "Minor stop", "minor_stop", "false"},
            new String[] {"BREAK", "Scheduled break", "planned", "true"},
            new String[] {"PLANNED_MAINT", "Planned maintenance", "planned", "true"},
            new String[] {"CLEANING", "Cleaning", "planned", "true"},
            new String[] {"NO_ORDERS", "No orders", "planned", "true"});

    /** Standard three-shift pattern: code, name, start, end, break minutes. */
    static final List<Object[]> DEFAULT_SHIFTS = List.of(
            new Object[] {"DAY", "Day shift", 360, 840, 30},
            new Object[] {"EVENING", "Evening shift", 840, 1320, 30},
            new Object[] {"NIGHT", "Night shift", 1320, 360, 30});

    private final ShopFloorSupport sf;
    private final MfgDb db;

    public ShopFloorSetupService(ShopFloorSupport sf) {
        this.sf = sf;
        this.db = sf.db();
    }

    // ================================================================== shifts

    @Transactional(readOnly = true)
    public List<Map<String, Object>> shifts() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : sf.shiftRows(false)) {
            s.put("lengthMinutes", ShiftClock.lengthMinutes(ShopFloorSupport.def(s)));
            out.add(s);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createShift(ShiftInput in) {
        String code = in.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (!db.list(ShopFloorShift.class, " AND code = ?", code).isEmpty()) {
            throw ApiException.conflict("Shift " + code + " already exists.");
        }
        Map<String, Object> row = db.insert(ShopFloorShift.class, Obj.of("code", code, "name", in.name().trim(),
                "startMinute", in.startMinute(), "endMinute", in.endMinute(), "breakMinutes",
                in.breakMinutes() == null ? 0 : in.breakMinutes(), "isActive", in.isActive() == null || in.isActive(),
                "sortOrder", in.sortOrder() == null ? 0 : in.sortOrder()));
        validateShifts(row);
        return withLength(row);
    }

    @Transactional
    public Map<String, Object> updateShift(String id, ShiftPatch in) {
        sf.requireShift(id);
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "name", in.name() == null ? null : in.name().trim());
        put(data, "startMinute", in.startMinute());
        put(data, "endMinute", in.endMinute());
        put(data, "breakMinutes", in.breakMinutes());
        put(data, "isActive", in.isActive());
        put(data, "sortOrder", in.sortOrder());
        if (data.isEmpty()) {
            return withLength(sf.requireShift(id));
        }
        Map<String, Object> row = db.update(ShopFloorShift.class, id, data);
        validateShifts(row);
        return withLength(row);
    }

    /** Create the standard Day / Evening / Night pattern when the organization has no active shift yet. */
    @Transactional
    public List<Map<String, Object>> defaultShifts() {
        if (sf.shiftRows(true).isEmpty()) {
            int order = 0;
            for (Object[] d : DEFAULT_SHIFTS) {
                if (db.list(ShopFloorShift.class, " AND code = ?", d[0]).isEmpty()) {
                    db.insert(ShopFloorShift.class, Obj.of("code", d[0], "name", d[1], "startMinute", d[2],
                            "endMinute", d[3], "breakMinutes", d[4], "isActive", true, "sortOrder", order));
                }
                order += 10;
            }
        }
        return shifts();
    }

    /** Refuse a shift whose length does not cover its break, or that overlaps another active shift. */
    private void validateShifts(Map<String, Object> changed) {
        ShiftDef d = ShopFloorSupport.def(changed);
        if (d.breakMinutes() >= ShiftClock.lengthMinutes(d)) {
            throw ApiException.badRequest("The break of shift " + d.code() + " must be shorter than the shift.");
        }
        String overlap = ShiftClock.overlap(sf.activeShifts());
        if (overlap != null) {
            throw ApiException.badRequest("Shifts " + overlap + " overlap; active shifts must not overlap.");
        }
    }

    private static Map<String, Object> withLength(Map<String, Object> s) {
        s.put("lengthMinutes", ShiftClock.lengthMinutes(ShopFloorSupport.def(s)));
        return s;
    }

    // ================================================================== downtime reasons

    @Transactional(readOnly = true)
    public List<Map<String, Object>> reasons(Boolean includeInactive) {
        return db.list(ShopFloorDowntimeReason.class, (Boolean.TRUE.equals(includeInactive) ? ""
                : " AND \"isActive\" = true") + " ORDER BY \"sortOrder\", category, code");
    }

    @Transactional
    public Map<String, Object> createReason(ReasonInput in) {
        String code = in.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (!db.list(ShopFloorDowntimeReason.class, " AND code = ?", code).isEmpty()) {
            throw ApiException.conflict("Downtime reason " + code + " already exists.");
        }
        String category = in.category() == null ? "other" : in.category();
        boolean planned = in.isPlanned() != null ? in.isPlanned() : "planned".equals(category);
        return db.insert(ShopFloorDowntimeReason.class, Obj.of("code", code, "name", in.name().trim(), "category",
                category, "isPlanned", planned, "isActive", in.isActive() == null || in.isActive(), "sortOrder",
                in.sortOrder() == null ? 0 : in.sortOrder()));
    }

    @Transactional
    public Map<String, Object> updateReason(String id, ReasonPatch in) {
        Map<String, Object> row = db.byId(ShopFloorDowntimeReason.class, id);
        if (row == null) {
            throw ApiException.notFound("Downtime reason not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "name", in.name() == null ? null : in.name().trim());
        put(data, "category", in.category());
        put(data, "isPlanned", in.isPlanned());
        put(data, "isActive", in.isActive());
        put(data, "sortOrder", in.sortOrder());
        return data.isEmpty() ? row : db.update(ShopFloorDowntimeReason.class, id, data);
    }

    /** Add the standard reason taxonomy (missing codes only; existing reasons are left alone). */
    @Transactional
    public List<Map<String, Object>> defaultReasons() {
        int order = 0;
        for (String[] r : DEFAULT_REASONS) {
            db.jdbc().sql("INSERT INTO \"ShopFloorDowntimeReason\" (id, \"organizationId\", code, name, category,"
                    + " \"isPlanned\", \"isActive\", \"sortOrder\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?,"
                    + " ?, true, ?, " + MfgDb.NOW + ", " + MfgDb.NOW + ") ON CONFLICT (\"organizationId\", code) DO NOTHING")
                    .params(java.util.UUID.randomUUID().toString(), MfgDb.org(), r[0], r[1], r[2],
                            Boolean.parseBoolean(r[3]), order).update();
            order += 10;
        }
        return reasons(false);
    }

    // ================================================================== work-centre profiles

    /** Every active work centre with its profile (null fields when none is saved yet). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> profiles() {
        return SqlRows.list(db.jdbc().sql("SELECT w.id AS \"workCenterId\", w.code AS \"workCenterCode\", w.name AS"
                + " \"workCenterName\", p.id, p.\"idealRatePerHour\", p.\"rateUom\", p.\"machineCode\","
                + " coalesce(p.\"meterCodes\", '[]'::jsonb) AS \"meterCodes\", p.\"updatedAt\" FROM \"WorkCenter\" w"
                + " LEFT JOIN \"ShopFloorWorkCenterProfile\" p ON p.\"workCenterId\" = w.id AND p.\"organizationId\""
                + " = w.\"organizationId\" WHERE w.\"organizationId\" = ? AND w.\"deletedAt\" IS NULL AND w.\"isActive\""
                + " = true ORDER BY w.code").param(MfgDb.org()));
    }

    @Transactional
    public Map<String, Object> saveProfile(String workCenterId, ProfileInput in) {
        sf.requireWorkCenter(workCenterId);
        String machine = in.machineCode() == null || in.machineCode().isBlank() ? null : in.machineCode().trim();
        if (machine != null) {
            List<Map<String, Object>> clash = db.list(ShopFloorWorkCenterProfile.class, " AND \"machineCode\" = ?"
                    + " AND \"workCenterId\" <> ?", machine, workCenterId);
            if (!clash.isEmpty()) {
                throw ApiException.conflict("Machine code " + machine + " is already used by another work centre.");
            }
        }
        List<String> meters = new ArrayList<>();
        if (in.meterCodes() != null) {
            for (String m : in.meterCodes()) {
                String c = m.trim().toLowerCase(java.util.Locale.ROOT);
                if (!c.isEmpty() && !meters.contains(c)) {
                    meters.add(c);
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("idealRatePerHour", in.idealRatePerHour());
        data.put("rateUom", in.rateUom() == null || in.rateUom().isBlank() ? null : in.rateUom().trim());
        data.put("machineCode", machine);
        data.put("meterCodes", new MfgDb.Json(meters));
        Map<String, Object> existing = sf.profile(workCenterId);
        if (existing == null) {
            data.put("workCenterId", workCenterId);
            return db.insert(ShopFloorWorkCenterProfile.class, data);
        }
        return db.update(ShopFloorWorkCenterProfile.class, str(existing, "id"), data);
    }

    private static void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
