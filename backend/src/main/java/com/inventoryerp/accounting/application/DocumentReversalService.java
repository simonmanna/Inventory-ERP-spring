package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared controls of the document-level reversal workflows (owner decision 2026-11-17): sales-invoice cancel,
 * supplier-bill void, credit-note and debit-note reversal, payment void. Each workflow keeps its own GL and sub-ledger
 * logic and calls this service for the parts they share:
 * <ul>
 *   <li>a mandatory reason ({@link #requireReason});</li>
 *   <li>an optional approval gate per action, active once a tenant configures an approval workflow for its entity type
 *       ({@link #requireApproval}: {@code invoice_cancel}, {@code vendor_bill_cancel}, {@code credit_note_reverse},
 *       {@code debit_note_reverse}, {@code payment_void});</li>
 *   <li>separation of duties: with the {@link SegregationOfDutiesService#DOCUMENT_REVERSAL} pair switched on, whoever
 *       created or posted the document may not reverse it, unless an approval request for the pair was approved
 *       ({@link #assertSeparated});</li>
 *   <li>one {@code DocumentReversal} row per document (V520, unique per document): reason, who, when, the original
 *       and reversal journal entry ids and the allocations the reversal released — the duplicate-reversal backstop and
 *       the permanent document ↔ journal link ({@link #record}).</li>
 * </ul>
 */
@Service
public class DocumentReversalService {

    /** One reversal to record. */
    public record Recording(String documentType, String documentId, String documentNumber, String partnerId,
            String reason, String auditEntity, List<String> entrySourceTypes, List<String> compensationSourceTypes,
            List<Map<String, Object>> unwound) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final SegregationOfDutiesService sod;
    private final ApprovalsService approvals;
    private final TransactionTemplate newTx;

    public DocumentReversalService(JdbcClient jdbc, AuditService audit, SegregationOfDutiesService sod,
            ApprovalsService approvals, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.sod = sod;
        this.approvals = approvals;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The trimmed reason; 400 naming the action when it is missing. */
    public String requireReason(String reason, String action) {
        String r = reason == null ? "" : reason.strip();
        if (r.isEmpty()) {
            throw ApiException.badRequest("A reason is required to " + action);
        }
        if (r.length() > 2000) {
            throw ApiException.badRequest("The reason must be at most 2000 characters");
        }
        return r;
    }

    /**
     * Approval gate (the {@code invoice_cancel} pattern): when the tenant configured an approval workflow for
     * {@code approvalType}, the first call opens a request and is refused with its id; once approved, the call passes.
     * Runs in its own transaction so the request survives the refusal.
     */
    public void requireApproval(String approvalType, String documentId, String label, BigDecimal amount) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (amount != null) {
            snapshot.put("amount", JsNumbers.js(amount));
        }
        snapshot.put("document", label);
        Map<String, Object> approval = newTx.execute(s -> approvals.checkOrRequestApproval(approvalType, documentId,
                snapshot));
        if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
            throw ApiException.forbidden("Reversing " + label + " requires approval. Request ID: "
                    + approval.get("requestId") + ". Please have an authorized person approve it via the approvals"
                    + " endpoint.");
        }
    }

    /**
     * Separation of duties on the {@code document_reversal} pair: once switched on, a maker (creator / poster) of the
     * document may not reverse it (403, audited), unless an approval request for the pair on this document was approved.
     */
    public void assertSeparated(String auditEntity, String documentId, Collection<String> makers) {
        sod.assertSeparated(SegregationOfDutiesService.DOCUMENT_REVERSAL, auditEntity, documentId,
                makers == null ? List.of() : new ArrayList<>(makers));
    }

    /** The recorded reversal of a document, or {@code null}. */
    public Map<String, Object> find(String documentType, String documentId) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"DocumentReversal\" WHERE \"organizationId\" = ? AND"
                + " \"documentType\" = ? AND \"documentId\" = ?").params(TenantContext.organizationId(), documentType,
                        documentId));
    }

    /**
     * Record a completed reversal in the caller's transaction: the document's reversed entries (its own
     * {@code entrySourceTypes} with {@code sourceId} = the document, now {@code reversed}), their mirrors, and the
     * compensation entries the workflow posted ({@code compensationSourceTypes}, e.g. a stock-return cost difference).
     * Idempotent: a second call for the same document returns the first row. Audited on the document.
     */
    public Map<String, Object> record(Recording r) {
        String org = TenantContext.organizationId();
        Map<String, Object> existing = find(r.documentType(), r.documentId());
        if (existing != null) {
            return existing;
        }
        List<String> originals = r.entrySourceTypes() == null || r.entrySourceTypes().isEmpty() ? List.of()
                : jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ? AND"
                        + " \"sourceType\" = ANY(?::text[]) AND status = 'reversed' ORDER BY \"postedAt\", id")
                        .params(org, r.documentId(), r.entrySourceTypes().toArray(String[]::new)).query(String.class)
                        .list();
        List<String> reversals = new ArrayList<>(originals.isEmpty() ? List.of()
                : jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"reversalOfId\" ="
                        + " ANY(?::text[]) ORDER BY \"postedAt\", id").params(org, originals.toArray(String[]::new))
                        .query(String.class).list());
        if (r.compensationSourceTypes() != null && !r.compensationSourceTypes().isEmpty()) {
            reversals.addAll(jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceId\" = ?"
                    + " AND \"sourceType\" = ANY(?::text[]) AND status = 'posted' ORDER BY \"postedAt\", id")
                    .params(org, r.documentId(), r.compensationSourceTypes().toArray(String[]::new))
                    .query(String.class).list());
        }
        String id = UUID.randomUUID().toString();
        String unwound = EntityJson.toJson(r.unwound() == null ? List.of() : r.unwound());
        jdbc.sql("INSERT INTO \"DocumentReversal\" (id, \"organizationId\", \"documentType\", \"documentId\","
                        + " \"documentNumber\", \"partnerId\", reason, \"originalEntryIds\", \"reversalEntryIds\", unwound,"
                        + " \"reversedBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?::text[], ?::text[], ?::jsonb, ?)"
                        + " ON CONFLICT (\"organizationId\", \"documentType\", \"documentId\") DO NOTHING")
                .params(id, org, r.documentType(), r.documentId(), r.documentNumber(), r.partnerId(), r.reason(),
                        originals.toArray(String[]::new), reversals.toArray(String[]::new), unwound,
                        TenantContext.userId())
                .update();
        Map<String, Object> row = find(r.documentType(), r.documentId());
        audit.recordInTx(AuditInput.of(r.auditEntity(), r.documentId(), "cancel", Obj.of("status", "posted"),
                Obj.of("documentReversalId", row == null ? id : row.get("id"), "documentType", r.documentType(),
                        "documentNumber", r.documentNumber(), "reason", r.reason(), "originalEntryIds", originals,
                        "reversalEntryIds", reversals, "unwound", r.unwound() == null ? List.of() : r.unwound())));
        return row;
    }

    /**
     * Payments applied to a document ({@code PaymentAllocation.documentId}) that are still posted: what must be voided
     * (or, for a deposit application, reversed from its deposit) before the document can be reversed.
     */
    public List<Map<String, Object>> appliedPayments(String documentId) {
        return SqlRows.list(jdbc.sql("SELECT p.id, p.\"paymentNumber\", p.\"paymentMethod\", a.amount, (SELECT d.number"
                + " FROM \"DepositApplication\" x JOIN \"CustomerDeposit\" d ON d.id = x.\"depositId\" WHERE"
                + " x.\"paymentId\" = p.id AND x.\"reversedAt\" IS NULL LIMIT 1) AS \"depositNumber\" FROM"
                + " \"PaymentAllocation\" a JOIN \"Payment\" p ON p.id = a.\"paymentId\" WHERE a.\"documentId\" = ? AND"
                + " a.\"organizationId\" = ? AND p.status::text = 'posted' ORDER BY p.\"paymentNumber\"")
                .params(documentId, TenantContext.organizationId()));
    }

    /** "PAY-1 (40), deposit application PAY-2 from deposit DEP-1 (10)" for a refusal message. */
    public static String describePayments(List<Map<String, Object>> payments) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> p : payments) {
            String amount = JsNumbers.dec(SqlRows.dec(p, "amount"));
            parts.add(p.get("depositNumber") != null
                    ? "deposit application " + p.get("paymentNumber") + " from deposit " + p.get("depositNumber") + " ("
                            + amount + ")"
                    : p.get("paymentNumber") + " (" + amount + ")");
        }
        return String.join(", ", parts);
    }

    /** Posted debit notes raised against a document ({@code DebitNote.documentId}), by number. */
    public List<String> postedDebitNotes(String documentId) {
        return jdbc.sql("SELECT \"noteNumber\" FROM \"DebitNote\" WHERE \"documentId\" = ? AND \"organizationId\" = ? AND"
                + " status = 'posted' AND \"deletedAt\" IS NULL ORDER BY \"noteNumber\"")
                .params(documentId, TenantContext.organizationId()).query(String.class).list();
    }
}
