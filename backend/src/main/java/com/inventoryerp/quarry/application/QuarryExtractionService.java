package com.inventoryerp.quarry.application;

import com.inventoryerp.fleet.application.WeighbridgeTicketService.CompletedTicket;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.LedgerSource;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quarry.application.QuarryCommands.ExtractionLogInput;
import com.inventoryerp.quarry.application.QuarryCommands.ExtractionLogQuery;
import com.inventoryerp.quarry.application.QuarryCommands.LoadInput;
import com.inventoryerp.quarry.application.QuarryCommands.LossInput;
import com.inventoryerp.quarry.application.QuarryCommands.LossLineInput;
import com.inventoryerp.quarry.application.QuarryCommands.ResourceInput;
import com.inventoryerp.quarry.domain.QuarryBlast;
import com.inventoryerp.quarry.domain.QuarryExtractionCost;
import com.inventoryerp.quarry.domain.QuarryExtractionLoad;
import com.inventoryerp.quarry.domain.QuarryExtractionLog;
import com.inventoryerp.quarry.domain.QuarryExtractionResource;
import com.inventoryerp.quarry.domain.QuarryHaulage;
import com.inventoryerp.quarry.domain.QuarryLoss;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily extraction logs (2.2, ADR-S012 §3–§4): a shift's extraction at a site / zone of one material — quantity lines
 * (loads × truck capacity, completed weighbridge tickets, haulage records or a survey figure), mobile plant with
 * operator, hours and fuel, labour, losses and the linked blast. Draft → <b>post</b> → <b>reverse</b>.
 *
 * <p>Posting (log, then blast, locked {@code FOR UPDATE}, so it happens exactly once; the route also carries an
 * Idempotency-Key and the GL entry a posting key): resolves the rates, builds the cost with
 * {@link QuarryCostCalculator}, receives the net tonnes into the ROM stockpile through {@link StockService} at the
 * cost per tonne, posts the capitalisation ({@link QuarryPostingService}), absorbs the blast's share and publishes
 * {@code quarry.extraction.posted}. Reversal takes the stone back out at the cost it came in with, reverses the entry
 * and releases the blast share, weighbridge tickets and haulage records.
 */
@Service
public class QuarryExtractionService {

    static final String AUDIT = "QuarryExtractionLog";
    static final String STOCK_SOURCE = "quarry_extraction";
    static final String STOCK_REVERSAL_SOURCE = "quarry_extraction_reversal";
    private static final Map<String, String> SORTS = Map.of(
            "extractedOn", "l.\"extractedOn\"",
            "code", "l.code",
            "netTonnes", "l.\"netTonnes\"",
            "totalCost", "l.\"totalCost\"",
            "site", "s.code");

    private final QuarryDb db;
    private final QuarrySiteService sites;
    private final QuarrySettingsService settings;
    private final QuarryHaulageService haulage;
    private final QuarryRefs refs;
    private final QuarryPostingService postings;
    private final StockService stock;
    private final StockReversalService stockReversal;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public QuarryExtractionService(QuarryDb db, QuarrySiteService sites, QuarrySettingsService settings,
            QuarryHaulageService haulage, QuarryRefs refs, QuarryPostingService postings, StockService stock,
            StockReversalService stockReversal, SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.sites = sites;
        this.settings = settings;
        this.haulage = haulage;
        this.refs = refs;
        this.postings = postings;
        this.stock = stock;
        this.stockReversal = stockReversal;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ reads

    private static final String LIST_SELECT = """
            SELECT l.*, s.code AS "siteCode", s.name AS "siteName", z.code AS "zoneCode", z.name AS "zoneName",
                   m.code AS "materialCode", m.name AS "materialName", k.code AS "stockpileCode", b.code AS "blastCode"
            """;
    private static final String LIST_FROM = """
             FROM "QuarryExtractionLog" l
             JOIN "QuarrySite" s ON s.id = l."siteId"
             JOIN "QuarryMaterial" m ON m.id = l."materialId"
             JOIN "QuarryStockpile" k ON k.id = l."stockpileId"
             LEFT JOIN "QuarryZone" z ON z.id = l."zoneId"
             LEFT JOIN "QuarryBlast" b ON b.id = l."blastId"
            """;

    /** Paged logs with filters, plus the posted tonnes / cost totals of the filtered set ({@code summary}). */
    @Transactional(readOnly = true)
    public Map<String, Object> list(ExtractionLogQuery q) {
        int page = QuarryDb.page(q.page());
        int size = QuarryDb.size(q.pageSize());
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org()));
        eq(where, params, "l.\"siteId\"", q.siteId());
        eq(where, params, "l.\"zoneId\"", q.zoneId());
        eq(where, params, "l.\"materialId\"", q.materialId());
        eq(where, params, "l.status", q.status());
        eq(where, params, "l.shift", q.shift());
        if (q.dateFrom() != null) {
            where.append(" AND l.\"extractedOn\" >= ?");
            params.add(java.sql.Date.valueOf(q.dateFrom()));
        }
        if (q.dateTo() != null) {
            where.append(" AND l.\"extractedOn\" <= ?");
            params.add(java.sql.Date.valueOf(q.dateTo()));
        }
        if (!QuarryDb.blank(q.search())) {
            where.append(" AND (l.code ILIKE ? OR s.code ILIKE ? OR s.name ILIKE ? OR z.code ILIKE ?)");
            String like = QuarryDb.like(q.search());
            for (int i = 0; i < 4; i++) {
                params.add(like);
            }
        }
        Map<String, Object> totals = SqlRows.one(db.jdbc().sql("SELECT count(*) AS total, COALESCE(SUM(l.\"netTonnes\")"
                + " FILTER (WHERE l.status = 'posted'), 0) AS \"netTonnes\", COALESCE(SUM(l.\"totalCost\") FILTER"
                + " (WHERE l.status = 'posted'), 0) AS \"totalCost\"" + LIST_FROM + where).params(params));
        String sort = SORTS.getOrDefault(q.sortBy() == null ? "extractedOn" : q.sortBy(), "l.\"extractedOn\"");
        String dir = "asc".equalsIgnoreCase(q.sortOrder()) ? "ASC" : "DESC";
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((page - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(LIST_SELECT + LIST_FROM + where + " ORDER BY "
                + sort + " " + dir + ", l.code DESC LIMIT ? OFFSET ?").params(pp));
        PageResult<Map<String, Object>> pr = PageResult.of(rows, page, size, ((Number) totals.get("total")).longValue());
        BigDecimal net = SqlRows.dec(totals, "netTonnes");
        BigDecimal cost = SqlRows.dec(totals, "totalCost");
        return Obj.of("data", pr.data(), "meta", pr.meta(), "summary", Obj.of("netTonnes", net, "totalCost", cost,
                "costPerTonne", net.signum() > 0 ? cost.divide(net, 4, java.math.RoundingMode.HALF_UP) : null));
    }

    /** Detail: header with references, quantity / resource / loss lines, cost build-up and GL entries. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        require(id, false);
        String org = QuarryDb.org();
        Map<String, Object> out = new LinkedHashMap<>(SqlRows.one(db.jdbc().sql(LIST_SELECT + LIST_FROM
                + " WHERE l.id = ? AND l.\"organizationId\" = ?").params(id, org)));
        out.put("supervisor", employeeRef((String) out.get("supervisorEmployeeId")));
        out.put("loads", SqlRows.list(db.jdbc().sql("""
                SELECT x.*, v.code AS "truckCode", v."registrationPlate" AS "truckPlate", wt."ticketNumber",
                       h.code AS "haulageCode"
                FROM "QuarryExtractionLoad" x
                LEFT JOIN "FleetVehicle" v ON v.id = x."truckVehicleId" AND v."organizationId" = x."organizationId"
                LEFT JOIN "WeighbridgeTicket" wt ON wt.id = x."weighbridgeTicketId" AND wt."organizationId" = x."organizationId"
                LEFT JOIN "QuarryHaulage" h ON h.id = x."haulageId"
                WHERE x."organizationId" = ? AND x."logId" = ? ORDER BY x."lineNumber\"""").params(org, id)));
        out.put("resources", SqlRows.list(db.jdbc().sql("""
                SELECT r.*, v.code AS "vehicleCode", v."registrationPlate" AS "vehiclePlate", c.name AS "vehicleClass",
                       e."employeeCode", trim(concat(e."firstName", ' ', e."lastName")) AS "employeeName"
                FROM "QuarryExtractionResource" r
                LEFT JOIN "FleetVehicle" v ON v.id = r."vehicleId" AND v."organizationId" = r."organizationId"
                LEFT JOIN "FleetVehicleCategory" c ON c.id = v."categoryId"
                LEFT JOIN "HrEmployee" e ON e.id = r."employeeId" AND e."organizationId" = r."organizationId"
                WHERE r."organizationId" = ? AND r."logId" = ? ORDER BY r."lineNumber\"""").params(org, id)));
        out.put("losses", SqlRows.list(QuarryLoss.class, db.jdbc().sql("SELECT * FROM \"QuarryLoss\" WHERE"
                + " \"organizationId\" = ? AND \"logId\" = ? AND \"deletedAt\" IS NULL ORDER BY \"createdAt\"")
                .params(org, id)));
        out.put("costs", SqlRows.list(QuarryExtractionCost.class, db.jdbc().sql("SELECT * FROM \"QuarryExtractionCost\""
                + " WHERE \"organizationId\" = ? AND \"logId\" = ? ORDER BY component, description").params(org, id)));
        out.put("journal", postings.journal(java.util.Arrays.asList((String) out.get("journalEntryId"),
                (String) out.get("reversalEntryId"))));
        return out;
    }

    Map<String, Object> require(String id, boolean lock) {
        return db.require(QuarryExtractionLog.class, id, lock, "Extraction log not found");
    }

    /** What posting would book now (rates, blast share, royalties, allocation) without posting. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String id) {
        Map<String, Object> log = require(id, false);
        Map<String, Object> s = SqlRows.one(db.jdbc().sql("SELECT * FROM \"QuarrySettings\" WHERE \"organizationId\" = ?")
                .params(QuarryDb.org()));
        Costing c = cost(log, s == null ? defaults() : s, false);
        return result(c.result());
    }

    private static Map<String, Object> defaults() {
        return Obj.of("allocationBasis", QuarryCostCalculator.NET, "fuelCostPerLitre", BigDecimal.ZERO,
                "labourRatePerHour", BigDecimal.ZERO, "equipmentRatePerHour", BigDecimal.ZERO);
    }

    static Map<String, Object> result(QuarryCostCalculator.Result r) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (QuarryCostCalculator.Line l : r.lines()) {
            lines.add(Obj.of("component", l.component(), "description", l.description(), "quantity", l.quantity(),
                    "rate", l.rate(), "amount", l.amount()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grossTonnes", r.grossTonnes());
        out.put("lossTonnes", r.lossTonnes());
        out.put("netTonnes", r.netTonnes());
        out.put("fuelCost", r.fuel());
        out.put("labourCost", r.labour());
        out.put("equipmentCost", r.equipment());
        out.put("blastingCost", r.blasting());
        out.put("royaltyCost", r.royalty());
        out.put("otherCost", r.other());
        out.put("totalCost", r.total());
        out.put("capitalisedCost", r.capitalised());
        out.put("lossCost", r.lossCost());
        out.put("unitCost", r.unitCost());
        out.put("lines", lines);
        return out;
    }

    // ------------------------------------------------------------------ writes

    /** Record a log (draft); {@code post: true} posts it in the same transaction (needs quarry_extraction:post). */
    @Transactional
    public Map<String, Object> create(ExtractionLogInput in) {
        boolean postNow = Boolean.TRUE.equals(in.post());
        if (postNow && !TenantContext.has(Permissions.QUARRY_EXTRACTION__POST)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.QUARRY_EXTRACTION__POST);
        }
        Map<String, Object> d = header(in);
        d.put("code", sequence.next("quarry_extraction", "QX-", 6));
        d.put("status", "draft");
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarryExtractionLog.class, d);
        String id = (String) row.get("id");
        writeLines(row, in);
        audit.recordInTx(AuditInput.of(AUDIT, id, "create", null, require(id, false)));
        if (postNow) {
            return doPost(require(id, true));
        }
        return get(id);
    }

    /** Replace a draft's header and lines. */
    @Transactional
    public Map<String, Object> update(String id, ExtractionLogInput in) {
        Map<String, Object> before = require(id, true);
        assertDraft(before, "edited");
        if (Boolean.TRUE.equals(in.post())) {
            throw ApiException.badRequest("Save the changes, then post the log");
        }
        clearLines(id);
        Map<String, Object> after = db.update(QuarryExtractionLog.class, id, header(in));
        writeLines(after, in);
        audit.recordInTx(AuditInput.of(AUDIT, id, "update", before, require(id, false)));
        return get(id);
    }

    /** Delete a draft (soft delete; its tickets and haulage records are released). */
    @Transactional
    public void delete(String id) {
        Map<String, Object> before = require(id, true);
        assertDraft(before, "deleted");
        clearLines(id);
        db.update(QuarryExtractionLog.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT, id, "delete", before, null));
    }

    @Transactional
    public Map<String, Object> post(String id) {
        Map<String, Object> log = require(id, true);
        assertDraft(log, "posted");
        return doPost(log);
    }

    private Map<String, Object> doPost(Map<String, Object> log) {
        String id = (String) log.get("id");
        Map<String, Object> site = sites.requireSite((String) log.get("siteId"), false);
        if (!"active".equals(site.get("status"))) {
            throw ApiException.badRequest("Site " + site.get("code") + " is " + site.get("status")
                    + "; extraction cannot be posted");
        }
        Map<String, Object> material = sites.requireMaterial((String) log.get("materialId"), false);
        Map<String, Object> stockpile = sites.requireStockpile((String) log.get("stockpileId"), false);
        if (!Boolean.TRUE.equals(stockpile.get("isActive"))) {
            throw ApiException.badRequest("Stockpile " + stockpile.get("code") + " is inactive");
        }
        Map<String, Object> product = sites.stockProduct((String) material.get("productId"));
        // Tickets may have been voided since the draft was saved.
        for (Map<String, Object> l : loadLines(id)) {
            if (l.get("weighbridgeTicketId") != null) {
                refs.ticket((String) l.get("weighbridgeTicketId"));
            }
        }
        Costing c = cost(log, settings.current(), true);
        QuarryCostCalculator.Result r = c.result();
        Instant at = postingInstant((LocalDate) log.get("extractedOn"));
        StockMove m = StockMove.of((String) product.get("id"), (String) stockpile.get("locationId"), r.netTonnes())
                .unitCost(r.unitCost()).source(STOCK_SOURCE, id).reference((String) log.get("code"))
                .notes("Extraction " + log.get("code") + " · " + site.get("code")).moveType("production_output");
        if (Boolean.TRUE.equals(product.get("batchTracking"))) {
            m.batch((String) log.get("code"));
        }
        m.responsibleById = TenantContext.userId();
        Received received = stock.receive(m);
        String entryId = postings.postExtraction(log, site, (String) product.get("id"), r, at);
        // cost build-up + resource costs
        String org = QuarryDb.org();
        db.jdbc().sql("DELETE FROM \"QuarryExtractionCost\" WHERE \"organizationId\" = ? AND \"logId\" = ?")
                .params(org, id).update();
        List<Map<String, Object>> resources = c.resources();
        for (QuarryCostCalculator.Line l : r.lines()) {
            String refType = null;
            String refId = l.refId();
            if (l.index() >= 0) {
                refType = "resource";
                refId = (String) resources.get(l.index()).get("id");
            } else if ("blasting".equals(l.component())) {
                refType = "blast";
            } else if ("royalty".equals(l.component())) {
                refType = "royalty_rate";
            }
            db.insert(QuarryExtractionCost.class, Obj.of("logId", id, "component", l.component(), "description",
                    l.description(), "quantity", l.quantity(), "rate", l.rate(), "amount", l.amount(), "refType", refType,
                    "refId", refId, "partnerId", l.partnerId()));
        }
        for (int i = 0; i < resources.size(); i++) {
            QuarryCostCalculator.ResourceCost rc = r.resourceCosts().get(i);
            QuarryCostCalculator.Resource in = c.inputs().get(i);
            db.update(QuarryExtractionResource.class, (String) resources.get(i).get("id"), Obj.of("appliedRate",
                    in.ratePerHour(), "appliedFuelRate", "equipment".equals(in.kind()) ? in.fuelCostPerLitre() : null,
                    "hoursCost", rc.hoursCost(), "fuelCost", rc.fuelCost()));
        }
        if (c.blast() != null && r.blasting().signum() > 0) {
            db.jdbc().sql("UPDATE \"QuarryBlast\" SET \"absorbedCost\" = \"absorbedCost\" + ?, \"updatedAt\" = "
                    + QuarryDb.NOW + " WHERE id = ? AND \"organizationId\" = ?").params(r.blasting(),
                    c.blast().get("id"), org).update();
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("status", "posted");
        d.put("grossTonnes", r.grossTonnes());
        d.put("lossTonnes", r.lossTonnes());
        d.put("netTonnes", r.netTonnes());
        d.put("allocationBasis", c.basis());
        d.put("fuelCost", r.fuel());
        d.put("labourCost", r.labour());
        d.put("equipmentCost", r.equipment());
        d.put("blastingCost", r.blasting());
        d.put("royaltyCost", r.royalty());
        d.put("totalCost", r.total());
        d.put("capitalisedCost", r.capitalised());
        d.put("lossCost", r.lossCost());
        d.put("unitCost", r.unitCost());
        d.put("stockLedgerCode", received.ledgerCode());
        d.put("journalEntryId", entryId);
        d.put("postedBy", TenantContext.userId());
        d.put("postedAt", Instant.now());
        d.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(QuarryExtractionLog.class, id, d);
        audit.recordInTx(AuditInput.of(AUDIT, id, "post", log, after));
        events.publish(DomainEvents.QUARRY_EXTRACTION_POSTED, Obj.of("organizationId", org, "extractionLogId", id,
                "siteId", site.get("id"), "stockpileId", stockpile.get("id"), "locationId", stockpile.get("locationId"),
                "materialId", material.get("id"), "productId", product.get("id"), "netTonnes",
                JsNumbers.dec(r.netTonnes()), "unitCost", JsNumbers.dec(r.unitCost()), "totalCost",
                JsNumbers.dec(r.total())));
        return get(id);
    }

    /** Reverse a posted log: stone out at the cost it came in with, GL reversed, blast share and evidence released. */
    @Transactional
    public Map<String, Object> reverse(String id, String reason) {
        Map<String, Object> log = require(id, true);
        if (!"posted".equals(log.get("status"))) {
            throw ApiException.badRequest("Only a posted extraction log can be reversed (" + log.get("code") + " is "
                    + log.get("status") + ")");
        }
        String note = "Reversal of extraction " + log.get("code") + ": " + reason.trim();
        if (log.get("stockLedgerCode") != null) {
            stockReversal.reverseLedgerSource(new LedgerSource(STOCK_SOURCE, id, STOCK_REVERSAL_SOURCE, id,
                    List.of(STOCK_SOURCE), note, null));
        }
        String reversalId = postings.reverse((String) log.get("journalEntryId"), note);
        String org = QuarryDb.org();
        BigDecimal blasting = SqlRows.dec(log, "blastingCost");
        if (log.get("blastId") != null && blasting.signum() > 0) {
            db.byId(QuarryBlast.class, (String) log.get("blastId"), true);
            db.jdbc().sql("UPDATE \"QuarryBlast\" SET \"absorbedCost\" = GREATEST(\"absorbedCost\" - ?, 0), \"updatedAt\""
                    + " = " + QuarryDb.NOW + " WHERE id = ? AND \"organizationId\" = ?").params(blasting,
                    log.get("blastId"), org).update();
        }
        db.jdbc().sql("UPDATE \"QuarryExtractionLoad\" SET active = false WHERE \"organizationId\" = ? AND \"logId\" = ?")
                .params(org, id).update();
        Map<String, Object> after = db.update(QuarryExtractionLog.class, id, Obj.of("status", "reversed",
                "reversalEntryId", reversalId, "reversedBy", TenantContext.userId(), "reversedAt", Instant.now(),
                "reversalReason", reason.trim(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT, id, "cancel", log, after));
        return get(id);
    }

    // ------------------------------------------------------------------ standalone losses

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> losses(String siteId, String kind, LocalDate dateFrom, LocalDate dateTo,
            Integer page, Integer pageSize) {
        int p = QuarryDb.page(page);
        int size = QuarryDb.size(pageSize);
        StringBuilder where = new StringBuilder(" WHERE x.\"organizationId\" = ? AND x.\"deletedAt\" IS NULL AND"
                + " (x.\"logId\" IS NULL OR g.status = 'posted')");
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org()));
        eq(where, params, "x.\"siteId\"", siteId);
        eq(where, params, "x.kind", kind);
        if (dateFrom != null) {
            where.append(" AND x.\"lossOn\" >= ?");
            params.add(java.sql.Date.valueOf(dateFrom));
        }
        if (dateTo != null) {
            where.append(" AND x.\"lossOn\" <= ?");
            params.add(java.sql.Date.valueOf(dateTo));
        }
        String from = " FROM \"QuarryLoss\" x JOIN \"QuarrySite\" s ON s.id = x.\"siteId\" LEFT JOIN \"QuarryZone\" z ON"
                + " z.id = x.\"zoneId\" LEFT JOIN \"QuarryExtractionLog\" g ON g.id = x.\"logId\" LEFT JOIN"
                + " \"QuarryMaterial\" m ON m.id = x.\"materialId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql("SELECT x.*, s.code AS \"siteCode\", z.code AS \"zoneCode\","
                + " g.code AS \"logCode\", m.name AS \"materialName\"" + from + where
                + " ORDER BY x.\"lossOn\" DESC, x.\"createdAt\" DESC LIMIT ? OFFSET ?").params(pp)), p, size, total);
    }

    @Transactional
    public Map<String, Object> createLoss(LossInput in) {
        Map<String, Object> site = sites.requireSite(in.siteId(), false);
        String zone = QuarryDb.text(in.zoneId());
        if (zone != null) {
            sites.zoneOfSite(zone, (String) site.get("id"));
        }
        String material = QuarryDb.text(in.materialId());
        db.requireRef("QuarryMaterial", material, true, "Material not found");
        Map<String, Object> row = db.insert(QuarryLoss.class, Obj.of("siteId", site.get("id"), "zoneId", zone,
                "materialId", material, "lossOn", in.lossOn(), "kind", in.kind(), "tonnes", in.tonnes(), "reason",
                QuarryDb.text(in.reason()), "createdBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("QuarryLoss", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public void deleteLoss(String id) {
        Map<String, Object> before = db.require(QuarryLoss.class, id, true, "Loss not found");
        if (before.get("logId") != null) {
            throw ApiException.badRequest("This loss belongs to an extraction log; edit or reverse the log instead");
        }
        db.update(QuarryLoss.class, id, Obj.of("deletedAt", Instant.now()));
        audit.recordInTx(AuditInput.of("QuarryLoss", id, "delete", before, null));
    }

    // ------------------------------------------------------------------ helpers

    private record Costing(QuarryCostCalculator.Result result, String basis, List<Map<String, Object>> resources,
            List<QuarryCostCalculator.Resource> inputs, Map<String, Object> blast) {}

    /** Resolve the rates and compute the log's cost; {@code lock} locks the blast row (posting). */
    private Costing cost(Map<String, Object> log, Map<String, Object> s, boolean lock) {
        String id = (String) log.get("id");
        String org = QuarryDb.org();
        BigDecimal gross = BigDecimal.ZERO;
        for (Map<String, Object> l : loadLines(id)) {
            gross = gross.add(SqlRows.dec(l, "tonnes"));
        }
        BigDecimal loss = db.jdbc().sql("SELECT COALESCE(SUM(tonnes), 0) FROM \"QuarryLoss\" WHERE \"organizationId\" = ?"
                + " AND \"logId\" = ? AND \"deletedAt\" IS NULL").params(org, id).query(BigDecimal.class).single();
        List<Map<String, Object>> resources = SqlRows.list(QuarryExtractionResource.class, db.jdbc().sql("SELECT * FROM"
                + " \"QuarryExtractionResource\" WHERE \"organizationId\" = ? AND \"logId\" = ? ORDER BY \"lineNumber\"")
                .params(org, id));
        List<QuarryCostCalculator.Resource> inputs = new ArrayList<>();
        BigDecimal fuelRate = SqlRows.dec(s, "fuelCostPerLitre");
        for (Map<String, Object> r : resources) {
            BigDecimal rate = (BigDecimal) r.get("ratePerHour");
            BigDecimal fRate = (BigDecimal) r.get("fuelCostPerLitre");
            if ("equipment".equals(r.get("kind"))) {
                if (rate == null) {
                    String vehicleId = (String) r.get("vehicleId");
                    String category = db.jdbc().sql("SELECT \"categoryId\" FROM \"FleetVehicle\" WHERE id = ? AND"
                            + " \"organizationId\" = ?").params(vehicleId, org).query(String.class).optional()
                            .orElse(null);
                    rate = settings.equipmentRate(vehicleId, category, s);
                }
                inputs.add(new QuarryCostCalculator.Resource("equipment", (BigDecimal) r.get("hours"), rate,
                        (BigDecimal) r.get("fuelLitres"), fRate == null ? fuelRate : fRate));
            } else {
                inputs.add(new QuarryCostCalculator.Resource("labour", (BigDecimal) r.get("hours"),
                        rate == null ? SqlRows.dec(s, "labourRatePerHour") : rate, null, null));
            }
        }
        Map<String, Object> blast = null;
        QuarryCostCalculator.Blast b = null;
        if (log.get("blastId") != null) {
            blast = db.require(QuarryBlast.class, (String) log.get("blastId"), lock, "Blast not found");
            if (lock && !"fired".equals(blast.get("status"))) {
                throw ApiException.badRequest("Blast " + blast.get("code") + " is " + blast.get("status")
                        + "; only a fired blast that is not closed passes its cost to extraction");
            }
            BigDecimal pool = SqlRows.dec(blast, "explosivesCost").add(SqlRows.dec(blast, "drillingCost"))
                    .add(SqlRows.dec(blast, "otherCost"));
            b = new QuarryCostCalculator.Blast((String) blast.get("id"), (String) blast.get("code"), pool,
                    SqlRows.dec(blast, "absorbedCost"), (BigDecimal) blast.get("estimatedTonnes"));
        }
        String basis = (String) s.get("allocationBasis");
        QuarryCostCalculator.Result result = QuarryCostCalculator.compute(new QuarryCostCalculator.Input(gross, loss,
                basis, inputs, b, settings.royaltiesFor((String) log.get("siteId"), (String) log.get("materialId"),
                        (LocalDate) log.get("extractedOn")), SqlRows.dec(log, "otherCost")));
        return new Costing(result, QuarryCostCalculator.GROSS.equals(basis) ? basis : QuarryCostCalculator.NET,
                resources, inputs, blast);
    }

    private List<Map<String, Object>> loadLines(String logId) {
        return SqlRows.list(QuarryExtractionLoad.class, db.jdbc().sql("SELECT * FROM \"QuarryExtractionLoad\" WHERE"
                + " \"organizationId\" = ? AND \"logId\" = ? AND active ORDER BY \"lineNumber\"")
                .params(QuarryDb.org(), logId));
    }

    /** Posting date: now for today, else the end of the extraction day (business zone). */
    static Instant postingInstant(LocalDate day) {
        Instant endOfDay = day.plusDays(1).atStartOfDay(BusinessZone.get()).toInstant().minusSeconds(1);
        Instant now = Instant.now();
        return endOfDay.isBefore(now) ? endOfDay : now;
    }

    private Map<String, Object> header(ExtractionLogInput in) {
        Map<String, Object> site = sites.requireSite(in.siteId(), false);
        String siteId = (String) site.get("id");
        if ("closed".equals(site.get("status"))) {
            throw ApiException.badRequest("Site " + site.get("code") + " is closed");
        }
        String zone = QuarryDb.text(in.zoneId());
        if (zone != null) {
            sites.zoneOfSite(zone, siteId);
        }
        Map<String, Object> material = sites.requireMaterial(in.materialId(), false);
        if (!Boolean.TRUE.equals(material.get("isActive"))) {
            throw ApiException.badRequest("Material " + material.get("code") + " is inactive");
        }
        String stockpileId = QuarryDb.text(in.stockpileId());
        Map<String, Object> stockpile = stockpileId == null ? sites.defaultRom(siteId)
                : sites.requireStockpile(stockpileId, false);
        if (!Objects.equals(stockpile.get("siteId"), siteId)) {
            throw ApiException.badRequest("Stockpile " + stockpile.get("code") + " belongs to another site");
        }
        if (!"rom".equals(stockpile.get("kind"))) {
            throw ApiException.badRequest("Stockpile " + stockpile.get("code") + " is not a ROM stockpile");
        }
        String blastId = QuarryDb.text(in.blastId());
        if (blastId != null) {
            Map<String, Object> blast = db.byId(QuarryBlast.class, blastId, false);
            if (blast == null) {
                throw ApiException.badRequest("Blast not found");
            }
            if (!Objects.equals(blast.get("siteId"), siteId)) {
                throw ApiException.badRequest("Blast " + blast.get("code") + " belongs to another site");
            }
            if ("cancelled".equals(blast.get("status"))) {
                throw ApiException.badRequest("Blast " + blast.get("code") + " was cancelled");
            }
        }
        String supervisor = QuarryDb.text(in.supervisorEmployeeId());
        refs.employee(supervisor);
        String hrShift = QuarryDb.text(in.hrShiftId());
        refs.hrShift(hrShift);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("siteId", siteId);
        d.put("zoneId", zone);
        d.put("materialId", material.get("id"));
        d.put("stockpileId", stockpile.get("id"));
        d.put("blastId", blastId);
        d.put("extractedOn", in.extractedOn());
        d.put("shift", in.shift());
        d.put("hrShiftId", hrShift);
        d.put("supervisorEmployeeId", supervisor);
        d.put("otherCost", in.otherCost() == null ? BigDecimal.ZERO : in.otherCost());
        d.put("otherCostNote", QuarryDb.text(in.otherCostNote()));
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    /** Validate and write the quantity, resource and loss lines; keeps the header's tonnes in step. */
    private void writeLines(Map<String, Object> log, ExtractionLogInput in) {
        String id = (String) log.get("id");
        String siteId = (String) log.get("siteId");
        BigDecimal gross = BigDecimal.ZERO;
        int n = 0;
        java.util.Set<String> seenTickets = new java.util.HashSet<>();
        java.util.Set<String> seenHauls = new java.util.HashSet<>();
        for (LoadInput l : in.loads() == null ? List.<LoadInput>of() : in.loads()) {
            Map<String, Object> d = load(l, siteId, seenTickets, seenHauls);
            d.put("logId", id);
            d.put("lineNumber", ++n);
            d.put("active", true);
            db.insert(QuarryExtractionLoad.class, d);
            gross = gross.add((BigDecimal) d.get("tonnes"));
        }
        n = 0;
        for (ResourceInput r : in.resources() == null ? List.<ResourceInput>of() : in.resources()) {
            Map<String, Object> d = resource(r);
            d.put("logId", id);
            d.put("lineNumber", ++n);
            db.insert(QuarryExtractionResource.class, d);
        }
        BigDecimal loss = BigDecimal.ZERO;
        for (LossLineInput l : in.losses() == null ? List.<LossLineInput>of() : in.losses()) {
            db.insert(QuarryLoss.class, Obj.of("siteId", siteId, "zoneId", log.get("zoneId"), "logId", id,
                    "materialId", log.get("materialId"), "lossOn", log.get("extractedOn"), "kind", l.kind(), "tonnes",
                    l.tonnes(), "reason", QuarryDb.text(l.reason()), "createdBy", TenantContext.userId()));
            loss = loss.add(l.tonnes());
        }
        db.update(QuarryExtractionLog.class, id, Obj.of("grossTonnes", gross, "lossTonnes", loss, "netTonnes",
                gross.subtract(loss).max(BigDecimal.ZERO)));
    }

    private Map<String, Object> load(LoadInput l, String siteId, java.util.Set<String> seenTickets,
            java.util.Set<String> seenHauls) {
        Map<String, Object> d = new LinkedHashMap<>();
        String truck = QuarryDb.text(l.truckVehicleId());
        String trip = QuarryDb.text(l.fleetTripId());
        refs.trip(trip);
        BigDecimal tonnes;
        switch (l.method()) {
            case "loads" -> {
                if (l.loadCount() == null) {
                    throw ApiException.badRequest("Enter the number of loads");
                }
                BigDecimal capacity = refs.capacity(truck, l.capacityT());
                tonnes = QuarryCostCalculator.loadTonnes(l.loadCount(), capacity);
                d.put("loadCount", l.loadCount());
                d.put("capacityT", capacity);
            }
            case "weighbridge" -> {
                String ticketId = QuarryDb.text(l.weighbridgeTicketId());
                if (ticketId == null) {
                    throw ApiException.badRequest("Choose the weighbridge ticket");
                }
                CompletedTicket t = refs.ticket(ticketId);
                if (!seenTickets.add(ticketId)) {
                    throw ApiException.badRequest("Weighbridge ticket " + t.ticketNumber() + " is listed twice");
                }
                haulage.assertTicketFree(ticketId, t.ticketNumber(), null);
                tonnes = QuarryCostCalculator.kgToTonnes(t.netKg());
                truck = truck == null ? t.vehicleId() : truck;
                trip = trip == null ? t.tripId() : trip;
                d.put("weighbridgeTicketId", ticketId);
                d.put("loadCount", 1);
            }
            case "haulage" -> {
                String hid = QuarryDb.text(l.haulageId());
                if (hid == null) {
                    throw ApiException.badRequest("Choose the haulage record");
                }
                Map<String, Object> h = db.byId(QuarryHaulage.class, hid, true);
                if (h == null) {
                    throw ApiException.badRequest("Haulage record not found");
                }
                if (!Objects.equals(h.get("siteId"), siteId)) {
                    throw ApiException.badRequest("Haulage " + h.get("code") + " belongs to another site");
                }
                if (!seenHauls.add(hid)) {
                    throw ApiException.badRequest("Haulage " + h.get("code") + " is listed twice");
                }
                String claimed = haulage.claimedBy(hid);
                if (claimed != null) {
                    throw ApiException.badRequest("Haulage " + h.get("code") + " is already on extraction log "
                            + claimed);
                }
                tonnes = SqlRows.dec(h, "tonnes");
                truck = truck == null ? (String) h.get("truckVehicleId") : truck;
                trip = trip == null ? (String) h.get("fleetTripId") : trip;
                d.put("haulageId", hid);
                d.put("loadCount", h.get("loadCount"));
                d.put("capacityT", h.get("capacityT"));
            }
            default -> {
                if (l.tonnes() == null) {
                    throw ApiException.badRequest("Enter the surveyed tonnes");
                }
                if (truck != null) {
                    refs.vehicle(truck);
                }
                tonnes = QuarryCostCalculator.qty(l.tonnes());
            }
        }
        d.put("method", l.method());
        d.put("truckVehicleId", truck);
        d.put("fleetTripId", trip);
        d.put("tonnes", tonnes);
        d.put("notes", QuarryDb.text(l.notes()));
        return d;
    }

    private Map<String, Object> resource(ResourceInput r) {
        Map<String, Object> d = new LinkedHashMap<>();
        String vehicle = QuarryDb.text(r.vehicleId());
        String employee = QuarryDb.text(r.employeeId());
        BigDecimal hours = r.hours();
        if (r.meterStart() != null && r.meterEnd() != null) {
            if (r.meterEnd().compareTo(r.meterStart()) < 0) {
                throw ApiException.badRequest("The closing hour-meter reading is below the opening one");
            }
            if (hours == null) {
                hours = r.meterEnd().subtract(r.meterStart());
            }
        }
        if (hours == null) {
            throw ApiException.badRequest("Enter the hours worked");
        }
        if ("equipment".equals(r.kind())) {
            if (vehicle == null) {
                throw ApiException.badRequest("Choose the equipment");
            }
            refs.plant(vehicle);
        } else {
            if (employee == null) {
                throw ApiException.badRequest("Choose the employee");
            }
            if (r.fuelLitres() != null && r.fuelLitres().signum() > 0) {
                throw ApiException.badRequest("Fuel is logged on equipment lines");
            }
        }
        refs.employee(employee);
        d.put("kind", r.kind());
        d.put("vehicleId", "equipment".equals(r.kind()) ? vehicle : null);
        d.put("employeeId", employee);
        d.put("role", QuarryDb.text(r.role()));
        d.put("hours", hours);
        d.put("meterStart", r.meterStart());
        d.put("meterEnd", r.meterEnd());
        d.put("fuelLitres", r.fuelLitres());
        d.put("ratePerHour", r.ratePerHour());
        d.put("fuelCostPerLitre", r.fuelCostPerLitre());
        d.put("notes", QuarryDb.text(r.notes()));
        return d;
    }

    /** Remove a draft's quantity, resource and loss lines (replaced on edit; released when the draft is deleted). */
    private void clearLines(String logId) {
        String org = QuarryDb.org();
        db.jdbc().sql("DELETE FROM \"QuarryExtractionLoad\" WHERE \"organizationId\" = ? AND \"logId\" = ?")
                .params(org, logId).update();
        db.jdbc().sql("DELETE FROM \"QuarryExtractionResource\" WHERE \"organizationId\" = ? AND \"logId\" = ?")
                .params(org, logId).update();
        db.jdbc().sql("DELETE FROM \"QuarryLoss\" WHERE \"organizationId\" = ? AND \"logId\" = ?").params(org, logId)
                .update();
    }

    private void assertDraft(Map<String, Object> log, String action) {
        if (!"draft".equals(log.get("status"))) {
            throw ApiException.badRequest("Extraction log " + log.get("code") + " is " + log.get("status")
                    + " and can no longer be " + action);
        }
    }

    private Map<String, Object> employeeRef(String id) {
        Map<String, Object> e = db.ref("SELECT id, \"employeeCode\", \"firstName\", \"lastName\" FROM \"HrEmployee\""
                + " WHERE id = ? AND \"organizationId\" = ?", id);
        return e == null ? null : Obj.of("id", e.get("id"), "employeeCode", e.get("employeeCode"), "name",
                (e.get("firstName") + " " + (e.get("lastName") == null ? "" : e.get("lastName"))).trim());
    }

    private static void eq(StringBuilder where, List<Object> params, String col, String value) {
        if (!QuarryDb.blank(value)) {
            where.append(" AND ").append(col).append(" = ?");
            params.add(value);
        }
    }
}
