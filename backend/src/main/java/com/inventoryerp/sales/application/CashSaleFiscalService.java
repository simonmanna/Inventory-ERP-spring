package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fiscal lifecycle of branch cash sales (ADR-S056 §8, cash-sales.md §7). Operations ({@code issue}, {@code cancel},
 * {@code correct}) are rows of {@code FiscalDocument} written in the business transaction (an outbox) and sent to the
 * branch's {@link FiscalDocumentPort} afterwards — by {@link CashSaleFiscalJob}, by the retry route, or inline for the
 * {@code none} provider, which makes no external call. Each row has a stable {@code requestRef}; a timeout leaves it
 * {@code unknown} and recovery looks the reference up before sending again, so a retry never creates a second fiscal
 * document. The submission's {@code fiscalStatus} gates void, invoice cancel and identity amendments.
 */
@Service
public class CashSaleFiscalService {

    private static final Logger log = LoggerFactory.getLogger(CashSaleFiscalService.class);
    static final int MAX_ATTEMPTS = 5;
    static final Set<String> IDENTITY = Set.of("customerName", "customerTin", "customerAddress");

    private final SalesDb db;
    private final Map<String, FiscalDocumentPort> adapters = new LinkedHashMap<>();
    private final TransactionTemplate newTx;

    public CashSaleFiscalService(SalesDb db, List<FiscalDocumentPort> ports, PlatformTransactionManager txm) {
        this.db = db;
        ports.forEach(p -> adapters.put(p.provider(), p));
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Provider keys a branch profile may use. */
    public List<String> providers() {
        return new ArrayList<>(adapters.keySet());
    }

    // ------------------------------------------------------------------ requests

    /**
     * Queue {@code operation} for the submission in the current transaction. With the {@code none} provider it
     * completes at once; otherwise it waits for the job / retry.
     */
    Map<String, Object> request(Map<String, Object> css, String operation, String invoiceId, Map<String, Object> payload,
            String reason) {
        String id = str(css.get("id"));
        Map<String, Object> profile = db.row("SELECT \"fiscalProvider\" FROM \"BranchSalesProfile\" WHERE id = ?",
                css.get("profileId"));
        String provider = profile == null ? "none" : str(profile.get("fiscalProvider"));
        long n = db.count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"submissionId\" = ? AND operation = ?",
                List.of(id, operation));
        Map<String, Object> doc = db.insert("FiscalDocument", Obj.of("submissionId", id, "invoiceId", invoiceId,
                "provider", provider, "operation", operation, "requestRef", "css:" + id + ":" + operation + ":" + (n + 1),
                "requestPayload", null, "reason", reason, "userId", user()));
        db.update("UPDATE \"FiscalDocument\" SET \"requestPayload\" = ?::jsonb WHERE id = ?", EntityJson.toJson(
                payload == null ? Map.of() : payload), doc.get("id"));
        String status = switch (operation) {
            case "issue" -> "pending";
            case "cancel" -> "cancellation_pending";
            default -> null;
        };
        if (status != null) {
            db.update("UPDATE \"SalesSubmission\" SET \"fiscalStatus\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?",
                    status, id);
        }
        timeline(id, "fiscal_" + operation + "_requested", reason);
        if ("none".equals(provider)) {
            run(str(doc.get("id")));
        }
        return db.row("SELECT * FROM \"FiscalDocument\" WHERE id = ?", doc.get("id"));
    }

    /**
     * Gate before an internal reversal (void, invoice cancel). Without an issued fiscal document it passes. With one,
     * a cancellation is requested in its own committed transaction: when it completes at once ({@code none}) the gate
     * passes, otherwise 409 and the caller retries once the cancellation succeeded. Call it before locking the
     * submission row.
     */
    void gateReversal(String submissionId, String reason) {
        Map<String, Object> css = db.row("SELECT * FROM \"SalesSubmission\" WHERE id = ? AND \"organizationId\" = ?",
                submissionId, org());
        if (css == null) {
            return;
        }
        String status = str(css.get("fiscalStatus"));
        switch (status) {
            case "none", "failed", "cancelled" -> {
                return;
            }
            case "pending" -> throw ApiException.conflict("The fiscal document is still being issued; retry once it"
                    + " is issued or failed");
            case "cancellation_pending" -> {
                newTx.executeWithoutResult(s -> {
                    if (db.count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"submissionId\" = ? AND operation ="
                            + " 'cancel' AND status IN ('pending', 'unknown')", List.of(submissionId)) == 0) {
                        // the last cancellation failed for good: ask again (new attempt, new reference)
                        request(css, "cancel", str(css.get("currentInvoiceId")), Obj.of("reason", reason), reason);
                    }
                    processOpen(submissionId);
                });
                if ("cancelled".equals(str(db.row("SELECT \"fiscalStatus\" FROM \"SalesSubmission\" WHERE id = ?",
                        submissionId).get("fiscalStatus")))) {
                    return;
                }
                throw ApiException.conflict("Fiscal cancellation is pending; the reversal runs once it succeeds");
            }
            default -> {
                // issued
            }
        }
        newTx.executeWithoutResult(s -> request(css, "cancel", str(css.get("currentInvoiceId")), Obj.of("reason",
                reason), reason));
        String after = str(db.row("SELECT \"fiscalStatus\" FROM \"SalesSubmission\" WHERE id = ?", submissionId)
                .get("fiscalStatus"));
        if (!"cancelled".equals(after)) {
            throw ApiException.conflict("Fiscal cancellation requested; the reversal runs once it succeeds");
        }
    }

    /** 409 when an identity field changes while a fiscal document is issued (correct it through the fiscal route). */
    void assertIdentityEditable(Map<String, Object> css, Map<String, Object> changes) {
        if (List.of("issued", "pending", "cancellation_pending").contains(str(css.get("fiscalStatus")))
                && changes.keySet().stream().anyMatch(IDENTITY::contains)) {
            throw ApiException.conflict("Fiscal document issued — correct it first");
        }
    }

    // ------------------------------------------------------------------ processing

    /** Process the open operations of one submission (retry route), each in its own transaction. */
    public List<Map<String, Object>> retry(String submissionId) {
        newTx.executeWithoutResult(s -> processOpen(submissionId));
        return db.rows("SELECT * FROM \"FiscalDocument\" WHERE \"submissionId\" = ? AND \"organizationId\" = ? ORDER BY"
                + " \"createdAt\"", submissionId, org());
    }

    void processOpen(String submissionId) {
        for (Map<String, Object> d : db.rows("SELECT id FROM \"FiscalDocument\" WHERE \"submissionId\" = ? AND"
                + " \"organizationId\" = ? AND status IN ('pending', 'unknown') ORDER BY \"createdAt\"", submissionId,
                org())) {
            run(str(d.get("id")));
        }
    }

    /** Open operations of the current organization (job). */
    @Transactional
    public int processAll() {
        int n = 0;
        for (Map<String, Object> d : db.rows("SELECT id FROM \"FiscalDocument\" WHERE \"organizationId\" = ? AND status IN"
                + " ('pending', 'unknown') ORDER BY \"createdAt\" LIMIT 200", org())) {
            String id = str(d.get("id"));
            try {
                newTx.executeWithoutResult(s -> run(id));
                n++;
            } catch (RuntimeException e) {
                log.warn("Fiscal operation {} failed: {}", id, e.toString());
            }
        }
        return n;
    }

    /** One attempt of an open operation: look up an unknown outcome first, then submit. */
    void run(String fiscalId) {
        Map<String, Object> d = db.row("SELECT * FROM \"FiscalDocument\" WHERE id = ? FOR UPDATE", fiscalId);
        if (d == null || !List.of("pending", "unknown").contains(str(d.get("status")))) {
            return;
        }
        FiscalDocumentPort port = adapters.get(str(d.get("provider")));
        if (port == null) {
            fail(d, "Fiscal provider " + d.get("provider") + " is not available");
            return;
        }
        String ref = str(d.get("requestRef"));
        int attempts = ((Number) d.get("attempts")).intValue() + 1;
        db.update("UPDATE \"FiscalDocument\" SET attempts = ?, \"updatedAt\" = " + NOW + " WHERE id = ?", attempts,
                fiscalId);
        FiscalDocumentPort.Result result;
        try {
            if ("unknown".equals(d.get("status"))) {
                Optional<FiscalDocumentPort.Result> known = port.lookup(ref);
                result = known.orElseGet(() -> port.submit(new FiscalDocumentPort.Request(ref, str(d.get("operation")),
                        payload(d))));
            } else {
                result = port.submit(new FiscalDocumentPort.Request(ref, str(d.get("operation")), payload(d)));
            }
        } catch (FiscalDocumentPort.Timeout e) {
            db.update("UPDATE \"FiscalDocument\" SET status = 'unknown', \"lastError\" = ?, \"updatedAt\" = " + NOW
                    + " WHERE id = ?", e.getMessage(), fiscalId);
            return;
        } catch (RuntimeException e) {
            retryOrFail(d, attempts, e.getMessage());
            return;
        }
        if (!result.accepted()) {
            fail(d, result.error());
            return;
        }
        db.update("UPDATE \"FiscalDocument\" SET status = 'succeeded', \"externalRef\" = ?, \"fiscalNumber\" = ?,"
                + " \"responsePayload\" = ?::jsonb, \"lastError\" = NULL, \"updatedAt\" = " + NOW + " WHERE id = ?",
                result.externalRef(), result.fiscalNumber(), EntityJson.toJson(result.response() == null ? Map.of()
                        : result.response()), fiscalId);
        succeeded(d);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payload(Map<String, Object> d) {
        Object p = d.get("requestPayload");
        if (p instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        Object parsed = p == null ? null : EntityJson.parseJson(p.toString());
        if (parsed instanceof tools.jackson.databind.JsonNode node && node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            node.properties().forEach(e -> out.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asString()));
            return out;
        }
        return Map.of();
    }

    private void retryOrFail(Map<String, Object> d, int attempts, String error) {
        if (attempts >= MAX_ATTEMPTS) {
            fail(d, error);
            return;
        }
        db.update("UPDATE \"FiscalDocument\" SET \"lastError\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?", error,
                d.get("id"));
    }

    private void fail(Map<String, Object> d, String error) {
        db.update("UPDATE \"FiscalDocument\" SET status = 'failed', \"lastError\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ?", error, d.get("id"));
        String sub = str(d.get("submissionId"));
        if ("issue".equals(d.get("operation"))) {
            db.update("UPDATE \"SalesSubmission\" SET \"fiscalStatus\" = 'failed', \"updatedAt\" = " + NOW
                    + " WHERE id = ?", sub);
        }
        // A failed cancellation keeps the submission in cancellation_pending: nothing is reversed internally.
        timeline(sub, "fiscal_" + d.get("operation") + "_failed", error);
    }

    private void succeeded(Map<String, Object> d) {
        String sub = str(d.get("submissionId"));
        switch (str(d.get("operation"))) {
            case "issue" -> db.update("UPDATE \"SalesSubmission\" SET \"fiscalStatus\" = 'issued', \"updatedAt\" = " + NOW
                    + " WHERE id = ?", sub);
            case "cancel" -> db.update("UPDATE \"SalesSubmission\" SET \"fiscalStatus\" = 'cancelled', \"updatedAt\" = "
                    + NOW + " WHERE id = ?", sub);
            case "correct" -> {
                Map<String, Object> css = db.row("SELECT * FROM \"SalesSubmission\" WHERE id = ?", sub);
                Map<String, Object> changes = payload(d);
                Map<String, Object> patch = new LinkedHashMap<>();
                for (String k : IDENTITY) {
                    if (changes.containsKey(k)) {
                        patch.put(k, changes.get(k));
                        db.update("INSERT INTO \"SalesSubmissionEvent\" (id, \"organizationId\", \"submissionId\", action,"
                                + " field, \"oldValue\", \"newValue\", reason, \"userId\") VALUES (?, ?, ?, 'amendment', ?, ?,"
                                + " ?, ?, ?)", SalesDb.newId(), css.get("organizationId"), sub, k, str(css.get(k)),
                                str(changes.get(k)), "Fiscal correction " + d.get("requestRef"), d.get("userId"));
                    }
                }
                if (!patch.isEmpty()) {
                    StringBuilder set = new StringBuilder();
                    List<Object> params = new ArrayList<>();
                    patch.forEach((k, v) -> {
                        set.append(set.isEmpty() ? "" : ", ").append('"').append(k).append("\" = ?");
                        params.add(v);
                    });
                    params.add(sub);
                    db.update("UPDATE \"SalesSubmission\" SET " + set + ", \"updatedAt\" = " + NOW + " WHERE id = ?",
                            params.toArray());
                }
            }
            default -> {
                // nothing
            }
        }
        timeline(sub, "fiscal_" + d.get("operation") + "_succeeded", null);
    }

    private void timeline(String submissionId, String action, String reason) {
        db.update("INSERT INTO \"SalesSubmissionEvent\" (id, \"organizationId\", \"submissionId\", action, reason,"
                + " \"userId\") SELECT ?, \"organizationId\", id, ?, ?, ? FROM \"SalesSubmission\" WHERE id = ?",
                SalesDb.newId(), action, reason, user(), submissionId);
    }
}
