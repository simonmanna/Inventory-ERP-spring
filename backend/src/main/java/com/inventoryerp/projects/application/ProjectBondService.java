package com.inventoryerp.projects.application;

import com.inventoryerp.accounting.application.AccountingDay;
import static com.inventoryerp.projects.application.ProjectService.blank;
import static com.inventoryerp.projects.application.ProjectService.day;
import static com.inventoryerp.projects.application.ProjectService.org;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bonds and guarantees (ADR-S074 §1): performance bonds, bank guarantees, advance-payment guarantees, retention bonds
 * and bid bonds — {@code issued} by us (our bank or insurer guarantees us to a client) or {@code received} by us (a
 * subcontractor's or supplier's bank guarantees them to us).
 *
 * <p>Lifecycle: {@code draft → active → extended / reduced → released | called | expired}. Optional GL, amounts in the
 * base currency, each posting keyed by its lifecycle event:
 * <table>
 *   <caption>postings</caption>
 *   <tr><th>Event</th><th>Posting</th></tr>
 *   <tr><td>cash margin lodged (activate)</td><td>Dr bond_margin_deposit / Cr bank</td></tr>
 *   <tr><td>bank commission (activate, extend)</td><td>Dr bank_charges / Cr bank</td></tr>
 *   <tr><td>margin returned (reduce, release)</td><td>Dr bank / Cr bond_margin_deposit</td></tr>
 *   <tr><td>call on a bond we received</td><td>Dr bank / Cr project cost (WIP or CIP, a negative cost line) or
 *       bond_claim_income</td></tr>
 *   <tr><td>call on a bond we issued</td><td>Dr bond_call_expense / Cr bond_margin_deposit (margin held) and bank
 *       (the rest)</td></tr>
 * </table>
 * Bonds past their expiry date are marked {@code expired} by {@link ProjectBondJob}; a held margin stays until released.
 */
@Service
public class ProjectBondService {

    static final Set<String> TYPES = Set.of("performance", "bank_guarantee", "advance_payment", "retention", "bid");
    static final Set<String> LIVE = Set.of("active", "extended", "reduced");

    public record BondInput(String bondType, String direction, String projectId, String subcontractId,
            String counterpartyId, String issuer, String issuerType, String bondNumber, BigDecimal amount,
            String currencyCode, String issueDate, String expiryDate, String documentId, String notes) {}

    /** Inputs of a lifecycle step; each step reads the fields it needs. */
    public record ActionInput(String date, BigDecimal amount, String expiryDate, BigDecimal margin,
            BigDecimal commission, String bankAccountId, String recoveryTo, String wbsId, String reason) {}

    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final ProjectCostService costs;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AccountDeterminationService determination;
    private final SequenceService seq;
    private final AuditService audit;

    public ProjectBondService(JdbcClient jdbc, ProjectService projects, ProjectCostService costs,
            PostingService posting, AccountResolverService accounts, AccountDeterminationService determination,
            SequenceService seq, AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.costs = costs;
        this.posting = posting;
        this.accounts = accounts;
        this.determination = determination;
        this.seq = seq;
        this.audit = audit;
    }

    // ---- accounts -----------------------------------------------------------------------------------------------------

    public String marginAccount() {
        return accounts.ensureByCode("1482", "Bond & Guarantee Margin Deposits", "current_asset",
                "bond_margin_deposit");
    }

    public String bankChargesAccount() {
        return accounts.ensureByCode("6730", "Bank Charges & Guarantee Commission", "operating_expense",
                "bank_charges");
    }

    public String callExpenseAccount() {
        return accounts.ensureByCode("6995", "Bonds & Guarantees Called", "other_expense", "bond_call_expense");
    }

    public String claimIncomeAccount() {
        return accounts.ensureByCode("4295", "Bond & Guarantee Claim Income", "other_income", "bond_claim_income");
    }

    // ---- register -----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String projectId, String status, String direction, String bondType) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT b.*, p.name AS \"counterpartyName\","
                        + " pr.code AS \"projectCode\", pr.name AS \"projectName\", s.number AS \"subcontractNumber\""
                        + " FROM \"ProjectBond\" b JOIN \"Partner\" p ON p.id = b.\"counterpartyId\" LEFT JOIN \"Project\" pr"
                        + " ON pr.id = b.\"projectId\" LEFT JOIN \"Subcontract\" s ON s.id = b.\"subcontractId\""
                        + " WHERE b.\"organizationId\" = :org AND (CAST(:project AS text) IS NULL OR b.\"projectId\" = :project)"
                        + " AND (CAST(:status AS text) IS NULL OR b.status = :status)"
                        + " AND (CAST(:direction AS text) IS NULL OR b.direction = :direction)"
                        + " AND (CAST(:type AS text) IS NULL OR b.\"bondType\" = :type)"
                        + " ORDER BY b.\"expiryDate\", b.number")
                .param("org", org()).param("project", blank(projectId)).param("status", blank(status))
                .param("direction", blank(direction)).param("type", blank(bondType)));
        rows.forEach(this::decorate);
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> b = SqlRows.one(jdbc.sql("SELECT b.*, p.name AS \"counterpartyName\", pr.code AS"
                + " \"projectCode\", pr.name AS \"projectName\", s.number AS \"subcontractNumber\" FROM \"ProjectBond\" b"
                + " JOIN \"Partner\" p ON p.id = b.\"counterpartyId\" LEFT JOIN \"Project\" pr ON pr.id = b.\"projectId\""
                + " LEFT JOIN \"Subcontract\" s ON s.id = b.\"subcontractId\" WHERE b.id = ? AND"
                + " b.\"organizationId\" = ?").params(id, org()));
        if (b == null) {
            throw ApiException.notFound("Bond not found");
        }
        decorate(b);
        b.put("events", SqlRows.list(jdbc.sql("SELECT * FROM \"ProjectBondEvent\" WHERE \"bondId\" = ? ORDER BY"
                + " \"createdAt\", id").param(id)));
        return b;
    }

    private void decorate(Map<String, Object> b) {
        LocalDate expiry = (LocalDate) b.get("expiryDate");
        b.put("daysToExpiry", LIVE.contains(b.get("status")) && expiry != null
                ? ChronoUnit.DAYS.between(today(), expiry) : null);
    }

    @Transactional
    public Map<String, Object> create(BondInput in) {
        Fields f = validate(in, null);
        String id = UUID.randomUUID().toString();
        String number = seq.next("project_bond", "BG/", 5);
        jdbc.sql("INSERT INTO \"ProjectBond\" (id, \"organizationId\", number, \"bondType\", direction, \"projectId\","
                        + " \"subcontractId\", \"counterpartyId\", issuer, \"issuerType\", \"bondNumber\", amount,"
                        + " \"originalAmount\", \"currencyCode\", \"issueDate\", \"expiryDate\", \"documentId\", notes,"
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?)")
                .params(id, org(), number, f.type, f.direction, f.projectId, f.subcontractId, f.counterpartyId,
                        f.issuer, f.issuerType, blank(in.bondNumber()), f.amount, f.amount, f.currency, f.issueDate,
                        f.expiryDate, blank(in.documentId()), blank(in.notes()), TenantContext.userId())
                .update();
        event(id, "create", f.issueDate, null, f.amount, null, f.expiryDate, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, null, null);
        audit.recordInTx(AuditInput.of("ProjectBond", id, "create", null, Obj.of("number", number, "bondType",
                f.type, "direction", f.direction, "amount", JsNumbers.dec(f.amount))));
        return get(id);
    }

    /** Change a draft (every field may change; omitted fields stay). */
    @Transactional
    public Map<String, Object> update(String id, BondInput in) {
        Map<String, Object> b = locked(id);
        if (!"draft".equals(b.get("status"))) {
            throw ApiException.badRequest("Only a draft bond can be changed; use extend or reduce");
        }
        BondInput merged = new BondInput(or(in.bondType(), b.get("bondType")), or(in.direction(), b.get("direction")),
                in.projectId() == null ? (String) b.get("projectId") : in.projectId(), in.subcontractId() == null
                        ? (String) b.get("subcontractId") : in.subcontractId(), or(in.counterpartyId(),
                        b.get("counterpartyId")), or(in.issuer(), b.get("issuer")), or(in.issuerType(),
                        b.get("issuerType")), in.bondNumber() == null ? (String) b.get("bondNumber") : in.bondNumber(),
                in.amount() == null ? SqlRows.dec(b, "amount") : in.amount(), or(in.currencyCode(),
                        b.get("currencyCode")), or(in.issueDate(), b.get("issueDate")), or(in.expiryDate(),
                        b.get("expiryDate")), in.documentId() == null ? (String) b.get("documentId") : in.documentId(),
                in.notes() == null ? (String) b.get("notes") : in.notes());
        Fields f = validate(merged, id);
        jdbc.sql("UPDATE \"ProjectBond\" SET \"bondType\" = ?, direction = ?, \"projectId\" = ?, \"subcontractId\" = ?,"
                        + " \"counterpartyId\" = ?, issuer = ?, \"issuerType\" = ?, \"bondNumber\" = ?, amount = ?,"
                        + " \"originalAmount\" = ?, \"currencyCode\" = ?, \"issueDate\" = ?::date, \"expiryDate\" = ?::date,"
                        + " \"documentId\" = ?, notes = ?, \"updatedAt\" = now() WHERE id = ?")
                .params(f.type, f.direction, f.projectId, f.subcontractId, f.counterpartyId, f.issuer, f.issuerType,
                        blank(merged.bondNumber()), f.amount, f.amount, f.currency, f.issueDate, f.expiryDate,
                        blank(merged.documentId()), blank(merged.notes()), id)
                .update();
        audit.recordInTx(AuditInput.of("ProjectBond", id, "update", null, null));
        return get(id);
    }

    private record Fields(String type, String direction, String projectId, String subcontractId,
            String counterpartyId, String issuer, String issuerType, BigDecimal amount, String currency,
            String issueDate, String expiryDate) {}

    private Fields validate(BondInput in, String id) {
        if (in.bondType() == null || !TYPES.contains(in.bondType())) {
            throw ApiException.badRequest("bondType must be one of the following values: performance, bank_guarantee,"
                    + " advance_payment, retention, bid");
        }
        if (!"issued".equals(in.direction()) && !"received".equals(in.direction())) {
            throw ApiException.badRequest("direction must be one of the following values: issued, received");
        }
        String issuerType = in.issuerType() == null ? "bank" : in.issuerType();
        if (!"bank".equals(issuerType) && !"insurer".equals(issuerType)) {
            throw ApiException.badRequest("issuerType must be one of the following values: bank, insurer");
        }
        if (blank(in.issuer()) == null) {
            throw ApiException.badRequest("issuer should not be empty");
        }
        String projectId = blank(in.projectId());
        if (projectId != null) {
            projects.project(projectId, false);
        }
        String subcontractId = blank(in.subcontractId());
        String counterparty = blank(in.counterpartyId());
        if (subcontractId != null) {
            Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT * FROM \"Subcontract\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(subcontractId, org()));
            if (s == null) {
                throw ApiException.notFound("Subcontract not found");
            }
            if (projectId == null) {
                projectId = (String) s.get("projectId");
            } else if (!projectId.equals(s.get("projectId"))) {
                throw ApiException.badRequest("The subcontract belongs to another project");
            }
            if (counterparty == null) {
                counterparty = (String) s.get("supplierId");
            }
        }
        if (counterparty == null || jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(counterparty, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Counterparty not found");
        }
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw ApiException.badRequest("amount must be greater than 0");
        }
        String issue = day(in.issueDate());
        String expiry = day(in.expiryDate());
        if (issue == null) {
            throw ApiException.badRequest("issueDate should not be empty");
        }
        if (expiry == null) {
            throw ApiException.badRequest("expiryDate should not be empty");
        }
        if (expiry.compareTo(issue) < 0) {
            throw ApiException.badRequest("expiryDate must not be before issueDate");
        }
        String currency = blank(in.currencyCode()) == null ? posting.baseCurrency()
                : in.currencyCode().trim().toUpperCase();
        return new Fields(in.bondType(), in.direction(), projectId, subcontractId, counterparty, in.issuer().trim(),
                issuerType, money(in.amount()), currency, issue, expiry);
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------------

    /** Draft → active; for a bond we issue, optionally the cash margin lodged and the bank's commission. */
    @Transactional
    public Map<String, Object> activate(String id, ActionInput in) {
        Map<String, Object> b = locked(id);
        if (!"draft".equals(b.get("status"))) {
            throw ApiException.badRequest("Only a draft bond can be activated");
        }
        BigDecimal margin = nonNegative(in.margin(), "margin");
        BigDecimal commission = nonNegative(in.commission(), "commission");
        ourSideOnly(b, margin.add(commission));
        String d = date(in.date());
        String eventId = UUID.randomUUID().toString();
        List<Line> lines = new ArrayList<>();
        String bank = bank(in.bankAccountId(), margin.add(commission));
        String label = "Bond " + b.get("number") + " · activation";
        if (margin.signum() > 0) {
            lines.add(line(marginAccount(), margin, null, b, label + " · margin"));
        }
        if (commission.signum() > 0) {
            lines.add(line(bankChargesAccount(), commission, null, b, label + " · commission"));
        }
        if (!lines.isEmpty()) {
            lines.add(line(bank, null, margin.add(commission), b, label));
        }
        String je = post(lines, label, d, eventId, b);
        jdbc.sql("UPDATE \"ProjectBond\" SET status = 'active', \"marginHeld\" = \"marginHeld\" + ?, \"commissionPaid\" ="
                + " \"commissionPaid\" + ?, \"updatedAt\" = now() WHERE id = ?").params(margin, commission, id).update();
        event(eventId, id, "activate", d, null, SqlRows.dec(b, "amount"), null, (LocalDate) b.get("expiryDate"), margin,
                commission, BigDecimal.ZERO, null, je, null, in.reason());
        audit.recordInTx(AuditInput.of("ProjectBond", id, "approve", Obj.of("status", "draft"), Obj.of("status",
                "active", "journalEntryId", je)));
        return get(id);
    }

    /** A later expiry date; optionally the bank's commission for the extension. */
    @Transactional
    public Map<String, Object> extend(String id, ActionInput in) {
        Map<String, Object> b = live(id);
        String expiry = day(in.expiryDate());
        LocalDate current = (LocalDate) b.get("expiryDate");
        if (expiry == null) {
            throw ApiException.badRequest("expiryDate should not be empty");
        }
        if (!LocalDate.parse(expiry).isAfter(current)) {
            throw ApiException.badRequest("expiryDate must be after the current expiry date " + current);
        }
        BigDecimal commission = nonNegative(in.commission(), "commission");
        ourSideOnly(b, commission);
        String d = date(in.date());
        String eventId = UUID.randomUUID().toString();
        String label = "Bond " + b.get("number") + " · extension to " + expiry;
        String je = null;
        if (commission.signum() > 0) {
            je = post(List.of(line(bankChargesAccount(), commission, null, b, label), line(bank(in.bankAccountId(),
                    commission), null, commission, b, label)), label, d, eventId, b);
        }
        jdbc.sql("UPDATE \"ProjectBond\" SET status = 'extended', \"expiryDate\" = ?::date, \"commissionPaid\" ="
                + " \"commissionPaid\" + ?, \"updatedAt\" = now() WHERE id = ?").params(expiry, commission, id).update();
        event(eventId, id, "extend", d, SqlRows.dec(b, "amount"), SqlRows.dec(b, "amount"), current,
                LocalDate.parse(expiry), BigDecimal.ZERO, commission, BigDecimal.ZERO, null, je, null, in.reason());
        audit.recordInTx(AuditInput.of("ProjectBond", id, "update", Obj.of("expiryDate", current.toString()),
                Obj.of("expiryDate", expiry)));
        return get(id);
    }

    /** A lower guaranteed amount (work progressed, advance recovered); optionally part of the margin returned. */
    @Transactional
    public Map<String, Object> reduce(String id, ActionInput in) {
        Map<String, Object> b = live(id);
        BigDecimal current = SqlRows.dec(b, "amount");
        if (in.amount() == null || in.amount().signum() <= 0 || money(in.amount()).compareTo(current) >= 0) {
            throw ApiException.badRequest("amount must be greater than 0 and below the current " + current
                    .toPlainString());
        }
        BigDecimal amount = money(in.amount());
        BigDecimal release = nonNegative(in.margin(), "margin");
        if (release.compareTo(SqlRows.dec(b, "marginHeld")) > 0) {
            throw ApiException.badRequest("margin exceeds the " + SqlRows.dec(b, "marginHeld").toPlainString()
                    + " held");
        }
        String d = date(in.date());
        String eventId = UUID.randomUUID().toString();
        String label = "Bond " + b.get("number") + " · reduced to " + amount.toPlainString();
        String je = release.signum() > 0 ? returnMargin(b, release, in.bankAccountId(), label, d, eventId) : null;
        jdbc.sql("UPDATE \"ProjectBond\" SET status = 'reduced', amount = ?, \"marginHeld\" = \"marginHeld\" - ?,"
                + " \"updatedAt\" = now() WHERE id = ?").params(amount, release, id).update();
        event(eventId, id, "reduce", d, current, amount, null, null, release.negate(), BigDecimal.ZERO,
                BigDecimal.ZERO, null, je, null, in.reason());
        audit.recordInTx(AuditInput.of("ProjectBond", id, "update", Obj.of("amount", JsNumbers.dec(current)),
                Obj.of("amount", JsNumbers.dec(amount))));
        return get(id);
    }

    /** The guarantee is handed back; any margin still held is returned to the bank account. */
    @Transactional
    public Map<String, Object> release(String id, ActionInput in) {
        Map<String, Object> b = locked(id);
        if (!LIVE.contains(b.get("status")) && !"expired".equals(b.get("status"))) {
            throw ApiException.badRequest("This bond is " + b.get("status") + " and cannot be released");
        }
        BigDecimal held = SqlRows.dec(b, "marginHeld");
        String d = date(in.date());
        String eventId = UUID.randomUUID().toString();
        String label = "Bond " + b.get("number") + " · release";
        String je = held.signum() > 0 ? returnMargin(b, held, in.bankAccountId(), label, d, eventId) : null;
        jdbc.sql("UPDATE \"ProjectBond\" SET status = 'released', \"marginHeld\" = 0, \"updatedAt\" = now() WHERE id ="
                + " ?").param(id).update();
        event(eventId, id, "release", d, SqlRows.dec(b, "amount"), SqlRows.dec(b, "amount"), null, null, held.negate(),
                BigDecimal.ZERO, BigDecimal.ZERO, null, je, null, in.reason());
        audit.recordInTx(AuditInput.of("ProjectBond", id, "update", Obj.of("status", b.get("status")),
                Obj.of("status", "released", "journalEntryId", je)));
        return get(id);
    }

    /**
     * The bond is called. A bond we received: the issuer pays us — Dr bank / Cr the project's cost (a negative cost line
     * on the subcontract's WBS, or {@code wbsId}) or claim income. A bond we issued: the client is paid — Dr bonds
     * called / Cr the margin held, the rest from the bank.
     */
    @Transactional
    public Map<String, Object> call(String id, ActionInput in) {
        Map<String, Object> b = live(id);
        BigDecimal face = SqlRows.dec(b, "amount");
        BigDecimal amount = in.amount() == null ? face : money(in.amount());
        if (amount.signum() <= 0 || amount.compareTo(face) > 0) {
            throw ApiException.badRequest("amount must be greater than 0 and at most the " + face.toPlainString()
                    + " guaranteed");
        }
        if (in.reason() == null || in.reason().isBlank()) {
            throw ApiException.badRequest("reason should not be empty");
        }
        String d = date(in.date());
        String eventId = UUID.randomUUID().toString();
        String label = "Bond " + b.get("number") + " · called · " + in.reason().trim();
        List<Line> lines = new ArrayList<>();
        String recoveryTo = null;
        String costLine = null;
        BigDecimal marginUsed = BigDecimal.ZERO;
        if ("received".equals(b.get("direction"))) {
            recoveryTo = in.recoveryTo();
            if (!"project_cost".equals(recoveryTo) && !"claim_income".equals(recoveryTo)) {
                throw ApiException.badRequest("recoveryTo must be one of the following values: project_cost,"
                        + " claim_income");
            }
            lines.add(line(bank(in.bankAccountId(), amount), amount, null, b, label));
            if ("project_cost".equals(recoveryTo)) {
                String projectId = (String) b.get("projectId");
                if (projectId == null) {
                    throw ApiException.badRequest("A recovery to project cost needs a bond linked to a project");
                }
                Map<String, Object> p = projects.project(projectId, false);
                String wbsId = blank(in.wbsId());
                if (wbsId == null && b.get("subcontractId") != null) {
                    wbsId = jdbc.sql("SELECT \"wbsId\" FROM \"Subcontract\" WHERE id = ?").param(b.get("subcontractId"))
                            .query(String.class).single();
                }
                if (wbsId == null) {
                    throw ApiException.badRequest("wbsId should not be empty");
                }
                projects.wbs(projectId, wbsId);
                String account = costs.costAccount(p);
                lines.add(new Line(account, null, JsNumbers.dec(amount), (String) b.get("counterpartyId"), label,
                        (String) p.get("branchId"), (String) p.get("costCenterId"), Obj.of("projectId", projectId,
                                "wbsId", wbsId, "bondId", id)));
                String je = post(lines, label, d, eventId, b);
                costLine = UUID.randomUUID().toString();
                // A bond recovery is a back-charge to the subcontractor: it reduces the project cost like one.
                jdbc.sql("INSERT INTO \"ProjectCostLine\" (id, \"organizationId\", \"projectId\", \"wbsId\","
                                + " \"costElement\", \"sourceType\", \"sourceId\", \"sourceLineId\", date, amount,"
                                + " \"creditAccountId\", \"journalEntryId\", description, \"createdBy\") VALUES (?, ?, ?, ?,"
                                + " 'subcontract', 'project_back_charge', ?, ?, ?::date, ?, ?, ?, ?, ?)")
                        .params(costLine, org(), projectId, wbsId, eventId, id, d, amount.negate(), account, je, label,
                                TenantContext.userId())
                        .update();
                return finishCall(b, id, eventId, d, face, amount, marginUsed, recoveryTo, je, costLine, in.reason());
            }
            lines.add(line(claimIncomeAccount(), null, amount, b, label));
        } else {
            marginUsed = SqlRows.dec(b, "marginHeld").min(amount);
            BigDecimal fromBank = amount.subtract(marginUsed);
            lines.add(line(callExpenseAccount(), amount, null, b, label));
            if (marginUsed.signum() > 0) {
                lines.add(line(marginAccount(), null, marginUsed, b, label + " · margin applied"));
            }
            if (fromBank.signum() > 0) {
                lines.add(line(bank(in.bankAccountId(), fromBank), null, fromBank, b, label));
            }
        }
        String je = post(lines, label, d, eventId, b);
        return finishCall(b, id, eventId, d, face, amount, marginUsed, recoveryTo, je, costLine, in.reason());
    }

    private Map<String, Object> finishCall(Map<String, Object> b, String id, String eventId, String d, BigDecimal face,
            BigDecimal amount, BigDecimal marginUsed, String recoveryTo, String je, String costLine, String reason) {
        jdbc.sql("UPDATE \"ProjectBond\" SET status = 'called', \"calledAmount\" = ?, \"marginHeld\" = \"marginHeld\" -"
                + " ?, \"updatedAt\" = now() WHERE id = ?").params(amount, marginUsed, id).update();
        event(eventId, id, "call", d, face, face, null, null, marginUsed.negate(), BigDecimal.ZERO, amount, recoveryTo,
                je, costLine, reason);
        audit.recordInTx(AuditInput.of("ProjectBond", id, "update", Obj.of("status", b.get("status")),
                Obj.of("status", "called", "calledAmount", JsNumbers.dec(amount), "journalEntryId", je)));
        return get(id);
    }

    /** Mark the organisation's live bonds past their expiry date as expired (the daily job). */
    @Transactional
    public int expireDue() {
        List<Map<String, Object>> due = SqlRows.list(jdbc.sql("SELECT id, amount, \"expiryDate\" FROM \"ProjectBond\""
                + " WHERE \"organizationId\" = ? AND status IN ('active', 'extended', 'reduced') AND \"expiryDate\" <"
                + " ?::date FOR UPDATE SKIP LOCKED").params(org(), today().toString()));
        for (Map<String, Object> b : due) {
            String id = (String) b.get("id");
            jdbc.sql("UPDATE \"ProjectBond\" SET status = 'expired', \"updatedAt\" = now() WHERE id = ?").param(id)
                    .update();
            event(id, "expire", today().toString(), SqlRows.dec(b, "amount"), SqlRows.dec(b, "amount"),
                    (LocalDate) b.get("expiryDate"), (LocalDate) b.get("expiryDate"), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, null, null, null, "Expired");
        }
        return due.size();
    }

    // ---- exposure -----------------------------------------------------------------------------------------------------

    /**
     * Live exposure (active, extended, reduced) by issuing bank / insurer and by project, per direction and currency,
     * with what expires within 30 days and the margin held.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> exposure() {
        String base = "FROM \"ProjectBond\" b LEFT JOIN \"Project\" pr ON pr.id = b.\"projectId\" WHERE"
                + " b.\"organizationId\" = ? AND b.status IN ('active', 'extended', 'reduced')";
        String sums = " count(*) AS bonds, sum(b.amount) AS amount, sum(CASE WHEN b.\"expiryDate\" <= ?::date + 30 THEN"
                + " b.amount ELSE 0 END) AS \"expiring30\", sum(b.\"marginHeld\") AS \"marginHeld\", min(b.\"expiryDate\")"
                + " AS \"nextExpiry\" ";
        List<Map<String, Object>> byBank = SqlRows.list(jdbc.sql("SELECT b.issuer, b.\"issuerType\", b.direction,"
                + " b.\"currencyCode\"," + sums + base + " GROUP BY 1, 2, 3, 4 ORDER BY 3, 5 DESC").params(
                today().toString(), org()));
        List<Map<String, Object>> byProject = SqlRows.list(jdbc.sql("SELECT b.\"projectId\", pr.code AS \"projectCode\","
                + " pr.name AS \"projectName\", b.direction, b.\"currencyCode\"," + sums + base + " GROUP BY 1, 2, 3, 4,"
                + " 5 ORDER BY 2 NULLS LAST, 4").params(today().toString(), org()));
        List<Map<String, Object>> totals = SqlRows.list(jdbc.sql("SELECT b.direction, b.\"currencyCode\"," + sums
                + base + " GROUP BY 1, 2 ORDER BY 1, 2").params(today().toString(), org()));
        return Obj.of("asOf", today().toString(), "byBank", byBank, "byProject", byProject, "totals", totals);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private Map<String, Object> locked(String id) {
        Map<String, Object> b = SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectBond\" WHERE id = ? AND \"organizationId\""
                + " = ? FOR UPDATE").params(id, org()));
        if (b == null) {
            throw ApiException.notFound("Bond not found");
        }
        return b;
    }

    private Map<String, Object> live(String id) {
        Map<String, Object> b = locked(id);
        if (!LIVE.contains(b.get("status"))) {
            throw ApiException.badRequest("This bond is " + b.get("status") + "; only an active bond can change");
        }
        return b;
    }

    private static void ourSideOnly(Map<String, Object> b, BigDecimal amount) {
        if (amount.signum() > 0 && !"issued".equals(b.get("direction"))) {
            throw ApiException.badRequest("Margin and commission are booked only on bonds we issue");
        }
    }

    private String returnMargin(Map<String, Object> b, BigDecimal amount, String bankAccountId, String label, String d,
            String eventId) {
        return post(List.of(line(bank(bankAccountId, amount), amount, null, b, label + " · margin returned"),
                line(marginAccount(), null, amount, b, label + " · margin returned")), label, d, eventId, b);
    }

    private String bank(String bankAccountId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return null;
        }
        String id = blank(bankAccountId);
        if (id == null) {
            return determination.mapped("default_bank");
        }
        if (jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS"
                + " NULL").params(id, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Bank account not found");
        }
        return id;
    }

    private static Line line(String account, BigDecimal debit, BigDecimal credit, Map<String, Object> b,
            String label) {
        Map<String, Object> dims = new LinkedHashMap<>();
        if (b.get("projectId") != null) {
            dims.put("projectId", b.get("projectId"));
        }
        dims.put("bondId", b.get("id"));
        return new Line(account, debit == null ? null : JsNumbers.dec(debit), credit == null ? null
                : JsNumbers.dec(credit), (String) b.get("counterpartyId"), label, null, null, dims);
    }

    private String post(List<Line> lines, String label, String d, String eventId, Map<String, Object> b) {
        if (lines.isEmpty()) {
            return null;
        }
        Map<String, Object> entry = posting.post(new PostingRequest("GEN", LocalDate.parse(d).atStartOfDay(
                ZoneOffset.UTC).toInstant(), label, null, null, "project_bond", (String) b.get("id"), null,
                "projects:bond:" + org() + ":" + eventId, null, null, null, lines));
        return entry == null ? null : (String) entry.get("id");
    }

    private void event(String bondId, String action, String d, BigDecimal previousAmount, BigDecimal amount,
            LocalDate previousExpiry, LocalDate expiry, BigDecimal margin, BigDecimal commission, BigDecimal called,
            String recoveryTo, String je, String costLine, String reason) {
        event(UUID.randomUUID().toString(), bondId, action, d, previousAmount, amount, previousExpiry, expiry, margin,
                commission, called, recoveryTo, je, costLine, reason);
    }

    private void event(String id, String bondId, String action, String d, BigDecimal previousAmount,
            BigDecimal amount, LocalDate previousExpiry, LocalDate expiry, BigDecimal margin, BigDecimal commission,
            BigDecimal called, String recoveryTo, String je, String costLine, String reason) {
        jdbc.sql("INSERT INTO \"ProjectBondEvent\" (id, \"organizationId\", \"bondId\", action, \"eventDate\","
                        + " \"previousAmount\", amount, \"previousExpiry\", \"expiryDate\", margin, commission,"
                        + " \"calledAmount\", \"recoveryTo\", \"journalEntryId\", \"costLineId\", reason, \"createdBy\")"
                        + " VALUES (?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), bondId, action, d, previousAmount, amount, previousExpiry == null ? null
                        : java.sql.Date.valueOf(previousExpiry), expiry == null ? null : java.sql.Date.valueOf(expiry),
                        margin, commission, called, recoveryTo, je, costLine, blank(reason), TenantContext.userId())
                .update();
    }

    private void event(String bondId, String action, String d, BigDecimal previousAmount, BigDecimal amount,
            String previousExpiry, String expiry, BigDecimal margin, BigDecimal commission, BigDecimal called,
            String recoveryTo, String je, String costLine, String reason) {
        event(bondId, action, d, previousAmount, amount, previousExpiry == null ? null : LocalDate.parse(previousExpiry),
                expiry == null ? null : LocalDate.parse(expiry), margin, commission, called, recoveryTo, je, costLine,
                reason);
    }

    private static BigDecimal nonNegative(BigDecimal v, String field) {
        BigDecimal m = money(v);
        if (m.signum() < 0) {
            throw ApiException.badRequest(field + " must not be negative");
        }
        return m;
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String or(String v, Object fallback) {
        return v != null ? v : fallback == null ? null : fallback.toString();
    }

    private String date(String v) {
        String d = day(v);
        return d == null ? today().toString() : d;
    }

    /** The organisation-local day (ADR-S070): bond events and expiry dates are org calendar days. */
    LocalDate today() {
        return LocalDate.now(AccountingDay.zone(jdbc));
    }
}
