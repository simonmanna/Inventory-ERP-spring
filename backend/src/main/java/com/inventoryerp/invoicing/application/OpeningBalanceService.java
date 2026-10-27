package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
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
    public record AccountLine(String accountId, BigDecimal debit, BigDecimal credit, String description) {}

    /** Customer or supplier open balance. */
    public record PartnerLine(String partnerId, BigDecimal amount, String dueDate, String reference) {}

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

    public OpeningBalanceService(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination,
            AccountResolverService accounts, InvoiceService invoices, VendorBillService bills) {
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
                BigDecimal d = l.debit() == null ? BigDecimal.ZERO : l.debit();
                BigDecimal c = l.credit() == null ? BigDecimal.ZERO : l.credit();
                if (d.signum() < 0 || c.signum() < 0 || (d.signum() > 0) == (c.signum() > 0)) {
                    throw ApiException.badRequest("Each account line needs exactly one positive debit or credit"
                            + " (account " + a.code() + ")");
                }
                String desc = l.description() == null || l.description().isBlank() ? label : l.description();
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
            Map<String, Object> doc = invoices.create(new InvoiceInput(c.partnerId(), in.date(), c.dueDate(), null, null,
                    null, null, null, null, null, null, null, null, null, null, ref(c, label), label,
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
            Map<String, Object> doc = bills.create(new BillInput(s.partnerId(), in.date(), s.dueDate(), null,
                    ref(s, label), label, null, List.of(), List.of(LineInput.of(null, equity, label, BigDecimal.ONE,
                            s.amount(), BigDecimal.ZERO, null, null))));
            String id = (String) doc.get("id");
            tag(id, batchId);
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

    private static String ref(PartnerLine l, String label) {
        return l.reference() == null || l.reference().isBlank() ? label : l.reference().trim();
    }

    private void tag(String documentId, String batchId) {
        jdbc.sql("UPDATE \"Document\" SET \"sourceType\" = ?, \"sourceId\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(SOURCE, batchId, documentId, org()).update();
    }
}
