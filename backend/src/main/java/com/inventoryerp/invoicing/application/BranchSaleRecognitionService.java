package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.TenderAccount;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Grouped;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revenue and VAT of a branch cash sale recognised at showroom confirm (ADR-S056, cash-sales.md §3 P3), and the
 * account checks the branch sales profile relies on (§4). Invoicing owns these postings: the accrual uses the same
 * income and tax accounts the invoice would ({@link DocumentBuilderService#groupForPosting}), so the head-office
 * invoice later only reclassifies {@code unbilled_receivable} to AR (P4).
 */
@Service
public class BranchSaleRecognitionService {

    /** Mapping key of the accrual account (asset, code 1215, created on first use). */
    public static final String UNBILLED_KEY = "unbilled_receivable";

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final DocumentBuilderService builder;
    private final AccountResolverService accounts;
    private final CustomerDepositService deposits;
    private final AccountDeterminationService determination;

    public BranchSaleRecognitionService(JdbcClient jdbc, PostingService posting, DocumentBuilderService builder,
            AccountResolverService accounts, CustomerDepositService deposits, AccountDeterminationService determination) {
        this.determination = determination;
        this.jdbc = jdbc;
        this.posting = posting;
        this.builder = builder;
        this.accounts = accounts;
        this.deposits = deposits;
    }

    /** One sold line: product, its tax and the server-computed amounts. */
    public record SaleLine(String productId, String taxId, BigDecimal subtotal, BigDecimal taxAmount) {}

    /** Accrual request: the submission, its customer, branch, date and lines. */
    public record Accrual(String submissionId, String number, String partnerId, String branchId, Instant date,
            List<SaleLine> lines) {}

    /** The {@code unbilled_receivable} account (created on first use). */
    public String unbilledAccount() {
        return accounts.ensureByCode("1215", "Unbilled Receivables", "current_asset", UNBILLED_KEY);
    }

    /**
     * P3: Dr {@code unbilled_receivable} (N+T) / Cr revenue (N) per income account, Cr output VAT (T) per tax account.
     * Idempotent on {@code css:<id>:accrual}. Returns the journal entry.
     */
    @Transactional
    public Map<String, Object> accrue(Accrual a) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SaleLine l : a.lines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", l.productId());
            m.put("lineType", "product");
            m.put("subtotal", l.subtotal());
            m.put("taxId", l.taxId());
            m.put("taxAmount", l.taxAmount());
            rows.add(m);
            total = total.add(l.subtotal()).add(l.taxAmount());
        }
        if (total.signum() <= 0) {
            throw ApiException.badRequest("A cash sale must have a positive total");
        }
        Grouped g = builder.groupForPosting(a.partnerId(), rows, "sales", null);
        List<Line> post = new ArrayList<>();
        post.add(new Line(unbilledAccount(), JsNumbers.dec(total), null, a.partnerId(), "Cash sale " + a.number(),
                a.branchId(), null, null));
        g.itemLines().forEach(it -> post.add(new Line(it.accountId(), null, JsNumbers.dec(it.amount()), a.partnerId(),
                "Revenue " + a.number(), a.branchId(), it.costCenterId(), it.glDimensions())));
        g.taxByAccount().forEach((acc, amt) -> post.add(new Line(acc, null, JsNumbers.dec(amt), null, "Output tax "
                + a.number(), a.branchId(), null, null)));
        return posting.post(new PostingRequest("SALES", a.date(), "Cash sale " + a.number() + " (revenue accrual)", null,
                null, "cash_sale", a.submissionId(), null, "css:" + a.submissionId() + ":accrual", a.branchId(), null,
                null, post));
    }

    /** P6: reverse the accrual of a voided sale. */
    @Transactional
    public Map<String, Object> reverseAccrual(String journalEntryId, String description) {
        return posting.reverse(journalEntryId, null, description);
    }

    /** Accrued N+T of a submission (from its accrual entry). */
    @Transactional(readOnly = true)
    public BigDecimal accruedTotal(String journalEntryId) {
        return jdbc.sql("SELECT COALESCE(sum(debit), 0) FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND"
                + " \"accountId\" = ?").params(journalEntryId, unbilledAccount()).query(BigDecimal.class).single();
    }

    // ------------------------------------------------------------------ account checks (cash-sales.md §4)

    /** Expected report sections by role. */
    static final Set<String> ASSET = Set.of("current_assets");
    static final Set<String> LIABILITY = Set.of("current_liabilities");
    static final Set<String> REVENUE = Set.of("revenue", "other_income");
    static final Set<String> COGS = Set.of("cogs");

    /**
     * Problems with the tender accounts and the mappings a cash sale posts to (empty when all are fine). Every
     * account must exist in this organization, be active and postable, and sit in the expected report section.
     */
    /** A tender account of the branch profile and the payment method it receives. */
    public record Tender(String label, String method, String accountId) {}

    @Transactional
    public List<String> accountProblems(List<Tender> tenders) {
        List<String> out = new ArrayList<>();
        for (Tender t : tenders) {
            if (t.accountId() != null) {
                out.addAll(tenderProblems(t.label(), t.method(), t.accountId()));
            }
        }
        check(out, "unbilled_receivable", unbilledAccount(), ASSET);
        check(out, "customer_deposit", deposits.depositAccount(), LIABILITY);
        mapping(out, "accounts_receivable", ASSET);
        mapping(out, "stock_valuation", ASSET);
        mapping(out, "cogs", COGS);
        mapping(out, "sales_revenue", REVENUE);
        mapping(out, "tax_payable", LIABILITY);
        return out;
    }

    /** Problems with one tender account: active postable asset of the organization, of the method's category. */
    @Transactional(readOnly = true)
    public List<String> tenderProblems(String label, String method, String accountId) {
        List<String> out = new ArrayList<>();
        check(out, label, accountId, ASSET);
        if (out.isEmpty()) {
            try {
                TenderAccount.resolve(jdbc, determination, TenantContext.organizationId(), method, accountId, null);
            } catch (ApiException e) {
                out.add(label + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** Problems with the income / tax accounts the given lines would post to (category overrides, tax accounts). */
    @Transactional(readOnly = true)
    public List<String> lineAccountProblems(String partnerId, List<SaleLine> lines) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SaleLine l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", l.productId());
            m.put("lineType", "product");
            m.put("subtotal", l.subtotal());
            m.put("taxId", l.taxId());
            m.put("taxAmount", l.taxAmount());
            rows.add(m);
        }
        List<String> out = new ArrayList<>();
        Grouped g = builder.groupForPosting(partnerId, rows, "sales", null);
        g.itemByAccount().keySet().forEach(acc -> check(out, "revenue", acc, REVENUE));
        g.taxByAccount().keySet().forEach(acc -> check(out, "output tax", acc, LIABILITY));
        return out;
    }

    private void mapping(List<String> out, String key, Set<String> sections) {
        String id = accounts.byMappingOptional(key);
        if (id == null) {
            out.add("Account mapping '" + key + "' is not configured");
            return;
        }
        check(out, key, id, sections);
    }

    private void check(List<String> out, String label, String accountId, Set<String> sections) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT a.code, a.\"isActive\", a.\"isPostable\", a.\"deletedAt\","
                + " COALESCE(a.\"reportSection\"::text, c.\"reportSection\"::text) AS section FROM \"Account\" a LEFT JOIN"
                + " \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ?")
                .params(accountId, TenantContext.organizationId()));
        if (a == null || a.get("deletedAt") != null) {
            out.add(label + ": account not found");
        } else if (!Boolean.TRUE.equals(a.get("isActive"))) {
            out.add(label + ": account " + a.get("code") + " is inactive");
        } else if (Boolean.FALSE.equals(a.get("isPostable"))) {
            out.add(label + ": account " + a.get("code") + " is not postable");
        } else if (!sections.contains(String.valueOf(a.get("section")))) {
            out.add(label + ": account " + a.get("code") + " is " + a.get("section") + ", expected "
                    + String.join(" / ", sections));
        }
    }
}
