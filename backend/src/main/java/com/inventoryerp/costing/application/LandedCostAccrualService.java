package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.application.LineageCapitalisation.Layer;
import com.inventoryerp.costing.application.LineageCapitalisation.Part;
import com.inventoryerp.inventory.application.CostLineageService;
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
import java.time.Instant;
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
 * Landed-cost accrual at receipt (Cost Management CM-6, ADR-S036).
 *
 * <ul>
 *   <li><b>Rules</b>: an estimated rate (% of receipt value) per supplier, product category, Incoterm of the purchase
 *       order, or the organisation default — resolved per receipt layer in that order.</li>
 *   <li><b>Accrue</b>: each receipt layer of the goods receipt gets {@code value × rate}; the estimate follows the
 *       layer's lineage (on hand → price-difference adjunct, sold since → cost of sales). Cr <b>2156 Landed Cost
 *       Accrual</b>.</li>
 *   <li><b>Clear</b>: when the actual voucher for the receipt posts (it capitalises the actual charges itself), the
 *       estimate is taken out in full exactly where it went — what is still open in the adjunct, what was consumed or
 *       released since to cost of sales / WIP / scrap — Dr 2156. The accrual account returns to zero; the difference
 *       between actual and estimate lands on hand vs consumed through the voucher's own lineage.</li>
 *   <li>An accrual with no voucher can be reversed the same way. FX differences on settling the charges are realised
 *       FX gain / loss of the payment, never capitalised (IAS 2 / IAS 21).</li>
 * </ul>
 */
@Service
public class LandedCostAccrualService {

    static final String SOURCE = "landed_cost_accrual";
    private static final Set<String> SCOPES = Set.of("default", "supplier", "category", "incoterm");

    /** An accrual rule. */
    public record RuleInput(String scopeType, String scopeId, BigDecimal ratePct, String notes) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AccountDeterminationService determination;
    private final CostLineageService lineage;
    private final LineageCapitalisation capitalisation;
    private final AuditService audit;

    public LandedCostAccrualService(JdbcClient jdbc, SequenceService seq, PostingService posting,
            AccountResolverService accounts, AccountDeterminationService determination, CostLineageService lineage,
            LineageCapitalisation capitalisation, AuditService audit) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.posting = posting;
        this.accounts = accounts;
        this.determination = determination;
        this.lineage = lineage;
        this.capitalisation = capitalisation;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Estimated landed costs not invoiced yet (2156). */
    public String accrualAccount() {
        return accounts.ensureByCode("2156", "Landed Cost Accrual", "current_liability", "landed_cost_accrual");
    }

    // ---- rules --------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rules() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"LandedCostAccrualRule\" WHERE \"organizationId\" = ? AND"
                + " \"isActive\" ORDER BY \"scopeType\", \"scopeId\"").param(org()));
    }

    /** Create or replace the active rule of a scope. */
    @Transactional
    public Map<String, Object> saveRule(RuleInput in) {
        String scope = in.scopeType() == null ? "default" : in.scopeType();
        if (!SCOPES.contains(scope)) {
            throw ApiException.badRequest("scopeType must be one of the following values: default, supplier, category,"
                    + " incoterm");
        }
        String scopeId = "default".equals(scope) ? "" : in.scopeId() == null ? "" : in.scopeId().trim();
        if (!"default".equals(scope) && scopeId.isEmpty()) {
            throw ApiException.badRequest("scopeId should not be empty");
        }
        if ("supplier".equals(scope) && count("Partner", scopeId) == 0) {
            throw ApiException.notFound("Supplier not found");
        }
        if ("category".equals(scope) && count("ProductCategory", scopeId) == 0) {
            throw ApiException.notFound("Product category not found");
        }
        if (in.ratePct() == null || in.ratePct().signum() <= 0 || in.ratePct().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("ratePct must be greater than 0 and at most 100");
        }
        jdbc.sql("UPDATE \"LandedCostAccrualRule\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND"
                + " \"scopeType\" = ? AND \"scopeId\" = ? AND \"isActive\"").params(org(), scope, scopeId).update();
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LandedCostAccrualRule\" (id, \"organizationId\", \"scopeType\", \"scopeId\", \"ratePct\","
                + " notes, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?)").params(id, org(), scope, scopeId, in.ratePct(),
                in.notes(), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("LandedCostAccrualRule", id, "create", null, Obj.of("scopeType", scope,
                "scopeId", scopeId, "ratePct", in.ratePct())));
        return SqlRows.one(jdbc.sql("SELECT * FROM \"LandedCostAccrualRule\" WHERE id = ?").param(id));
    }

    @Transactional
    public void deleteRule(String id) {
        if (jdbc.sql("UPDATE \"LandedCostAccrualRule\" SET \"isActive\" = false WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"isActive\"").params(id, org()).update() == 0) {
            throw ApiException.notFound("Accrual rule not found");
        }
    }

    private long count(String table, String id) {
        return jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Long.class).single();
    }

    /** The rule of a receipt layer: supplier, then product category, then Incoterm, then default. */
    private Map<String, Object> rule(String supplierId, String categoryId, String incoterm) {
        for (String[] s : new String[][] {{"supplier", supplierId}, {"category", categoryId}, {"incoterm", incoterm},
                {"default", ""}}) {
            if (s[1] == null) {
                continue;
            }
            Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"LandedCostAccrualRule\" WHERE"
                    + " \"organizationId\" = ? AND \"scopeType\" = ? AND \"scopeId\" = ? AND \"isActive\"")
                    .params(org(), s[0], s[1]));
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    // ---- accrue -------------------------------------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> accrue(String goodsReceiptId) {
        Map<String, Object> grn = SqlRows.one(jdbc.sql("SELECT g.id, g.\"receiptNumber\", g.status::text AS status,"
                + " g.\"reversedAt\", g.\"partnerId\", d.incoterm FROM \"GoodsReceiptNote\" g LEFT JOIN \"Document\" d ON"
                + " d.id = g.\"purchaseOrderId\" WHERE g.id = ? AND g.\"organizationId\" = ? AND g.\"deletedAt\" IS NULL")
                .params(goodsReceiptId, org()));
        if (grn == null) {
            throw ApiException.notFound("Goods receipt not found");
        }
        if (!"posted".equals(grn.get("status")) || grn.get("reversedAt") != null) {
            throw ApiException.badRequest("Goods receipt " + grn.get("receiptNumber") + " is not posted");
        }
        if (jdbc.sql("SELECT count(*) FROM \"LandedCostAccrual\" WHERE \"organizationId\" = ? AND \"goodsReceiptId\" = ?"
                + " AND status = 'posted'").params(org(), goodsReceiptId).query(Long.class).single() > 0) {
            throw ApiException.conflict("Goods receipt " + grn.get("receiptNumber") + " already has an open landed-cost"
                    + " accrual");
        }
        List<Layer> layers = capitalisation.receipts("goods_receipt", List.of(goodsReceiptId,
                (String) grn.get("receiptNumber")));
        if (layers.isEmpty()) {
            throw ApiException.badRequest(grn.get("receiptNumber") + " put no goods into stock; nothing to accrue on");
        }
        String id = UUID.randomUUID().toString();
        List<Part> parts = new ArrayList<>();
        List<Object[]> lines = new ArrayList<>();
        BigDecimal basis = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (Layer l : layers) {
            String category = jdbc.sql("SELECT \"categoryId\" FROM \"Product\" WHERE id = ?").param(l.productId())
                    .query(String.class).optional().orElse(null);
            Map<String, Object> rule = rule((String) grn.get("partnerId"), category, (String) grn.get("incoterm"));
            if (rule == null) {
                throw ApiException.badRequest("No landed-cost accrual rule applies to " + grn.get("receiptNumber")
                        + "; define a default rate");
            }
            BigDecimal rate = SqlRows.dec(rule, "ratePct");
            BigDecimal amount = l.value().multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            basis = basis.add(l.value());
            if (amount.signum() <= 0) {
                continue;
            }
            total = total.add(amount);
            parts.addAll(capitalisation.spread(amount, List.of(l)));
            lines.add(new Object[] {l, rule.get("id"), rate, amount});
        }
        if (total.signum() <= 0) {
            throw ApiException.badRequest("Nothing to accrue: the receipt has no value");
        }
        String number = seq.next("landed_cost_accrual", "LCA/", 5);
        String text = "Landed cost accrual " + number + " · GRN " + grn.get("receiptNumber");
        List<Line> je = new ArrayList<>();
        capitalisation.debits(parts).forEach((acc, v) -> je.add(signed(acc, v, text)));
        je.add(signed(accrualAccount(), total.negate(), text));
        String entryId = post(id, text, je);
        jdbc.sql("INSERT INTO \"LandedCostAccrual\" (id, \"organizationId\", number, \"goodsReceiptId\", \"basisValue\","
                + " amount, \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)").params(id, org(), number,
                goodsReceiptId, basis.setScale(2, RoundingMode.HALF_UP), total, entryId, TenantContext.userId())
                .update();
        for (Object[] l : lines) {
            Layer layer = (Layer) l[0];
            jdbc.sql("INSERT INTO \"LandedCostAccrualLine\" (id, \"organizationId\", \"accrualId\", \"layerLedgerId\","
                    + " \"productId\", \"ruleId\", \"ratePct\", \"basisValue\", amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, layer.ledgerId(), layer.productId(), l[1], l[2],
                            layer.value().setScale(2, RoundingMode.HALF_UP), l[3])
                    .update();
        }
        capitalisation.record(parts, "landed_accrual", SOURCE, id, entryId, text);
        audit.recordInTx(AuditInput.of("LandedCostAccrual", id, "post", null, Obj.of("number", number, "amount", total,
                "journalEntryId", entryId)));
        return get(id);
    }

    // ---- clear / reverse ----------------------------------------------------------------------------------------------

    /** Clear the estimate once the actual landed-cost voucher covering the receipt is posted. */
    @Transactional
    public Map<String, Object> clear(String id, String voucherId) {
        Map<String, Object> a = lock(id);
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT id, code, status FROM \"LandedCost\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"voucherType\" = 'voucher'").params(voucherId, org()));
        if (v == null) {
            throw ApiException.notFound("Landed cost voucher not found");
        }
        if (!"posted".equals(v.get("status"))) {
            throw ApiException.badRequest("Landed cost voucher " + v.get("code") + " is not posted");
        }
        if (jdbc.sql("SELECT count(*) FROM \"LandedCostReceipt\" WHERE \"landedCostId\" = ? AND \"goodsReceiptId\" = ?")
                .params(voucherId, a.get("goodsReceiptId")).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Landed cost voucher " + v.get("code") + " does not cover this goods receipt");
        }
        BigDecimal actual = jdbc.sql("SELECT coalesce(sum(\"allocatedAmount\"), 0) FROM \"LandedCostAllocation\" WHERE"
                + " \"landedCostId\" = ? AND \"goodsReceiptId\" = ?").params(voucherId, a.get("goodsReceiptId"))
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        String entry = unwind(a, "Landed cost accrual " + a.get("number") + " cleared by " + v.get("code"));
        jdbc.sql("UPDATE \"LandedCostAccrual\" SET status = 'cleared', \"voucherId\" = ?, \"actualAmount\" = ?,"
                + " \"clearingEntryId\" = ?, \"clearedAt\" = now() WHERE id = ?").params(voucherId, actual, entry, id)
                .update();
        audit.recordInTx(AuditInput.of("LandedCostAccrual", id, "update", null, Obj.of("cleared", true, "voucher",
                v.get("code"))));
        return get(id);
    }

    /** Reverse an accrual that no voucher will clear. */
    @Transactional
    public Map<String, Object> reverse(String id) {
        Map<String, Object> a = lock(id);
        String entry = unwind(a, "Landed cost accrual " + a.get("number") + " reversed");
        jdbc.sql("UPDATE \"LandedCostAccrual\" SET status = 'reversed', \"clearingEntryId\" = ?, \"clearedAt\" = now()"
                + " WHERE id = ?").params(entry, id).update();
        audit.recordInTx(AuditInput.of("LandedCostAccrual", id, "cancel", null, Obj.of("number", a.get("number"))));
        return get(id);
    }

    /**
     * Take the whole estimate out exactly where it went: still-open adjunct (value-only row), shares consumed at
     * posting to their targets, shares released since to cost of sales; Dr the accrual.
     */
    private String unwind(Map<String, Object> a, String text) {
        String id = (String) a.get("id");
        BigDecimal estimate = SqlRows.dec(a, "amount");
        Map<String, BigDecimal> credits = new LinkedHashMap<>();
        BigDecimal covered = BigDecimal.ZERO;
        for (Map<String, Object> adj : SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversedAt\" IS NULL")
                .params(org(), SOURCE, id))) {
            BigDecimal open = SqlRows.dec(adj, "openAmount");
            BigDecimal released = SqlRows.dec(adj, "onHandAmount").subtract(open);
            BigDecimal inAdjunct = open;
            credits.merge(determination.mapped("cogs"), released, BigDecimal::add);
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT target, amount FROM \"CostValueAdjustmentLine\""
                    + " WHERE \"adjustmentId\" = ? AND target <> 'on_hand'").param(adj.get("id")))) {
                if ("transfer".equals(l.get("target"))) {
                    // A transferred share rode on the source pool's adjunct (ADR-S031).
                    inAdjunct = inAdjunct.add(SqlRows.dec(l, "amount"));
                } else {
                    credits.merge(capitalisation.accountFor((String) l.get("target")), SqlRows.dec(l, "amount"),
                            BigDecimal::add);
                }
            }
            credits.merge(lineage.priceDifferenceAccount(), inAdjunct, BigDecimal::add);
            if (inAdjunct.signum() != 0) {
                lineage.recordValueOnly((String) adj.get("productId"), (String) adj.get("variantId"),
                        (String) adj.get("locationId"), null, inAdjunct.negate(), SOURCE + "_clearing", id,
                        "price_difference", text);
            }
            covered = covered.add(SqlRows.dec(adj, "amount"));
        }
        BigDecimal rest = estimate.subtract(covered);
        if (rest.signum() != 0) {
            credits.merge(determination.mapped("cogs"), rest, BigDecimal::add);
        }
        lineage.closeAdjustments(SOURCE, id);
        List<Line> lines = new ArrayList<>();
        lines.add(signed(accrualAccount(), estimate, text));
        credits.forEach((acc, v) -> {
            if (v.signum() != 0) {
                lines.add(signed(acc, v.negate(), text));
            }
        });
        Map<String, Object> je = posting.post(new PostingRequest("INV", Instant.now(), text, null, null,
                SOURCE + "_clearing", id, null, "costing:landed_accrual_clearing:" + org() + ":" + id, null, null,
                null, lines));
        return je == null ? null : (String) je.get("id");
    }

    private Map<String, Object> lock(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"LandedCostAccrual\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Landed cost accrual not found");
        }
        if (!"posted".equals(a.get("status"))) {
            throw ApiException.badRequest("Only an open accrual can be cleared or reversed");
        }
        return a;
    }

    // ---- queries ------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        boolean all = status == null || status.isBlank();
        return SqlRows.list(jdbc.sql("SELECT a.*, g.\"receiptNumber\" FROM \"LandedCostAccrual\" a JOIN"
                + " \"GoodsReceiptNote\" g ON g.id = a.\"goodsReceiptId\" WHERE a.\"organizationId\" = ?"
                + (all ? "" : " AND a.status = ?") + " ORDER BY a.\"createdAt\" DESC")
                .params(all ? List.of(org()) : List.of(org(), status)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT a.*, g.\"receiptNumber\" FROM \"LandedCostAccrual\" a JOIN"
                + " \"GoodsReceiptNote\" g ON g.id = a.\"goodsReceiptId\" WHERE a.id = ? AND a.\"organizationId\" = ?")
                .params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Landed cost accrual not found");
        }
        a.put("lines", SqlRows.list(jdbc.sql("SELECT * FROM \"LandedCostAccrualLine\" WHERE \"accrualId\" = ?")
                .param(id)));
        a.put("lineage", lineage.adjustments(SOURCE, id));
        return a;
    }

    /** Open accruals vs the accrual account: the account must equal what is still open. */
    @Transactional
    public Map<String, Object> reconciliation() {
        BigDecimal open = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"LandedCostAccrual\" WHERE \"organizationId\" = ?"
                + " AND status = 'posted'").param(org()).query(BigDecimal.class).single();
        BigDecimal gl = jdbc.sql("SELECT coalesce(sum(jl.\"baseCredit\" - jl.\"baseDebit\"), 0) FROM \"JournalLine\" jl"
                + " JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = ? AND"
                + " jl.\"accountId\" = ? AND e.status <> 'draft'").params(org(), accrualAccount())
                .query(BigDecimal.class).single();
        return Obj.of("openAccruals", JsNumbers.dec(open), "accountBalance", JsNumbers.dec(gl), "difference",
                JsNumbers.dec(gl.subtract(open)));
    }

    private String post(String id, String text, List<Line> lines) {
        Map<String, Object> je = posting.post(PostingRequest.of("INV", Instant.now(), text, SOURCE, id,
                "costing:landed_accrual:" + org() + ":" + id, lines));
        return je == null ? null : (String) je.get("id");
    }

    private static Line signed(String account, BigDecimal amount, String text) {
        String v = JsNumbers.dec(amount.abs());
        return amount.signum() >= 0 ? Line.debit(account, v, text) : Line.credit(account, v, text);
    }
}
