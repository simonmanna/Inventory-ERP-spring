package com.inventoryerp.quarry.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quarry.application.QuarryCommands.BlastInput;
import com.inventoryerp.quarry.application.QuarryCommands.BlastQuery;
import com.inventoryerp.quarry.application.QuarryCommands.FireInput;
import com.inventoryerp.quarry.domain.QuarryBlast;
import com.inventoryerp.quarry.domain.QuarryExplosiveIssue;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drilling &amp; blasting records (2.2): pattern (holes, depth, burden × spacing, sub-drill, stemming), drilling (rig,
 * hours, cost), the estimated tonnes broken, compliance (shotfirer and licence, blast permit, exclusion zone, warning
 * and all-clear, misfires, vibration / airblast) and the cost pool — explosives issued net of returns
 * ({@link QuarryExplosiveService}) + drilling + other — that extraction logs absorb per tonne.
 *
 * <p>planned → fired (compliance fields required, no explosive request still open) → closed (every issue reconciled;
 * no more absorption). A planned blast without issued explosives can be cancelled (open requests are cancelled).
 */
@Service
public class QuarryBlastService {

    static final String AUDIT = "QuarryBlast";

    private final QuarryDb db;
    private final QuarrySiteService sites;
    private final QuarryRefs refs;
    private final SequenceService sequence;
    private final AuditService audit;

    public QuarryBlastService(QuarryDb db, QuarrySiteService sites, QuarryRefs refs, SequenceService sequence,
            AuditService audit) {
        this.db = db;
        this.sites = sites;
        this.refs = refs;
        this.sequence = sequence;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT b.*, s.code AS "siteCode", s.name AS "siteName", z.code AS "zoneCode", z.name AS "zoneName",
                   m.name AS "materialName",
                   (b."explosivesCost" + b."drillingCost" + b."otherCost") AS "totalCost"
            FROM "QuarryBlast" b
            JOIN "QuarrySite" s ON s.id = b."siteId"
            LEFT JOIN "QuarryZone" z ON z.id = b."zoneId"
            LEFT JOIN "QuarryMaterial" m ON m.id = b."materialId"
            """;

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(BlastQuery q) {
        int p = QuarryDb.page(q.page());
        int size = QuarryDb.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE b.\"organizationId\" = ? AND b.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org()));
        if (!QuarryDb.blank(q.siteId())) {
            where.append(" AND b.\"siteId\" = ?");
            params.add(q.siteId());
        }
        if (!QuarryDb.blank(q.status())) {
            where.append(" AND b.status = ?");
            params.add(q.status());
        }
        if (q.dateFrom() != null) {
            where.append(" AND COALESCE(b.\"firedAt\", b.\"plannedAt\", b.\"createdAt\") >= ?");
            params.add(SqlRows.ts(q.dateFrom().atStartOfDay(BusinessZone.get()).toInstant()));
        }
        if (q.dateTo() != null) {
            where.append(" AND COALESCE(b.\"firedAt\", b.\"plannedAt\", b.\"createdAt\") < ?");
            params.add(SqlRows.ts(q.dateTo().plusDays(1).atStartOfDay(BusinessZone.get()).toInstant()));
        }
        if (!QuarryDb.blank(q.search())) {
            where.append(" AND (b.code ILIKE ? OR b.\"blastPermitNo\" ILIKE ? OR z.code ILIKE ?)");
            String like = QuarryDb.like(q.search());
            params.add(like);
            params.add(like);
            params.add(like);
        }
        String from = SELECT.substring(SELECT.indexOf("FROM \"QuarryBlast\""));
        long total = db.jdbc().sql("SELECT count(*) " + from + where).params(params).query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY COALESCE(b.\"firedAt\","
                + " b.\"plannedAt\", b.\"createdAt\") DESC, b.code DESC LIMIT ? OFFSET ?").params(pp)), p, size, total);
    }

    /** Detail: the blast, cost pool (rate per tonne, absorbed, remaining), explosive issues, absorbing logs. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        require(id, false);
        String org = QuarryDb.org();
        Map<String, Object> out = new LinkedHashMap<>(SqlRows.one(db.jdbc().sql(SELECT
                + " WHERE b.id = ? AND b.\"organizationId\" = ?").params(id, org)));
        BigDecimal pool = SqlRows.dec(out, "totalCost");
        BigDecimal absorbed = SqlRows.dec(out, "absorbedCost");
        BigDecimal tonnes = (BigDecimal) out.get("estimatedTonnes");
        out.put("costPerTonne", tonnes == null || tonnes.signum() == 0 ? null
                : pool.divide(tonnes, 4, RoundingMode.HALF_UP));
        out.put("powderFactorKgPerT", tonnes == null || tonnes.signum() == 0 ? null
                : SqlRows.dec(out, "explosivesKg").divide(tonnes, 4, RoundingMode.HALF_UP));
        out.put("remainingCost", pool.subtract(absorbed).max(BigDecimal.ZERO));
        out.put("drillVehicle", db.ref("SELECT id, code, \"registrationPlate\" FROM \"FleetVehicle\" WHERE id = ? AND"
                + " \"organizationId\" = ?", out.get("drillVehicleId")));
        Map<String, Object> e = db.ref("SELECT id, \"employeeCode\", \"firstName\", \"lastName\" FROM \"HrEmployee\""
                + " WHERE id = ? AND \"organizationId\" = ?", out.get("shotfirerEmployeeId"));
        out.put("shotfirer", e == null ? null : Obj.of("id", e.get("id"), "employeeCode", e.get("employeeCode"),
                "name", (e.get("firstName") + " " + (e.get("lastName") == null ? "" : e.get("lastName"))).trim()));
        out.put("issues", SqlRows.list(db.jdbc().sql("SELECT i.id, i.code, i.status, i.\"issuedValue\","
                + " i.\"returnedValue\", i.\"requestedAt\", i.\"issuedAt\", g.code AS \"magazineCode\" FROM"
                + " \"QuarryExplosiveIssue\" i JOIN \"QuarryMagazine\" g ON g.id = i.\"magazineId\" WHERE"
                + " i.\"organizationId\" = ? AND i.\"blastId\" = ? ORDER BY i.\"createdAt\"").params(org, id)));
        out.put("extractionLogs", SqlRows.list(db.jdbc().sql("SELECT id, code, \"extractedOn\", status, \"netTonnes\","
                + " \"blastingCost\" FROM \"QuarryExtractionLog\" WHERE \"organizationId\" = ? AND \"blastId\" = ? AND"
                + " \"deletedAt\" IS NULL ORDER BY \"extractedOn\", code").params(org, id)));
        return out;
    }

    Map<String, Object> require(String id, boolean lock) {
        return db.require(QuarryBlast.class, id, lock, "Blast not found");
    }

    @Transactional
    public Map<String, Object> create(BlastInput in) {
        Map<String, Object> d = data(in);
        d.put("code", sequence.next("quarry_blast", "BL-", 5));
        d.put("status", "planned");
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarryBlast.class, d);
        audit.recordInTx(AuditInput.of(AUDIT, (String) row.get("id"), "create", null, row));
        return get((String) row.get("id"));
    }

    @Transactional
    public Map<String, Object> update(String id, BlastInput in) {
        Map<String, Object> before = require(id, true);
        assertOpen(before, "edited");
        if (!before.get("siteId").equals(in.siteId())) {
            throw ApiException.badRequest("A blast cannot move to another site");
        }
        Map<String, Object> after = db.update(QuarryBlast.class, id, data(in));
        audit.recordInTx(AuditInput.of(AUDIT, id, "update", before, after));
        return get(id);
    }

    /** Record the firing; the compliance record must be complete and no explosive request may still be open. */
    @Transactional
    public Map<String, Object> fire(String id, FireInput in) {
        Map<String, Object> b = require(id, true);
        if (!"planned".equals(b.get("status"))) {
            throw ApiException.badRequest("Blast " + b.get("code") + " is " + b.get("status") + "; only a planned blast"
                    + " can be fired");
        }
        List<String> missing = new ArrayList<>();
        if (b.get("shotfirerEmployeeId") == null) {
            missing.add("shotfirer");
        }
        if (b.get("shotfirerLicenceNo") == null) {
            missing.add("shotfirer licence number");
        }
        if (b.get("blastPermitNo") == null) {
            missing.add("blast permit number");
        }
        if (b.get("exclusionZoneM") == null) {
            missing.add("exclusion zone");
        }
        if (b.get("estimatedTonnes") == null) {
            missing.add("estimated tonnes (pattern or entered)");
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("Complete the blast record before firing: " + String.join(", ", missing));
        }
        Instant firedAt = in.firedAt() == null ? Instant.now() : in.firedAt();
        if (firedAt.isAfter(Instant.now().plusSeconds(300))) {
            throw ApiException.badRequest("The firing time cannot be in the future");
        }
        LocalDate day = LocalDate.ofInstant(firedAt, BusinessZone.get());
        if (b.get("permitExpiresOn") != null && day.isAfter((LocalDate) b.get("permitExpiresOn"))) {
            throw ApiException.badRequest("Blast permit " + b.get("blastPermitNo") + " expired on "
                    + b.get("permitExpiresOn"));
        }
        if (in.warningSignalAt() != null && in.warningSignalAt().isAfter(firedAt)) {
            throw ApiException.badRequest("The warning signal must come before the firing");
        }
        if (in.allClearAt() != null && in.allClearAt().isBefore(firedAt)) {
            throw ApiException.badRequest("The all-clear must come after the firing");
        }
        String open = openIssue(id, List.of("pending_approval", "approved"));
        if (open != null) {
            throw ApiException.badRequest("Explosive issue " + open + " is still open; issue or cancel it before firing");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("status", "fired");
        d.put("firedAt", firedAt);
        d.put("warningSignalAt", in.warningSignalAt());
        d.put("allClearAt", in.allClearAt());
        d.put("misfireCount", in.misfireCount() == null ? 0 : in.misfireCount());
        d.put("misfireNotes", QuarryDb.text(in.misfireNotes()));
        d.put("vibrationPpvMmS", in.vibrationPpvMmS());
        d.put("airblastDb", in.airblastDb());
        d.put("weather", QuarryDb.text(in.weather()));
        if (QuarryDb.text(in.notes()) != null) {
            d.put("notes", QuarryDb.text(in.notes()));
        }
        d.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(QuarryBlast.class, id, d);
        audit.recordInTx(AuditInput.of(AUDIT, id, "post", b, after));
        return get(id);
    }

    /** Close a fired blast: every explosive issue reconciled; its cost stops being absorbed. */
    @Transactional
    public Map<String, Object> close(String id) {
        Map<String, Object> b = require(id, true);
        if (!"fired".equals(b.get("status"))) {
            throw ApiException.badRequest("Only a fired blast can be closed (" + b.get("code") + " is "
                    + b.get("status") + ")");
        }
        String open = openIssue(id, List.of("pending_approval", "approved", "issued"));
        if (open != null) {
            throw ApiException.badRequest("Explosive issue " + open + " is not reconciled; record the unused"
                    + " explosives returned to the magazine first");
        }
        Map<String, Object> after = db.update(QuarryBlast.class, id, Obj.of("status", "closed", "closedAt",
                Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT, id, "update", b, after));
        return get(id);
    }

    /** Cancel a planned blast that has no explosives issued; open requests are cancelled with it. */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Map<String, Object> b = require(id, true);
        if (!"planned".equals(b.get("status"))) {
            throw ApiException.badRequest("Only a planned blast can be cancelled (" + b.get("code") + " is "
                    + b.get("status") + ")");
        }
        String issued = openIssue(id, List.of("issued", "reconciled"));
        if (issued != null) {
            throw ApiException.badRequest("Explosives were issued to blast " + b.get("code") + " (" + issued
                    + "); fire or reconcile them instead");
        }
        for (Map<String, Object> i : db.list(QuarryExplosiveIssue.class, " AND \"blastId\" = ? AND status IN"
                + " ('pending_approval', 'approved') FOR UPDATE", id)) {
            db.update(QuarryExplosiveIssue.class, (String) i.get("id"), Obj.of("status", "cancelled", "cancelReason",
                    "Blast cancelled: " + reason.trim()));
            audit.recordInTx(AuditInput.of(QuarryExplosiveService.AUDIT, (String) i.get("id"), "cancel", i, null));
        }
        Map<String, Object> after = db.update(QuarryBlast.class, id, Obj.of("status", "cancelled", "notes",
                appendNote((String) b.get("notes"), "Cancelled: " + reason.trim()), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT, id, "cancel", b, after));
        return get(id);
    }

    private String openIssue(String blastId, List<String> statuses) {
        return db.jdbc().sql("SELECT code FROM \"QuarryExplosiveIssue\" WHERE \"organizationId\" = :org AND \"blastId\" ="
                + " :id AND status IN (:st) ORDER BY \"createdAt\" LIMIT 1").param("org", QuarryDb.org())
                .param("id", blastId).param("st", statuses).query(String.class).optional().orElse(null);
    }

    static void assertOpen(Map<String, Object> b, String action) {
        if ("closed".equals(b.get("status")) || "cancelled".equals(b.get("status"))) {
            throw ApiException.badRequest("Blast " + b.get("code") + " is " + b.get("status") + " and can no longer be "
                    + action);
        }
    }

    private static String appendNote(String notes, String line) {
        return notes == null || notes.isBlank() ? line : notes + "\n" + line;
    }

    private Map<String, Object> data(BlastInput in) {
        Map<String, Object> site = sites.requireSite(in.siteId(), false);
        String siteId = (String) site.get("id");
        String zone = QuarryDb.text(in.zoneId());
        Map<String, Object> zoneRow = zone == null ? null : sites.zoneOfSite(zone, siteId);
        String material = QuarryDb.text(in.materialId());
        if (material == null && zoneRow != null) {
            material = (String) zoneRow.get("materialId");
        }
        Map<String, Object> materialRow = material == null ? null : sites.requireMaterial(material, false);
        String drill = QuarryDb.text(in.drillVehicleId());
        if (drill != null) {
            refs.plant(drill);
        }
        String shotfirer = QuarryDb.text(in.shotfirerEmployeeId());
        refs.employee(shotfirer);
        // Pattern geometry: drilled metres = holes × depth; volume = holes × burden × spacing × (depth − sub-drill).
        BigDecimal drilled = null;
        BigDecimal volume = null;
        if (in.holeCount() != null && in.averageDepthM() != null) {
            drilled = in.averageDepthM().multiply(BigDecimal.valueOf(in.holeCount())).setScale(2, RoundingMode.HALF_UP);
            if (in.burdenM() != null && in.spacingM() != null) {
                BigDecimal bench = in.averageDepthM().subtract(in.subDrillM() == null ? BigDecimal.ZERO : in.subDrillM());
                if (bench.signum() > 0) {
                    volume = in.burdenM().multiply(in.spacingM()).multiply(bench)
                            .multiply(BigDecimal.valueOf(in.holeCount())).setScale(3, RoundingMode.HALF_UP);
                }
            }
        }
        BigDecimal tonnes = in.estimatedTonnes();
        if (tonnes == null && volume != null && materialRow != null && materialRow.get("densityTPerM3") != null) {
            tonnes = volume.multiply(SqlRows.dec(materialRow, "densityTPerM3")).setScale(3, RoundingMode.HALF_UP);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("siteId", siteId);
        d.put("zoneId", zone);
        d.put("materialId", material);
        d.put("plannedAt", in.plannedAt());
        d.put("patternType", in.patternType());
        d.put("holeCount", in.holeCount());
        d.put("holeDiameterMm", in.holeDiameterMm());
        d.put("averageDepthM", in.averageDepthM());
        d.put("burdenM", in.burdenM());
        d.put("spacingM", in.spacingM());
        d.put("subDrillM", in.subDrillM());
        d.put("stemmingM", in.stemmingM());
        d.put("drilledMetres", drilled);
        d.put("drillVehicleId", drill);
        d.put("drillHours", in.drillHours());
        d.put("drillingCost", in.drillingCost() == null ? BigDecimal.ZERO : in.drillingCost());
        d.put("otherCost", in.otherCost() == null ? BigDecimal.ZERO : in.otherCost());
        d.put("estimatedVolumeM3", volume);
        d.put("estimatedTonnes", tonnes);
        d.put("shotfirerEmployeeId", shotfirer);
        d.put("shotfirerLicenceNo", QuarryDb.text(in.shotfirerLicenceNo()));
        d.put("blastPermitNo", QuarryDb.text(in.blastPermitNo()));
        d.put("permitExpiresOn", in.permitExpiresOn());
        d.put("exclusionZoneM", in.exclusionZoneM());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }
}
