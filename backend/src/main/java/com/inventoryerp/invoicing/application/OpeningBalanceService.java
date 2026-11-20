package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.accounting.application.InventoryValuationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.InvoiceService.InvoiceInput;
import com.inventoryerp.invoicing.application.VendorBillService.BillInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opening balances when migrating onto the ERP (ADR-S005), all-or-nothing in one transaction:
 * <ul>
 *   <li>GL lines (cash, bank, inventory, fixed assets, loans, equity...) post as one {@code GEN} entry with
 *       {@code sourceType = opening_balance}; any difference balances to the {@code retained_earnings} mapping
 *       (opening-balance equity). AR / AP control accounts are refused here.</li>
 *   <li>Each customer balance becomes a posted sales invoice, and each supplier balance a posted vendor bill, with
 *       one line on the equity account — real open items for aging, statements and payments.</li>
 * </ul>
 */
@Service
public class OpeningBalanceService {

    /** GL line: exactly one of debit / credit positive. */
    public record AccountLine(String accountId, BigDecimal debit, BigDecimal credit, String description,
            String currencyCode, BigDecimal exchangeRate) {

        /** A functional-currency line. */
        public AccountLine(String accountId, BigDecimal debit, BigDecimal credit, String description) {
            this(accountId, debit, credit, description, null, null);
        }
    }

    /** Customer or supplier open balance. */
    public record PartnerLine(String partnerId, BigDecimal amount, String dueDate, String reference,
            String currencyCode, BigDecimal exchangeRate) {

        /** A functional-currency balance. */
        public PartnerLine(String partnerId, BigDecimal amount, String dueDate, String reference) {
            this(partnerId, amount, dueDate, reference, null, null);
        }
    }

    /** Whole request. */
    public record OpeningInput(String date, String reference, List<AccountLine> accounts,
            List<PartnerLine> customers, List<PartnerLine> suppliers) {}

    static final String SOURCE = "opening_balance";

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AccountResolverService accounts;
    private final InvoiceService invoices;
    private final VendorBillService bills;
    private final InventoryValuationService valuation;
    private final CurrencyService currency;

    public OpeningBalanceService(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination,
            AccountResolverService accounts, InvoiceService invoices, VendorBillService bills,
            InventoryValuationService valuation, CurrencyService currency) {
        this.valuation = valuation;
        this.currency = currency;
        this.jdbc = jdbc;
        this.posting = posting;
        this.determination = determination;
        this.accounts = accounts;
        this.invoices = invoices;
        this.bills = bills;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Opening entries and opening documents posted so far, newest first. */
    @Transactional(readOnly = true)
    public Map<String, Object> list() {
        List<Map<String, Object>> entries = SqlRows.list(jdbc.sql("SELECT e.id, e.\"entryNumber\", e.\"postingDate\","
                + " e.description, e.\"sourceId\" AS \"batchId\", e.status::text AS status, (SELECT sum(l.\"baseDebit\") FROM"
                + " \"JournalLine\" l WHERE l.\"journalEntryId\" = e.id) AS total FROM \"JournalEntry\" e WHERE"
                + " e.\"organizationId\" = ? AND e.\"sourceType\" = ? ORDER BY e.\"createdAt\" DESC").params(org(), SOURCE));
        List<Map<String, Object>> documents = SqlRows.list(jdbc.sql("SELECT d.id, d.\"documentNumber\","
                + " d.\"documentType\"::text AS \"documentType\", d.\"partnerId\", p.name AS \"partnerName\", d.\"issueDate\","
                + " d.\"dueDate\", d.\"totalAmount\", d.\"amountResidual\", d.status::text AS status, d.\"sourceId\" AS"
                + " \"batchId\" FROM \"Document\" d JOIN \"Partner\" p ON p.id = d.\"partnerId\" WHERE d.\"organizationId\" = ?"
                + " AND d.\"sourceType\" = ? ORDER BY d.\"createdAt\" DESC").params(org(), SOURCE));
        return Obj.of("entries", entries, "documents", documents);
    }

    @Transactional
    public Map<String, Object> post(OpeningInput in) {
        Instant date;
        try {
            date = EntityPatch.toInstant(in.date());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("date must be a valid ISO 8601 date string");
        }
        List<AccountLine> glLines = in.accounts() == null ? List.of() : in.accounts();
        List<PartnerLine> customers = in.customers() == null ? List.of() : in.customers();
        List<PartnerLine> suppliers = in.suppliers() == null ? List.of() : in.suppliers();
        if (glLines.isEmpty() && customers.isEmpty() && suppliers.isEmpty()) {
            throw ApiException.badRequest("Provide at least one account, customer or supplier balance");
        }
        String equity = determination.mapped("retained_earnings");
        String batchId = UUID.randomUUID().toString();
        String label = "Opening balance" + (in.reference() == null || in.reference().isBlank() ? ""
                : " — " + in.reference().trim());

        Map<String, Object> entry = null;
        if (!glLines.isEmpty()) {
            Set<String> ids = new HashSet<>();
            glLines.forEach(l -> ids.add(l.accountId()));
            Map<String, AccountMeta> meta = accounts.meta(ids);
            // Owner decision D5: inventory value enters only through an approved opening count, so the stock
            // subledger and the GL inventory control account start equal.
            Set<String> inventory = new HashSet<>(valuation.inventoryAccountIds());
            List<Line> lines = new ArrayList<>();
            BigDecimal net = BigDecimal.ZERO;
            for (AccountLine l : glLines) {
                AccountMeta a = meta.get(l.accountId());
                if (a == null) {
                    throw ApiException.badRequest("Account " + l.accountId() + " not found");
                }
                if ("ar".equals(a.controlAccountType()) || "ap".equals(a.controlAccountType())) {
                    throw ApiException.badRequest("Account " + a.code() + " is a receivable / payable control account."
                            + " Enter customer and supplier balances in their own sections.");
                }
                if (inventory.contains(a.id())) {
                    throw ApiException.badRequest("Account " + a.code() + " holds inventory value. Enter opening stock"
                            + " with an approved opening count so the stock ledger and the GL agree.");
                }
                if (!a.allowManualPosting()) {
                    // tax and other control accounts are fed by their sub-ledger; a GL-only opening balance would never
                    // tie out (review 2026-11-15, G-9). Inventory accounts get the more specific message above.
                    throw ApiException.badRequest("Account " + a.code() + " does not allow manual posting ("
                            + (a.categoryName() == null ? "control account" : a.categoryName())
                            + "). Enter its opening balance through its own module.");
                }
                BigDecimal d = l.debit() == null ? BigDecimal.ZERO : l.debit();
                BigDecimal c = l.credit() == null ? BigDecimal.ZERO : l.credit();
                if (d.signum() < 0 || c.signum() < 0 || (d.signum() > 0) == (c.signum() > 0)) {
                    throw ApiException.badRequest("Each account line needs exactly one positive debit or credit"
                            + " (account " + a.code() + ")");
                }
                String desc = l.description() == null || l.description().isBlank() ? label : l.description();
                // FX-2 F: a foreign balance (e.g. a USD bank) is entered in its currency and valued at the opening
                // rate (stated, else the table on the opening date, context opening_balance); equity takes the value.
                String ccy = foreignCode(l.currencyCode());
                if (ccy != null) {
                    BigDecimal rate = currency.resolveStated(ccy, posting.baseCurrency(), l.exchangeRate(), date,
                            "opening_balance").rate();
                    BigDecimal fd = d.multiply(rate).setScale(6, java.math.RoundingMode.HALF_UP);
                    BigDecimal fc = c.multiply(rate).setScale(6, java.math.RoundingMode.HALF_UP);
                    lines.add((d.signum() > 0 ? Line.debit(a.id(), PostingService.plain(fd), desc)
                            : Line.credit(a.id(), PostingService.plain(fc), desc))
                            .inCurrency(ccy, PostingService.plain(d.signum() > 0 ? d : c)));
                    net = net.add(fd).subtract(fc);
                    continue;
                }
                lines.add(d.signum() > 0 ? Line.debit(a.id(), PostingService.plain(d), desc)
                        : Line.credit(a.id(), PostingService.plain(c), desc));
                net = net.add(d).subtract(c);
            }
            if (net.signum() > 0) {
                lines.add(Line.credit(equity, PostingService.plain(net), "Opening balance equity"));
            } else if (net.signum() < 0) {
                lines.add(Line.debit(equity, PostingService.plain(net.negate()), "Opening balance equity"));
            }
            entry = posting.post(PostingRequest.of("GEN", date, label, SOURCE, batchId, "opening_balance:" + batchId,
                    lines));
        }

        List<Map<String, Object>> invoiceOut = new ArrayList<>();
        for (PartnerLine c : customers) {
            assertPartner(c, "Customer");
            // FX-2 F: a foreign customer balance is an invoice in its currency at the opening rate
            String ccy = foreignCode(c.currencyCode());
            Map<String, Object> doc = invoices.create(new InvoiceInput(c.partnerId(), in.date(), c.dueDate(), null, null,
                    null, null, null, null, null, null, null, null, ccy, ccy == null ? null : c.exchangeRate(),
                    ref(c, label), label,
                    List.of(LineInput.of(null, equity, label, BigDecimal.ONE, c.amount(), BigDecimal.ZERO, null,
                            null))));
            String id = (String) doc.get("id");
            tag(id, batchId);
            Map<String, Object> posted = invoices.post(id);
            invoiceOut.add(Obj.of("id", id, "documentNumber", posted.get("documentNumber"), "partnerId",
                    c.partnerId(), "amount", PostingService.plain(c.amount())));
        }
        List<Map<String, Object>> billOut = new ArrayList<>();
        for (PartnerLine s : suppliers) {
            assertPartner(s, "Supplier");
            String ccy = foreignCode(s.currencyCode());
            Map<String, Object> doc = bills.create(new BillInput(s.partnerId(), in.date(), s.dueDate(), ccy,
                    ref(s, label), label, null, List.of(), List.of(LineInput.of(null, equity, label, BigDecimal.ONE,
                            s.amount(), BigDecimal.ZERO, null, null))));
            String id = (String) doc.get("id");
            tag(id, batchId);
            if (ccy != null && s.exchangeRate() != null) {
                // a bill takes no stated rate on create; the opening rate is stated on the document before posting
                jdbc.sql("UPDATE \"Document\" SET \"exchangeRate\" = ? WHERE id = ? AND \"organizationId\" = ?")
                        .params(s.exchangeRate(), id, org()).update();
            }
            Map<String, Object> posted = bills.post(id, false, false);
            billOut.add(Obj.of("id", id, "documentNumber", posted.get("documentNumber"), "partnerId", s.partnerId(),
                    "amount", PostingService.plain(s.amount())));
        }
        return Obj.of("batchId", batchId, "date", in.date(), "equityAccountId", equity,
                "journalEntry", entry == null ? null : Obj.of("id", entry.get("id"), "entryNumber",
                        entry.get("entryNumber")),
                "invoices", invoiceOut, "bills", billOut);
    }

    private void assertPartner(PartnerLine l, String kind) {
        if (l.partnerId() == null) {
            throw ApiException.badRequest(kind + " balance needs a partnerId");
        }
        if (l.amount() == null || l.amount().signum() <= 0) {
            throw ApiException.badRequest(kind + " opening balance must be greater than 0");
        }
        long n = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\""
                + " IS NULL").params(l.partnerId(), org()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest(kind + " " + l.partnerId() + " not found");
        }
    }

    /** The upper-cased currency code when it is not the functional currency, else {@code null}. */
    private String foreignCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String c = code.trim().toUpperCase();
        return c.equals(posting.baseCurrency()) ? null : c;
    }

    private static String ref(PartnerLine l, String label) {
        return l.reference() == null || l.reference().isBlank() ? label : l.reference().trim();
    }

    private void tag(String documentId, String batchId) {
        jdbc.sql("UPDATE \"Document\" SET \"sourceType\" = ?, \"sourceId\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(SOURCE, batchId, documentId, org()).update();
    }
}
