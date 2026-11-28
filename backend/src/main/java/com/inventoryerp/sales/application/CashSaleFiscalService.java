package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.Savepoints;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *
 * <p>ADR-S094 C3: an operation belongs to a legacy submission ({@code submissionId}) or to a cash Sale
 * ({@code salesOrderId}, reference {@code so:<id>:<operation>:<n>}); the sale's {@code fiscalStatus} and its
 * {@code SalesOrderEvent} timeline follow it the same way.
 *
 * <p>ADR-S094 C3b (§5.5): an operation sent to an external provider goes {@code pending → sent → succeeded | failed |
 * unknown}. The claim ({@code sent}) commits before the call and the result in a transaction of its own after it. So
 * no row lock is held while the provider answers, two workers never send the same row, and a worker that dies after the
 * claim leaves evidence that the provider may have the request. An outcome that is unknown (a timeout, or a claim left
 * behind) is looked up first. It is sent again, with the same reference, only once the provider's settlement time has
 * passed since the last send. A provider that can neither be asked nor ignore a repeated reference never gets the
 * request again automatically: the row goes to {@code review} and a person reconciles it ({@link #resolve}).
 */
@Service
public class CashSaleFiscalService {

    private static final Logger log = LoggerFactory.getLogger(CashSaleFiscalService.class);
    static final int MAX_ATTEMPTS = 5;
    static final Set<String> IDENTITY = Set.of("customerName", "customerTin", "customerAddress");
    /** A claim older than this was left by a worker that stopped; the next attempt takes it over, lookup first. */
    static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(2);
    /** An operation still not confirmed this long after it was asked for raises an alert, once. */
    static final Duration ALERT_AFTER = Duration.ofMinutes(30);
    /** Statuses an attempt may pick up ({@code sent} only once its claim has expired). */
    private static final String OPEN = "('pending', 'sent', 'unknown')";
    /** Statuses of an operation that is not finished (one per kind and owner). */
    private static final String LIVE = "('pending', 'sent', 'unknown', 'review')";

    private final SalesDb db;
    private final Map<String, FiscalDocumentPort> adapters = new LinkedHashMap<>();
    private final TransactionTemplate newTx;
    private final TransactionTemplate retrySafe;
    private final TransactionTemplate joined;
    private final TransactionTemplate readOnly;
    private final NotificationsService notifications;

    public CashSaleFiscalService(SalesDb db, List<FiscalDocumentPort> ports, PlatformTransactionManager txm,
            NotificationsService notifications) {
        this.db = db;
        this.notifications = notifications;
        ports.forEach(p -> adapters.put(p.provider(), p));
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Outbox bookkeeping holds no money and is safe to repeat (stable references, conditional claims): it never keeps
        // the idempotency key of the request it runs under (BusinessOutcome.RETRY_SAFE_TX).
        this.retrySafe = new TransactionTemplate(txm);
        this.retrySafe.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.retrySafe.setName(BusinessOutcome.RETRY_SAFE_TX);
        this.joined = new TransactionTemplate(txm);
        this.joined.setName(BusinessOutcome.RETRY_SAFE_TX);
        this.readOnly = new TransactionTemplate(txm);
        this.readOnly.setReadOnly(true);
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
        return queue(new Target(false, str(css.get("id"))), str(css.get("profileId")), "css:", operation, invoiceId,
                payload, reason);
    }

    /**
     * ADR-S094 C3: queue {@code operation} for a cash Sale in the current transaction (the outbox row commits with
     * the sale). The branch profile names the provider; {@code none} completes at once without an external call.
     */
    Map<String, Object> requestForSale(Map<String, Object> sale, String operation, String invoiceId,
            Map<String, Object> payload, String reason) {
        return queue(new Target(true, str(sale.get("id"))), str(sale.get("profileId")), "so:", operation, invoiceId,
                payload, reason);
    }

    private Map<String, Object> queue(Target t, String profileId, String refPrefix, String operation, String invoiceId,
            Map<String, Object> payload, String reason) {
        Map<String, Object> profile = profileId == null ? null : db.row("SELECT \"fiscalProvider\" FROM"
                + " \"BranchSalesProfile\" WHERE id = ? AND \"organizationId\" = ?", profileId, org());
        String provider = profile == null ? "none" : str(profile.get("fiscalProvider"));
        long n = db.count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"" + t.column() + "\" = ? AND operation = ?",
                List.of(t.id(), operation));
        Map<String, Object> doc = db.insert("FiscalDocument", Obj.of(t.column(), t.id(), "invoiceId", invoiceId,
                "provider", provider, "operation", operation, "requestRef", refPrefix + t.id() + ":" + operation + ":"
                        + (n + 1), "requestPayload", null, "reason", reason, "userId", user()));
        db.update("UPDATE \"FiscalDocument\" SET \"requestPayload\" = ?::jsonb WHERE id = ?"
                + " AND \"organizationId\" = ?", EntityJson.toJson(
                payload == null ? Map.of() : payload), doc.get("id"), org());
        String status = switch (operation) {
            case "issue" -> "pending";
            case "cancel" -> "cancellation_pending";
            default -> null;
        };
        if (status != null) {
            fiscalStatus(t, status);
        }
        timeline(t, "fiscal_" + operation + "_requested", reason);
        FiscalDocumentPort port = adapters.get(provider);
        if (port != null && port.local()) {
            run(str(doc.get("id")));
        }
        return db.row("SELECT * FROM \"FiscalDocument\" WHERE id = ? AND \"organizationId\" = ?", doc.get("id"), org());
    }

    /** The document a fiscal operation belongs to: a legacy submission or a cash Sale (ADR-S094 C3). */
    private record Target(boolean sale, String id) {

        String table() {
            return sale ? "SalesOrder" : "SalesSubmission";
        }

        String column() {
            return sale ? "salesOrderId" : "submissionId";
        }
    }

    private static Target target(Map<String, Object> d) {
        return d.get("salesOrderId") != null ? new Target(true, str(d.get("salesOrderId")))
                : new Target(false, str(d.get("submissionId")));
    }

    private void fiscalStatus(Target t, String status) {
        db.update("UPDATE \"" + t.table() + "\" SET \"fiscalStatus\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                + " AND \"organizationId\" = ?", status, t.id(), org());
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
                            + " 'cancel' AND status IN " + LIVE, List.of(submissionId)) == 0) {
                        // the last cancellation failed for good: ask again (new attempt, new reference)
                        request(css, "cancel", str(css.get("currentInvoiceId")), Obj.of("reason", reason), reason);
                    }
                });
                processOpen(submissionId);
                if ("cancelled".equals(str(db.row("SELECT \"fiscalStatus\" FROM \"SalesSubmission\" WHERE id = ?"
                        + " AND \"organizationId\" = ?",
                        submissionId, org()).get("fiscalStatus")))) {
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
        String after = str(db.row("SELECT \"fiscalStatus\" FROM \"SalesSubmission\" WHERE id = ?"
                + " AND \"organizationId\" = ?", submissionId, org())
                .get("fiscalStatus"));
        if (!"cancelled".equals(after)) {
            throw ApiException.conflict("Fiscal cancellation requested; the reversal runs once it succeeds");
        }
    }

    /**
     * ADR-S094 C3b (§7.4 phase 1): the fiscal gate before a cash Sale's reversal. Without an issued fiscal document it
     * passes. With one, the cancellation is requested in a transaction of its own and sent at once. The gate passes
     * when the provider has cancelled the document; otherwise 409 (the sale shows the cancellation pending), and the
     * reversal is asked for again once it has. A cancellation that failed for good is asked for again, under a new
     * reference. While the document is still being issued, or its outcome is unknown, the reversal is refused. Call it
     * before the reversal transaction, never inside one.
     */
    void gateSaleReversal(String orderId, String reason) {
        Map<String, Object> o = db.row("SELECT \"fiscalStatus\" FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\""
                + " = ?", orderId, org());
        String status = o == null ? "none" : str(o.get("fiscalStatus"));
        switch (status) {
            case "none", "failed", "cancelled" -> {
                return;
            }
            case "pending" -> throw ApiException.conflict("The fiscal document is still being issued; retry once it"
                    + " is issued or failed");
            default -> {
                // issued, cancellation_pending
            }
        }
        retrySafe.executeWithoutResult(s -> {
            Map<String, Object> sale = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? FOR"
                    + " UPDATE", orderId, org());
            if (!Set.of("issued", "cancellation_pending").contains(str(sale.get("fiscalStatus")))) {
                return;
            }
            if (db.count("SELECT count(*) FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND \"organizationId\" = ? AND"
                    + " operation = 'cancel' AND status IN " + LIVE, List.of(orderId, org())) == 0) {
                // the first request, or the last cancellation failed for good: ask (again) under a new reference
                Map<String, Object> issued = db.row("SELECT \"invoiceId\" FROM \"FiscalDocument\" WHERE \"salesOrderId\" ="
                        + " ? AND \"organizationId\" = ? AND operation = 'issue' AND status = 'succeeded' ORDER BY"
                        + " \"createdAt\" DESC LIMIT 1", orderId, org());
                requestForSale(sale, "cancel", issued == null ? null : str(issued.get("invoiceId")), Obj.of("reason",
                        reason), reason);
            }
        });
        for (String id : openOf("salesOrderId", orderId)) {
            run(id);
        }
        String after = str(db.row("SELECT \"fiscalStatus\" FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?",
                orderId, org()).get("fiscalStatus"));
        if ("cancelled".equals(after)) {
            return;
        }
        throw ApiException.conflict("cancellation_pending".equals(status)
                ? "Fiscal cancellation is pending; the reversal runs once it succeeds"
                : "Fiscal cancellation requested; the reversal runs once it succeeds");
    }

    /** 409 when an identity field changes while a fiscal document is issued (correct it through the fiscal route). */
    void assertIdentityEditable(Map<String, Object> css, Map<String, Object> changes) {
        if (List.of("issued", "pending", "cancellation_pending").contains(str(css.get("fiscalStatus")))
                && changes.keySet().stream().anyMatch(IDENTITY::contains)) {
            throw ApiException.conflict("Fiscal document issued — correct it first");
        }
    }

    // ------------------------------------------------------------------ processing

    /** Process the open operations of one submission (retry route), each attempt in transactions of its own. */
    public List<Map<String, Object>> retry(String submissionId) {
        processOpen(submissionId);
        return db.rows("SELECT * FROM \"FiscalDocument\" WHERE \"submissionId\" = ? AND \"organizationId\" = ? ORDER BY"
                + " \"createdAt\"", submissionId, org());
    }

    /**
     * ADR-S094 C3: send the open operations of one cash Sale again (retry route). Each keeps its request reference and
     * an unknown outcome is looked up first, so a retry never creates a second fiscal document.
     */
    public List<Map<String, Object>> retryForSale(String orderId) {
        for (String id : openOf("salesOrderId", orderId)) {
            run(id);
        }
        return forSale(orderId);
    }

    /** ADR-S094 C3: the fiscal operations of a cash Sale, oldest first. */
    public List<Map<String, Object>> forSale(String orderId) {
        return db.rows("SELECT * FROM \"FiscalDocument\" WHERE \"salesOrderId\" = ? AND \"organizationId\" = ? ORDER BY"
                + " \"createdAt\", id", orderId, org());
    }

    void processOpen(String submissionId) {
        for (String id : openOf("submissionId", submissionId)) {
            run(id);
        }
    }

    private List<String> openOf(String column, String ownerId) {
        return readOnly.execute(s -> db.rows("SELECT id FROM \"FiscalDocument\" WHERE \"" + column + "\" = ? AND"
                + " \"organizationId\" = ? AND status IN " + OPEN + " ORDER BY \"createdAt\"", ownerId, org())).stream()
                .map(r -> str(r.get("id"))).toList();
    }

    /** Open operations of the current organization (job), each attempt in transactions of its own. */
    public int processAll() {
        List<String> ids = readOnly.execute(s -> db.rows("SELECT id FROM \"FiscalDocument\" WHERE \"organizationId\" = ?"
                + " AND status IN " + OPEN + " ORDER BY \"createdAt\" LIMIT 200", org())).stream()
                .map(r -> str(r.get("id"))).toList();
        int n = 0;
        for (String id : ids) {
            try {
                run(id);
                n++;
            } catch (RuntimeException e) {
                log.warn("Fiscal operation {} failed: {}", id, e.toString());
            }
        }
        return n;
    }

    /**
     * One attempt of an open operation. A local adapter (no external call) completes in the caller's transaction. Any
     * other runs the outbox protocol (§5.5): claim, call the provider outside any transaction, record.
     */
    void run(String fiscalId) {
        Map<String, Object> d = db.row("SELECT provider FROM \"FiscalDocument\" WHERE id = ? AND \"organizationId\" = ?",
                fiscalId, org());
        if (d == null) {
            return;
        }
        FiscalDocumentPort port = adapters.get(str(d.get("provider")));
        if (port == null || port.local()) {
            joined.executeWithoutResult(s -> runLocal(fiscalId, port));
            return;
        }
        Claim claim = retrySafe.execute(s -> claim(fiscalId, port));
        if (claim == null) {
            return;
        }
        Attempt attempt = call(claim);
        retrySafe.executeWithoutResult(s -> record(claim, attempt));
    }

    /** A local adapter answers at once and never times out. */
    private void runLocal(String fiscalId, FiscalDocumentPort port) {
        Map<String, Object> d = db.row("SELECT * FROM \"FiscalDocument\" WHERE id = ? AND \"organizationId\" = ? FOR"
                + " UPDATE", fiscalId, org());
        if (d == null || !List.of("pending", "unknown").contains(str(d.get("status")))) {
            return;
        }
        if (port == null) {
            fail(d, "Fiscal provider " + d.get("provider") + " is not available");
            return;
        }
        db.update("UPDATE \"FiscalDocument\" SET attempts = attempts + 1, submissions = submissions + 1, \"submittedAt\" = "
                + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", fiscalId, org());
        accepted(d, port.submit(new FiscalDocumentPort.Request(str(d.get("requestRef")), str(d.get("operation")),
                payload(d))));
    }

    /**
     * Claim an open operation (its own transaction): status {@code sent}, attempt counted. A row claimed by another
     * worker is left alone until its claim expires. An operation whose outcome is unknown is looked up first when the
     * provider can be asked; one sent straight away has its send recorded here, before the call. A provider that can
     * neither be asked nor dedupe gets nothing: the row goes to review.
     */
    private Claim claim(String fiscalId, FiscalDocumentPort port) {
        Map<String, Object> d = db.row("SELECT *, \"claimedAt\" < " + NOW + " - make_interval(secs => ?) AS expired,"
                + " EXTRACT(EPOCH FROM " + NOW + " - \"submittedAt\") AS \"sinceSubmit\" FROM \"FiscalDocument\" WHERE"
                + " id = ? AND \"organizationId\" = ? FOR UPDATE", (double) CLAIM_TIMEOUT.toSeconds(), fiscalId, org());
        if (d == null) {
            return null;
        }
        String status = str(d.get("status"));
        boolean abandoned = "sent".equals(status) && Boolean.TRUE.equals(d.get("expired"));
        if (!"pending".equals(status) && !"unknown".equals(status) && !abandoned) {
            return null;
        }
        if (abandoned) {
            log.warn("Fiscal operation {} ({}): the worker of attempt {} stopped after its claim; looking it up first",
                    fiscalId, d.get("requestRef"), d.get("attempts"));
        }
        // after a timeout, or a claim whose worker never recorded what happened, the provider may have the request
        boolean unknownOutcome = !"pending".equals(status);
        if (unknownOutcome && !port.supportsLookup() && !port.dedupes()) {
            review(d, abandoned ? "A worker stopped while sending this request and the provider cannot be asked whether"
                    + " it received it: reconcile it with the provider" : "The provider cannot be asked whether it"
                    + " received this request: reconcile it with the provider");
            return null;
        }
        boolean lookupFirst = unknownOutcome && port.supportsLookup();
        int attempt = ((Number) d.get("attempts")).intValue() + 1;
        db.update("UPDATE \"FiscalDocument\" SET status = 'sent', \"claimedAt\" = " + NOW + ", attempts = ?,"
                + (lookupFirst ? "" : " submissions = submissions + 1, \"submittedAt\" = " + NOW + ",")
                + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", attempt, fiscalId, org());
        Object since = d.get("sinceSubmit");
        Duration sinceSubmit = since == null ? null : Duration.ofMillis((long) (Double.parseDouble(since.toString())
                * 1000));
        return new Claim(fiscalId, str(d.get("requestRef")), str(d.get("operation")), payload(d), port, lookupFirst,
                sinceSubmit, attempt);
    }

    /** A claimed attempt. {@code sinceSubmit}: time since the last send before this attempt (null: never sent). */
    private record Claim(String id, String ref, String operation, Map<String, Object> payload, FiscalDocumentPort port,
            boolean lookupFirst, Duration sinceSubmit, int attempt) {}

    private enum Kind { RESULT, UNSETTLED, TIMEOUT, SUBMIT_ERROR, LOOKUP_ERROR, LOST }

    /** What one call to the provider came to. */
    private record Attempt(Kind kind, FiscalDocumentPort.Result result, String error) {}

    /** The call, outside any transaction: lookup first when the outcome is unknown, else (or then) submit. */
    private Attempt call(Claim c) {
        if (c.lookupFirst()) {
            try {
                Optional<FiscalDocumentPort.Result> known = c.port().lookup(c.ref());
                if (known.isPresent()) {
                    return new Attempt(Kind.RESULT, known.get(), null);
                }
            } catch (RuntimeException e) {
                return new Attempt(Kind.LOOKUP_ERROR, null, e.getMessage());
            }
            if (c.sinceSubmit() != null && c.sinceSubmit().compareTo(c.port().settlement()) < 0) {
                return new Attempt(Kind.UNSETTLED, null, null);
            }
            // not received, and the settlement time has passed: send it again under the same reference, recording the
            // send before it happens
            Integer marked = retrySafe.execute(s -> db.update("UPDATE \"FiscalDocument\" SET submissions = submissions + 1,"
                    + " \"submittedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND status = 'sent' AND"
                    + " attempts = ? AND \"organizationId\" = ?", c.id(), c.attempt(), org()));
            if (marked == null || marked == 0) {
                return new Attempt(Kind.LOST, null, null);
            }
        }
        try {
            return new Attempt(Kind.RESULT, c.port().submit(new FiscalDocumentPort.Request(c.ref(), c.operation(),
                    c.payload())), null);
        } catch (FiscalDocumentPort.Timeout e) {
            return new Attempt(Kind.TIMEOUT, null, e.getMessage());
        } catch (RuntimeException e) {
            return new Attempt(Kind.SUBMIT_ERROR, null, e.getMessage());
        }
    }

    /** Record the attempt (its own transaction), only while the claim is still this attempt's. */
    private void record(Claim c, Attempt a) {
        Map<String, Object> d = db.row("SELECT * FROM \"FiscalDocument\" WHERE id = ? AND \"organizationId\" = ? AND status ="
                + " 'sent' AND attempts = ? FOR UPDATE", c.id(), org(), c.attempt());
        if (d == null || a.kind() == Kind.LOST) {
            log.warn("Fiscal operation {}: attempt {} lost its claim; the next attempt settles it", c.id(), c.attempt());
            return;
        }
        switch (a.kind()) {
            case RESULT -> accepted(d, a.result());
            case UNSETTLED -> unknown(d, "Not found at the provider yet; looked up again after its settlement time");
            case TIMEOUT, LOOKUP_ERROR -> unknown(d, a.error());
            case SUBMIT_ERROR -> {
                // the adapter's answer for a request the provider did not accept: it may be sent again
                if (c.attempt() >= MAX_ATTEMPTS) {
                    fail(d, a.error());
                } else {
                    db.update("UPDATE \"FiscalDocument\" SET status = 'pending', \"claimedAt\" = NULL, \"lastError\" = ?,"
                            + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", a.error(), c.id(),
                            org());
                }
            }
            default -> {
                // LOST: handled above
            }
        }
    }

    private void accepted(Map<String, Object> d, FiscalDocumentPort.Result result) {
        if (!result.accepted()) {
            fail(d, result.error());
            return;
        }
        db.update("UPDATE \"FiscalDocument\" SET status = 'succeeded', \"claimedAt\" = NULL, \"externalRef\" = ?,"
                + " \"fiscalNumber\" = ?, \"responsePayload\" = ?::jsonb, \"lastError\" = NULL, \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?", result.externalRef(), result.fiscalNumber(),
                EntityJson.toJson(result.response() == null ? Map.of() : result.response()), d.get("id"), org());
        succeeded(d);
    }

    private void unknown(Map<String, Object> d, String error) {
        db.update("UPDATE \"FiscalDocument\" SET status = 'unknown', \"claimedAt\" = NULL, \"lastError\" = ?, \"updatedAt\""
                + " = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", error, d.get("id"), org());
    }

    private void review(Map<String, Object> d, String reason) {
        db.update("UPDATE \"FiscalDocument\" SET status = 'review', \"claimedAt\" = NULL, \"lastError\" = ?, \"updatedAt\" ="
                + " " + NOW + " WHERE id = ? AND \"organizationId\" = ?", reason, d.get("id"), org());
        timeline(target(d), "fiscal_" + d.get("operation") + "_review", reason);
    }

    // ------------------------------------------------------------------ manual reconciliation

    /**
     * ADR-S094 C3b (§5.5): settle an operation whose outcome is unknown by hand, after checking with the provider.
     * {@code resolution}: {@code issued} (the provider has it: its {@code fiscalNumber} for an issue, optional
     * {@code externalRef}) or {@code not_received} (it never arrived: sent again under the same reference). A
     * {@code note} is required. Only for operations in review or unknown.
     */
    public Map<String, Object> resolve(String orderId, String fiscalId, Map<String, Object> in) {
        for (String k : in.keySet()) {
            if (!Set.of("resolution", "fiscalNumber", "externalRef", "note").contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
        String resolution = str(in.get("resolution"));
        if (!Set.of("issued", "not_received").contains(Objects.requireNonNullElse(resolution, ""))) {
            throw ApiException.badRequest("resolution must be one of: issued, not_received");
        }
        String note = str(in.get("note"));
        if (blank(note)) {
            throw ApiException.badRequest("note should not be empty");
        }
        Boolean resend = retrySafe.execute(s -> {
            Map<String, Object> d = db.row("SELECT * FROM \"FiscalDocument\" WHERE id = ? AND \"salesOrderId\" = ? AND"
                    + " \"organizationId\" = ? FOR UPDATE", fiscalId, orderId, org());
            if (d == null) {
                throw ApiException.notFound("Fiscal operation not found");
            }
            if (!Set.of("review", "unknown").contains(str(d.get("status")))) {
                throw ApiException.badRequest("Only an operation whose outcome is unknown can be reconciled by hand");
            }
            String number = str(in.get("fiscalNumber"));
            if ("issued".equals(resolution) && "issue".equals(d.get("operation")) && blank(number)) {
                throw ApiException.badRequest("fiscalNumber should not be empty: give the number the provider issued");
            }
            db.update("UPDATE \"FiscalDocument\" SET \"resolvedById\" = ?, \"resolvedAt\" = " + NOW + ", resolution = ?,"
                    + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", user(), resolution + ": "
                            + note.trim(), fiscalId, org());
            timeline(target(d), "fiscal_" + d.get("operation") + "_resolved", resolution + ": " + note.trim());
            if ("issued".equals(resolution)) {
                accepted(d, FiscalDocumentPort.Result.ok(blank(str(in.get("externalRef"))) ? null
                        : str(in.get("externalRef")).trim(), blank(number) ? null : number.trim()));
                return false;
            }
            db.update("UPDATE \"FiscalDocument\" SET status = 'pending', \"lastError\" = NULL, \"updatedAt\" = " + NOW
                    + " WHERE id = ? AND \"organizationId\" = ?", fiscalId, org());
            return true;
        });
        if (Boolean.TRUE.equals(resend)) {
            run(fiscalId);
        }
        return db.row("SELECT * FROM \"FiscalDocument\" WHERE id = ? AND \"organizationId\" = ?", fiscalId, org());
    }

    /**
     * ADR-S094 C3b (§5.5): alert, once, on operations still not confirmed {@link #ALERT_AFTER} after they were asked
     * for (unknown, in review, or claimed by a worker that stopped): a timeline line and a notification to whoever
     * asked. Returns how many were raised.
     */
    @Transactional
    public int alertStuck() {
        int n = 0;
        for (Map<String, Object> d : db.rows("SELECT * FROM \"FiscalDocument\" WHERE \"organizationId\" = ? AND"
                + " \"alertedAt\" IS NULL AND \"createdAt\" < " + NOW + " - make_interval(secs => ?) AND (status IN"
                + " ('unknown', 'review') OR (status = 'sent' AND \"claimedAt\" < " + NOW + " - make_interval(secs => ?)))"
                + " FOR UPDATE SKIP LOCKED", org(), (double) ALERT_AFTER.toSeconds(), (double) CLAIM_TIMEOUT.toSeconds())) {
            db.update("UPDATE \"FiscalDocument\" SET \"alertedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?",
                    d.get("id"), org());
            Target t = target(d);
            String what = "Fiscal " + d.get("operation") + " " + d.get("requestRef") + " is not confirmed";
            timeline(t, "fiscal_" + d.get("operation") + "_stuck", what);
            String userId = str(d.get("userId"));
            if (userId != null) {
                try {
                    // under a savepoint: a failed notification must not abort the alert's transaction
                    Savepoints.run(db.jdbc(), () -> notifications.send(new NotificationsService.SendInput(org(), userId,
                            "in_app", "sales", what, "Its outcome at the fiscal authority is " + d.get("status") + ": "
                                    + Objects.requireNonNullElse(str(d.get("lastError")), "no answer yet"),
                            Obj.of("link", (t.sale() ? "/sales/orders/" : "/sales/cash-sales/") + t.id()))));
                } catch (RuntimeException e) {
                    // a notification never blocks the outbox: the timeline line above records the alert
                    log.warn("Fiscal alert notification for {} failed: {}", d.get("id"), e.toString());
                }
            }
            n++;
        }
        return n;
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

    private void fail(Map<String, Object> d, String error) {
        db.update("UPDATE \"FiscalDocument\" SET status = 'failed', \"claimedAt\" = NULL, \"lastError\" = ?, \"updatedAt\" = "
                + NOW + " WHERE id = ? AND \"organizationId\" = ?", error, d.get("id"), org());
        Target t = target(d);
        if ("issue".equals(d.get("operation"))) {
            fiscalStatus(t, "failed");
        }
        // A failed cancellation keeps the document in cancellation_pending: nothing is reversed internally.
        timeline(t, "fiscal_" + d.get("operation") + "_failed", error);
    }

    private void succeeded(Map<String, Object> d) {
        Target t = target(d);
        String sub = t.id();
        switch (str(d.get("operation"))) {
            case "issue" -> fiscalStatus(t, "issued");
            case "cancel" -> fiscalStatus(t, "cancelled");
            case "correct" -> {
                if (t.sale()) {
                    break; // identity corrections are a legacy submission route
                }
                Map<String, Object> css = db.row("SELECT * FROM \"SalesSubmission\" WHERE id = ?"
                        + " AND \"organizationId\" = ?", sub, org());
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
                    params.add(org());
                    db.update("UPDATE \"SalesSubmission\" SET " + set + ", \"updatedAt\" = " + NOW + " WHERE id = ?"
                            + " AND \"organizationId\" = ?", params.toArray());
                }
            }
            default -> {
                // nothing
            }
        }
        timeline(t, "fiscal_" + d.get("operation") + "_succeeded", null);
    }

    private void timeline(Target t, String action, String reason) {
        if (t.sale()) {
            db.update("INSERT INTO \"SalesOrderEvent\" (id, \"organizationId\", \"orderId\", action, note, \"userId\")"
                    + " SELECT ?, \"organizationId\", id, ?, ?, ? FROM \"SalesOrder\" WHERE id = ?", SalesDb.newId(), action,
                    reason, user(), t.id());
            return;
        }
        db.update("INSERT INTO \"SalesSubmissionEvent\" (id, \"organizationId\", \"submissionId\", action, reason,"
                + " \"userId\") SELECT ?, \"organizationId\", id, ?, ?, ? FROM \"SalesSubmission\" WHERE id = ?",
                SalesDb.newId(), action, reason, user(), t.id());
    }
}
