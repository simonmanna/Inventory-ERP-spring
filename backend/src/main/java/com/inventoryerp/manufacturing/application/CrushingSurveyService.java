package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Adjusted;
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
 * {@code crushing_survey}), or rejects it. A posted survey can be reversed (the opposite adjustment).
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

    public CrushingSurveyService(CrushingSupport rows, SequenceService seq, AuditService audit, StockService stock) {
        this.rows = rows;
        this.db = rows.db();
        this.seq = seq;
        this.audit = audit;
        this.stock = stock;
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
        Map<String, Object> k = rows.stockpile(str(v, "stockpileId"));
        String loc = str(k, "locationId");
        BigDecimal book = rows.onHand(str(v, "productId"), loc);
        CrushingCalculator.SurveyVariance sv = CrushingCalculator.surveyVariance(dec(v, "surveyedTonnes"), book);
        BigDecimal unit = rows.averageCost(str(v, "productId"), loc);
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
        BigDecimal delta = dec(v, "varianceTonnes");
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
        BigDecimal delta = dec(v, "varianceTonnes");
        if (delta.signum() != 0) {
            adjust(v, delta.negate(), SOURCE_REVERSAL, "Reversal of stockpile survey " + v.get("code") + ": " + reason);
        }
        db.update(CrushingStockpileSurvey.class, id, Obj.of("status", "reversed", "reversedAt", Instant.now(),
                "reversedBy", TenantContext.userId(), "reversalReason", reason, "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("CrushingStockpileSurvey", id, "cancel", null, Obj.of("code", v.get("code"),
                "reason", reason)));
        return get(id);
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
        m.countedQuantity = counted;
        return stock.adjust(m);
    }

    private Map<String, Object> data(SurveyInput in) {
        Map<String, Object> k = rows.stockpile(in.stockpileId());
        if (!Boolean.TRUE.equals(k.get("isActive"))) {
            throw ApiException.badRequest("Stockpile " + k.get("code") + " is inactive");
        }
        rows.product(in.productId());
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
