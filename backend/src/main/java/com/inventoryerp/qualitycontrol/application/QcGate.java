package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Action;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Cancel;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Create;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Existing;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.ProductInfo;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Recompute;
import com.inventoryerp.qualitycontrol.application.QcCheckGenerator.Requirement;
import com.inventoryerp.qualitycontrol.domain.QcCheck;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The quality gate (ADR-S087 §6) that manufacturing, procurement and sales call inside their own transaction
 * ({@code MANDATORY}): quality control never imports those modules. Enforcement is on the server, inside the business
 * method, before its stock or GL writes.
 *
 * <ul>
 *   <li>{@link #sync}: creates / reconciles the generated checks of a document (idempotent; serialised per document).</li>
 *   <li>{@link #assertComplete}: refuses completion while a blocking check is pending or failed
 *       ({@link QcBlockingPolicy}).</li>
 *   <li>{@link #onCompleted}: closes pre-completion checks, quarantines failed quantities, and for a receipt creates the
 *       post-receipt checks and moves their quantity into quarantine (atomically with the receipt).</li>
 *   <li>{@link #cancelFor}: cancels the pending checks of a cancelled document; done checks are kept.</li>
 * </ul>
 */
@Service
public class QcGate {

    static final String PRE = "pre_completion";
    static final String POST = "post_receipt";

    private final QcDb db;
    private final SequenceService sequence;
    private final QcQuarantineService quarantine;
    private final QcRandom random;

    public QcGate(QcDb db, SequenceService sequence, QcQuarantineService quarantine, QcRandom random) {
        this.db = db;
        this.sequence = sequence;
        this.quarantine = quarantine;
        this.random = random;
    }

    /** Creates or reconciles the pre-completion checks of a document. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sync(QcSourceDoc doc) {
        generate(doc, PRE);
    }

    /**
     * Refuses completion while a generated pre-completion check of the document (or of one of {@code workOrderIds})
     * blocks: 400 {@code Quality checks are pending: QCC-000012, QCC-000013.}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertComplete(String sourceType, String sourceId, Collection<String> workOrderIds) {
        List<Map<String, Object>> rows = blocking(sourceType, sourceId, workOrderIds, true);
        List<String> pending = rows.stream().filter(r -> "pending".equals(r.get("status")))
                .map(r -> QcUtil.str(r, "checkNumber")).toList();
        if (!pending.isEmpty()) {
            throw ApiException.badRequest("Quality checks are pending: " + String.join(", ", pending) + ".");
        }
        List<String> failed = rows.stream().map(r -> QcUtil.str(r, "checkNumber")).toList();
        if (!failed.isEmpty()) {
            throw ApiException.badRequest("Quality checks failed: " + String.join(", ", failed)
                    + ". A quality manager can waive them.");
        }
    }

    /** Does a blocking check hold the document (no lock; for a read before deciding to post)? */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isBlocked(String sourceType, String sourceId) {
        return !blocking(sourceType, sourceId, List.of(), false).isEmpty();
    }

    /**
     * A one-shot receipt (receive and post in one step) cannot satisfy a {@code pre_receipt} control point: true when
     * one matches this receipt, whatever its frequency (ADR-S087 §6).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean preReceiptRequired(QcSourceDoc doc) {
        Map<String, ProductInfo> info = products(doc);
        for (Map<String, Object> p : points(doc.operation(), PRE)) {
            if (!QcCheckGenerator.requirements(p, doc, info::get).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The document completed / posted (call after its stock moves, in the same transaction). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCompleted(QcSourceDoc doc) {
        lock(doc);
        db.jdbc().sql("UPDATE \"QcCheck\" SET \"sourceClosed\" = true, \"updatedAt\" = now() WHERE \"organizationId\" = ?"
                + " AND \"sourceType\" = ? AND \"sourceId\" = ? AND phase = 'pre_completion' AND NOT \"sourceClosed\"")
                .params(QcDb.org(), doc.sourceType(), doc.sourceId()).update();
        boolean movesStock = doc.locationId() != null
                && ("manufacturing".equals(doc.operation()) || "receipt".equals(doc.operation()));
        if (movesStock) {
            for (Map<String, Object> c : db.list(QcCheck.class, " AND \"sourceType\" = ? AND \"sourceId\" = ? AND phase ="
                    + " 'pre_completion' AND status = 'failed' AND \"failedQty\" > 0 AND \"quarantineStatus\" ="
                    + " 'not_required' ORDER BY \"checkNumber\" FOR UPDATE", doc.sourceType(), doc.sourceId())) {
                quarantine.quarantineFailure(withBatch(c, doc), doc.locationId());
            }
        }
        if ("receipt".equals(doc.operation()) && doc.locationId() != null) {
            for (Map<String, Object> c : generate(doc, POST)) {
                quarantine.holdOnReceipt(c);
            }
        }
    }

    /** Cancels the pending generated checks of a cancelled document (and its work orders); done checks stay. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelFor(String sourceType, String sourceId, Collection<String> workOrderIds, String reason) {
        String why = reason == null || reason.isBlank() ? "source document cancelled" : reason;
        for (Map<String, Object> c : SqlRows.list(db.jdbc().sql("SELECT id, \"currentRevision\" FROM \"QcCheck\" WHERE"
                + " \"organizationId\" = ? AND origin = 'generated' AND status = 'pending' AND ((\"sourceType\" = ? AND"
                + " \"sourceId\" = ?) OR (\"sourceType\" = 'work_order' AND \"sourceId\" = ANY(?))) FOR UPDATE")
                .params(QcDb.org(), sourceType, sourceId, workOrderIds(workOrderIds)))) {
            cancel(QcUtil.str(c, "id"), why, QcUtil.integer(c, "currentRevision"));
        }
    }

    // ================================================================== generation

    /** Applies the generator for one phase; returns the checks it created. */
    List<Map<String, Object>> generate(QcSourceDoc doc, String phase) {
        lock(doc);
        List<Map<String, Object>> points = points(doc.operation(), phase);
        if (points.isEmpty()) {
            return List.of();
        }
        Map<String, ProductInfo> info = products(doc);
        Map<String, List<Existing>> existing = new HashMap<>();
        for (Map<String, Object> e : SqlRows.list(db.jdbc().sql("SELECT id, \"controlPointId\", \"baseKey\","
                + " \"generationSeq\", status, \"coveredQty\" FROM \"QcCheck\" WHERE \"organizationId\" = ? AND origin ="
                + " 'generated' AND status <> 'cancelled' AND phase = ? AND \"sourceType\" = ? AND \"sourceId\" = ?"
                + " AND \"workOrderId\" IS NOT DISTINCT FROM ? FOR UPDATE").params(QcDb.org(), phase, doc.sourceType(),
                doc.sourceId(), doc.workOrderId()))) {
            existing.computeIfAbsent(QcUtil.str(e, "controlPointId"), k -> new ArrayList<>())
                    .add(new Existing(QcUtil.str(e, "id"), QcUtil.str(e, "baseKey"), QcUtil.integer(e, "generationSeq"),
                            QcUtil.str(e, "status"), e.get("coveredQty") == null ? null : QcUtil.dec(e, "coveredQty")));
        }
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> p : points) {
            List<Requirement> reqs = QcCheckGenerator.requirements(p, doc, info::get);
            List<Existing> mine = existing.getOrDefault(QcUtil.str(p, "id"), List.of());
            for (Action a : QcCheckGenerator.reconcile(reqs, mine, p, info::get)) {
                switch (a) {
                    case Create c -> {
                        if (c.supplementary() || selected(p, c.req())) {
                            created.add(insert(p, doc, phase, c));
                        }
                    }
                    case Recompute r -> recompute(r);
                    case Cancel x -> cancel(x.checkId(), x.reason(), 1);
                }
            }
        }
        return created;
    }

    private Map<String, Object> insert(Map<String, Object> p, QcSourceDoc doc, String phase, Create c) {
        Requirement r = c.req();
        int seq = db.jdbc().sql("INSERT INTO \"QcGenerationCounter\" (id, \"organizationId\", \"baseKey\", \"nextSeq\")"
                + " VALUES (?, ?, ?, 0) ON CONFLICT (\"organizationId\", \"baseKey\") DO UPDATE SET \"nextSeq\" ="
                + " \"QcGenerationCounter\".\"nextSeq\" + 1, \"updatedAt\" = now() RETURNING \"nextSeq\"")
                .params(UUID.randomUUID().toString(), QcDb.org(), r.baseKey()).query(Integer.class).single();
        Map<String, Object> snapshot = QcCheckService.snapshotOf(p);
        String templateId = null;
        String templateKey = QcUtil.str(p, "worksheetTemplateKey");
        if (templateKey != null) {
            Map<String, Object> t = SqlRows.one(db.jdbc().sql("SELECT id, \"templateKey\", version, name, fields FROM"
                    + " \"QcWorksheetTemplate\" WHERE \"organizationId\" = ? AND \"templateKey\" = ? AND \"isLatest\"")
                    .params(QcDb.org(), templateKey));
            if (t != null) {
                templateId = QcUtil.str(t, "id");
                snapshot.put("worksheet", Obj.of("templateId", templateId, "templateKey", t.get("templateKey"), "version",
                        t.get("version"), "name", t.get("name"), "fields", t.get("fields")));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkNumber", sequence.next("quality_control_check", "QCC-", 6));
        data.put("title", c.supplementary() ? p.get("title") + " (supplementary)" : p.get("title"));
        data.put("controlPointId", p.get("id"));
        data.put("controlPointVersionId", currentVersionId(p));
        data.put("origin", "generated");
        data.put("phase", phase);
        data.put("baseKey", r.baseKey());
        data.put("generationSeq", seq);
        data.put("generationKey", r.baseKey() + "#" + seq);
        data.put("operation", p.get("operation"));
        data.put("checkType", p.get("checkType"));
        data.put("sourceType", doc.sourceType());
        data.put("sourceId", doc.sourceId());
        data.put("sourceLineId", r.lineId());
        data.put("sourceRef", doc.sourceRef());
        data.put("workOrderId", doc.workOrderId());
        data.put("productId", r.productId());
        data.put("variantId", r.variantId());
        data.put("batchNumber", r.batchNumber());
        data.put("locationId", doc.locationId());
        data.put("supplierPartnerId", doc.supplierPartnerId());
        data.put("workCenterId", doc.workCenterId());
        data.put("teamId", p.get("teamId"));
        data.put("responsibleUserId", p.get("responsibleUserId"));
        data.put("coveredQty", c.coveredQty());
        data.put("testQty", c.testQty());
        data.put("snapshot", EntityJson.toJson(snapshot));
        data.put("worksheetTemplateId", templateId);
        data.put("blocking", POST.equals(phase) ? false : QcUtil.bool(p, "blocking"));
        data.put("blockOnFailure", POST.equals(phase) ? false : QcUtil.bool(p, "blockOnFailure"));
        data.put("failureClassRequired", QcUtil.bool(p, "failureClassRequired"));
        data.put("status", "pending");
        data.put("currentRevision", 1);
        data.put("includeInSpc", true);
        data.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QcCheck.class, data);
        QcCheckService.event(db, QcUtil.str(row, "id"), "created", null, "pending", 1, null, Obj.of("origin",
                "generated", "generationKey", data.get("generationKey"), "supplementary", c.supplementary()));
        return row;
    }

    private void recompute(Recompute r) {
        Map<String, Object> before = db.byId(QcCheck.class, r.checkId(), false);
        db.update(QcCheck.class, r.checkId(), Obj.of("coveredQty", r.coveredQty(), "testQty", r.testQty()));
        QcCheckService.event(db, r.checkId(), "recomputed", "pending", "pending", QcUtil.integer(before,
                "currentRevision"), null, Obj.of("coveredQty", before.get("coveredQty"), "newCoveredQty", r.coveredQty(),
                "testQty", before.get("testQty"), "newTestQty", r.testQty()));
    }

    private void cancel(String id, String reason, int revision) {
        db.update(QcCheck.class, id, Obj.of("status", "cancelled", "cancelReason", reason));
        QcCheckService.event(db, id, "cancelled", "pending", "cancelled", revision, reason, Map.of());
    }

    /** Frequency (ADR-S087 §4): the decision is made once per base key and kept. */
    private boolean selected(Map<String, Object> p, Requirement r) {
        String frequency = Objects.requireNonNullElse(QcUtil.str(p, "frequency"), "all");
        if ("all".equals(frequency)) {
            return true;
        }
        Map<String, Object> decided = SqlRows.one(db.jdbc().sql("SELECT selected FROM \"QcSamplingDecision\" WHERE"
                + " \"organizationId\" = ? AND \"baseKey\" = ?").params(QcDb.org(), r.baseKey()));
        if (decided != null) {
            return QcUtil.bool(decided, "selected");
        }
        boolean selected;
        BigDecimal roll;
        BigDecimal pct;
        if ("random".equals(frequency)) {
            pct = QcUtil.dec(p, "randomPct");
            roll = BigDecimal.valueOf(random.next()).setScale(6, java.math.RoundingMode.DOWN);
            selected = roll.multiply(BigDecimal.valueOf(100)).compareTo(pct) < 0;
        } else {
            // periodic: due when the last check of this point (and product) is older than the period.
            String pointId = QcUtil.str(p, "id");
            db.jdbc().sql("INSERT INTO \"QcPointSchedule\" (id, \"organizationId\", \"controlPointId\", \"scopeKey\","
                    + " \"lastGeneratedAt\") VALUES (?, ?, ?, ?, timestamp '1970-01-01') ON CONFLICT (\"organizationId\","
                    + " \"controlPointId\", \"scopeKey\") DO NOTHING").params(UUID.randomUUID().toString(), QcDb.org(),
                    pointId, r.scheduleScope()).update();
            selected = db.jdbc().sql("SELECT \"lastGeneratedAt\" + make_interval(days => ?, months => ?) <= now() FROM"
                    + " \"QcPointSchedule\" WHERE \"organizationId\" = ? AND \"controlPointId\" = ? AND \"scopeKey\" = ?"
                    + " FOR UPDATE").params(periodDays(p), periodMonths(p), QcDb.org(), pointId, r.scheduleScope())
                    .query(Boolean.class).single();
            if (selected) {
                db.jdbc().sql("UPDATE \"QcPointSchedule\" SET \"lastGeneratedAt\" = now() WHERE \"organizationId\" = ?"
                        + " AND \"controlPointId\" = ? AND \"scopeKey\" = ?").params(QcDb.org(), pointId, r.scheduleScope())
                        .update();
            }
            // The decision row records due (roll 0) / not due (roll 1) so a later sync of this document keeps it.
            roll = selected ? BigDecimal.ZERO : BigDecimal.ONE;
            pct = BigDecimal.ZERO;
        }
        db.jdbc().sql("INSERT INTO \"QcSamplingDecision\" (id, \"organizationId\", \"controlPointId\", \"baseKey\","
                + " selected, roll, pct) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"baseKey\") DO"
                + " NOTHING").params(UUID.randomUUID().toString(), QcDb.org(), p.get("id"), r.baseKey(), selected, roll, pct)
                .update();
        return selected;
    }

    private static int periodDays(Map<String, Object> p) {
        int n = p.get("periodValue") == null ? 1 : QcUtil.integer(p, "periodValue");
        return switch (Objects.requireNonNullElse(QcUtil.str(p, "periodUnit"), "day")) {
            case "week" -> 7 * n;
            case "month" -> 0;
            default -> n;
        };
    }

    private static int periodMonths(Map<String, Object> p) {
        return "month".equals(QcUtil.str(p, "periodUnit"))
                ? (p.get("periodValue") == null ? 1 : QcUtil.integer(p, "periodValue")) : 0;
    }

    // ================================================================== helpers

    /** Active points of an operation for a phase; receipts split by receipt mode. */
    private List<Map<String, Object>> points(String operation, String phase) {
        String mode = "";
        if ("receipt".equals(operation)) {
            mode = POST.equals(phase) ? " AND \"receiptMode\" = 'quarantine_on_receipt'"
                    : " AND COALESCE(\"receiptMode\", 'pre_receipt') = 'pre_receipt'";
        } else if (POST.equals(phase)) {
            return List.of();
        }
        return SqlRows.list(db.jdbc().sql("SELECT * FROM \"QcControlPoint\" WHERE \"organizationId\" = ? AND \"isActive\""
                + " AND operation = ?" + mode + " ORDER BY sequence, reference").params(QcDb.org(), operation));
    }

    private Map<String, ProductInfo> products(QcSourceDoc doc) {
        List<String> ids = doc.lines().stream().map(QcSourceDoc.Line::productId).filter(Objects::nonNull).distinct()
                .toList();
        Map<String, ProductInfo> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT p.id, p.\"categoryId\", u.\"roundingPrecision\""
                + " FROM \"Product\" p LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE p.\"organizationId\" = ?"
                + " AND p.id = ANY(?)").params(QcDb.org(), ids.toArray(String[]::new)))) {
            Object rp = r.get("roundingPrecision");
            int scale = 0;
            if (rp != null) {
                BigDecimal precision = new BigDecimal(rp.toString());
                scale = precision.signum() > 0 ? Math.max(0, precision.stripTrailingZeros().scale()) : 0;
            }
            out.put(QcUtil.str(r, "id"), new ProductInfo(QcUtil.str(r, "categoryId"), Math.min(scale, 6)));
        }
        return out;
    }

    private List<Map<String, Object>> blocking(String sourceType, String sourceId, Collection<String> workOrderIds,
            boolean lock) {
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT id, \"checkNumber\", origin, status, blocking,"
                + " \"blockOnFailure\" FROM \"QcCheck\" WHERE \"organizationId\" = ? AND origin = 'generated' AND phase ="
                + " 'pre_completion' AND status IN ('pending', 'failed') AND ((\"sourceType\" = ? AND \"sourceId\" = ?) OR"
                + " (\"sourceType\" = 'work_order' AND \"sourceId\" = ANY(?))) ORDER BY \"checkNumber\""
                + (lock ? " FOR UPDATE" : "")).params(QcDb.org(), sourceType, sourceId, workOrderIds(workOrderIds)));
        return rows.stream().filter(r -> QcBlockingPolicy.blocks(QcUtil.str(r, "origin"), QcUtil.str(r, "status"),
                QcUtil.bool(r, "blocking"), QcUtil.bool(r, "blockOnFailure"))).toList();
    }

    private static String[] workOrderIds(Collection<String> ids) {
        return ids == null ? new String[0] : ids.toArray(String[]::new);
    }

    /** A failed check without a batch takes the batch of its product's line at completion. */
    private Map<String, Object> withBatch(Map<String, Object> c, QcSourceDoc doc) {
        if (c.get("batchNumber") != null) {
            return c;
        }
        List<String> batches = doc.lines().stream().filter(l -> Objects.equals(l.productId(), c.get("productId"))
                && Objects.equals(l.variantId(), c.get("variantId"))).map(QcSourceDoc.Line::batchNumber)
                .filter(Objects::nonNull).distinct().toList();
        if (batches.size() != 1) {
            return c;
        }
        db.update(QcCheck.class, QcUtil.str(c, "id"), Obj.of("batchNumber", batches.getFirst()));
        Map<String, Object> copy = new LinkedHashMap<>(c);
        copy.put("batchNumber", batches.getFirst());
        return copy;
    }

    private String currentVersionId(Map<String, Object> point) {
        return db.jdbc().sql("SELECT id FROM \"QcControlPointVersion\" WHERE \"controlPointId\" = ? AND \"organizationId\""
                + " = ? AND version = ?").params(point.get("id"), QcDb.org(), point.get("version")).query(String.class)
                .optional().orElse(null);
    }

    /** One generating transaction per document at a time; the hash is only a lock key (identity = generationKey). */
    private void lock(QcSourceDoc doc) {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text")
                .param("qc:" + QcDb.org() + "|" + doc.sourceType() + "|" + doc.sourceId()).query(String.class).list();
    }
}
