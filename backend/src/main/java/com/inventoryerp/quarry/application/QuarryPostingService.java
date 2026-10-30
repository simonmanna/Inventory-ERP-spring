package com.inventoryerp.quarry.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The GL side of the quarry (ADR-S012 §4), always through the posting engine with a posting key per source
 * (idempotent: a replay returns the entry already posted), debits = credits:
 *
 * <ul>
 *   <li><b>Extraction log</b> ({@code quarry:extraction:<id>}): Dr the ROM product's stock account (capitalised
 *       cost) [+ Dr extraction losses (5363) for the loss share under gross-tonne allocation] / Cr extraction cost
 *       absorbed (5361) per fuel / labour / equipment / blasting / other component + Cr royalties &amp; levies payable
 *       (2177) per rate, the authority as partner. Lines carry the site cost centre and the {@code quarrySiteId} /
 *       {@code extractionLogId} dimensions.</li>
 *   <li><b>Explosive issue</b> ({@code quarry:explosive-issue:<id>}): Dr blasting &amp; explosives (6470, dimension
 *       {@code blastId}) / Cr the explosive's stock account at the issued cost. <b>Return</b>
 *       ({@code quarry:explosive-return:<id>}) mirrors it for the unused quantity.</li>
 * </ul>
 *
 * Fuel, labour and plant hours are expensed where they arise (fleet fuel, payroll, plant costs); extraction
 * capitalises its share into ROM stock against the absorbed account, like manufacturing overhead absorption.
 */
@Service
public class QuarryPostingService {

    static final String EXTRACTION_SOURCE = "quarry_extraction_log";
    static final String ISSUE_SOURCE = "quarry_explosive_issue";

    private final QuarryDb db;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final StockPostingService stockPosting;

    public QuarryPostingService(QuarryDb db, PostingService posting, AccountResolverService accounts,
            StockPostingService stockPosting) {
        this.db = db;
        this.posting = posting;
        this.accounts = accounts;
        this.stockPosting = stockPosting;
    }

    /** Quarry extraction cost absorbed into ROM stock (5361, mapping {@code quarry_extraction_absorbed}). */
    public String absorbedAccount() {
        return accounts.ensureByCode("5361", "Quarry Extraction Cost Absorbed", "operating_expense",
                "quarry_extraction_absorbed");
    }

    /** Royalties, levies and fees owed per tonne extracted (2177, mapping {@code quarry_royalty_payable}). */
    public String royaltyPayableAccount() {
        return accounts.ensureByCode("2177", "Quarry Royalties & Levies Payable", "current_liability",
                "quarry_royalty_payable");
    }

    /** Explosives and blasting consumed (6470, mapping {@code quarry_blasting_expense}). */
    public String blastingExpenseAccount() {
        return accounts.ensureByCode("6470", "Quarry Blasting & Explosives", "operating_expense",
                "quarry_blasting_expense");
    }

    /** Loss share written off under gross-tonne allocation (5363, mapping {@code quarry_extraction_loss}). */
    public String lossAccount() {
        return accounts.ensureByCode("5363", "Quarry Extraction Losses", "operating_expense", "quarry_extraction_loss");
    }

    /** Post a log's capitalisation; null when it carries no cost. */
    String postExtraction(Map<String, Object> log, Map<String, Object> site, String productId,
            QuarryCostCalculator.Result r, Instant at) {
        if (r.total().signum() <= 0) {
            return null;
        }
        String id = (String) log.get("id");
        String label = "Extraction " + log.get("code") + " · " + site.get("code");
        String cc = (String) site.get("costCenterId");
        Map<String, Object> dims = Obj.of("quarrySiteId", site.get("id"), "extractionLogId", id);
        List<Line> lines = new ArrayList<>();
        if (r.capitalised().signum() > 0) {
            lines.add(new Line(stockPosting.stockAccount(productId, r.capitalised()), dec(r.capitalised()), null, null,
                    label + " · ROM stock " + QuarryCostCalculator.plain(r.netTonnes()) + " t", null, cc, dims));
        }
        if (r.lossCost().signum() > 0) {
            lines.add(new Line(lossAccount(), dec(r.lossCost()), null, null, label + " · losses "
                    + QuarryCostCalculator.plain(r.lossTonnes()) + " t", null, cc, dims));
        }
        Map<String, BigDecimal> absorbed = new LinkedHashMap<>();
        for (QuarryCostCalculator.Line l : r.lines()) {
            if ("royalty".equals(l.component())) {
                lines.add(new Line(royaltyPayableAccount(), null, dec(l.amount()), l.partnerId(), label + " · "
                        + l.description(), null, cc, dims));
            } else {
                absorbed.merge(l.component(), l.amount(), BigDecimal::add);
            }
        }
        for (Map.Entry<String, BigDecimal> e : absorbed.entrySet()) {
            if (e.getValue().signum() > 0) {
                lines.add(new Line(absorbedAccount(), null, dec(e.getValue()), null, label + " · " + e.getKey()
                        + " absorbed", null, cc, dims));
            }
        }
        Map<String, Object> entry = posting.post(new PostingRequest("INV", at, label, null, null, EXTRACTION_SOURCE, id,
                null, "quarry:extraction:" + id, (String) site.get("branchId"), cc, dims, lines));
        return (String) entry.get("id");
    }

    /** Post an explosive issue (Dr blasting / Cr stock per product); null when nothing had value. */
    String postExplosives(Map<String, Object> issue, Map<String, Object> site, Map<String, BigDecimal> valueByProduct,
            Instant at, boolean isReturn) {
        BigDecimal total = valueByProduct.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            return null;
        }
        String id = (String) issue.get("id");
        String label = (isReturn ? "Explosives returned " : "Explosives issued ") + issue.get("code");
        String cc = site == null ? null : (String) site.get("costCenterId");
        Map<String, Object> dims = Obj.of("blastId", issue.get("blastId"), "explosiveIssueId", id);
        List<Line> lines = new ArrayList<>();
        String expense = blastingExpenseAccount();
        for (Map.Entry<String, BigDecimal> e : valueByProduct.entrySet()) {
            if (e.getValue().signum() <= 0) {
                continue;
            }
            String stock = stockPosting.stockAccount(e.getKey(), e.getValue());
            String v = dec(e.getValue());
            lines.add(isReturn ? new Line(stock, v, null, null, label, null, null, null)
                    : new Line(stock, null, v, null, label, null, null, null));
        }
        String v = dec(total);
        lines.add(isReturn ? new Line(expense, null, v, null, label, null, cc, dims)
                : new Line(expense, v, null, null, label, null, cc, dims));
        Map<String, Object> entry = posting.post(new PostingRequest("INV", at, label, null, null, ISSUE_SOURCE, id, null,
                (isReturn ? "quarry:explosive-return:" : "quarry:explosive-issue:") + id,
                site == null ? null : (String) site.get("branchId"), cc, dims, lines));
        return (String) entry.get("id");
    }

    /** Reverse a posted entry (posting engine reversal); returns the reversal id or null. */
    String reverse(String entryId, String note) {
        if (entryId == null) {
            return null;
        }
        String status = db.jdbc().sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ? AND \"organizationId\" = ?")
                .params(entryId, QuarryDb.org()).query(String.class).optional().orElse(null);
        if (!"posted".equals(status)) {
            return null;
        }
        return (String) posting.reverse(entryId, Instant.now(), note).get("id");
    }

    /** The GL entries (and their reversals) with lines, for detail screens. */
    List<Map<String, Object>> journal(List<String> entryIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : entryIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> je : SqlRows.list(db.jdbc().sql("SELECT id, \"entryNumber\", status::text AS status,"
                + " \"postingDate\", description, \"postingType\", \"reversalOfId\" FROM \"JournalEntry\" WHERE"
                + " \"organizationId\" = :org AND (id IN (:ids) OR \"reversalOfId\" IN (:ids)) ORDER BY \"postedAt\","
                + " \"entryNumber\"").param("org", QuarryDb.org()).param("ids", List.copyOf(ids)))) {
            Map<String, Object> entry = new LinkedHashMap<>(je);
            entry.put("lines", SqlRows.list(db.jdbc().sql("""
                    SELECT l.id, l."lineNumber", l.debit, l.credit, l.description, a.code AS "accountCode",
                           a.name AS "accountName"
                    FROM "JournalLine" l JOIN "Account" a ON a.id = l."accountId"
                    WHERE l."journalEntryId" = ? ORDER BY l."lineNumber\"""").param(je.get("id"))));
            entries.add(entry);
        }
        return entries;
    }

    private static String dec(BigDecimal v) {
        return JsNumbers.dec(v);
    }
}
