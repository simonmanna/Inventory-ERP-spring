package com.inventoryerp.fleet.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.expenses.application.ExpensesService;
import com.inventoryerp.fleet.application.DriverCommands.ClaimDecision;
import com.inventoryerp.fleet.application.DriverCommands.ClaimInput;
import com.inventoryerp.fleet.application.DriverCommands.ClaimLineInput;
import com.inventoryerp.fleet.application.DriverCommands.ClaimPatch;
import com.inventoryerp.fleet.application.DriverCommands.ClaimPayment;
import com.inventoryerp.fleet.application.DriverCommands.ClaimPayrollExport;
import com.inventoryerp.fleet.application.DriverCommands.ClaimQuery;
import com.inventoryerp.fleet.application.DriverCommands.ClaimReversal;
import com.inventoryerp.fleet.domain.FleetClaimReceipt;
import com.inventoryerp.fleet.domain.FleetDriverClaim;
import com.inventoryerp.fleet.domain.FleetDriverClaimLine;
import com.inventoryerp.fleet.domain.FleetVehicle;
import com.inventoryerp.fleet.domain.FleetVehicleCategory;
import com.inventoryerp.hr.application.HrPayrollInputService;
import com.inventoryerp.kernel.approvals.ApprovalAdminService;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Driver allowance and trip-expense claims (fleet 1.2, ADR-S009 §2 / §6).
 *
 * <p>Lifecycle {@code draft → submitted → approved → paid | exported}, with {@code rejected} and {@code cancelled};
 * a paid / exported claim can be reversed back to {@code approved}. Lines are allowances (the matching
 * {@link FleetAllowanceRuleService allowance rule}'s rate × trips / days / nights, priced on the claim date for the
 * claim vehicle's class and route zone) or trip expenses the driver paid (with receipts in the kernel file store).
 *
 * <p>Approval goes through the kernel approval engine (entity type {@value #APPROVAL_ENTITY}; the default workflow
 * "Fleet manager" with approver permission {@code fleet_driver:approve_allowance} is created on first use); the
 * submitter and the driver cannot approve their own claim. Settlement, both through the posting engine with the
 * claim's cost centre (the vehicle's) on the expense line and {@code vehicleId / driverId / claimId} dimensions:
 * <ul>
 *   <li><b>pay via expenses</b> — {@link ExpensesService#createPaidFromSource}: Dr driver allowances &amp; trip
 *       expenses (6440, mapping {@code fleet_driver_allowance_expense}) / Cr the cash / bank account;</li>
 *   <li><b>export to payroll</b> — Dr 6440 / Cr driver allowances payable (2185, mapping
 *       {@code fleet_driver_allowance_payable}) now, plus an hr payroll input (earning) whose debit account is that
 *       payable, so the payroll run settles it (Dr payable / Cr net pay) without expensing it twice.</li>
 * </ul>
 * Reversal voids the expense (its payment journal is reversed) or cancels the still-pending payroll input and
 * reverses the accrual journal.
 */
@Service
public class FleetClaimService {

    public static final String APPROVAL_ENTITY = "fleet_driver_claim";
    public static final String PAYROLL_SOURCE = "fleet_driver_claim";
    static final String AUDIT_ENTITY = "FleetDriverClaim";

    private final FleetDb db;
    private final FleetDriverService drivers;
    private final FleetAllowanceRuleService rules;
    private final ApprovalsService approvals;
    private final ApprovalAdminService approvalAdmin;
    private final ExpensesService expenses;
    private final HrPayrollInputService payrollInputs;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final FilesService files;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public FleetClaimService(FleetDb db, FleetDriverService drivers, FleetAllowanceRuleService rules,
            ApprovalsService approvals, ApprovalAdminService approvalAdmin, ExpensesService expenses,
            HrPayrollInputService payrollInputs, PostingService posting, AccountResolverService accounts,
            FilesService files, SequenceService sequence, AuditService audit, EventBus events) {
        this.db = db;
        this.drivers = drivers;
        this.rules = rules;
        this.approvals = approvals;
        this.approvalAdmin = approvalAdmin;
        this.expenses = expenses;
        this.payrollInputs = payrollInputs;
        this.posting = posting;
        this.accounts = accounts;
        this.files = files;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    static final String SELECT = """
            SELECT k.*, d.code AS "driverCode", d."employeeId" AS "employeeId",
                   trim(coalesce(e."firstName", '') || ' ' || coalesce(e."lastName", '')) AS "driverName",
                   v.code AS "vehicleCode", v."registrationPlate" AS "vehicleRegistrationPlate"
            FROM "FleetDriverClaim" k
            JOIN "FleetDriver" d ON d.id = k."driverId"
            LEFT JOIN "HrEmployee" e ON e.id = d."employeeId" AND e."organizationId" = d."organizationId"
            LEFT JOIN "FleetVehicle" v ON v.id = k."vehicleId"
            """;

    // ------------------------------------------------------------------ reads

    /** Paged claims, newest first. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(ClaimQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE k.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(q.search())) {
            where.append(" AND (k.code ILIKE ? OR d.code ILIKE ? OR e.\"firstName\" ILIKE ? OR e.\"lastName\" ILIKE ?"
                    + " OR k.description ILIKE ?)");
            String like = "%" + q.search().trim().replace("%", "\\%").replace("_", "\\_") + "%";
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }
        for (String[] f : new String[][] {{"k.status", q.status()}, {"k.\"driverId\"", q.driverId()},
            {"k.\"vehicleId\"", q.vehicleId()}, {"k.settlement", q.settlement()}}) {
            if (FleetVehicleService.notBlank(f[1])) {
                where.append(" AND ").append(f[0]).append(" = ?");
                params.add(f[1]);
            }
        }
        if (q.from() != null) {
            where.append(" AND k.\"claimDate\" >= ?");
            params.add(java.sql.Date.valueOf(q.from()));
        }
        if (q.to() != null) {
            where.append(" AND k.\"claimDate\" <= ?");
            params.add(java.sql.Date.valueOf(q.to()));
        }
        String from = " FROM \"FleetDriverClaim\" k JOIN \"FleetDriver\" d ON d.id = k.\"driverId\" LEFT JOIN"
                + " \"HrEmployee\" e ON e.id = d.\"employeeId\" AND e.\"organizationId\" = d.\"organizationId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        return PageResult.of(SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY k.\"claimDate\" DESC, k.code DESC LIMIT ? OFFSET ?").params(params)), page, size, total);
    }

    /** Claim with its lines, receipts, cost centre, approval request and settlement documents. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        String org = FleetDb.org();
        Map<String, Object> k = SqlRows.one(db.jdbc().sql(SELECT + " WHERE k.id = ? AND k.\"organizationId\" = ?")
                .params(id, org));
        if (k == null) {
            throw ApiException.notFound("Claim not found");
        }
        k.put("lines", SqlRows.list(db.jdbc().sql("SELECT l.*, r.code AS \"ruleCode\", r.name AS \"ruleName\" FROM"
                + " \"FleetDriverClaimLine\" l LEFT JOIN \"FleetAllowanceRule\" r ON r.id = l.\"ruleId\" WHERE"
                + " l.\"organizationId\" = ? AND l.\"claimId\" = ? ORDER BY l.\"sortOrder\", l.\"createdAt\"")
                .params(org, id)));
        List<Map<String, Object>> receipts = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT r.*, f.filename, f.\"contentType\","
                + " f.\"byteSize\" FROM \"FleetClaimReceipt\" r JOIN \"File\" f ON f.id = r.\"fileId\" AND"
                + " f.\"deletedAt\" IS NULL WHERE r.\"organizationId\" = ? AND r.\"claimId\" = ? ORDER BY r.\"createdAt\"")
                .params(org, id))) {
            Map<String, Object> out = new LinkedHashMap<>(r);
            out.put("url", files.signDownload((String) r.get("fileId")).get("url"));
            receipts.add(out);
        }
        k.put("receipts", receipts);
        k.put("costCenter", k.get("costCenterId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, code, name"
                + " FROM \"CostCenter\" WHERE id = ? AND \"organizationId\" = ?").params(k.get("costCenterId"), org)));
        k.put("approvalRequest", k.get("approvalRequestId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id,"
                + " status::text AS status, \"currentStep\" FROM \"ApprovalRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(k.get("approvalRequestId"), org)));
        k.put("expense", k.get("expenseId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, \"expenseCode\","
                + " status::text AS status, \"paymentStatus\"::text AS \"paymentStatus\", amount FROM \"Expense\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(k.get("expenseId"), org)));
        k.put("payrollInput", k.get("payrollInputId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT i.id,"
                + " i.status, i.\"runId\", r.\"runNumber\" FROM \"HrPayrollInput\" i LEFT JOIN \"HrPayrollRun\" r ON"
                + " r.id = i.\"runId\" WHERE i.id = ? AND i.\"organizationId\" = ?").params(k.get("payrollInputId"), org)));
        return k;
    }

    /** Cash / bank / mobile-money accounts a claim can be paid from (the expenses module's list). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> paymentAccounts() {
        return expenses.paymentAccounts();
    }

    // ------------------------------------------------------------------ draft

    /** Open a draft claim (optionally with lines) in the organization's currency. */
    @Transactional
    public Map<String, Object> create(ClaimInput in) {
        drivers.require(in.driverId(), false);
        if (FleetVehicleService.notBlank(in.vehicleId())) {
            db.require(FleetVehicle.class, in.vehicleId(), false, "Vehicle not found");
        }
        assertNotFuture(in.claimDate());
        String code = sequence.next("fleet_driver_claim", "CLM-", 6);
        Map<String, Object> row = db.insert(FleetDriverClaim.class, Obj.of(
                "code", code,
                "driverId", in.driverId(),
                "vehicleId", blank(in.vehicleId()),
                "tripId", FleetVehicleService.trim(in.tripId()),
                "claimDate", in.claimDate(),
                "routeZone", FleetVehicleService.trim(in.routeZone()),
                "description", FleetVehicleService.trim(in.description()),
                "notes", FleetVehicleService.trim(in.notes()),
                "status", "draft",
                "totalAmount", BigDecimal.ZERO,
                "currencyCode", rules.currency(),
                "createdBy", TenantContext.userId(),
                "updatedBy", TenantContext.userId()));
        String id = (String) row.get("id");
        if (in.lines() != null) {
            for (ClaimLineInput l : in.lines()) {
                insertLine(row, l);
            }
        }
        recomputeTotal(id);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "create", null, row));
        return get(id);
    }

    /** Edit a draft; a new date, vehicle or zone re-prices the allowance lines. */
    @Transactional
    public Map<String, Object> update(String id, ClaimPatch in) {
        Map<String, Object> before = draft(id);
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!DriverCommands.CLAIM_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (FleetVehicleService.notBlank(in.vehicleId())) {
            db.require(FleetVehicle.class, in.vehicleId(), false, "Vehicle not found");
            data.put("vehicleId", in.vehicleId());
        }
        putIf(data, "tripId", FleetVehicleService.trim(in.tripId()));
        if (in.claimDate() != null) {
            assertNotFuture(in.claimDate());
            data.put("claimDate", in.claimDate());
        }
        putIf(data, "routeZone", FleetVehicleService.trim(in.routeZone()));
        putIf(data, "description", FleetVehicleService.trim(in.description()));
        putIf(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return get(id);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetDriverClaim.class, id, data);
        if (data.containsKey("claimDate") || data.containsKey("vehicleId") || data.containsKey("routeZone")) {
            reprice(after);
        }
        recomputeTotal(id);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return get(id);
    }

    /** Add a line to a draft. */
    @Transactional
    public Map<String, Object> addLine(String id, ClaimLineInput in) {
        Map<String, Object> claim = draft(id);
        insertLine(claim, in);
        recomputeTotal(id);
        return get(id);
    }

    /** Remove a line from a draft. */
    @Transactional
    public Map<String, Object> removeLine(String id, String lineId) {
        draft(id);
        int n = db.jdbc().sql("DELETE FROM \"FleetDriverClaimLine\" WHERE id = ? AND \"claimId\" = ? AND"
                + " \"organizationId\" = ?").params(lineId, id, FleetDb.org()).update();
        if (n == 0) {
            throw ApiException.notFound("Claim line not found");
        }
        recomputeTotal(id);
        return get(id);
    }

    /** Attach a receipt (image / PDF) to a claim, optionally to one of its lines. */
    @Transactional
    public Map<String, Object> addReceipt(String id, String lineId, String title, String filename, String contentType,
            byte[] bytes) {
        Map<String, Object> claim = db.require(FleetDriverClaim.class, id, false, "Claim not found");
        if (List.of("cancelled", "rejected").contains(claim.get("status"))) {
            throw ApiException.badRequest("Claim " + claim.get("code") + " is " + claim.get("status")
                    + "; receipts can no longer be added");
        }
        String line = blank(lineId);
        if (line != null) {
            Map<String, Object> l = db.byId(FleetDriverClaimLine.class, line, false);
            if (l == null || !id.equals(l.get("claimId"))) {
                throw ApiException.badRequest("Claim line not found on this claim");
            }
        }
        Map<String, Object> file = files.upload(filename, contentType, bytes, APPROVAL_ENTITY, id);
        Map<String, Object> row = db.insert(FleetClaimReceipt.class, Obj.of("claimId", id, "lineId", line,
                "fileId", file.get("id"), "title", FleetVehicleService.trim(title), "createdBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetClaimReceipt", (String) row.get("id"), "create", null, row));
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.put("filename", file.get("filename"));
        out.put("url", files.signDownload((String) file.get("id")).get("url"));
        return out;
    }

    /** Cancel a draft or rejected claim. */
    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> k = lock(id);
        if (!List.of("draft", "rejected").contains(k.get("status"))) {
            throw ApiException.badRequest("Only draft or rejected claims can be cancelled (claim " + k.get("code")
                    + " is " + k.get("status") + ")");
        }
        Map<String, Object> after = db.update(FleetDriverClaim.class, id, Obj.of("status", "cancelled",
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "cancel", k, after));
        return get(id);
    }

    // ------------------------------------------------------------------ approval

    /** Submit for approval; without an active workflow the engine auto-approves. */
    @Transactional
    public Map<String, Object> submit(String id) {
        Map<String, Object> k = draft(id);
        long lines = db.jdbc().sql("SELECT count(*) FROM \"FleetDriverClaimLine\" WHERE \"organizationId\" = ? AND"
                + " \"claimId\" = ?").params(FleetDb.org(), id).query(Long.class).single();
        if (lines == 0) {
            throw ApiException.badRequest("Add at least one line before submitting the claim");
        }
        BigDecimal total = SqlRows.dec(k, "totalAmount");
        if (total.signum() <= 0) {
            throw ApiException.badRequest("The claim total must be greater than zero");
        }
        String costCenter = null;
        if (k.get("vehicleId") != null) {
            costCenter = (String) db.require(FleetVehicle.class, (String) k.get("vehicleId"), false, "Vehicle not found")
                    .get("costCenterId");
        }
        Map<String, Object> after = db.update(FleetDriverClaim.class, id, Obj.of("status", "submitted",
                "costCenterId", costCenter, "submittedBy", TenantContext.userId(), "submittedAt", Instant.now(),
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", k, after));
        ensureWorkflow();
        Map<String, Object> d = drivers.require((String) k.get("driverId"), false);
        Map<String, Object> gate = approvals.checkOrRequestApproval(APPROVAL_ENTITY, id, Obj.of("amount",
                total.toPlainString(), "claim", k.get("code"), "driver", d.get("code"), "claimDate",
                k.get("claimDate").toString(), "currency", k.get("currencyCode")));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            db.update(FleetDriverClaim.class, id, Obj.of("approvalRequestId", gate.get("requestId")));
            return get(id);
        }
        decide(id, true, null, "Approved automatically (no active approval workflow)");
        return get(id);
    }

    /** Approve a submitted claim (decides the current step of its approval request). */
    @Transactional
    public Map<String, Object> approve(String id, ClaimDecision in) {
        return decideByUser(id, true, in == null ? null : FleetVehicleService.trim(in.notes()));
    }

    /** Reject a submitted claim (a reason is required). */
    @Transactional
    public Map<String, Object> reject(String id, ClaimDecision in) {
        String notes = in == null ? null : FleetVehicleService.trim(in.notes());
        if (notes == null) {
            throw ApiException.badRequest("A reason is required to reject a claim");
        }
        return decideByUser(id, false, notes);
    }

    private Map<String, Object> decideByUser(String id, boolean approved, String notes) {
        Map<String, Object> k = lock(id);
        if (!"submitted".equals(k.get("status"))) {
            throw ApiException.badRequest("Only submitted claims can be " + (approved ? "approved" : "rejected")
                    + " (claim " + k.get("code") + " is " + k.get("status") + ")");
        }
        String actor = TenantContext.userId();
        if (actor != null && (actor.equals(k.get("submittedBy")) || actor.equals(k.get("createdBy")))) {
            throw ApiException.forbidden("The person who submitted a claim cannot approve or reject it");
        }
        String driverUser = db.jdbc().sql("SELECT e.\"userId\" FROM \"FleetDriver\" d JOIN \"HrEmployee\" e ON"
                + " e.id = d.\"employeeId\" WHERE d.id = ? AND d.\"organizationId\" = ?").params(k.get("driverId"),
                FleetDb.org()).query(String.class).optional().orElse(null);
        if (actor != null && actor.equals(driverUser)) {
            throw ApiException.forbidden("A driver cannot approve or reject their own claim");
        }
        String requestId = (String) k.get("approvalRequestId");
        if (requestId != null) {
            approvals.decide(requestId, approved ? "approved" : "rejected", notes);
            String status = requestStatus(requestId);
            if ("pending".equals(status)) {
                return get(id);
            }
            decide(id, "approved".equals(status), actor, notes);
            return get(id);
        }
        decide(id, approved, actor, notes);
        return get(id);
    }

    /** Apply the approval engine's decision (from {@code approval.decided}); idempotent. */
    @Transactional
    public Map<String, Object> applyDecision(String id, boolean approved, String deciderId) {
        decide(id, approved, deciderId, null);
        return db.require(FleetDriverClaim.class, id, false, "Claim not found");
    }

    /** Re-read the claim's approval request and apply its outcome now; 400 while it is pending. */
    @Transactional
    public Map<String, Object> sync(String id) {
        Map<String, Object> k = db.require(FleetDriverClaim.class, id, false, "Claim not found");
        if (!"submitted".equals(k.get("status"))) {
            return get(id);
        }
        String requestId = (String) k.get("approvalRequestId");
        if (requestId == null) {
            decide(id, true, null, null);
            return get(id);
        }
        String status = requestStatus(requestId);
        if ("pending".equals(status)) {
            throw ApiException.badRequest("The claim is waiting for approval (request " + requestId + ")");
        }
        String decider = db.jdbc().sql("SELECT \"approverId\" FROM \"ApprovalDecision\" WHERE \"requestId\" = ? ORDER BY"
                + " \"decidedAt\" DESC LIMIT 1").param(requestId).query(String.class).optional().orElse(null);
        decide(id, "approved".equals(status), decider, null);
        return get(id);
    }

    private void decide(String id, boolean approved, String deciderId, String notes) {
        Map<String, Object> k = lock(id);
        if (!"submitted".equals(k.get("status"))) {
            return;
        }
        Map<String, Object> after = db.update(FleetDriverClaim.class, id, Obj.of("status",
                approved ? "approved" : "rejected", "decidedBy", deciderId, "decidedAt", Instant.now(),
                "decisionNotes", notes));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, approved ? "approve" : "reject", k, after));
    }

    // ------------------------------------------------------------------ settlement

    /** Pay an approved claim through expenses (Dr driver allowances, cost centre / Cr cash-bank). Money route. */
    @Transactional
    public Map<String, Object> pay(String id, ClaimPayment in) {
        if (!TenantContext.has(Permissions.EXPENSE__POST)) {
            throw ApiException.forbidden("Paying a driver claim through expenses requires the expense:post permission");
        }
        Map<String, Object> k = approvedForSettlement(id);
        BigDecimal total = SqlRows.dec(k, "totalAmount");
        String expenseAccount = expenseAccount();
        Map<String, Object> d = drivers.require((String) k.get("driverId"), false);
        Map<String, Object> view = SqlRows.one(db.jdbc().sql(SELECT + " WHERE k.id = ? AND k.\"organizationId\" = ?")
                .params(id, FleetDb.org()));
        String driverName = view == null ? null : (String) view.get("driverName");
        String title = "Driver claim " + k.get("code") + " — " + (driverName == null || driverName.isBlank()
                ? d.get("code") : driverName);
        Map<String, Object> expense = expenses.createPaidFromSource(new ExpensesService.CreateInput(title,
                        (String) k.get("description"), total, null, k.get("claimDate").toString(),
                        FleetVehicleService.trim(in.notes()), null, "CASH", in.paymentMethod(), in.accountId(),
                        FleetVehicleService.trim(in.reference())),
                (String) k.get("decidedBy"), new ExpensesService.SourcePosting(expenseAccount, costCenter(k),
                        dimensions(k)));
        String expenseId = (String) expense.get("id");
        String journal = db.jdbc().sql("SELECT \"journalEntryId\" FROM \"ExpensePayment\" WHERE \"expenseId\" = ? AND"
                + " \"organizationId\" = ? AND status = 'posted' ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(expenseId, FleetDb.org()).query(String.class).optional().orElse(null);
        Map<String, Object> after = db.update(FleetDriverClaim.class, id, Obj.of("status", "paid",
                "settlement", "expenses", "expenseId", expenseId, "journalEntryId", journal,
                "settledBy", TenantContext.userId(), "settledAt", Instant.now(),
                "settlementCount", ((Number) k.get("settlementCount")).intValue() + 1,
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "post", k, after));
        settled(k, "expenses", journal);
        return get(id);
    }

    /**
     * Export an approved claim to hr payroll as an earning of the driver's employee: accrual Dr driver allowances
     * (cost centre) / Cr driver allowances payable now; the payroll run settles the payable. Money route.
     */
    @Transactional
    public Map<String, Object> exportToPayroll(String id, ClaimPayrollExport in) {
        Map<String, Object> k = approvedForSettlement(id);
        Map<String, Object> d = drivers.require((String) k.get("driverId"), false);
        BigDecimal total = SqlRows.dec(k, "totalAmount");
        String expenseAccount = expenseAccount();
        String payable = accounts.ensureByCode("2185", "Driver Allowances Payable (Payroll)", "current_liability",
                "fleet_driver_allowance_payable");
        int n = ((Number) k.get("settlementCount")).intValue() + 1;
        String description = "Driver claim " + k.get("code") + " exported to payroll";
        Map<String, Object> journal = posting.post(new PostingRequest("GEN", Instant.now(), description, null, null,
                APPROVAL_ENTITY, id, "primary", "fleet:claim:" + id + ":payroll:" + n, null, null, null, List.of(
                        new Line(expenseAccount, total, null, null, description, null, costCenter(k), dimensions(k)),
                        Line.credit(payable, total, description))));
        LocalDate earnedOn = in == null || in.earnedOn() == null ? (LocalDate) k.get("claimDate") : in.earnedOn();
        Map<String, Object> input = payrollInputs.addEarning((String) d.get("employeeId"), "Driver claim "
                + k.get("code"), total, earnedOn, false, payable, PAYROLL_SOURCE, id);
        Map<String, Object> after = db.update(FleetDriverClaim.class, id, Obj.of("status", "exported",
                "settlement", "payroll", "payrollInputId", input.get("id"), "journalEntryId", journal.get("id"),
                "settledBy", TenantContext.userId(), "settledAt", Instant.now(), "settlementCount", n,
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "post", k, after));
        settled(k, "payroll", (String) journal.get("id"));
        return get(id);
    }

    /**
     * Reverse a paid claim (voids its expense: the payment journal is reversed) or an exported one (cancels the
     * still-pending payroll input and reverses the accrual) — back to {@code approved}. Money route.
     */
    @Transactional
    public Map<String, Object> reverse(String id, ClaimReversal in) {
        Map<String, Object> k = lock(id);
        String status = (String) k.get("status");
        String reason = in.reason().trim();
        if ("paid".equals(status)) {
            if (!TenantContext.has(Permissions.EXPENSE__POST)) {
                throw ApiException.forbidden("Reversing a paid driver claim requires the expense:post permission");
            }
            expenses.voidExpense((String) k.get("expenseId"), "Driver claim " + k.get("code") + " reversed: " + reason);
        } else if ("exported".equals(status)) {
            payrollInputs.cancel(PAYROLL_SOURCE, id, reason);
            posting.reverse((String) k.get("journalEntryId"), null, "Reverse driver claim " + k.get("code") + ": "
                    + reason);
        } else {
            throw ApiException.badRequest("Only paid or exported claims can be reversed (claim " + k.get("code")
                    + " is " + status + ")");
        }
        Map<String, Object> after = db.update(FleetDriverClaim.class, id, Obj.of("status", "approved",
                "settlement", null, "expenseId", null, "payrollInputId", null, "journalEntryId", null,
                "settledBy", null, "settledAt", null, "reversedBy", TenantContext.userId(), "reversedAt", Instant.now(),
                "reversalReason", reason, "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "cancel", k, after));
        return get(id);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> approvedForSettlement(String id) {
        Map<String, Object> k = lock(id);
        if (!"approved".equals(k.get("status"))) {
            throw ApiException.badRequest("Only approved claims can be paid or exported (claim " + k.get("code")
                    + " is " + k.get("status") + ")");
        }
        return k;
    }

    private void settled(Map<String, Object> k, String settlement, String journalEntryId) {
        events.publish(DomainEvents.FLEET_CLAIM_SETTLED, Obj.of("organizationId", FleetDb.org(), "claimId", k.get("id"),
                "code", k.get("code"), "driverId", k.get("driverId"), "vehicleId", k.get("vehicleId"),
                "settlement", settlement, "amount", SqlRows.dec(k, "totalAmount").toPlainString(),
                "currencyCode", k.get("currencyCode"), "journalEntryId", journalEntryId));
    }

    private String expenseAccount() {
        return accounts.ensureByCode("6440", "Driver Allowances & Trip Expenses", "operating_expense",
                "fleet_driver_allowance_expense");
    }

    /** The claim's cost centre (snapshot at submit), else the vehicle's current one. */
    private String costCenter(Map<String, Object> k) {
        if (k.get("costCenterId") != null) {
            return (String) k.get("costCenterId");
        }
        if (k.get("vehicleId") == null) {
            return null;
        }
        Map<String, Object> v = db.byId(FleetVehicle.class, (String) k.get("vehicleId"), false);
        return v == null ? null : (String) v.get("costCenterId");
    }

    private static Map<String, Object> dimensions(Map<String, Object> k) {
        Map<String, Object> dims = new LinkedHashMap<>();
        if (k.get("vehicleId") != null) {
            dims.put("vehicleId", k.get("vehicleId"));
        }
        dims.put("driverId", k.get("driverId"));
        dims.put("claimId", k.get("id"));
        return dims;
    }

    private void insertLine(Map<String, Object> claim, ClaimLineInput in) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claimId", claim.get("id"));
        data.put("lineType", in.lineType());
        data.put("description", FleetVehicleService.trim(in.description()));
        if ("allowance".equals(in.lineType())) {
            if (in.basis() == null) {
                throw ApiException.badRequest("basis is required for an allowance line");
            }
            if (in.amount() != null) {
                throw ApiException.badRequest("An allowance line takes its rate from the allowance rules; leave the"
                        + " amount empty");
            }
            if (in.expenseType() != null) {
                throw ApiException.badRequest("expenseType applies to expense lines only");
            }
            BigDecimal qty = in.quantity() == null ? BigDecimal.ONE : in.quantity();
            Map<String, Object> rule = priceFor(claim, in.basis());
            BigDecimal rate = SqlRows.dec(rule, "amount");
            data.put("basis", in.basis());
            data.put("ruleId", rule.get("id"));
            data.put("quantity", qty);
            data.put("rate", rate);
            data.put("amount", AllowanceCalculator.amount(rate, qty));
        } else {
            if (in.expenseType() == null) {
                throw ApiException.badRequest("expenseType is required for an expense line");
            }
            if (in.amount() == null) {
                throw ApiException.badRequest("amount is required for an expense line");
            }
            if (in.basis() != null) {
                throw ApiException.badRequest("basis applies to allowance lines only");
            }
            data.put("expenseType", in.expenseType());
            data.put("quantity", BigDecimal.ONE);
            data.put("rate", in.amount());
            data.put("amount", AllowanceCalculator.amount(in.amount(), BigDecimal.ONE));
        }
        Integer sort = db.jdbc().sql("SELECT coalesce(max(\"sortOrder\"), 0) + 10 FROM \"FleetDriverClaimLine\" WHERE"
                + " \"organizationId\" = ? AND \"claimId\" = ?").params(FleetDb.org(), claim.get("id"))
                .query(Integer.class).single();
        data.put("sortOrder", sort);
        db.insert(FleetDriverClaimLine.class, data);
    }

    /** The allowance rule for a basis on the claim's date, vehicle class and route zone (400 when none). */
    private Map<String, Object> priceFor(Map<String, Object> claim, String basis) {
        String categoryId = null;
        String categoryName = null;
        if (claim.get("vehicleId") != null) {
            Map<String, Object> v = db.byId(FleetVehicle.class, (String) claim.get("vehicleId"), false);
            if (v != null) {
                categoryId = (String) v.get("categoryId");
                Map<String, Object> c = db.byId(FleetVehicleCategory.class, categoryId, false);
                categoryName = c == null ? null : (String) c.get("name");
            }
        }
        return rules.price(basis, (LocalDate) claim.get("claimDate"), categoryId, categoryName,
                (String) claim.get("routeZone"));
    }

    /** Re-price every allowance line of a draft after its date / vehicle / zone changed. */
    private void reprice(Map<String, Object> claim) {
        for (Map<String, Object> l : db.list(FleetDriverClaimLine.class, " AND \"claimId\" = ? AND \"lineType\" ="
                + " 'allowance'", claim.get("id"))) {
            Map<String, Object> rule = priceFor(claim, (String) l.get("basis"));
            BigDecimal rate = SqlRows.dec(rule, "amount");
            db.update(FleetDriverClaimLine.class, (String) l.get("id"), Obj.of("ruleId", rule.get("id"), "rate", rate,
                    "amount", AllowanceCalculator.amount(rate, SqlRows.dec(l, "quantity"))));
        }
    }

    private void recomputeTotal(String id) {
        BigDecimal total = db.jdbc().sql("SELECT coalesce(sum(amount), 0) FROM \"FleetDriverClaimLine\" WHERE"
                + " \"organizationId\" = ? AND \"claimId\" = ?").params(FleetDb.org(), id).query(BigDecimal.class)
                .single();
        db.update(FleetDriverClaim.class, id, Obj.of("totalAmount", total.setScale(2, java.math.RoundingMode.HALF_UP)));
    }

    private Map<String, Object> draft(String id) {
        Map<String, Object> k = lock(id);
        if (!"draft".equals(k.get("status"))) {
            throw ApiException.badRequest("Only draft claims can be changed (claim " + k.get("code") + " is "
                    + k.get("status") + ")");
        }
        return k;
    }

    private Map<String, Object> lock(String id) {
        return db.require(FleetDriverClaim.class, id, true, "Claim not found");
    }

    private String requestStatus(String requestId) {
        return db.jdbc().sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ? AND \"organizationId\" = ?")
                .params(requestId, FleetDb.org()).query(String.class).optional().orElse("pending");
    }

    /** Creates the default claim workflow when the organization has none (active or not). */
    private void ensureWorkflow() {
        long n = db.jdbc().sql("SELECT count(*) FROM \"ApprovalWorkflow\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = ?").params(FleetDb.org(), APPROVAL_ENTITY).query(Long.class).single();
        if (n == 0) {
            approvalAdmin.createWorkflow("Fleet driver claim", APPROVAL_ENTITY, null, true, true,
                    List.of(new ApprovalAdminService.Step(1, "Fleet manager",
                            List.of(Permissions.FLEET_DRIVER__APPROVE_ALLOWANCE), 1, null, null)));
        }
    }

    private static void assertNotFuture(LocalDate claimDate) {
        if (claimDate.isAfter(FleetDriverService.today())) {
            throw ApiException.badRequest("The claim date cannot be in the future");
        }
    }

    private static void putIf(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
