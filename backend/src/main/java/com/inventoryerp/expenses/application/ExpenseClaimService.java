package com.inventoryerp.expenses.application;

import com.inventoryerp.expenses.application.ExpensesService.CreateInput;
import com.inventoryerp.expenses.application.ExpensesService.Details;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Employee expense claims (EX-3, ADR-S052). A claim groups employee-paid expenses (one {@code Expense} per line):
 * draft → submitted (approval engine, entity type {@code expense_claim}; else a manual approval by someone other than
 * the claimant) → approved: every line accrues Dr expense / Cr employee reimbursement payable, and a linked, disbursed
 * advance is settled first (Dr reimbursement payable / Cr advance) → paid / partially paid by reimbursing the rest.
 * An approver can return a submitted claim for correction or reject it.
 */
@Service
public class ExpenseClaimService {

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";
    static final String ENTITY = "expense_claim";

    /** One claim line. */
    public record LineInput(String title, BigDecimal amount, String expenseDate, String categoryId, String description,
            String supplierId, Details details) {}

    /** Create / update body; {@code lines} on update replaces every line. */
    public record ClaimInput(String title, String claimantId, String advanceId, String notes, List<LineInput> lines) {}

    /** Reimbursement body. */
    public record PayInput(String paymentMethod, String accountId, String reference, BigDecimal amount) {}

    private final JdbcClient jdbc;
    private final ExpensesService expenses;
    private final ExpenseAdvanceService advances;
    private final ExpenseControlService controls;
    private final ApprovalsService approvals;
    private final SegregationOfDutiesService sod;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate newTx;

    public ExpenseClaimService(JdbcClient jdbc, ExpensesService expenses, ExpenseAdvanceService advances,
            ExpenseControlService controls, ApprovalsService approvals, SegregationOfDutiesService sod,
            SequenceService sequence, AuditService audit, EventBus events, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.expenses = expenses;
        this.advances = advances;
        this.controls = controls;
        this.approvals = approvals;
        this.sod = sod;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String actor() {
        String id = TenantContext.userId();
        if (id == null) {
            throw ApiException.forbidden("An authenticated user is required");
        }
        return id;
    }

    // ------------------------------------------------------------------ reads

    /** Claims, newest first; {@code mine=true} limits to the caller's own claims. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String claimantId, boolean mine) {
        StringBuilder where = new StringBuilder(" WHERE \"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        String who = mine ? actor() : claimantId;
        if (who != null && !who.isBlank()) {
            where.append(" AND \"claimantId\" = ?");
            params.add(who);
        }
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ExpenseClaim\"" + where + " ORDER BY \"createdAt\" DESC LIMIT 500")
                .params(params)).stream().map(this::shape).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> c = shape(require(id, false));
        c.put("lines", expenses.claimLines(id));
        c.put("advance", c.get("advanceId") == null ? null : advances.findOne((String) c.get("advanceId")));
        c.put("approvalRequest", approvals.latestRequest(ENTITY, id));
        return c;
    }

    private Map<String, Object> shape(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        for (String k : List.of("totalAmount", "advanceApplied", "amountPaid")) {
            m.put(k, JsNumbers.js(Money.dec(r.get(k))));
        }
        m.put("amountDue", JsNumbers.js(due(r)));
        m.put("claimant", SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\", email FROM \"User\" WHERE id = ?")
                .param(r.get("claimantId"))));
        return m;
    }

    static BigDecimal due(Map<String, Object> c) {
        return Money.dec(c.get("totalAmount")).subtract(Money.dec(c.get("advanceApplied")))
                .subtract(Money.dec(c.get("amountPaid")));
    }

    private Map<String, Object> require(String id, boolean lock) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"ExpenseClaim\" WHERE id = ? AND \"organizationId\" = ?"
                + (lock ? " FOR UPDATE" : "")).params(id, org()));
        if (c == null) {
            throw ApiException.notFound("Expense claim not found");
        }
        return c;
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public Map<String, Object> create(ClaimInput in) {
        String actorId = actor();
        if (in.title() == null || in.title().isBlank()) {
            throw ApiException.badRequest("A title is required");
        }
        String claimant = in.claimantId() == null || in.claimantId().isBlank() ? actorId : in.claimantId();
        if (!claimant.equals(actorId) && !TenantContext.has("expense:approve")) {
            throw ApiException.forbidden("Raising a claim for someone else requires expense:approve");
        }
        String id = UUID.randomUUID().toString();
        String number = sequence.next("expense_claim", "CLM-", 5);
        jdbc.sql("INSERT INTO \"ExpenseClaim\" (id, \"organizationId\", \"claimNumber\", \"claimantId\", title, notes,"
                        + " \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), number, claimant, in.title().trim(), in.notes(), actorId).update();
        linkAdvance(id, claimant, in.advanceId());
        if (in.lines() != null) {
            for (LineInput l : in.lines()) {
                addLine(id, claimant, l);
            }
        }
        recomputeTotal(id);
        audit.recordInTx(AuditInput.of("ExpenseClaim", id, "create", null, Obj.of("claimNumber", number)));
        return findOne(id);
    }

    /** Edit a draft / returned claim; {@code lines}, when sent, replaces every line. */
    @Transactional
    public Map<String, Object> update(String id, ClaimInput in) {
        Map<String, Object> c = require(id, true);
        editable(c);
        if (in.title() != null && !in.title().isBlank()) {
            jdbc.sql("UPDATE \"ExpenseClaim\" SET title = ? WHERE id = ?").params(in.title().trim(), id).update();
        }
        if (in.notes() != null) {
            jdbc.sql("UPDATE \"ExpenseClaim\" SET notes = ? WHERE id = ?").params(in.notes(), id).update();
        }
        if (in.advanceId() != null) {
            linkAdvance(id, (String) c.get("claimantId"), in.advanceId());
        }
        if (in.lines() != null) {
            for (Map<String, Object> l : expenses.claimLines(id)) {
                expenses.deleteClaimLine((String) l.get("id"));
            }
            for (LineInput l : in.lines()) {
                addLine(id, (String) c.get("claimantId"), l);
            }
        }
        recomputeTotal(id);
        audit.recordInTx(AuditInput.of("ExpenseClaim", id, "update"));
        return findOne(id);
    }

    private void addLine(String claimId, String claimant, LineInput l) {
        if (l.title() == null || l.title().isBlank() || l.amount() == null || l.amount().signum() <= 0
                || l.expenseDate() == null) {
            throw ApiException.badRequest("Each claim line needs a title, a positive amount and a date");
        }
        expenses.createClaimLine(new CreateInput(l.title(), l.description(), l.amount(), l.categoryId(), l.expenseDate(),
                null, l.supplierId(), "CREDIT", null, null, null), l.details(), claimId, claimant);
    }

    private void linkAdvance(String claimId, String claimant, String advanceId) {
        if (advanceId == null || advanceId.isBlank()) {
            jdbc.sql("UPDATE \"ExpenseClaim\" SET \"advanceId\" = NULL WHERE id = ?").param(claimId).update();
            return;
        }
        Map<String, Object> a = advances.require(advanceId, false);
        if (!claimant.equals(a.get("employeeId"))) {
            throw ApiException.badRequest("The advance belongs to another employee");
        }
        if (!"disbursed".equals(a.get("status"))) {
            throw ApiException.badRequest("Only a disbursed advance can be settled by a claim");
        }
        jdbc.sql("UPDATE \"ExpenseClaim\" SET \"advanceId\" = ? WHERE id = ?").params(advanceId, claimId).update();
    }

    private void recomputeTotal(String id) {
        jdbc.sql("UPDATE \"ExpenseClaim\" SET \"totalAmount\" = (SELECT COALESCE(SUM(amount), 0) FROM \"Expense\" WHERE"
                + " \"claimId\" = ? AND \"deletedAt\" IS NULL), \"updatedAt\" = " + NOW + " WHERE id = ?").params(id, id)
                .update();
    }

    private static void editable(Map<String, Object> c) {
        if (!List.of("draft", "returned").contains(c.get("status"))) {
            throw ApiException.badRequest("Only a draft or returned claim can be changed");
        }
    }

    /** Submit for approval: needs at least one line; receipts when the settings ask; spending limits per line. */
    @Transactional
    public Map<String, Object> submit(String id) {
        Map<String, Object> c = require(id, true);
        editable(c);
        String actorId = actor();
        if (!actorId.equals(c.get("claimantId")) && !actorId.equals(c.get("createdById"))) {
            throw ApiException.forbidden("Only the claimant can submit a claim");
        }
        List<Map<String, Object>> lines = expenses.claimLines(id);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Add at least one line before submitting the claim");
        }
        boolean receipts = controls.setting("claimReceiptRequired");
        for (Map<String, Object> l : lines) {
            if (receipts && (l.get("receiptNumber") == null || String.valueOf(l.get("receiptNumber")).isBlank())) {
                throw ApiException.badRequest("Every claim line needs a receipt number (" + l.get("title") + ")");
            }
            expenses.checkClaimLineLimits((String) l.get("id"));
        }
        expenses.setClaimLinesStatus(id, "DRAFT", null);
        jdbc.sql("UPDATE \"ExpenseClaim\" SET status = 'submitted', \"submittedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                + " WHERE id = ?").param(id).update();
        Map<String, Object> fresh = require(id, false);
        Map<String, Object> snapshot = Obj.of("amount", JsNumbers.js(Money.dec(fresh.get("totalAmount"))), "claimantId",
                fresh.get("claimantId"), "title", fresh.get("title"), "branchId", lines.getFirst().get("branchId"),
                "departmentId", lines.getFirst().get("departmentId"));
        Map<String, Object> gate = newTx.execute(s -> approvals.checkOrRequestApprovalOfAmount(ENTITY, id, snapshot));
        audit.recordInTx(AuditInput.of("ExpenseClaim", id, "update", null, Obj.of("submitted", true)));
        publish("expense.claim_submitted", Obj.of("claimId", id));
        if (gate != null && !Boolean.TRUE.equals(gate.get("needsApproval"))) {
            finalizeApproval(id, null, null);
        }
        return findOne(id);
    }

    private String pendingRequest(String id) {
        return jdbc.sql("SELECT id FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\" = ? AND"
                + " \"entityId\" = ? AND status = 'pending' LIMIT 1").params(org(), ENTITY, id).query(String.class)
                .optional().orElse(null);
    }

    /** Approve a submitted claim (decides the pending request when one exists). Not by the claimant or raiser. */
    @Transactional
    public Map<String, Object> approve(String id, String notes) {
        Map<String, Object> c = require(id, true);
        if (!"submitted".equals(c.get("status"))) {
            throw ApiException.badRequest("Only a submitted claim can be approved");
        }
        String actorId = actor();
        if (actorId.equals(c.get("claimantId")) || actorId.equals(c.get("createdById"))) {
            throw ApiException.forbidden("The claimant cannot approve their own claim");
        }
        String pending = pendingRequest(id);
        if (pending != null) {
            approvals.decide(pending, "approved", notes);
            String status = jdbc.sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ?").param(pending)
                    .query(String.class).single();
            if (!"approved".equals(status)) {
                return findOne(id);
            }
        }
        finalizeApproval(id, actorId, notes);
        return findOne(id);
    }

    /** Accrue every line, settle the linked advance, set the claim status. */
    private void finalizeApproval(String id, String approverId, String notes) {
        Map<String, Object> c = require(id, true);
        List<Map<String, Object>> lines = expenses.claimLines(id);
        for (Map<String, Object> l : lines) {
            expenses.approveClaimLine((String) l.get("id"), approverId, notes);
        }
        BigDecimal applied = BigDecimal.ZERO;
        if (c.get("advanceId") != null) {
            Map<String, Object> a = advances.require((String) c.get("advanceId"), true);
            BigDecimal left = ExpenseAdvanceService.outstanding(a).min(Money.dec(c.get("totalAmount")));
            String advanceAccount = advances.advanceAccount();
            for (Map<String, Object> l : lines) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal part = left.min(expenses.residual((String) l.get("id")));
                if (part.signum() <= 0) {
                    continue;
                }
                String entry = expenses.settleAgainst((String) l.get("id"), part, advanceAccount, "ADVANCE",
                        (String) a.get("advanceNumber"), actor());
                advances.recordSettlement((String) a.get("id"), id, part, entry);
                applied = applied.add(part);
                left = left.subtract(part);
            }
        }
        BigDecimal total = Money.dec(c.get("totalAmount"));
        String status = applied.compareTo(total) >= 0 ? "paid" : "approved";
        jdbc.sql("UPDATE \"ExpenseClaim\" SET status = ?, \"advanceApplied\" = ?, \"approvedById\" = ?, \"approvedAt\" = "
                + NOW + ", \"decisionNotes\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(status, applied, approverId, notes, id).update();
        audit.recordInTx(AuditInput.of("ExpenseClaim", id, "approve", null, Obj.of("reason", notes, "advanceApplied",
                JsNumbers.js(applied))));
        publish("expense.claim_approved", Obj.of("claimId", id));
        controls.notify("notifyOnDecision", (String) c.get("claimantId"), "Claim " + c.get("claimNumber") + " approved",
                (String) c.get("title"), Obj.of("claimId", id));
    }

    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        return decideNegative(id, reason, "rejected", "REJECTED");
    }

    @Transactional
    public Map<String, Object> returnForCorrection(String id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reason is required to return a claim");
        }
        return decideNegative(id, reason, "returned", "RETURNED");
    }

    private Map<String, Object> decideNegative(String id, String reason, String status, String lineStatus) {
        Map<String, Object> c = require(id, true);
        if (!"submitted".equals(c.get("status"))) {
            throw ApiException.badRequest("Only a submitted claim can be " + status);
        }
        String actorId = actor();
        if (actorId.equals(c.get("claimantId")) || actorId.equals(c.get("createdById"))) {
            throw ApiException.forbidden("The claimant cannot decide on their own claim");
        }
        String pending = pendingRequest(id);
        if (pending != null) {
            approvals.decide(pending, "rejected", reason);
        }
        expenses.setClaimLinesStatus(id, lineStatus, reason);
        jdbc.sql("UPDATE \"ExpenseClaim\" SET status = ?, \"decisionNotes\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(status, reason, id).update();
        audit.recordInTx(AuditInput.of("ExpenseClaim", id, "reject", null, Obj.of("reason", reason, "status", status)));
        controls.notify("notifyOnDecision", (String) c.get("claimantId"), "Claim " + c.get("claimNumber") + " " + status,
                reason, Obj.of("claimId", id));
        return findOne(id);
    }

    /** Cancel a draft / returned claim: its lines are deleted (nothing was posted). */
    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> c = require(id, true);
        editable(c);
        for (Map<String, Object> l : expenses.claimLines(id)) {
            expenses.deleteClaimLine((String) l.get("id"));
        }
        jdbc.sql("UPDATE \"ExpenseClaim\" SET status = 'cancelled', \"updatedAt\" = " + NOW + " WHERE id = ?").param(id)
                .update();
        audit.recordInTx(AuditInput.of("ExpenseClaim", id, "cancel"));
        return findOne(id);
    }

    /** Reimburse the claimant (all or part of what is due): Dr reimbursement payable / Cr cash, line by line. */
    @Transactional
    public Map<String, Object> pay(String id, PayInput in) {
        Map<String, Object> c = require(id, true);
        if (!List.of("approved", "partially_paid").contains(c.get("status"))) {
            throw ApiException.badRequest("Only an approved claim can be paid");
        }
        String payer = actor();
        List<String> makers = new ArrayList<>();
        for (String k : List.of("claimantId", "createdById", "approvedById")) {
            if (c.get(k) != null) {
                makers.add((String) c.get(k));
            }
        }
        sod.assertSeparated(SegregationOfDutiesService.EXPENSE_PAYMENT, "ExpenseClaim", id, makers);
        BigDecimal due = due(c);
        BigDecimal amount = in.amount() == null ? due : in.amount();
        if (amount.signum() <= 0 || amount.compareTo(due) > 0) {
            throw ApiException.badRequest("Payment amount must be between 0 and the amount due of " + JsNumbers.dec(due));
        }
        BigDecimal left = amount;
        for (Map<String, Object> l : expenses.claimLines(id)) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal part = left.min(expenses.residual((String) l.get("id")));
            if (part.signum() <= 0) {
                continue;
            }
            expenses.payClaimLine((String) l.get("id"), payer, in.paymentMethod(), in.accountId(), in.reference(), part);
            left = left.subtract(part);
        }
        BigDecimal paid = Money.dec(c.get("amountPaid")).add(amount);
        String status = paid.add(Money.dec(c.get("advanceApplied"))).compareTo(Money.dec(c.get("totalAmount"))) >= 0
                ? "paid" : "partially_paid";
        jdbc.sql("UPDATE \"ExpenseClaim\" SET \"amountPaid\" = ?, status = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(paid, status, id).update();
        audit.recordInTx(AuditInput.of("ExpenseClaim", id, "post", null, Obj.of("paid", JsNumbers.js(amount))));
        publish("expense.claim_paid", Obj.of("claimId", id));
        controls.notify("notifyOnPayment", (String) c.get("claimantId"), "Claim " + c.get("claimNumber") + " paid",
                JsNumbers.dec(amount), Obj.of("claimId", id));
        return findOne(id);
    }

    private void publish(String event, Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>(payload);
        p.put("organizationId", org());
        events.publish(event, p);
    }
}
