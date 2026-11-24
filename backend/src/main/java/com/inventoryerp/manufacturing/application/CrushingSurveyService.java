package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Adjusted;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.LedgerSource;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.CrushingCommands.SurveyInput;
import com.inventoryerp.manufacturing.domain.CrushingStockpileSurvey;
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
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stockpile surveys (ADR-S016): a surveyor measures a stockpile (drone, total station, GPS, tape ...) — volume × bulk
 * density = surveyed tonnes. Submitting snapshots the book stock and the variance; a second person with
 * {@code crushing_survey:approve} approves it, which applies the variance to the stock on hand through the inventory
 * adjustment ({@link StockService#adjust}: ADJUSTMENT_GAIN / LOSS through the posting engine, sourceType
 * {@code crushing_survey}), or rejects it. A posted survey can be reversed (its own movement and journal reversed at
 * the posted value). Integrity fix 2026-11-24 (MV-2): one submitted survey per stockpile × product, book stock as of
 * the survey date, no gain at zero cost, tonnes only.
 */
@Service
public class CrushingSurveyService {

    static final String SOURCE = "crushing_survey";
    static final String SOURCE_REVERSAL = "crushing_survey_reversal";

    private final CrushingSupport rows;
    private final MfgDb db;
    private final SequenceService seq;
    private final AuditService audit;
    private final StockService stock;
    private final StockReversalService stockReversal;

    public CrushingSurveyService(CrushingSupport rows, SequenceService seq, AuditService audit, StockService stock,
            StockReversalService stockReversal) {
        this.rows = rows;
        this.db = rows.db();
        this.seq = seq;
        this.audit = audit;
        this.stock = stock;
        this.stockReversal = stockReversal;
    }

    private static final String SELECT = """
            SELECT v.*, k.code AS "stockpileCode", k.name AS "stockpileName", k.kind AS "stockpileKind",
                   k."locationId", p.code AS "productCode", p.name AS "productName"
            FROM "CrushingStockpileSurvey" v
            JOIN "QuarryStockpile" k ON k.id = v."stockpileId"
            JOIN "Product" p ON p.id = v."productId"
            WHERE v."organizationId" = ? AND v."deletedAt" IS NULL""";

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String stockpileId, String status, Integer page, Integer pageSize) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        StringBuilder w = new StringBuilder();
        if (CrushingSupport.text(stockpileId) != null) {
            w.append(" AND v.\"stockpileId\" = ?");
            params.add(stockpileId);
        }
        if (CrushingSupport.text(status) != null) {
            w.append(" AND v.status = ?");
            params.add(status);
        }
        int p = CrushingSupport.page(page);
        int size = CrushingSupport.size(pageSize);
        long total = db.jdbc().sql("SELECT count(*) FROM (" + SELECT + w + ") x").params(params).query(Long.class)
                .single();
        List<Object> paged = new ArrayList<>(params);
        paged.add(size);
        paged.add((p - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + w + " ORDER BY v.\"surveyDate\" DESC, v.code DESC"
                + " LIMIT ? OFFSET ?").params(paged)), p, size, total);
    }

    /** The survey with the live book stock (drafts) and its GL entries. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> v = SqlRows.one(db.jdbc().sql(SELECT + " AND v.id = ?").params(MfgDb.org(), id));
        if (v == null) {
            throw ApiException.notFound("Stockpile survey not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(v);
        BigDecimal onHand = rows.onHand(str(v, "productId"), str(v, "locationId"));
        out.put("currentBookTonnes", onHand);
        if ("draft".equals(v.get("status"))) {
            CrushingCalculator.SurveyVariance sv = CrushingCalculator.surveyVariance(dec(v, "surveyedTonnes"), onHand);
            out.put("previewVarianceTonnes", sv.tonnes());
            out.put("previewVariancePct", sv.pct());
        }
        List<Map<String, Object>> journals = new ArrayList<>(rows.journals(SOURCE, List.of(id)));
        journals.addAll(rows.journals(SOURCE_REVERSAL, List.of(id)));
        out.put("journals", journals);
        return out;
    }

    @Transactional
    public Map<String, Object> create(SurveyInput in) {
        Map<String, Object> d = data(in);
        d.put("code", seq.next("crushing_survey", "SV-", 5));
        d.put("status", "draft");
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> v = db.insert(CrushingStockpileSurvey.class, d);
        audit.recordInTx(AuditInput.of("CrushingStockpileSurvey", str(v, "id"), "create", null, Obj.of("code",
                v.get("code"), "surveyedTonnes", JsNumbers.dec(dec(v, "surveyedTonnes")))));
        return get(str(v, "id"));
    }

    @Transactional
    public Map<String, Object> update(String id, SurveyInput in) {
        Map<String, Object> v = rows.require(CrushingStockpileSurvey.class, id, true, "Stockpile survey not found");
        status(v, "draft", "edited");
        db.update(CrushingStockpileSurvey.class, id, data(in));
        audit.recordInTx(AuditInput.of("CrushingStockpileSurvey", id, "update", null, Obj.of("code", v.get("code"))));
        return get(id);
    }

    @Transactional
    public void remove(String id) {
        Map<String, Object> v = rows.require(CrushingStockpileSurvey.class, id, true, "Stockpile survey not found");
        status(v, "draft", "deleted");
        db.update(CrushingStockpileSurvey.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy",
                TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingStockpileSurvey", id, "delete", Obj.of("code", v.get("code")), null));
    }

    /** Snapshot the book stock and the variance, and hand the survey to an approver. */
    @Transactional
    public Map<String, Object> submit(String id) {
        Map<String, Object> v = rows.require(CrushingStockpileSurvey.class, id, true, "Stockpile survey not found");
        status(v, "draft", "submitted");
        // Integrity fix 2026-11-24 (MV-2a): one survey of a stockpile × product waits for approval at a time, and a
        // survey older than one already posted cannot be applied (both would correct the same book stock).
        lockPile(v);
        String waiting = db.jdbc().sql("SELECT code FROM \"CrushingStockpileSurvey\" WHERE \"organizationId\" = ?"
                + " AND \"stockpileId\" = ? AND \"productId\" = ? AND status = 'submitted' AND \"deletedAt\" IS NULL"
                + " AND id <> ? LIMIT 1").params(MfgDb.org(), v.get("stockpileId"), v.get("productId"), id)
                .query(String.class).optional().orElse(null);
        if (waiting != null) {
            throw ApiException.badRequest("Stockpile survey " + waiting + " of this stockpile and product is already"
                    + " waiting for approval; approve or reject it first");
        }
        refuseLaterPosted(v);
        Map<String, Object> k = rows.stockpile(str(v, "stockpileId"));
        String loc = str(k, "locationId");
        rows.requireTonnes(str(v, "productId"));
        // Integrity fix 2026-11-24 (MV-2b): the book stock as of the end of the survey date (business time zone), not
        // the stock at submission — movements after the survey are not part of what the surveyor measured.
        BigDecimal book = bookAsOf(str(v, "productId"), loc, localDate(v.get("surveyDate")));
        CrushingCalculator.SurveyVariance sv = CrushingCalculator.surveyVariance(dec(v, "surveyedTonnes"), book);
        BigDecimal unit = rows.averageCost(str(v, "productId"), loc);
        requireGainCost(sv.tonnes(), unit, k, str(v, "productId"));
        db.update(CrushingStockpileSurvey.class, id, Obj.of("status", "submitted", "bookTonnes", book,
                "varianceTonnes", sv.tonnes(), "variancePct", sv.pct(), "unitCost", unit, "varianceValue",
                sv.tonnes().multiply(unit).setScale(2, RoundingMode.HALF_UP), "submittedAt", Instant.now(),
                "submittedBy", TenantContext.userId(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingStockpileSurvey", id, "update", null, Obj.of("code", v.get("code"),
                "status", "submitted", "varianceTonnes", JsNumbers.dec(sv.tonnes()))));
        return get(id);
    }

    /**
     * Approve a submitted survey (never by its submitter): the variance (surveyed − book at submission) is applied to
     * the stock on hand now — an inventory adjustment at the running average cost, GL through the posting engine.
     */
    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> v = rows.require(CrushingStockpileSurvey.class, id, true, "Stockpile survey not found");
        status(v, "submitted", "approved");
        if (Objects.equals(v.get("submittedBy"), TenantContext.userId())) {
            throw ApiException.forbidden("The person who submitted a stockpile survey cannot approve it");
        }
        lockPile(v);
        // Integrity fix 2026-11-24 (MV-2a): a survey posted since this one's book snapshot already corrected it.
        String since = db.jdbc().sql("SELECT code FROM \"CrushingStockpileSurvey\" WHERE \"organizationId\" = ?"
                + " AND \"stockpileId\" = ? AND \"productId\" = ? AND status = 'posted' AND \"deletedAt\" IS NULL"
                + " AND id <> ? AND (\"decidedAt\" > ? OR \"surveyDate\" > ?) LIMIT 1").params(MfgDb.org(),
                v.get("stockpileId"), v.get("productId"), id, MfgDb.param(v.get("submittedAt")),
                MfgDb.param(v.get("surveyDate"))).query(String.class).optional().orElse(null);
        if (since != null) {
            throw ApiException.badRequest("Stockpile survey " + since + " of this stockpile and product was posted after"
                    + " the book snapshot of " + v.get("code") + "; reject it and record a new survey");
        }
        BigDecimal delta = dec(v, "varianceTonnes");
        if (delta.signum() > 0) {
            Map<String, Object> k = rows.stockpile(str(v, "stockpileId"));
            requireGainCost(delta, rows.averageCost(str(v, "productId"), str(k, "locationId")), k,
                    str(v, "productId"));
        }
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("status", "posted");
        upd.put("decidedAt", Instant.now());
        upd.put("decidedBy", TenantContext.userId());
        upd.put("updatedBy", TenantContext.userId());
        if (delta.signum() != 0) {
            Adjusted a = adjust(v, delta, SOURCE, "Stockpile survey " + v.get("code"));
            upd.put("ledgerCode", a.ledgerCode());
            upd.put("unitCost", a.unitCost());
            upd.put("varianceValue", delta.multiply(a.unitCost()).setScale(2, RoundingMode.HALF_UP));
        }
        db.update(CrushingStockpileSurvey.class, id, upd);
        audit.recordInTx(AuditInput.of("CrushingStockpileSurvey", id, "approve", null, Obj.of("code", v.get("code"),
                "varianceTonnes", JsNumbers.dec(delta))));
        return get(id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> v = rows.require(CrushingStockpileSurvey.class, id, true, "Stockpile survey not found");
        status(v, "submitted", "rejected");
        db.update(CrushingStockpileSurvey.class, id, Obj.of("status", "rejected", "rejectionReason", reason,
                "decidedAt", Instant.now(), "decidedBy", TenantContext.userId(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingStockpileSurvey", id, "reject", null, Obj.of("code", v.get("code"),
                "reason", reason)));
        return get(id);
    }

    /** Reverse a posted survey: the opposite adjustment (refused when the stock would go below zero). */
    @Transactional
    public Map<String, Object> reverse(String id, String reason) {
        Map<String, Object> v = rows.require(CrushingStockpileSurvey.class, id, true, "Stockpile survey not found");
        status(v, "posted", "reversed");
        lockPile(v);
        BigDecimal delta = dec(v, "varianceTonnes");
        if (delta.signum() != 0) {
            // Integrity fix 2026-11-24 (MV-2c): reverse the survey's own movement and journal at the value it was
            // posted at (a loss comes back at its stored unit cost), not a new adjustment at today's average.
            Map<String, Object> k = rows.stockpile(str(v, "stockpileId"));
            BigDecimal current = rows.onHand(str(v, "productId"), str(k, "locationId"));
            if (current.subtract(delta).signum() < 0) {
                throw ApiException.badRequest("Stockpile " + k.get("code") + " now holds "
                        + current.stripTrailingZeros().toPlainString() + " t; applying "
                        + delta.negate().stripTrailingZeros().toPlainString() + " t would take it below zero");
            }
            stockReversal.reverseLedgerSource(new LedgerSource(SOURCE, id, SOURCE_REVERSAL, id, List.of(SOURCE),
                    "Reversal of stockpile survey " + v.get("code") + ": " + reason, null));
        }
        db.update(CrushingStockpileSurvey.class, id, Obj.of("status", "reversed", "reversedAt", Instant.now(),
                "reversedBy", TenantContext.userId(), "reversalReason", reason, "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingStockpileSurvey", id, "cancel", null, Obj.of("code", v.get("code"),
                "reason", reason)));
        return get(id);
    }

    /** MV-2a: serialises submit / approve / reverse of one stockpile × product. */
    private void lockPile(Map<String, Object> v) {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param("crushing-survey:" + MfgDb.org()
                + ":" + v.get("stockpileId") + ":" + v.get("productId")).query().listOfRows();
    }

    /** MV-2a: a survey dated before one already posted for the same stockpile × product cannot be submitted. */
    private void refuseLaterPosted(Map<String, Object> v) {
        Map<String, Object> later = SqlRows.one(db.jdbc().sql("SELECT code, \"surveyDate\" FROM"
                + " \"CrushingStockpileSurvey\" WHERE \"organizationId\" = ? AND \"stockpileId\" = ? AND \"productId\" = ?"
                + " AND status = 'posted' AND \"deletedAt\" IS NULL AND \"surveyDate\" > ? ORDER BY \"surveyDate\" DESC"
                + " LIMIT 1").params(MfgDb.org(), v.get("stockpileId"), v.get("productId"),
                MfgDb.param(v.get("surveyDate"))));
        if (later != null) {
            throw ApiException.badRequest("Stockpile survey " + later.get("code") + " of " + later.get("surveyDate")
                    + " is already posted for this stockpile and product; a survey of " + v.get("surveyDate")
                    + " can no longer be applied");
        }
    }

    /**
     * MV-2b: on-hand at the end of {@code date} (business time zone) = on hand now − every movement recorded after
     * it. Today (or later) is simply the stock on hand.
     */
    BigDecimal bookAsOf(String productId, String locationId, LocalDate date) {
        BigDecimal now = rows.onHand(productId, locationId);
        if (date == null || !date.isBefore(LocalDate.now(BusinessZone.get()))) {
            return now;
        }
        Instant end = date.plusDays(1).atStartOfDay(BusinessZone.get()).toInstant();
        BigDecimal after = db.jdbc().sql("SELECT COALESCE(SUM(\"quantityChange\"), 0) FROM \"InventoryLedger\""
                + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"locationId\" = ? AND \"createdAt\" >= ?")
                .params(MfgDb.org(), productId, locationId, MfgDb.param(end)).query(BigDecimal.class).single();
        return now.subtract(after).setScale(3, RoundingMode.HALF_UP);
    }

    private static LocalDate localDate(Object d) {
        if (d == null || d instanceof LocalDate) {
            return (LocalDate) d;
        }
        if (d instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(d).substring(0, 10));
    }

    /** MV-2d: a gain is valued at the stock's average / the product cost; refused when that is zero. */
    private void requireGainCost(BigDecimal delta, BigDecimal unitCost, Map<String, Object> k, String productId) {
        if (delta.signum() > 0 && (unitCost == null || unitCost.signum() <= 0)) {
            throw ApiException.badRequest("Stockpile " + k.get("code") + " has no cost for "
                    + rows.product(productId).get("name") + "; set the product's cost price before a survey adds"
                    + " stock");
        }
    }

    private Adjusted adjust(Map<String, Object> v, BigDecimal delta, String sourceType, String notes) {
        Map<String, Object> k = rows.stockpile(str(v, "stockpileId"));
        String loc = str(k, "locationId");
        BigDecimal current = rows.onHand(str(v, "productId"), loc);
        BigDecimal counted = current.add(delta);
        if (counted.signum() < 0) {
            throw ApiException.badRequest("Stockpile " + k.get("code") + " now holds "
                    + current.stripTrailingZeros().toPlainString() + " t; applying "
                    + delta.stripTrailingZeros().toPlainString() + " t would take it below zero");
        }
        StockMove m = StockMove.of(str(v, "productId"), loc, delta.abs()).source(sourceType, str(v, "id"))
                .notes(notes).people(TenantContext.userId(), TenantContext.userId());
        // Audit A-2: post the variance as a delta on the on-hand read under the quant lock, not an absolute count
        // read before it (a movement between the read and the write would be lost). The check above fails fast; the
        // one below is authoritative.
        m.deltaQuantity = delta;
        Adjusted a = stock.adjust(m);
        if (a.newQuantity() != null && a.newQuantity().signum() < 0) {
            BigDecimal before = a.previousQuantity() == null ? current : a.previousQuantity();
            throw ApiException.badRequest("Stockpile " + k.get("code") + " now holds "
                    + before.stripTrailingZeros().toPlainString() + " t; applying "
                    + delta.stripTrailingZeros().toPlainString() + " t would take it below zero");
        }
        return a;
    }

    private Map<String, Object> data(SurveyInput in) {
        Map<String, Object> k = rows.stockpile(in.stockpileId());
        if (!Boolean.TRUE.equals(k.get("isActive"))) {
            throw ApiException.badRequest("Stockpile " + k.get("code") + " is inactive");
        }
        rows.product(in.productId());
        rows.requireTonnes(in.productId());
        LocalDate date = CrushingSupport.date(in.surveyDate());
        if (date.isAfter(LocalDate.now(BusinessZone.get()))) {
            throw ApiException.badRequest("The survey date cannot be in the future");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("stockpileId", in.stockpileId());
        d.put("productId", in.productId());
        d.put("surveyDate", date);
        d.put("method", in.method() == null ? "drone" : in.method());
        d.put("surveyor", CrushingSupport.text(in.surveyor()));
        d.put("volumeM3", in.volumeM3());
        d.put("bulkDensity", in.bulkDensity());
        d.put("surveyedTonnes", CrushingCalculator.surveyTonnes(in.volumeM3(), in.bulkDensity()));
        d.put("notes", CrushingSupport.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    private static void status(Map<String, Object> v, String expected, String action) {
        if (!expected.equals(v.get("status"))) {
            throw ApiException.badRequest("Stockpile survey " + v.get("code") + " is " + v.get("status")
                    + " and cannot be " + action);
        }
    }
}
