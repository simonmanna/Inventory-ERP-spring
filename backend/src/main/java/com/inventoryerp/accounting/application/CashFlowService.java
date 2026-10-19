package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money accounts and explicit treasury operations (port of {@code cash-flow.service.ts}): payment-account
 * listing with balances / today's flow / restrictions, deposits and withdrawals constrained by operation type,
 * per-account transactions with running balances and the org-wide movement log.
 */
@Service
public class CashFlowService {

    private record Operation(String key, String label, List<String> classifications) {}

    private static final List<Operation> DEPOSIT_OPERATIONS = List.of(
            new Operation("owner_contribution", "Owner contribution", List.of("equity")),
            new Operation("loan_received", "Loan received", List.of("liability")),
            new Operation("other_income", "Other income (non-sales)", List.of("revenue")),
            new Operation("refund_received", "Refund received from supplier", List.of("expense")));

    private static final List<Operation> WITHDRAWAL_OPERATIONS = List.of(
            new Operation("owner_drawing", "Owner drawing", List.of("equity")),
            new Operation("bank_charge", "Bank / provider charge", List.of("expense")),
            new Operation("expense", "Direct expense", List.of("expense")),
            new Operation("loan_repayment", "Loan repayment", List.of("liability")),
            new Operation("tax_payment", "Tax payment", List.of("liability")));

    private static final Map<String, String> PAYMENT_TYPE_TO_CATEGORY = Map.of("cash", "cash", "bank", "bank",
            "mobile_money", "mobile_money", "petty_cash", "petty_cash");

    /** Deposit / withdrawal body. */
    public record OperationInput(String accountId, String counterpartAccountId, String operationType, BigDecimal amount,
            String description, String date) {}

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AuditService audit;

    public CashFlowService(JdbcClient jdbc, PostingService posting, AccountResolverService accounts, AuditService audit) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.accounts = accounts;
        this.audit = audit;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String fixed2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Payment (cash-equivalent) accounts with balances, today's flow, registers, POS methods and restrictions. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCashAccounts() {
        String orgId = TenantContext.organizationId();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.*, c.key AS cat_key FROM \"Account\" a"
                + " JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ? AND c.\"isCashEquivalent\" = true"
                + " AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL ORDER BY a.\"sortOrder\" ASC, a.code ASC").param(orgId));
        Map<String, BigDecimal> balance = new HashMap<>();
        Map<String, Object> lastActivity = new HashMap<>();
        for (Map<String, Object> g : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d, sum(l.\"baseCredit\") AS c,"
                + " max(l.\"createdAt\") AS last FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + " WHERE l.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') GROUP BY l.\"accountId\"").param(orgId))) {
            balance.put((String) g.get("accountId"), SqlRows.dec(g, "d").subtract(SqlRows.dec(g, "c")));
            lastActivity.put((String) g.get("accountId"), g.get("last"));
        }
        Map<String, Object> org = SqlRows.one(jdbc.sql("SELECT \"currencyCode\", timezone FROM \"Organization\" WHERE id = ?").param(orgId));
        ZoneId zone = OrgDates.orgTimezone(jdbc, orgId);
        Instant[] today = OrgDates.zonedDayRange(OrgDates.orgToday(zone, Instant.now()), zone);
        Map<String, BigDecimal[]> todayMap = new HashMap<>();
        for (Map<String, Object> t : SqlRows.list(jdbc.sql("SELECT l.\"accountId\", sum(l.\"baseDebit\") AS d, sum(l.\"baseCredit\") AS c"
                + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                + " AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ?"
                + " GROUP BY l.\"accountId\"").params(orgId, FiscalPeriodControl.ts(today[0]), FiscalPeriodControl.ts(today[1])))) {
            todayMap.put((String) t.get("accountId"), new BigDecimal[] {SqlRows.dec(t, "d"), SqlRows.dec(t, "c")});
        }
        Map<String, List<Map<String, Object>>> methodsOf = new HashMap<>();
        for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT id, code, label, kind, \"isActive\", \"accountId\" FROM \"PosPaymentMethod\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"accountId\" IS NOT NULL"
                + " ORDER BY \"sortOrder\" ASC, label ASC").param(orgId))) {
            methodsOf.computeIfAbsent((String) m.get("accountId"), k -> new ArrayList<>())
                    .add(body("id", m.get("id"), "code", m.get("code"), "label", m.get("label"), "kind", m.get("kind"),
                            "isActive", m.get("isActive")));
        }
        Set<String> openDrawerIds = new HashSet<>(jdbc.sql("SELECT \"drawerAccountId\" FROM \"CashSession\" WHERE \"organizationId\" = ?"
                + " AND status = 'open' AND \"drawerAccountId\" IS NOT NULL").param(orgId).query(String.class).list());
        Object baseCurrency = org == null ? null : org.get("currencyCode");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : rows) {
            String id = (String) a.get("id");
            List<Map<String, Object>> registers = SqlRows.list(jdbc.sql("SELECT id, name, code, \"isActive\" FROM \"CashRegister\""
                    + " WHERE \"defaultAccountId\" = ? AND \"deletedAt\" IS NULL").param(id));
            boolean isDrawer = !registers.isEmpty() || openDrawerIds.contains(id);
            BigDecimal[] t = todayMap.get(id);
            BigDecimal bal = balance.get(id);
            out.add(body("id", id, "code", a.get("code"), "name", a.get("name"), "accountType", a.get("cat_key"),
                    "currencyId", a.get("currencyId"), "currencyCode", a.get("currencyId") != null ? a.get("currencyId") : baseCurrency,
                    "baseCurrency", baseCurrency, "bankName", a.get("bankName"), "accountNumber", a.get("accountNumber"),
                    "isDefault", a.get("isDefault"), "balance", bal == null ? "0" : plain(bal),
                    "cashRegister", registers.isEmpty() ? null : registers.getFirst(), "registers", registers,
                    "posMethods", methodsOf.getOrDefault(id, List.of()), "restrictions", isDrawer ? List.of("drawer") : List.of(),
                    "lastActivityAt", lastActivity.get(id), "todayIn", fixed2(t == null ? BigDecimal.ZERO : t[0]),
                    "todayOut", fixed2(t == null ? BigDecimal.ZERO : t[1])));
        }
        return out;
    }

    /** Create a payment account under its payment category. */
    @Transactional
    public Map<String, Object> create(String code, String name, String accountType, String currencyId, String bankName,
            String accountNumber, Boolean isDefault) {
        String orgId = TenantContext.organizationId();
        if (code == null || code.isEmpty() || name == null || name.isEmpty()) {
            throw ApiException.badRequest("Code and name are required");
        }
        String categoryKey = PAYMENT_TYPE_TO_CATEGORY.get(accountType);
        if (categoryKey == null) {
            throw ApiException.badRequest("Account type must be a payment account type");
        }
        Map<String, Object> category = SqlRows.one(jdbc.sql("SELECT id, \"normalBalance\"::text AS nb FROM \"AccountCategory\""
                + " WHERE key = ? LIMIT 1").param(categoryKey));
        if (category == null) {
            throw ApiException.badRequest("Account category '" + categoryKey + "' is missing for this organization. Run the"
                    + " accounting backfill (prisma/backfill-account-category.ts).");
        }
        long existing = jdbc.sql("SELECT count(*) FROM \"Account\" WHERE \"organizationId\" = ? AND code = ?").params(orgId, code)
                .query(Long.class).single();
        if (existing > 0) {
            throw ApiException.badRequest("Account code already exists");
        }
        if (Boolean.TRUE.equals(isDefault)) {
            jdbc.sql("SELECT id FROM \"AccountCategory\" WHERE id = ? FOR UPDATE").param(category.get("id")).query(String.class).list();
            jdbc.sql("UPDATE \"Account\" SET \"isDefault\" = false, \"updatedAt\" = now() WHERE \"organizationId\" = ?"
                    + " AND \"categoryId\" = ? AND \"isDefault\" = true AND \"deletedAt\" IS NULL").params(orgId, category.get("id")).update();
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Account\" (id, \"organizationId\", code, name, \"categoryId\", \"normalBalance\", \"currencyId\","
                        + " \"bankName\", \"accountNumber\", \"isDefault\", \"cashFlowCategory\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?::\"NormalBalance\", ?, ?, ?, ?, 'operating', now())")
                .params(id, orgId, code, name, category.get("id"), category.get("nb"), currencyId, bankName, accountNumber,
                        Boolean.TRUE.equals(isDefault))
                .update();
        accounts.invalidate(orgId);
        return accountRow(id);
    }

    private Map<String, Object> accountRow(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"Account\" WHERE id = ?").param(id));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> dto) {
        String orgId = TenantContext.organizationId();
        Map<String, Object> account = SqlRows.one(jdbc.sql("SELECT * FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(id, orgId));
        if (account == null) {
            throw ApiException.notFound("Account not found");
        }
        if (Boolean.TRUE.equals(dto.get("isDefault"))) {
            jdbc.sql("SELECT id FROM \"AccountCategory\" WHERE id = ? FOR UPDATE").param(account.get("categoryId")).query(String.class).list();
            jdbc.sql("UPDATE \"Account\" SET \"isDefault\" = false, \"updatedAt\" = now() WHERE \"organizationId\" = ?"
                            + " AND \"categoryId\" = ? AND \"isDefault\" = true AND id <> ? AND \"deletedAt\" IS NULL")
                    .params(orgId, account.get("categoryId"), id).update();
        }
        for (String f : List.of("name", "currencyId", "bankName", "accountNumber", "isDefault")) {
            if (dto.get(f) != null) {
                jdbc.sql("UPDATE \"Account\" SET \"" + f + "\" = ?, \"updatedAt\" = now() WHERE id = ?").params(dto.get(f), id).update();
            }
        }
        jdbc.sql("UPDATE \"Account\" SET \"updatedAt\" = now() WHERE id = ?").param(id).update();
        accounts.invalidate(orgId);
        return accountRow(id);
    }

    @Transactional
    public void remove(String id) {
        String orgId = TenantContext.organizationId();
        long found = jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, orgId).query(Long.class).single();
        if (found == 0) {
            throw ApiException.notFound("Account not found");
        }
        long registers = jdbc.sql("SELECT count(*) FROM \"CashRegister\" WHERE \"organizationId\" = ? AND \"defaultAccountId\" = ?"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL").params(orgId, id).query(Long.class).single();
        long methods = jdbc.sql("SELECT count(*) FROM \"PosPaymentMethod\" WHERE \"organizationId\" = ? AND \"accountId\" = ?"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL").params(orgId, id).query(Long.class).single();
        long openSessions = jdbc.sql("SELECT count(*) FROM \"CashSession\" s JOIN \"CashRegister\" r ON r.id = s.\"cashRegisterId\""
                + " WHERE s.\"organizationId\" = ? AND s.status = 'open' AND r.\"defaultAccountId\" = ?").params(orgId, id)
                .query(Long.class).single();
        if (registers > 0 || methods > 0 || openSessions > 0) {
            throw ApiException.badRequest("Account is in active use by a register, payment method or open shift");
        }
        jdbc.sql("UPDATE \"Account\" SET \"deletedAt\" = now(), \"isActive\" = false, \"updatedAt\" = now() WHERE id = ?")
                .param(id).update();
        accounts.invalidate(orgId);
    }

    /** Operation catalogue + the counterpart accounts each operation may use. */
    @Transactional(readOnly = true)
    public Map<String, Object> operationTypes() {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name, c.classification::text AS cls,"
                + " coalesce(c.\"isCashEquivalent\", false) AS cash_eq FROM \"Account\" a LEFT JOIN \"AccountCategory\" c"
                + " ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ? AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL"
                + " AND a.\"isPostable\" = true ORDER BY a.code ASC").param(TenantContext.organizationId()));
        java.util.function.Function<Operation, List<Map<String, Object>>> eligible = op -> rows.stream()
                .filter(a -> !Boolean.TRUE.equals(a.get("cash_eq")) && op.classifications().contains(String.valueOf(a.get("cls"))))
                .map(a -> body("id", a.get("id"), "code", a.get("code"), "name", a.get("name"), "classification", a.get("cls")))
                .toList();
        return body("deposit", DEPOSIT_OPERATIONS.stream().map(op -> body("key", op.key(), "label", op.label(),
                        "accounts", eligible.apply(op))).toList(),
                "withdrawal", WITHDRAWAL_OPERATIONS.stream().map(op -> body("key", op.key(), "label", op.label(),
                        "accounts", eligible.apply(op))).toList());
    }

    @Transactional
    public Map<String, Object> deposit(OperationInput dto) {
        return postOperation("deposit", dto);
    }

    @Transactional
    public Map<String, Object> withdraw(OperationInput dto) {
        return postOperation("withdrawal", dto);
    }

    private Map<String, Object> postOperation(String direction, OperationInput dto) {
        BigDecimal amount = dto.amount() == null ? BigDecimal.ZERO : Money.dec(dto.amount());
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        List<Operation> catalogue = "deposit".equals(direction) ? DEPOSIT_OPERATIONS : WITHDRAWAL_OPERATIONS;
        Operation op = catalogue.stream().filter(o -> o.key().equals(dto.operationType())).findFirst().orElse(null);
        if (op == null) {
            throw ApiException.badRequest("Choose a " + direction + " type: "
                    + String.join(", ", catalogue.stream().map(Operation::key).toList()));
        }
        if (dto.description() == null || dto.description().trim().isEmpty()) {
            throw ApiException.badRequest("A description is required for every treasury operation");
        }
        if (dto.counterpartAccountId().equals(dto.accountId())) {
            throw ApiException.badRequest("Counterpart account must differ from the payment account");
        }
        String orgId = TenantContext.organizationId();
        Instant postingDate = OrgDates.orgPostingInstant(dto.date(), OrgDates.orgTimezone(jdbc, orgId), Instant.now());
        TreasuryGuards.lockAccounts(jdbc, orgId, List.of(dto.accountId(), dto.counterpartAccountId()));
        TreasuryGuards.AccountRow account = TreasuryGuards.requireAccount(jdbc, orgId, dto.accountId(), "Payment account");
        if (!account.isCashEquivalent()) {
            throw ApiException.badRequest("Account is not a cash, bank or mobile-money account");
        }
        TreasuryGuards.assertNotDrawerAccount(jdbc, orgId, account.id(), "deposit".equals(direction) ? "A deposit" : "A withdrawal");
        TreasuryGuards.AccountRow counterpart = TreasuryGuards.requireAccount(jdbc, orgId, dto.counterpartAccountId(),
                "Counterpart account");
        if (counterpart.isCashEquivalent()) {
            throw ApiException.badRequest("Moving money between payment accounts is a transfer, not a deposit or withdrawal");
        }
        String classification = jdbc.sql("SELECT c.classification::text FROM \"Account\" a LEFT JOIN \"AccountCategory\" c"
                + " ON c.id = a.\"categoryId\" WHERE a.id = ?").param(counterpart.id()).query(String.class).optional().orElse(null);
        if (!op.classifications().contains(String.valueOf(classification))) {
            throw ApiException.badRequest(op.label() + " must use a " + String.join(" or ", op.classifications()) + " account; "
                    + counterpart.code() + " is " + (classification == null ? "undefined" : classification));
        }
        if ("withdrawal".equals(direction)) {
            TreasuryGuards.assertSufficientFunds(jdbc, orgId, account.id(), amount, account.name());
        }
        String id = TreasuryGuards.operationId();
        String description = op.label() + ": " + dto.description().trim();
        String amt = plain(amount);
        List<PostingRequest.Line> lines = "deposit".equals(direction)
                ? List.of(PostingRequest.Line.debit(account.id(), amt, null), PostingRequest.Line.credit(counterpart.id(), amt, null))
                : List.of(PostingRequest.Line.debit(counterpart.id(), amt, null), PostingRequest.Line.credit(account.id(), amt, null));
        String journal = "cash".equals(account.categoryKey()) || "petty_cash".equals(account.categoryKey()) ? "CASH" : "BANK";
        Map<String, Object> entry = posting.post(new PostingRequest(journal, postingDate, description, null, null,
                "deposit".equals(direction) ? "cash_flow_deposit" : "cash_flow_withdrawal", id, null,
                "cash-flow:" + direction + ":" + id, null, null, Map.of("treasuryOperation", op.key()), lines));
        audit.recordInTx(AuditInput.of("TreasuryOperation", id, "create", null, body("direction", direction,
                "operationType", op.key(), "accountId", account.id(), "counterpartAccountId", counterpart.id(),
                "amount", amt, "journalEntryId", entry.get("id"), "description", description)));
        BusinessOutcome.record(jdbc, entry, true);
        return entry;
    }

    /** One account's lines (newest first) with authoritative running balances. */
    @Transactional(readOnly = true)
    public Map<String, Object> getTransactions(String accountId, int page, int pageSize) {
        String orgId = TenantContext.organizationId();
        Map<String, Object> account = SqlRows.one(jdbc.sql("SELECT a.*, c.key AS cat_key FROM \"Account\" a LEFT JOIN \"AccountCategory\" c"
                + " ON c.id = a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL").params(accountId, orgId));
        if (account == null) {
            throw ApiException.badRequest("Account not found");
        }
        List<Map<String, Object>> registers = SqlRows.list(jdbc.sql("SELECT id, code, name FROM \"CashRegister\""
                + " WHERE \"defaultAccountId\" = ? AND \"deletedAt\" IS NULL").param(accountId));
        String base = " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                + " AND l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed')";
        long total = jdbc.sql("SELECT count(*)" + base).params(orgId, accountId).query(Long.class).single();
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.*, e.\"entryNumber\" AS e_number, e.\"postingDate\" AS e_date,"
                        + " e.description AS e_description, e.\"sourceType\" AS e_source, e.\"sourceId\" AS e_source_id" + base
                        + " ORDER BY l.\"createdAt\" DESC, l.id DESC LIMIT ? OFFSET ?")
                .params(orgId, accountId, pageSize, (page - 1) * pageSize));
        BigDecimal currentBalance = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\"), 0) - coalesce(sum(l.\"baseCredit\"), 0)" + base)
                .params(orgId, accountId).query(BigDecimal.class).single();
        int offset = (page - 1) * pageSize;
        BigDecimal newerDelta = BigDecimal.ZERO;
        if (offset > 0) {
            newerDelta = jdbc.sql("SELECT COALESCE(SUM(x.\"baseDebit\" - x.\"baseCredit\"), 0) FROM (SELECT l.\"baseDebit\", l.\"baseCredit\""
                            + base + " ORDER BY l.\"createdAt\" DESC, l.id DESC LIMIT ?) x")
                    .params(orgId, accountId, offset).query(BigDecimal.class).single();
        }
        Map<String, String[]> paymentOf = paymentsOf(orgId, lines.stream().filter(l -> "payment".equals(l.get("e_source"))
                && l.get("e_source_id") != null).map(l -> (String) l.get("e_source_id")).toList());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            String[] p = paymentOf.get((String) l.get("e_source_id"));
            String effective = MoneyActivityTaxonomy.effectiveSourceType((String) l.get("e_source"), p == null ? null : p[0],
                    p == null ? null : p[1], p != null);
            BigDecimal running = currentBalance.subtract(newerDelta);
            newerDelta = newerDelta.add(SqlRows.dec(l, "baseDebit").subtract(SqlRows.dec(l, "baseCredit")));
            String category = MoneyActivityTaxonomy.categoryOf(effective);
            rows.add(body("id", l.get("id"), "journalEntryId", l.get("journalEntryId"), "entryNumber", l.get("e_number"),
                    "postingDate", l.get("e_date"), "description", l.get("e_description") != null ? l.get("e_description") : l.get("description"),
                    "sourceType", l.get("e_source"), "sourceId", l.get("e_source_id"), "category", category,
                    "categoryLabel", MoneyActivityTaxonomy.CATEGORY_LABEL.getOrDefault(category, "Other"),
                    "debit", plain(SqlRows.dec(l, "debit")), "credit", plain(SqlRows.dec(l, "credit")),
                    "baseDebit", plain(SqlRows.dec(l, "baseDebit")), "baseCredit", plain(SqlRows.dec(l, "baseCredit")),
                    "runningBalance", plain(running)));
        }
        return body("data", rows, "total", total, "page", page, "pageSize", pageSize,
                "totalPages", (long) Math.ceil(total / (double) pageSize),
                "account", body("id", account.get("id"), "code", account.get("code"), "name", account.get("name"),
                        "accountType", account.get("cat_key"), "bankName", account.get("bankName"),
                        "accountNumber", account.get("accountNumber"), "currencyId", account.get("currencyId"),
                        "currentBalance", plain(currentBalance), "cashRegister", registers.isEmpty() ? null : registers.getFirst(),
                        "registers", registers));
    }

    private Map<String, String[]> paymentsOf(String orgId, List<String> ids) {
        Map<String, String[]> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, \"cashSessionId\", direction::text FROM \"Payment\" WHERE \"organizationId\" = :org AND id IN (:ids)")
                .param("org", orgId).param("ids", ids)
                .query((rs, n) -> out.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)})).list();
        return out;
    }

    /** Org-wide movement log: every entry touching a cash-equivalent account, classified by the shared taxonomy. */
    @Transactional(readOnly = true)
    public Map<String, Object> getAllTransactions(int page, int pageSize, String type, String search) {
        String orgId = TenantContext.organizationId();
        Set<String> cashIds = new HashSet<>(accounts.cashEquivalentIds());
        String typeSource = "deposit".equals(type) ? "cash_flow_deposit" : "withdrawal".equals(type) ? "cash_flow_withdrawal"
                : "transfer".equals(type) ? "treasury_transfer" : null;
        String q = search == null ? null : search.trim();
        StringBuilder where = new StringBuilder(" FROM \"JournalEntry\" je WHERE je.\"organizationId\" = :org"
                + " AND je.status::text IN ('posted', 'reversed')"
                + " AND EXISTS (SELECT 1 FROM \"JournalLine\" l WHERE l.\"journalEntryId\" = je.id AND l.\"accountId\" IN (:cash))");
        if (typeSource != null) {
            where.append(" AND je.\"sourceType\" = :src");
        }
        if (q != null && !q.isEmpty()) {
            where.append(" AND (je.\"entryNumber\" ILIKE :q ESCAPE '\\' OR je.description ILIKE :q ESCAPE '\\'"
                    + " OR EXISTS (SELECT 1 FROM \"JournalLine\" l2 JOIN \"Account\" a2 ON a2.id = l2.\"accountId\""
                    + " WHERE l2.\"journalEntryId\" = je.id AND (a2.name ILIKE :q ESCAPE '\\' OR a2.code ILIKE :q ESCAPE '\\')))");
        }
        List<String> cash = cashIds.isEmpty() ? List.of("") : List.copyOf(cashIds);
        var count = jdbc.sql("SELECT count(*)" + where).param("org", orgId).param("cash", cash);
        var list = jdbc.sql("SELECT je.*" + where + " ORDER BY je.\"postingDate\" DESC, je.\"createdAt\" DESC LIMIT :lim OFFSET :off")
                .param("org", orgId).param("cash", cash).param("lim", pageSize).param("off", (page - 1) * pageSize);
        if (typeSource != null) {
            count = count.param("src", typeSource);
            list = list.param("src", typeSource);
        }
        if (q != null && !q.isEmpty()) {
            String pattern = "%" + BaseCrudService.escapeLike(q) + "%";
            count = count.param("q", pattern);
            list = list.param("q", pattern);
        }
        long total = count.query(Long.class).single();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> entry : SqlRows.list(list)) {
            List<Map<String, Object>> cashLines = SqlRows.list(jdbc.sql("SELECT l.*, a.name AS a_name, a.code AS a_code FROM \"JournalLine\" l"
                    + " LEFT JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? ORDER BY l.\"lineNumber\" ASC")
                    .param(entry.get("id"))).stream().filter(l -> cashIds.contains((String) l.get("accountId"))).toList();
            String sourceType = (String) entry.get("sourceType");
            List<MoneyActivityTaxonomy.MoneyLine> money = cashLines.stream().map(l -> new MoneyActivityTaxonomy.MoneyLine(
                    (String) l.get("accountId"), accountName(l), null, plain(SqlRows.dec(l, "baseDebit")),
                    plain(SqlRows.dec(l, "baseCredit")))).toList();
            Map<String, Object> figures = MoneyActivityTaxonomy.classify(sourceType, money);
            String direction = (String) figures.get("direction");
            String txType = "internal".equals(direction) ? "transfer" : "out".equals(direction) ? "withdrawal" : "deposit";
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> legs = (List<Map<String, Object>>) figures.get("legs");
            Map<String, Object> outLeg = legs.stream().filter(l -> "out".equals(l.get("side"))).findFirst().orElse(null);
            Map<String, Object> inLeg = legs.stream().filter(l -> "in".equals(l.get("side"))).findFirst().orElse(null);
            Map<String, Object> primaryLeg = "out".equals(direction) ? outLeg : inLeg != null ? inLeg : outLeg;
            Map<String, Object> primary = cashLines.stream()
                    .filter(l -> primaryLeg != null && l.get("accountId").equals(primaryLeg.get("accountId"))).findFirst()
                    .orElse(cashLines.isEmpty() ? null : cashLines.getFirst());
            Object amount = "internal".equals(direction) ? figures.get("internalMoved")
                    : "out".equals(direction) ? figures.get("externalOut")
                    : "in".equals(direction) ? figures.get("externalIn") : figures.get("grossAmount");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", entry.get("id"));
            row.put("journalEntryId", entry.get("id"));
            row.put("entryNumber", entry.get("entryNumber"));
            row.put("date", entry.get("postingDate"));
            row.put("description", entry.get("description") != null ? entry.get("description")
                    : primary == null ? null : primary.get("description"));
            row.put("sourceType", sourceType);
            row.put("type", txType);
            row.put("category", figures.get("category"));
            row.put("categoryLabel", figures.get("categoryLabel"));
            row.put("amount", amount);
            row.put("direction", "out".equals(direction) ? "out" : "internal".equals(direction) ? "internal" : "in");
            row.put("accountId", primary == null ? null : primary.get("accountId"));
            row.put("accountName", primary == null ? "—" : accountName(primary));
            row.put("fromName", "transfer".equals(txType) && outLeg != null ? outLeg.get("accountName") : null);
            row.put("toName", "transfer".equals(txType) && inLeg != null ? inLeg.get("accountName") : null);
            rows.add(row);
        }
        return body("data", rows, "total", total, "page", page, "pageSize", pageSize,
                "totalPages", (long) Math.ceil(total / (double) pageSize));
    }

    private static String accountName(Map<String, Object> l) {
        if (l == null) {
            return "—";
        }
        Object n = l.get("a_name") != null ? l.get("a_name") : l.get("a_code");
        return n == null ? "—" : n.toString();
    }
}
