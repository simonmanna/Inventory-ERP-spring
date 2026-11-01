package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.ProjectService.blank;
import static com.inventoryerp.projects.application.ProjectService.day;
import static com.inventoryerp.projects.application.ProjectService.money;
import static com.inventoryerp.projects.application.ProjectService.org;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.AccountingPolicyService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.InvoiceService.InvoiceInput;
import com.inventoryerp.invoicing.application.VendorBillService;
import com.inventoryerp.invoicing.application.VendorBillService.BillInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.projects.application.ContractCalculator.Claim;
import com.inventoryerp.projects.application.ContractCalculator.OnerousInput;
import com.inventoryerp.projects.application.ContractCalculator.OnerousResult;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract commercials (Cost Management CM-8b, ADR-S039): IFRS 15 and IAS 37 on top of the project core.
 *
 * <ul>
 *   <li><b>IFRS 15.35 criteria</b>: an approved assessment (a / b / c met) is what lets the contract's governed
 *       {@code project_revenue_recognition} election be over time; without it recognition is point in time.</li>
 *   <li><b>Variations</b>: pending → approved / rejected (maker ≠ checker). An approved variation changes the
 *       transaction price (cumulative catch-up or prospective; a separate contract is excluded) and revises the
 *       budget. Pending variations never enter the transaction price.</li>
 *   <li><b>Customer advances</b>: invoiced to 2480 (contract liability), recovered at a % of each claim.</li>
 *   <li><b>Progress claims</b>: certifying raises a posted sales invoice for the net amount, crediting
 *       <em>2470 Contract Billings</em>; conditional retention → 1475 contract asset; time-only retention → a
 *       separate invoice due at the release date (receivable); advance recovery → Dr 2480.</li>
 *   <li><b>Subcontract valuations</b>: certified work is project cost in full (Dr 1460 gross); Cr 2485 retention
 *       payable (financial liability) and the net through 2486 clearing to a vendor bill. Back-charges reduce the
 *       payable and the project cost.</li>
 *   <li><b>Revenue recognition run</b> per contract and period: cost-to-cost progress excluding flagged cost,
 *       cumulative catch-up: Dr 1470 / Cr 4610 revenue and Dr 5610 / Cr 1460 cost of revenue. Point in time
 *       recognises everything on completion.</li>
 *   <li><b>Onerous contracts</b> (IAS 37.66–69, 68A): an indicator opens the assessment; benefits of the remaining
 *       obligation vs the least net cost of exiting; impairment of dedicated assets first; approved by a second
 *       person; the movement in the provision posts Dr 6990 / Cr 2490 (or releases it).</li>
 * </ul>
 */
@Service
public class ContractService {

    static final Set<String> CONDITIONS = Set.of("completion_certificate", "defects_period", "date_only");
    static final Set<String> MODIFICATIONS = Set.of("cumulative_catch_up", "separate_contract", "prospective");
    static final Set<String> INDICATORS = Set.of("negative_margin", "eac_increase", "dispute", "penalty_notice",
            "management_flag");

    public record VariationInput(String description, BigDecimal priceDelta, BigDecimal costDelta, String wbsId,
            String costElement, String modification) {}

    public record ClaimInput(String claimDate, BigDecimal cumulativeCertified, BigDecimal retentionPct,
            String retentionCondition, String retentionReleaseDate, String taxId) {}

    public record SubcontractInput(String supplierId, String wbsId, String scope, BigDecimal value,
            BigDecimal retentionPct, String retentionCondition) {}

    public record OnerousAssessmentInput(String asOf, List<String> indicators, BigDecimal probableClaims,
            BigDecimal costOfExiting, String exitEvidence, BigDecimal impairmentRecognised,
            String impairmentReference) {}

    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final ProjectCostService costs;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AccountingPolicyService policies;
    private final InvoiceService invoices;
    private final VendorBillService bills;
    private final SequenceService seq;
    private final AuditService audit;

    public ContractService(JdbcClient jdbc, ProjectService projects, ProjectCostService costs, PostingService posting,
            AccountResolverService accounts, AccountingPolicyService policies, InvoiceService invoices,
            VendorBillService bills, SequenceService seq, AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.costs = costs;
        this.posting = posting;
        this.accounts = accounts;
        this.policies = policies;
        this.invoices = invoices;
        this.bills = bills;
        this.seq = seq;
        this.audit = audit;
    }

    // ---- accounts -----------------------------------------------------------------------------------------------------

    public String unbilledAccount() {
        return accounts.ensureByCode("1470", "Contract Asset - Unbilled Revenue", "current_asset",
                "contract_asset_unbilled");
    }

    public String retentionAssetAccount() {
        return accounts.ensureByCode("1475", "Contract Asset - Retention", "current_asset",
                "contract_asset_retention");
    }

    public String billingsAccount() {
        return accounts.ensureByCode("2470", "Contract Billings", "current_liability", "contract_billings");
    }

    public String advancesAccount() {
        return accounts.ensureByCode("2480", "Customer Advances - Contract Liability", "current_liability",
                "contract_advances");
    }

    public String retentionPayableAccount() {
        return accounts.ensureByCode("2485", "Retention Payable", "current_liability", "retention_payable");
    }

    public String subcontractClearingAccount() {
        return accounts.ensureByCode("2486", "Subcontract Valuation Clearing", "current_liability",
                "subcontract_clearing");
    }

    public String onerousProvisionAccount() {
        return accounts.ensureByCode("2490", "Onerous Contract Provision", "current_liability", "onerous_provision");
    }

    public String revenueAccount() {
        return accounts.ensureByCode("4610", "Contract Revenue", "revenue", "contract_revenue");
    }

    public String costOfRevenueAccount() {
        return accounts.ensureByCode("5610", "Contract Cost of Revenue", "cost_of_goods_sold",
                "contract_cost_of_revenue");
    }

    public String onerousExpenseAccount() {
        return accounts.ensureByCode("6990", "Onerous Contract Expense", "operating_expense", "onerous_expense");
    }

    // ---- IFRS 15.35 criteria ------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> assessCriteria(String projectId, Boolean a, Boolean b, Boolean c, String evidence) {
        projects.project(projectId, false);
        if (evidence == null || evidence.trim().length() < 10) {
            throw ApiException.badRequest("Record the evidence for the IFRS 15.35 assessment (at least 10"
                    + " characters)");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ContractCriteriaAssessment\" (id, \"organizationId\", \"projectId\", \"criterionA\","
                + " \"criterionB\", \"criterionC\", evidence, \"assessedBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)").params(id,
                org(), projectId, Boolean.TRUE.equals(a), Boolean.TRUE.equals(b), Boolean.TRUE.equals(c),
                evidence.trim(), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("ContractCriteriaAssessment", id, "create", null, Obj.of("projectId",
                projectId)));
        return criteria(id);
    }

    @Transactional
    public Map<String, Object> approveCriteria(String id) {
        Map<String, Object> r = criteria(id);
        if (!"draft".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a draft assessment can be approved");
        }
        notSelf(r.get("assessedBy"), "An IFRS 15.35 assessment must be approved by someone other than its assessor");
        jdbc.sql("UPDATE \"ContractCriteriaAssessment\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" ="
                + " now() WHERE id = ?").params(TenantContext.userId(), id).update();
        audit.recordInTx(AuditInput.of("ContractCriteriaAssessment", id, "approve", null, null));
        return criteria(id);
    }

    private Map<String, Object> criteria(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"ContractCriteriaAssessment\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Assessment not found");
        }
        return r;
    }

    /** The recognition method in force for the contract (governed election, AD-4). */
    public String method(String projectId) {
        return policies.current("project_revenue_recognition", "contract", projectId);
    }

    // ---- variations ---------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createVariation(String projectId, VariationInput in) {
        projects.project(projectId, false);
        if (in.description() == null || in.description().isBlank()) {
            throw ApiException.badRequest("description should not be empty");
        }
        if (in.priceDelta() == null) {
            throw ApiException.badRequest("priceDelta should not be empty");
        }
        String modification = in.modification() == null ? "cumulative_catch_up" : in.modification();
        if (!MODIFICATIONS.contains(modification)) {
            throw ApiException.badRequest("modification must be one of the following values: cumulative_catch_up,"
                    + " separate_contract, prospective");
        }
        BigDecimal costDelta = in.costDelta() == null ? BigDecimal.ZERO : money(in.costDelta());
        if (costDelta.signum() != 0) {
            projects.wbs(projectId, in.wbsId());
            ProjectService.element(in.costElement());
        }
        String id = UUID.randomUUID().toString();
        String number = seq.next("contract_variation", "VAR/", 5);
        jdbc.sql("INSERT INTO \"ContractVariation\" (id, \"organizationId\", \"projectId\", number, description,"
                        + " \"priceDelta\", \"costDelta\", \"wbsId\", \"costElement\", modification, \"createdBy\") VALUES"
                        + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), projectId, number, in.description().trim(), money(in.priceDelta()), costDelta,
                        blank(in.wbsId()), blank(in.costElement()), modification, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ContractVariation", id, "create", null, Obj.of("number", number)));
        return variation(id);
    }

    @Transactional
    public Map<String, Object> decideVariation(String id, boolean approve) {
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT * FROM \"ContractVariation\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (v == null) {
            throw ApiException.notFound("Variation not found");
        }
        if (!"pending".equals(v.get("status"))) {
            throw ApiException.badRequest("Only a pending variation can be decided");
        }
        notSelf(v.get("createdBy"), "A variation must be approved by someone other than its author");
        String budgetId = null;
        BigDecimal costDelta = SqlRows.dec(v, "costDelta");
        if (approve && costDelta.signum() != 0) {
            budgetId = projects.reviseBudget((String) v.get("projectId"), (String) v.get("wbsId"),
                    (String) v.get("costElement"), costDelta, "separate_contract".equals(v.get("modification"))
                            ? BigDecimal.ZERO : SqlRows.dec(v, "priceDelta").max(BigDecimal.ZERO),
                    "Variation " + v.get("number"));
        }
        jdbc.sql("UPDATE \"ContractVariation\" SET status = ?, \"budgetId\" = ?, \"decidedBy\" = ?, \"decidedAt\" ="
                + " now() WHERE id = ?").params(approve ? "approved" : "rejected", budgetId, TenantContext.userId(), id)
                .update();
        audit.recordInTx(AuditInput.of("ContractVariation", id, approve ? "approve" : "reject", null, null));
        return variation(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> variations(String projectId) {
        projects.project(projectId, false);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ContractVariation\" WHERE \"projectId\" = ? ORDER BY number")
                .param(projectId));
    }

    private Map<String, Object> variation(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"ContractVariation\" WHERE id = ?").param(id));
    }

    /** Contract value + approved variations (a separate contract is accounted for on its own). */
    public BigDecimal transactionPrice(String projectId) {
        Map<String, Object> p = projects.project(projectId, false);
        BigDecimal approved = jdbc.sql("SELECT coalesce(sum(\"priceDelta\"), 0) FROM \"ContractVariation\" WHERE"
                + " \"projectId\" = ? AND status = 'approved' AND modification <> 'separate_contract'").param(projectId)
                .query(BigDecimal.class).single();
        return SqlRows.dec(p, "contractValue").add(approved);
    }

    // ---- customer advances --------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createAdvance(String projectId, BigDecimal amount, BigDecimal recoveryPct,
            String date) {
        Map<String, Object> p = projects.project(projectId, true);
        String customer = customer(p);
        BigDecimal a = money(amount);
        if (a.signum() <= 0) {
            throw ApiException.badRequest("amount must be greater than 0");
        }
        if (recoveryPct == null || recoveryPct.signum() <= 0 || recoveryPct.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("recoveryPct must be between 0 and 100");
        }
        String id = UUID.randomUUID().toString();
        String d = day(date) == null ? today().toString() : day(date);
        String invoice = invoice(customer, d, null, "Advance · " + p.get("code"), advancesAccount(), a, null);
        jdbc.sql("INSERT INTO \"CustomerAdvance\" (id, \"organizationId\", \"projectId\", amount, \"recoveryPct\","
                + " \"invoiceId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?)").params(id, org(), projectId, a,
                recoveryPct, invoice, TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("CustomerAdvance", id, "create", null, Obj.of("amount", a)));
        return SqlRows.one(jdbc.sql("SELECT * FROM \"CustomerAdvance\" WHERE id = ?").param(id));
    }

    // ---- progress claims ----------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createClaim(String projectId, ClaimInput in) {
        Map<String, Object> p = projects.project(projectId, true);
        customer(p);
        if (in.cumulativeCertified() == null) {
            throw ApiException.badRequest("cumulativeCertified should not be empty");
        }
        BigDecimal cumulative = money(in.cumulativeCertified());
        BigDecimal previous = previousCertified(projectId);
        if (cumulative.compareTo(previous) <= 0) {
            throw ApiException.badRequest("cumulativeCertified must exceed the " + previous.toPlainString()
                    + " already certified");
        }
        if (cumulative.compareTo(transactionPrice(projectId)) > 0) {
            throw ApiException.badRequest("Cumulative certified work exceeds the transaction price; approve a"
                    + " variation first");
        }
        String condition = in.retentionCondition() == null ? "completion_certificate" : in.retentionCondition();
        if (!CONDITIONS.contains(condition)) {
            throw ApiException.badRequest("retentionCondition must be one of the following values:"
                    + " completion_certificate, defects_period, date_only");
        }
        String release = day(in.retentionReleaseDate());
        if ("date_only".equals(condition) && release == null) {
            throw ApiException.badRequest("A time-only retention needs retentionReleaseDate");
        }
        BigDecimal retentionPct = in.retentionPct() == null ? BigDecimal.ZERO : in.retentionPct();
        if (retentionPct.signum() < 0 || retentionPct.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw ApiException.badRequest("retentionPct must be between 0 and 100");
        }
        Map<String, Object> adv = SqlRows.one(jdbc.sql("SELECT coalesce(sum(amount - recovered), 0) AS outstanding,"
                + " coalesce(max(\"recoveryPct\"), 0) AS pct FROM \"CustomerAdvance\" WHERE \"projectId\" = ? AND status ="
                + " 'open'").param(projectId));
        Claim c = ContractCalculator.claim(cumulative, previous, retentionPct, SqlRows.dec(adv, "pct"),
                SqlRows.dec(adv, "outstanding"));
        if (c.net().signum() < 0) {
            throw ApiException.badRequest("Deductions exceed the claim");
        }
        if (jdbc.sql("SELECT count(*) FROM \"ProgressClaim\" WHERE \"projectId\" = ? AND status = 'draft'")
                .param(projectId).query(Long.class).single() > 0) {
            throw ApiException.conflict("This contract already has a draft claim; certify or cancel it first");
        }
        String id = UUID.randomUUID().toString();
        String number = seq.next("progress_claim", "PC/", 5);
        jdbc.sql("INSERT INTO \"ProgressClaim\" (id, \"organizationId\", \"projectId\", number, \"claimDate\","
                        + " \"cumulativeCertified\", \"previousCertified\", \"thisClaim\", \"retentionPct\", retention,"
                        + " \"retentionCondition\", \"retentionReleaseDate\", \"advanceRecovery\", \"netAmount\", \"taxId\","
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?)")
                .params(id, org(), projectId, number, day(in.claimDate()) == null ? today().toString()
                                : day(in.claimDate()), cumulative, previous, c.thisClaim(), retentionPct, c.retention(),
                        condition, release, c.advanceRecovery(), c.net(), blank(in.taxId()), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ProgressClaim", id, "create", null, Obj.of("number", number, "thisClaim",
                c.thisClaim())));
        return claim(id);
    }

    @Transactional
    public Map<String, Object> cancelClaim(String id) {
        Map<String, Object> c = lockedClaim(id);
        if (!"draft".equals(c.get("status"))) {
            throw ApiException.badRequest("Only a draft claim can be cancelled");
        }
        jdbc.sql("UPDATE \"ProgressClaim\" SET status = 'cancelled' WHERE id = ?").param(id).update();
        return claim(id);
    }

    /** Certify: invoice the net, book retention and advance recovery against contract billings. */
    @Transactional
    public Map<String, Object> certifyClaim(String id) {
        Map<String, Object> c = lockedClaim(id);
        if (!"draft".equals(c.get("status"))) {
            throw ApiException.badRequest("Only a draft claim can be certified");
        }
        String projectId = (String) c.get("projectId");
        Map<String, Object> p = projects.project(projectId, true);
        String customer = customer(p);
        String date = c.get("claimDate").toString();
        String label = "Claim " + c.get("number") + " · " + p.get("code");
        BigDecimal net = SqlRows.dec(c, "netAmount");
        BigDecimal retention = SqlRows.dec(c, "retention");
        BigDecimal recovery = SqlRows.dec(c, "advanceRecovery");
        String condition = (String) c.get("retentionCondition");
        String billings = billingsAccount();
        String invoice = net.signum() > 0 ? invoice(customer, date, null, label, billings, net,
                (String) c.get("taxId")) : null;
        String retentionInvoice = null;
        List<Line> lines = new ArrayList<>();
        Map<String, Object> dims = Obj.of("projectId", projectId, "progressClaimId", id);
        if (retention.signum() > 0) {
            if ("date_only".equals(condition)) {
                retentionInvoice = invoice(customer, date, c.get("retentionReleaseDate").toString(), label
                        + " · retention", billings, retention, null);
            } else {
                lines.add(new Line(retentionAssetAccount(), JsNumbers.dec(retention), null, customer, label
                        + " · retention", (String) p.get("branchId"), null, dims));
                lines.add(new Line(billings, null, JsNumbers.dec(retention), customer, label + " · retention",
                        (String) p.get("branchId"), null, dims));
            }
        }
        if (recovery.signum() > 0) {
            lines.add(new Line(advancesAccount(), JsNumbers.dec(recovery), null, customer, label
                    + " · advance recovery", (String) p.get("branchId"), null, dims));
            lines.add(new Line(billings, null, JsNumbers.dec(recovery), customer, label + " · advance recovery",
                    (String) p.get("branchId"), null, dims));
            recoverAdvances(projectId, recovery);
        }
        String je = null;
        if (!lines.isEmpty()) {
            Map<String, Object> entry = posting.post(new PostingRequest("GEN", day0(date), label, null, null,
                    "progress_claim", id, null, "projects:claim:" + org() + ":" + id, (String) p.get("branchId"), null,
                    null, lines));
            je = entry == null ? null : (String) entry.get("id");
        }
        if (retention.signum() > 0) {
            boolean timeOnly = "date_only".equals(condition);
            jdbc.sql("INSERT INTO \"RetentionLedger\" (id, \"organizationId\", \"projectId\", side, \"partnerId\","
                            + " \"claimId\", amount, \"releaseCondition\", \"releaseDate\", classification, \"journalEntryId\","
                            + " \"releaseDocumentId\") VALUES (?, ?, ?, 'customer', ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), projectId, customer, id, retention, condition,
                            c.get("retentionReleaseDate") == null ? null : Date.valueOf(c.get("retentionReleaseDate")
                                    .toString()), timeOnly ? "receivable" : "contract_asset", je, retentionInvoice)
                    .update();
        }
        jdbc.sql("UPDATE \"ProgressClaim\" SET status = 'certified', \"invoiceId\" = ?, \"retentionInvoiceId\" = ?,"
                + " \"journalEntryId\" = ?, \"certifiedBy\" = ?, \"certifiedAt\" = now() WHERE id = ?").params(invoice,
                retentionInvoice, je, TenantContext.userId(), id).update();
        audit.recordInTx(AuditInput.of("ProgressClaim", id, "post", null, Obj.of("invoiceId", invoice)));
        return claim(id);
    }

    private void recoverAdvances(String projectId, BigDecimal amount) {
        BigDecimal left = amount;
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT * FROM \"CustomerAdvance\" WHERE \"projectId\" = ?"
                + " AND status = 'open' ORDER BY \"createdAt\" FOR UPDATE").param(projectId))) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal open = SqlRows.dec(a, "amount").subtract(SqlRows.dec(a, "recovered"));
            BigDecimal take = open.min(left);
            left = left.subtract(take);
            jdbc.sql("UPDATE \"CustomerAdvance\" SET recovered = recovered + ?, status = CASE WHEN recovered + ? >="
                    + " amount THEN 'recovered' ELSE status END WHERE id = ?").params(take, take, a.get("id")).update();
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> claims(String projectId) {
        projects.project(projectId, false);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"ProgressClaim\" WHERE \"projectId\" = ? ORDER BY number")
                .param(projectId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> claim(String id) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"ProgressClaim\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (c == null) {
            throw ApiException.notFound("Progress claim not found");
        }
        return c;
    }

    private Map<String, Object> lockedClaim(String id) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"ProgressClaim\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (c == null) {
            throw ApiException.notFound("Progress claim not found");
        }
        return c;
    }

    private BigDecimal previousCertified(String projectId) {
        return jdbc.sql("SELECT coalesce(max(\"cumulativeCertified\"), 0) FROM \"ProgressClaim\" WHERE \"projectId\" = ?"
                + " AND status = 'certified'").param(projectId).query(BigDecimal.class).single();
    }

    // ---- subcontracts -------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createSubcontract(String projectId, SubcontractInput in) {
        projects.project(projectId, false);
        projects.wbs(projectId, in.wbsId());
        if (in.supplierId() == null || jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" ="
                + " ?").params(in.supplierId(), org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Supplier not found");
        }
        if (in.value() == null || in.value().signum() <= 0) {
            throw ApiException.badRequest("value must be greater than 0");
        }
        BigDecimal pct = in.retentionPct() == null ? BigDecimal.ZERO : in.retentionPct();
        if (pct.signum() < 0 || pct.compareTo(BigDecimal.valueOf(100)) >= 0) {
            throw ApiException.badRequest("retentionPct must be between 0 and 100");
        }
        String condition = in.retentionCondition() == null ? "completion_certificate" : in.retentionCondition();
        if (!CONDITIONS.contains(condition)) {
            throw ApiException.badRequest("retentionCondition must be one of the following values:"
                    + " completion_certificate, defects_period, date_only");
        }
        String id = UUID.randomUUID().toString();
        String number = seq.next("subcontract", "SC/", 5);
        jdbc.sql("INSERT INTO \"Subcontract\" (id, \"organizationId\", \"projectId\", number, \"supplierId\", \"wbsId\","
                        + " scope, value, \"retentionPct\", \"retentionCondition\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?)")
                .params(id, org(), projectId, number, in.supplierId(), in.wbsId(), in.scope() == null ? "" : in.scope(),
                        money(in.value()), pct, condition, TenantContext.userId())
                .update();
        return SqlRows.one(jdbc.sql("SELECT * FROM \"Subcontract\" WHERE id = ?").param(id));
    }

    /** A subcontractor valuation: gross to project cost, retention payable held, the net billed through clearing. */
    @Transactional
    public Map<String, Object> valuation(String subcontractId, BigDecimal cumulativeValue, String date) {
        Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT * FROM \"Subcontract\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(subcontractId, org()));
        if (s == null) {
            throw ApiException.notFound("Subcontract not found");
        }
        if (!"active".equals(s.get("status"))) {
            throw ApiException.badRequest("Only an active subcontract can be valued");
        }
        String projectId = (String) s.get("projectId");
        Map<String, Object> p = projects.project(projectId, true);
        if (!"active".equals(p.get("status"))) {
            throw ApiException.badRequest("Cost can only be captured on an active project");
        }
        BigDecimal cumulative = money(cumulativeValue);
        BigDecimal previous = jdbc.sql("SELECT coalesce(max(\"cumulativeValue\"), 0) FROM \"SubcontractValuation\""
                + " WHERE \"subcontractId\" = ?").param(subcontractId).query(BigDecimal.class).single();
        if (cumulative.compareTo(previous) <= 0) {
            throw ApiException.badRequest("cumulativeValue must exceed the " + previous.toPlainString()
                    + " already valued");
        }
        if (cumulative.compareTo(SqlRows.dec(s, "value")) > 0) {
            throw ApiException.badRequest("cumulativeValue exceeds the subcontract value");
        }
        Claim c = ContractCalculator.claim(cumulative, previous, SqlRows.dec(s, "retentionPct"), BigDecimal.ZERO,
                BigDecimal.ZERO);
        String d = day(date) == null ? today().toString() : day(date);
        String id = UUID.randomUUID().toString();
        String number = seq.next("subcontract_valuation", "SCV/", 5);
        String label = "Subcontract " + s.get("number") + " · valuation " + number;
        String supplier = (String) s.get("supplierId");
        String clearing = subcontractClearingAccount();
        Map<String, Object> dims = Obj.of("projectId", projectId, "wbsId", s.get("wbsId"), "subcontractId",
                subcontractId);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(costs.wipAccount(), JsNumbers.dec(c.thisClaim()), null, supplier, label,
                (String) p.get("branchId"), (String) p.get("costCenterId"), dims));
        lines.add(new Line(clearing, null, JsNumbers.dec(c.net()), supplier, label, (String) p.get("branchId"), null,
                dims));
        if (c.retention().signum() > 0) {
            lines.add(new Line(retentionPayableAccount(), null, JsNumbers.dec(c.retention()), supplier, label
                    + " · retention", (String) p.get("branchId"), null, dims));
        }
        Map<String, Object> entry = posting.post(new PostingRequest("GEN", day0(d), label, null, null,
                "project_subcontract", id, null, "projects:subcontract:" + org() + ":" + id, (String) p.get("branchId"),
                null, null, lines));
        String je = entry == null ? null : (String) entry.get("id");
        String bill = c.net().signum() > 0 ? bill(supplier, d, label, clearing, c.net()) : null;
        String costLine = UUID.randomUUID().toString();
        costLine(costLine, projectId, (String) s.get("wbsId"), "project_subcontract", id, subcontractId, d,
                c.thisClaim(), clearing, je, label);
        jdbc.sql("INSERT INTO \"SubcontractValuation\" (id, \"organizationId\", \"subcontractId\", number,"
                        + " \"valuationDate\", \"cumulativeValue\", \"previousValue\", \"thisValuation\", retention,"
                        + " \"netAmount\", \"billId\", \"journalEntryId\", \"costLineId\", \"createdBy\") VALUES (?, ?, ?, ?,"
                        + " ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), subcontractId, number, d, cumulative, previous, c.thisClaim(), c.retention(),
                        c.net(), bill, je, costLine, TenantContext.userId())
                .update();
        if (c.retention().signum() > 0) {
            jdbc.sql("INSERT INTO \"RetentionLedger\" (id, \"organizationId\", \"projectId\", side, \"partnerId\","
                            + " \"valuationId\", amount, \"releaseCondition\", classification, \"journalEntryId\") VALUES"
                            + " (?, ?, ?, 'subcontractor', ?, ?, ?, ?, 'payable', ?)")
                    .params(UUID.randomUUID().toString(), org(), projectId, supplier, id, c.retention(),
                            s.get("retentionCondition"), je)
                    .update();
        }
        audit.recordInTx(AuditInput.of("SubcontractValuation", id, "post", null, Obj.of("thisValuation",
                c.thisClaim())));
        return SqlRows.one(jdbc.sql("SELECT * FROM \"SubcontractValuation\" WHERE id = ?").param(id));
    }

    /** A back-charge or defects deduction: reduces the retention payable and the project cost. */
    @Transactional
    public Map<String, Object> backCharge(String retentionId, BigDecimal amount, String reason) {
        Map<String, Object> r = lockedRetention(retentionId);
        if (!"subcontractor".equals(r.get("side")) || !"held".equals(r.get("status"))) {
            throw ApiException.badRequest("Back-charges apply to a held subcontractor retention");
        }
        BigDecimal a = money(amount);
        BigDecimal open = SqlRows.dec(r, "amount").subtract(SqlRows.dec(r, "backCharged"));
        if (a.signum() <= 0 || a.compareTo(open) > 0) {
            throw ApiException.badRequest("amount must be between 0 and the " + open.toPlainString() + " still held");
        }
        String projectId = (String) r.get("projectId");
        Map<String, Object> p = projects.project(projectId, false);
        String wbsId = jdbc.sql("SELECT s.\"wbsId\" FROM \"SubcontractValuation\" v JOIN \"Subcontract\" s ON s.id ="
                + " v.\"subcontractId\" WHERE v.id = ?").param(r.get("valuationId")).query(String.class).single();
        String id = UUID.randomUUID().toString();
        String label = "Back-charge · " + (reason == null ? "" : reason);
        Map<String, Object> dims = Obj.of("projectId", projectId, "wbsId", wbsId, "retentionId", retentionId);
        Map<String, Object> entry = posting.post(new PostingRequest("GEN", Instant.now(), label, null, null,
                "project_back_charge", id, null, "projects:back_charge:" + org() + ":" + id, (String) p.get("branchId"),
                null, null, List.of(new Line(retentionPayableAccount(), JsNumbers.dec(a), null,
                        (String) r.get("partnerId"), label, (String) p.get("branchId"), null, dims),
                        new Line(costs.wipAccount(), null, JsNumbers.dec(a), (String) r.get("partnerId"), label,
                                (String) p.get("branchId"), (String) p.get("costCenterId"), dims))));
        String je = entry == null ? null : (String) entry.get("id");
        costLine(id, projectId, wbsId, "project_back_charge", id, retentionId, today().toString(), a.negate(),
                retentionPayableAccount(), je, label);
        jdbc.sql("UPDATE \"RetentionLedger\" SET \"backCharged\" = \"backCharged\" + ? WHERE id = ?").params(a,
                retentionId).update();
        audit.recordInTx(AuditInput.of("RetentionLedger", retentionId, "update", null, Obj.of("backCharge", a)));
        return retention(retentionId);
    }

    // ---- retentions ---------------------------------------------------------------------------------------------------

    /**
     * Release a retention once its condition is met. Customer, conditional → invoice crediting the 1475 contract
     * asset (it becomes a receivable); customer, time-only → already invoiced at the release date (status only);
     * subcontractor → vendor bill debiting 2485 for what is still held after back-charges.
     */
    @Transactional
    public Map<String, Object> releaseRetention(String id, String evidence) {
        Map<String, Object> r = lockedRetention(id);
        if (!"held".equals(r.get("status"))) {
            throw ApiException.badRequest("This retention is already released");
        }
        String condition = (String) r.get("releaseCondition");
        if ("date_only".equals(condition)) {
            if (r.get("releaseDate") != null && LocalDate.parse(r.get("releaseDate").toString()).isAfter(today())) {
                throw ApiException.badRequest("This retention is released on " + r.get("releaseDate"));
            }
        } else if (evidence == null || evidence.isBlank()) {
            throw ApiException.badRequest("Give the " + ("defects_period".equals(condition)
                    ? "end of defects period certificate" : "practical completion certificate")
                    + " reference that meets the release condition");
        }
        BigDecimal open = SqlRows.dec(r, "amount").subtract(SqlRows.dec(r, "backCharged"));
        String projectId = (String) r.get("projectId");
        Map<String, Object> p = projects.project(projectId, false);
        String label = "Retention release · " + p.get("code") + (evidence == null ? "" : " · " + evidence);
        String doc = (String) r.get("releaseDocumentId");
        String classification = (String) r.get("classification");
        if ("customer".equals(r.get("side"))) {
            if ("contract_asset".equals(classification) && open.signum() > 0) {
                doc = invoice((String) r.get("partnerId"), today().toString(), null, label, retentionAssetAccount(),
                        open, null);
                classification = "receivable";
            }
        } else if (open.signum() > 0) {
            doc = bill((String) r.get("partnerId"), today().toString(), label, retentionPayableAccount(), open);
        }
        jdbc.sql("UPDATE \"RetentionLedger\" SET status = 'released', classification = ?, \"releaseDocumentId\" = ?,"
                + " \"releasedAt\" = now() WHERE id = ?").params(classification, doc, id).update();
        audit.recordInTx(AuditInput.of("RetentionLedger", id, "approve", null, Obj.of("releaseDocumentId", doc)));
        return retention(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> retentions(String projectId) {
        projects.project(projectId, false);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"RetentionLedger\" WHERE \"projectId\" = ? ORDER BY side,"
                + " \"createdAt\"").param(projectId));
    }

    private Map<String, Object> retention(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"RetentionLedger\" WHERE id = ?").param(id));
    }

    private Map<String, Object> lockedRetention(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"RetentionLedger\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Retention not found");
        }
        return r;
    }

    // ---- revenue recognition ------------------------------------------------------------------------------------------

    /** What a run for {@code periodEnd} would post (nothing is written). */
    @Transactional(readOnly = true)
    public Map<String, Object> previewRevenue(String projectId, String periodEnd) {
        return render(computeRevenue(projectId, periodEnd(periodEnd)));
    }

    @Transactional
    public Map<String, Object> runRevenue(String projectId, String periodEndText) {
        projects.project(projectId, true);
        LocalDate periodEnd = periodEnd(periodEndText);
        Map<String, Object> last = lastRun(projectId);
        if (last != null && !LocalDate.parse(last.get("periodEnd").toString()).isBefore(periodEnd)) {
            throw ApiException.conflict("Revenue is already recognised up to " + last.get("periodEnd"));
        }
        Rev r = computeRevenue(projectId, periodEnd);
        Map<String, Object> p = projects.project(projectId, false);
        String id = UUID.randomUUID().toString();
        String label = "Revenue recognition · " + p.get("code") + " · " + periodEnd;
        Map<String, Object> dims = Obj.of("projectId", projectId, "revenueRunId", id);
        List<Line> lines = new ArrayList<>();
        pair(lines, unbilledAccount(), revenueAccount(), r.revenueThisPeriod(), label, p, dims);
        pair(lines, costOfRevenueAccount(), costs.wipAccount(), r.costThisPeriod(), label + " · cost of revenue", p,
                dims);
        String je = null;
        if (!lines.isEmpty()) {
            Map<String, Object> entry = posting.post(new PostingRequest("GEN", periodEnd.atStartOfDay(ZoneOffset.UTC)
                    .toInstant(), label, null, null, "revenue_recognition", id, null, "projects:revenue:" + org() + ":"
                    + id, (String) p.get("branchId"), null, null, lines));
            je = entry == null ? null : (String) entry.get("id");
        }
        jdbc.sql("INSERT INTO \"RevenueRecognitionRun\" (id, \"organizationId\", \"projectId\", \"periodEnd\", method,"
                        + " \"transactionPrice\", \"costToDate\", \"estimatedTotalCost\", progress, \"cumulativeRevenue\","
                        + " \"revenueThisPeriod\", \"cumulativeCost\", \"costThisPeriod\", \"journalEntryId\","
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), projectId, Date.valueOf(periodEnd), r.method(), r.price(), r.costToDate(),
                        r.totalCost(), r.progress(), r.cumulativeRevenue(), r.revenueThisPeriod(), r.cumulativeCost(),
                        r.costThisPeriod(), je, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("RevenueRecognitionRun", id, "post", null, Obj.of("revenue",
                r.revenueThisPeriod())));
        return run(id);
    }

    /** Reverse the latest posted run of the contract. */
    @Transactional
    public Map<String, Object> reverseRevenue(String runId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"RevenueRecognitionRun\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(runId, org()));
        if (r == null) {
            throw ApiException.notFound("Revenue recognition run not found");
        }
        if (!"posted".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a posted run can be reversed");
        }
        Map<String, Object> last = lastRun((String) r.get("projectId"));
        if (last == null || !runId.equals(last.get("id"))) {
            throw ApiException.badRequest("Reverse the later runs of this contract first");
        }
        String reversal = r.get("journalEntryId") == null ? null : (String) posting.reverse((String) r.get(
                "journalEntryId"), Instant.now(), "Reversal of revenue recognition " + r.get("periodEnd")).get("id");
        jdbc.sql("UPDATE \"RevenueRecognitionRun\" SET status = 'reversed', \"reversalEntryId\" = ? WHERE id = ?")
                .params(reversal, runId).update();
        audit.recordInTx(AuditInput.of("RevenueRecognitionRun", runId, "cancel", null, null));
        return run(runId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs(String projectId) {
        projects.project(projectId, false);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"RevenueRecognitionRun\" WHERE \"projectId\" = ? ORDER BY"
                + " \"periodEnd\", \"createdAt\"").param(projectId));
    }

    private Map<String, Object> run(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"RevenueRecognitionRun\" WHERE id = ?").param(id));
    }

    private Map<String, Object> lastRun(String projectId) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"RevenueRecognitionRun\" WHERE \"projectId\" = ? AND status ="
                + " 'posted' ORDER BY \"periodEnd\" DESC LIMIT 1").param(projectId));
    }

    record Rev(String method, BigDecimal price, BigDecimal costToDate, BigDecimal totalCost, BigDecimal progress,
            BigDecimal cumulativeRevenue, BigDecimal revenueThisPeriod, BigDecimal cumulativeCost,
            BigDecimal costThisPeriod) {}

    private Rev computeRevenue(String projectId, LocalDate periodEnd) {
        Map<String, Object> p = projects.project(projectId, false);
        String method = method(projectId);
        BigDecimal price = transactionPrice(projectId);
        BigDecimal progressCost = costs.actual(projectId, true);
        BigDecimal allCost = costs.actual(projectId, false);
        BigDecimal etc = costs.costToComplete(projectId);
        BigDecimal progress;
        BigDecimal cumRevenue;
        BigDecimal cumCost;
        if ("over_time_cost_to_cost".equals(method)) {
            progress = ContractCalculator.progress(progressCost, etc);
            cumRevenue = ContractCalculator.cumulativeRevenue(price, progress);
            cumCost = progressCost;
        } else if (Set.of("completed", "closed").contains(p.get("status"))) {
            progress = BigDecimal.ONE.setScale(8);
            cumRevenue = price;
            cumCost = allCost;
        } else {
            progress = BigDecimal.ZERO.setScale(8);
            cumRevenue = BigDecimal.ZERO;
            cumCost = BigDecimal.ZERO;
        }
        Map<String, Object> last = lastRun(projectId);
        BigDecimal prevRevenue = last == null ? BigDecimal.ZERO : SqlRows.dec(last, "cumulativeRevenue");
        BigDecimal prevCost = last == null ? BigDecimal.ZERO : SqlRows.dec(last, "cumulativeCost");
        return new Rev(method, price, progressCost, progressCost.add(etc), progress, cumRevenue,
                cumRevenue.subtract(prevRevenue), cumCost, cumCost.subtract(prevCost));
    }

    private static Map<String, Object> render(Rev r) {
        return Obj.of("method", r.method(), "transactionPrice", JsNumbers.dec(r.price()), "costToDate",
                JsNumbers.dec(r.costToDate()), "estimatedTotalCost", JsNumbers.dec(r.totalCost()), "progress",
                JsNumbers.dec(r.progress()), "cumulativeRevenue", JsNumbers.dec(r.cumulativeRevenue()),
                "revenueThisPeriod", JsNumbers.dec(r.revenueThisPeriod()), "cumulativeCost",
                JsNumbers.dec(r.cumulativeCost()), "costThisPeriod", JsNumbers.dec(r.costThisPeriod()));
    }

    /** Cost released from project WIP to cost of revenue by posted runs. */
    public BigDecimal costReleased(String projectId) {
        return jdbc.sql("SELECT coalesce(sum(\"costThisPeriod\"), 0) FROM \"RevenueRecognitionRun\" WHERE \"projectId\" ="
                + " ? AND status = 'posted'").param(projectId).query(BigDecimal.class).single();
    }

    /** Revenue recognised to date. */
    public BigDecimal revenueRecognised(String projectId) {
        Map<String, Object> last = lastRun(projectId);
        return last == null ? BigDecimal.ZERO : SqlRows.dec(last, "cumulativeRevenue");
    }

    // ---- onerous contracts --------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> assessOnerous(String projectId, OnerousAssessmentInput in) {
        projects.project(projectId, false);
        if (in.indicators() == null || in.indicators().isEmpty()) {
            throw ApiException.badRequest("An onerous-contract assessment is opened by an indicator: negative_margin,"
                    + " eac_increase, dispute, penalty_notice or management_flag");
        }
        for (String i : in.indicators()) {
            if (!INDICATORS.contains(i)) {
                throw ApiException.badRequest("indicators must be among the following values: negative_margin,"
                        + " eac_increase, dispute, penalty_notice, management_flag");
            }
        }
        if (in.costOfExiting() != null && (in.exitEvidence() == null || in.exitEvidence().isBlank())) {
            throw ApiException.badRequest("A cost of exiting needs its evidence (contract clause, legal advice)");
        }
        if (in.impairmentRecognised() != null && in.impairmentRecognised().signum() > 0
                && (in.impairmentReference() == null || in.impairmentReference().isBlank())) {
            throw ApiException.badRequest("Reference the impairment of the dedicated assets (IAS 37.69)");
        }
        BigDecimal incremental = BigDecimal.ZERO;
        BigDecimal related = BigDecimal.ZERO;
        BigDecimal excluded = BigDecimal.ZERO;
        for (Map<String, Object> f : projects.forecast(projectId)) {
            BigDecimal etc = SqlRows.dec(f, "costToComplete");
            switch (String.valueOf(f.get("fulfilmentClass"))) {
                case "directly_related" -> related = related.add(etc);
                case "excluded" -> excluded = excluded.add(etc);
                default -> incremental = incremental.add(etc);
            }
        }
        BigDecimal remaining = transactionPrice(projectId).subtract(revenueRecognised(projectId)).max(BigDecimal.ZERO);
        BigDecimal claims = in.probableClaims() == null ? BigDecimal.ZERO : money(in.probableClaims());
        OnerousResult r = ContractCalculator.onerous(new OnerousInput(remaining, claims, incremental, related,
                in.costOfExiting() == null ? null : money(in.costOfExiting()), in.impairmentRecognised() == null
                        ? BigDecimal.ZERO : money(in.impairmentRecognised())));
        BigDecimal previous = onerousBalance(projectId);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"OnerousContractAssessment\" (id, \"organizationId\", \"projectId\", \"asOf\","
                        + " indicators, \"remainingPrice\", \"probableClaims\", \"expectedBenefits\", \"incrementalCost\","
                        + " \"directlyRelatedCost\", \"excludedCost\", \"costOfFulfilling\", \"costOfExiting\","
                        + " \"exitEvidence\", \"unavoidableCost\", \"impairmentRecognised\", \"impairmentReference\","
                        + " provision, \"previousProvision\", movement, \"createdBy\") VALUES (?, ?, ?, ?::date, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), projectId, day(in.asOf()) == null ? today().toString() : day(in.asOf()),
                        String.join(",", in.indicators()), remaining, claims, r.expectedBenefits(), incremental,
                        related, excluded, r.costOfFulfilling(), in.costOfExiting() == null ? null
                                : money(in.costOfExiting()), in.exitEvidence(), r.unavoidableCost(),
                        in.impairmentRecognised() == null ? BigDecimal.ZERO : money(in.impairmentRecognised()),
                        in.impairmentReference(), r.provision(), previous, r.provision().subtract(previous),
                        TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("OnerousContractAssessment", id, "create", null, Obj.of("provision",
                r.provision())));
        return onerous(id);
    }

    @Transactional
    public Map<String, Object> approveOnerous(String id) {
        Map<String, Object> a = lockedOnerous(id);
        if (!"draft".equals(a.get("status"))) {
            throw ApiException.badRequest("Only a draft assessment can be approved");
        }
        notSelf(a.get("createdBy"), "An onerous-contract assessment must be approved by someone other than its"
                + " author");
        jdbc.sql("UPDATE \"OnerousContractAssessment\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" ="
                + " now() WHERE id = ?").params(TenantContext.userId(), id).update();
        audit.recordInTx(AuditInput.of("OnerousContractAssessment", id, "approve", null, null));
        return onerous(id);
    }

    /** Post the movement of the provision to what this approved assessment measured (recognise / utilise / release). */
    @Transactional
    public Map<String, Object> postOnerous(String id) {
        Map<String, Object> a = lockedOnerous(id);
        if (!"approved".equals(a.get("status"))) {
            throw ApiException.badRequest("Only an approved assessment can be posted");
        }
        String projectId = (String) a.get("projectId");
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("onerous:" + projectId).query()
                .listOfRows();
        Map<String, Object> p = projects.project(projectId, false);
        BigDecimal previous = onerousBalance(projectId);
        BigDecimal movement = SqlRows.dec(a, "provision").subtract(previous);
        String je = null;
        if (movement.signum() != 0) {
            String label = "Onerous contract " + p.get("code") + (movement.signum() > 0 ? " · provision"
                    : " · utilisation / release");
            List<Line> lines = new ArrayList<>();
            pair(lines, onerousExpenseAccount(), onerousProvisionAccount(), movement, label, p, Obj.of("projectId",
                    projectId, "onerousAssessmentId", id));
            Map<String, Object> entry = posting.post(new PostingRequest("GEN", Instant.now(), label, null, null,
                    "onerous_contract", id, null, "projects:onerous:" + org() + ":" + id, (String) p.get("branchId"),
                    null, null, lines));
            je = entry == null ? null : (String) entry.get("id");
        }
        jdbc.sql("UPDATE \"OnerousContractAssessment\" SET status = 'posted', \"previousProvision\" = ?, movement = ?,"
                + " \"journalEntryId\" = ? WHERE id = ?").params(previous, movement, je, id).update();
        audit.recordInTx(AuditInput.of("OnerousContractAssessment", id, "post", null, Obj.of("movement", movement)));
        return onerous(id);
    }

    @Transactional
    public Map<String, Object> rejectOnerous(String id) {
        Map<String, Object> a = lockedOnerous(id);
        if (!Set.of("draft", "approved").contains(a.get("status"))) {
            throw ApiException.badRequest("Only a draft or approved assessment can be rejected");
        }
        jdbc.sql("UPDATE \"OnerousContractAssessment\" SET status = 'rejected' WHERE id = ?").param(id).update();
        return onerous(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> onerousAssessments(String projectId) {
        projects.project(projectId, false);
        return SqlRows.list(jdbc.sql("SELECT * FROM \"OnerousContractAssessment\" WHERE \"projectId\" = ? ORDER BY"
                + " \"createdAt\"").param(projectId));
    }

    private Map<String, Object> onerous(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"OnerousContractAssessment\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Onerous-contract assessment not found");
        }
        return a;
    }

    private Map<String, Object> lockedOnerous(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"OnerousContractAssessment\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Onerous-contract assessment not found");
        }
        return a;
    }

    /** The provision currently carried for the contract (Σ posted movements). */
    public BigDecimal onerousBalance(String projectId) {
        return jdbc.sql("SELECT coalesce(sum(movement), 0) FROM \"OnerousContractAssessment\" WHERE \"projectId\" = ?"
                + " AND status = 'posted'").param(projectId).query(BigDecimal.class).single();
    }

    // ---- contract position --------------------------------------------------------------------------------------------

    /**
     * Contract position (presented net per contract): revenue recognised vs gross billings gives the contract asset
     * (unbilled) or the contract liability; retentions, advances, provision; and the organisation-wide sub-ledger vs
     * GL tie-out of the retention, advance and provision accounts.
     */
    @Transactional
    public Map<String, Object> position(String projectId) {
        projects.project(projectId, false);
        BigDecimal revenue = revenueRecognised(projectId);
        BigDecimal billed = jdbc.sql("SELECT coalesce(sum(\"thisClaim\"), 0) FROM \"ProgressClaim\" WHERE"
                + " \"projectId\" = ? AND status = 'certified'").param(projectId).query(BigDecimal.class).single();
        BigDecimal net = revenue.subtract(billed);
        Map<String, Object> ret = SqlRows.one(jdbc.sql("SELECT coalesce(sum(CASE WHEN side = 'customer' AND status ="
                + " 'held' AND classification = 'contract_asset' THEN amount ELSE 0 END), 0) AS asset, coalesce(sum(CASE"
                + " WHEN side = 'customer' AND classification = 'receivable' THEN amount ELSE 0 END), 0) AS receivable,"
                + " coalesce(sum(CASE WHEN side = 'subcontractor' AND status = 'held' THEN amount - \"backCharged\" ELSE 0"
                + " END), 0) AS payable FROM \"RetentionLedger\" WHERE \"projectId\" = ?").param(projectId));
        BigDecimal advances = jdbc.sql("SELECT coalesce(sum(amount - recovered), 0) FROM \"CustomerAdvance\" WHERE"
                + " \"projectId\" = ?").param(projectId).query(BigDecimal.class).single();
        return Obj.of("projectId", projectId, "method", method(projectId), "transactionPrice",
                JsNumbers.dec(transactionPrice(projectId)), "revenueRecognised", JsNumbers.dec(revenue),
                "grossBilled", JsNumbers.dec(billed), "contractAsset", JsNumbers.dec(net.max(BigDecimal.ZERO)),
                "contractLiability", JsNumbers.dec(net.negate().max(BigDecimal.ZERO)), "retentionContractAsset",
                JsNumbers.dec(SqlRows.dec(ret, "asset")), "retentionReceivable",
                JsNumbers.dec(SqlRows.dec(ret, "receivable")), "retentionPayable",
                JsNumbers.dec(SqlRows.dec(ret, "payable")), "advancesOutstanding", JsNumbers.dec(advances),
                "onerousProvision", JsNumbers.dec(onerousBalance(projectId)), "glTieOut", glTieOut());
    }

    /** Organisation-wide: each contract sub-ledger vs its GL account (difference must be 0). */
    @Transactional
    public Map<String, Object> glTieOut() {
        BigDecimal retentionAsset = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"RetentionLedger\" WHERE"
                + " \"organizationId\" = ? AND side = 'customer' AND status = 'held' AND classification ="
                + " 'contract_asset'").param(org()).query(BigDecimal.class).single();
        BigDecimal payable = jdbc.sql("SELECT coalesce(sum(amount - \"backCharged\"), 0) FROM \"RetentionLedger\" WHERE"
                + " \"organizationId\" = ? AND side = 'subcontractor' AND status = 'held'").param(org())
                .query(BigDecimal.class).single();
        BigDecimal advances = jdbc.sql("SELECT coalesce(sum(amount - recovered), 0) FROM \"CustomerAdvance\" WHERE"
                + " \"organizationId\" = ?").param(org()).query(BigDecimal.class).single();
        BigDecimal provision = jdbc.sql("SELECT coalesce(sum(movement), 0) FROM \"OnerousContractAssessment\" WHERE"
                + " \"organizationId\" = ? AND status = 'posted'").param(org()).query(BigDecimal.class).single();
        return Obj.of("retentionContractAsset", tie(retentionAsset, gl(retentionAssetAccount())), "retentionPayable",
                tie(payable, gl(retentionPayableAccount()).negate()), "customerAdvances", tie(advances,
                        gl(advancesAccount()).negate()), "onerousProvision", tie(provision,
                        gl(onerousProvisionAccount()).negate()));
    }

    private static Map<String, Object> tie(BigDecimal sub, BigDecimal gl) {
        return Obj.of("subledger", JsNumbers.dec(sub), "gl", JsNumbers.dec(gl), "difference",
                JsNumbers.dec(gl.subtract(sub)));
    }

    private BigDecimal gl(String accountId) {
        return jdbc.sql("SELECT coalesce(sum(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM \"JournalLine\" jl JOIN"
                + " \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = ? AND"
                + " jl.\"accountId\" = ? AND e.status <> 'draft'").params(org(), accountId).query(BigDecimal.class)
                .single();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private String customer(Map<String, Object> p) {
        String c = (String) p.get("customerId");
        if (c == null) {
            throw ApiException.badRequest("The project needs a customer for contract billing");
        }
        return c;
    }

    private String invoice(String partner, String date, String dueDate, String label, String account,
            BigDecimal amount, String taxId) {
        Map<String, Object> doc = invoices.create(new InvoiceInput(partner, date, dueDate, null, null, null, null,
                null, null, null, null, null, null, null, null, label, label, List.of(LineInput.of(null, account,
                        label, BigDecimal.ONE, amount, BigDecimal.ZERO, taxId, null))));
        String id = (String) doc.get("id");
        invoices.post(id);
        return id;
    }

    private String bill(String partner, String date, String label, String account, BigDecimal amount) {
        Map<String, Object> doc = bills.create(new BillInput(partner, date, null, null, label, label, null, List.of(),
                List.of(LineInput.of(null, account, label, BigDecimal.ONE, amount, BigDecimal.ZERO, null, null))));
        String id = (String) doc.get("id");
        bills.post(id, false, false);
        return id;
    }

    private void costLine(String id, String projectId, String wbsId, String sourceType, String sourceId,
            String sourceLineId, String date, BigDecimal amount, String creditAccount, String je, String label) {
        jdbc.sql("INSERT INTO \"ProjectCostLine\" (id, \"organizationId\", \"projectId\", \"wbsId\", \"costElement\","
                        + " \"sourceType\", \"sourceId\", \"sourceLineId\", date, amount, \"creditAccountId\","
                        + " \"journalEntryId\", description, \"createdBy\") VALUES (?, ?, ?, ?, 'subcontract', ?, ?, ?,"
                        + " ?::date, ?, ?, ?, ?, ?)")
                .params(id, org(), projectId, wbsId, sourceType, sourceId, sourceLineId, date, amount, creditAccount,
                        je, label, TenantContext.userId())
                .update();
    }

    private static void pair(List<Line> lines, String debitAccount, String creditAccount, BigDecimal amount,
            String label, Map<String, Object> p, Map<String, Object> dims) {
        if (amount.signum() == 0) {
            return;
        }
        BigDecimal a = amount.abs().setScale(2, RoundingMode.HALF_UP);
        String dr = amount.signum() > 0 ? debitAccount : creditAccount;
        String cr = amount.signum() > 0 ? creditAccount : debitAccount;
        lines.add(new Line(dr, JsNumbers.dec(a), null, (String) p.get("customerId"), label, (String) p.get("branchId"),
                (String) p.get("costCenterId"), dims));
        lines.add(new Line(cr, null, JsNumbers.dec(a), (String) p.get("customerId"), label, (String) p.get("branchId"),
                (String) p.get("costCenterId"), dims));
    }

    private static void notSelf(Object author, String message) {
        if (TenantContext.userId() != null && TenantContext.userId().equals(author)) {
            throw ApiException.forbidden(message);
        }
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private static Instant day0(String d) {
        return LocalDate.parse(d).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static LocalDate periodEnd(String v) {
        String d = day(v);
        if (d == null) {
            throw ApiException.badRequest("periodEnd should not be empty");
        }
        LocalDate p = LocalDate.parse(d);
        if (p.isAfter(today())) {
            throw ApiException.badRequest("periodEnd cannot be in the future");
        }
        return p;
    }
}
