package com.inventoryerp.quarry.application;

import com.inventoryerp.fleet.application.WeighbridgeTicketService.CompletedTicket;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quarry.application.QuarryCommands.HaulageInput;
import com.inventoryerp.quarry.application.QuarryCommands.HaulageQuery;
import com.inventoryerp.quarry.domain.QuarryHaulage;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Haulage from the face to the ROM stockpile / crusher (2.2): internal trips counted as loads × truck capacity or
 * weighed on the weighbridge (completed ticket, read-only), optionally referencing a fleet trip. A haulage record has
 * no stock effect of its own: the extraction log that pulls it in (quantity line {@code haulage}) receives the stone.
 * A record on an active extraction log can no longer be edited or deleted.
 */
@Service
public class QuarryHaulageService {

    private final QuarryDb db;
    private final QuarrySiteService sites;
    private final QuarryRefs refs;
    private final SequenceService sequence;
    private final AuditService audit;

    public QuarryHaulageService(QuarryDb db, QuarrySiteService sites, QuarryRefs refs, SequenceService sequence,
            AuditService audit) {
        this.db = db;
        this.sites = sites;
        this.refs = refs;
        this.sequence = sequence;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT h.*, s.code AS "siteCode", z.code AS "zoneCode", z.name AS "zoneName", k.code AS "stockpileCode",
                   v.code AS "truckCode", v."registrationPlate" AS "truckPlate", m.name AS "materialName",
                   e."firstName" AS "driverFirstName", e."lastName" AS "driverLastName",
                   wt."ticketNumber" AS "ticketNumber",
                   (SELECT x."logId" FROM "QuarryExtractionLoad" x WHERE x."haulageId" = h.id AND x.active LIMIT 1)
                       AS "extractionLogId",
                   (SELECT g.code FROM "QuarryExtractionLoad" x JOIN "QuarryExtractionLog" g ON g.id = x."logId"
                       WHERE x."haulageId" = h.id AND x.active LIMIT 1) AS "extractionLogCode"
            """;

    private static final String FROM = """
             FROM "QuarryHaulage" h
            JOIN "QuarrySite" s ON s.id = h."siteId"
            LEFT JOIN "QuarryZone" z ON z.id = h."fromZoneId"
            LEFT JOIN "QuarryStockpile" k ON k.id = h."stockpileId"
            LEFT JOIN "QuarryMaterial" m ON m.id = h."materialId"
            LEFT JOIN "FleetVehicle" v ON v.id = h."truckVehicleId" AND v."organizationId" = h."organizationId"
            LEFT JOIN "HrEmployee" e ON e.id = h."driverEmployeeId" AND e."organizationId" = h."organizationId"
            LEFT JOIN "WeighbridgeTicket" wt ON wt.id = h."weighbridgeTicketId" AND wt."organizationId" = h."organizationId"
            """;

    /** Paged haulage records with filters plus the tonnes / loads totals of the filtered set. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(HaulageQuery q) {
        int p = QuarryDb.page(q.page());
        int size = QuarryDb.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE h.\"organizationId\" = ? AND h.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org()));
        if (!QuarryDb.blank(q.siteId())) {
            where.append(" AND h.\"siteId\" = ?");
            params.add(q.siteId());
        }
        if (!QuarryDb.blank(q.truckVehicleId())) {
            where.append(" AND h.\"truckVehicleId\" = ?");
            params.add(q.truckVehicleId());
        }
        if (!QuarryDb.blank(q.destination())) {
            where.append(" AND h.destination = ?");
            params.add(q.destination());
        }
        if (q.dateFrom() != null) {
            where.append(" AND h.\"hauledOn\" >= ?");
            params.add(java.sql.Date.valueOf(q.dateFrom()));
        }
        if (q.dateTo() != null) {
            where.append(" AND h.\"hauledOn\" <= ?");
            params.add(java.sql.Date.valueOf(q.dateTo()));
        }
        if (Boolean.TRUE.equals(q.unassigned())) {
            where.append(" AND NOT EXISTS (SELECT 1 FROM \"QuarryExtractionLoad\" x WHERE x.\"haulageId\" = h.id AND"
                    + " x.active)");
        }
        if (!QuarryDb.blank(q.search())) {
            where.append(" AND (h.code ILIKE ? OR v.code ILIKE ? OR v.\"registrationPlate\" ILIKE ?)");
            String like = QuarryDb.like(q.search());
            params.add(like);
            params.add(like);
            params.add(like);
        }
        Map<String, Object> totals = SqlRows.one(db.jdbc().sql("SELECT count(*) AS total, COALESCE(SUM(h.tonnes), 0) AS"
                + " tonnes, COALESCE(SUM(h.\"loadCount\"), 0) AS loads" + FROM + where).params(params));
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(SELECT + FROM + where
                + " ORDER BY h.\"hauledOn\" DESC, h.code DESC LIMIT ? OFFSET ?").params(pp));
        PageResult<Map<String, Object>> pr = PageResult.of(rows, p, size, ((Number) totals.get("total")).longValue());
        return Obj.of("data", pr.data(), "meta", pr.meta(), "summary", Obj.of("tonnes", totals.get("tonnes"), "loads",
                totals.get("loads")));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        require(id, false);
        return SqlRows.one(db.jdbc().sql(SELECT + FROM + " WHERE h.id = ? AND h.\"organizationId\" = ?").params(id,
                QuarryDb.org()));
    }

    Map<String, Object> require(String id, boolean lock) {
        return db.require(QuarryHaulage.class, id, lock, "Haulage record not found");
    }

    @Transactional
    public Map<String, Object> create(HaulageInput in) {
        Map<String, Object> d = data(in, null);
        d.put("code", sequence.next("quarry_haulage", "QH-", 6));
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarryHaulage.class, d);
        audit.recordInTx(AuditInput.of("QuarryHaulage", (String) row.get("id"), "create", null, row));
        return get((String) row.get("id"));
    }

    @Transactional
    public Map<String, Object> update(String id, HaulageInput in) {
        Map<String, Object> before = require(id, true);
        assertFree(before, "edited");
        Map<String, Object> after = db.update(QuarryHaulage.class, id, data(in, id));
        audit.recordInTx(AuditInput.of("QuarryHaulage", id, "update", before, after));
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        Map<String, Object> before = require(id, true);
        assertFree(before, "deleted");
        db.update(QuarryHaulage.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("QuarryHaulage", id, "delete", before, null));
    }

    /** The extraction log code holding this haulage record on an active line, or null. */
    String claimedBy(String haulageId) {
        return db.jdbc().sql("SELECT g.code FROM \"QuarryExtractionLoad\" x JOIN \"QuarryExtractionLog\" g ON g.id ="
                + " x.\"logId\" WHERE x.\"organizationId\" = ? AND x.\"haulageId\" = ? AND x.active LIMIT 1")
                .params(QuarryDb.org(), haulageId).query(String.class).optional().orElse(null);
    }

    private void assertFree(Map<String, Object> h, String action) {
        String log = claimedBy((String) h.get("id"));
        if (log != null) {
            throw ApiException.badRequest("Haulage " + h.get("code") + " is on extraction log " + log
                    + " and can no longer be " + action);
        }
    }

    private Map<String, Object> data(HaulageInput in, String selfId) {
        Map<String, Object> site = sites.requireSite(in.siteId(), false);
        String siteId = (String) site.get("id");
        String zone = QuarryDb.text(in.fromZoneId());
        if (zone != null) {
            sites.zoneOfSite(zone, siteId);
        }
        String destination = in.destination() == null ? "rom_stockpile" : in.destination();
        String stockpile = QuarryDb.text(in.stockpileId());
        if (stockpile != null) {
            Map<String, Object> k = sites.requireStockpile(stockpile, false);
            if (!Objects.equals(k.get("siteId"), siteId)) {
                throw ApiException.badRequest("Stockpile " + k.get("code") + " belongs to another site");
            }
        }
        String material = QuarryDb.text(in.materialId());
        db.requireRef("QuarryMaterial", material, true, "Material not found");
        String driver = QuarryDb.text(in.driverEmployeeId());
        refs.employee(driver);
        String trip = QuarryDb.text(in.fleetTripId());
        refs.trip(trip);
        String truck = QuarryDb.text(in.truckVehicleId());
        Map<String, Object> d = new LinkedHashMap<>();
        BigDecimal tonnes;
        Integer loads = null;
        BigDecimal capacity = null;
        String ticketId = null;
        if ("weighbridge".equals(in.method())) {
            ticketId = QuarryDb.text(in.weighbridgeTicketId());
            if (ticketId == null) {
                throw ApiException.badRequest("Choose the weighbridge ticket");
            }
            CompletedTicket t = refs.ticket(ticketId);
            assertTicketFree(ticketId, t.ticketNumber(), selfId);
            tonnes = QuarryCostCalculator.kgToTonnes(t.netKg());
            if (truck == null) {
                truck = t.vehicleId();
            }
            if (trip == null) {
                trip = t.tripId();
            }
            loads = 1;
        } else {
            if (in.loadCount() == null) {
                throw ApiException.badRequest("Enter the number of loads");
            }
            loads = in.loadCount();
            capacity = refs.capacity(truck, in.capacityT());
            tonnes = QuarryCostCalculator.loadTonnes(loads, capacity);
        }
        if (truck != null && "loads".equals(in.method()) && in.capacityT() != null) {
            refs.vehicle(truck);
        }
        d.put("siteId", siteId);
        d.put("fromZoneId", zone);
        d.put("destination", destination);
        d.put("stockpileId", stockpile);
        d.put("destinationNote", QuarryDb.text(in.destinationNote()));
        d.put("materialId", material);
        d.put("hauledOn", in.hauledOn());
        d.put("shift", in.shift());
        d.put("truckVehicleId", truck);
        d.put("driverEmployeeId", driver);
        d.put("method", in.method());
        d.put("loadCount", loads);
        d.put("capacityT", capacity);
        d.put("weighbridgeTicketId", ticketId);
        d.put("fleetTripId", trip);
        d.put("tonnes", tonnes);
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    /** A weighbridge ticket evidences one haulage record or one extraction quantity line, never two. */
    void assertTicketFree(String ticketId, String ticketNumber, String exceptHaulageId) {
        String haul = db.jdbc().sql("SELECT code FROM \"QuarryHaulage\" WHERE \"organizationId\" = ? AND"
                + " \"weighbridgeTicketId\" = ? AND \"deletedAt\" IS NULL AND id IS DISTINCT FROM ? LIMIT 1")
                .params(QuarryDb.org(), ticketId, exceptHaulageId).query(String.class).optional().orElse(null);
        if (haul != null) {
            throw ApiException.badRequest("Weighbridge ticket " + ticketNumber + " is already recorded on haulage "
                    + haul);
        }
        String log = db.jdbc().sql("SELECT g.code FROM \"QuarryExtractionLoad\" x JOIN \"QuarryExtractionLog\" g ON"
                + " g.id = x.\"logId\" WHERE x.\"organizationId\" = ? AND x.\"weighbridgeTicketId\" = ? AND x.active"
                + " LIMIT 1").params(QuarryDb.org(), ticketId).query(String.class).optional().orElse(null);
        if (log != null) {
            throw ApiException.badRequest("Weighbridge ticket " + ticketNumber + " is already counted on extraction log "
                    + log);
        }
    }
}
