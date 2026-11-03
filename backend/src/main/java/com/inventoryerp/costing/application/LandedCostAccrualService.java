package com.inventoryerp.costing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.costing.application.LineageCapitalisation.Layer;
import com.inventoryerp.costing.application.LineageCapitalisation.Part;
import com.inventoryerp.core.application.UomConversionService;
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

    /** An accrual rule (LC-4: name, effective period, rate type with currency / unit / weight basis, settlement days). */
    public record RuleInput(String scopeType, String scopeId, BigDecimal ratePct, String notes, String name,
            String effectiveFrom, String effectiveTo, String rateType, BigDecimal rate, String currencyCode,
            String uomId, String weightBasis, Integer expectedSettlementDays) {

        public RuleInput(String scopeType, String scopeId, BigDecimal ratePct, String notes) {
            this(scopeType, scopeId, ratePct, notes, null, null, null, null, null, null, null, null, null);
        }
    }

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AccountDeterminationService determination;
    private final CostLineageService lineage;
    private final LineageCapitalisation capitalisation;
    private final AuditService audit;
    private final CurrencyService currencies;
    private final UomConversionService uoms;

    public LandedCostAccrualService(JdbcClient jdbc, SequenceService seq, PostingService posting,
            AccountResolverService accounts, AccountDeterminationService determination, CostLineageService lineage,
            LineageCapitalisation capitalisation, AuditService audit, CurrencyService currencies,
            UomConversionService uoms) {
        this.currencies = currencies;
        this.uoms = uoms;
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

    /**
     * Create a rule (LC-4). A rule without an effective period replaces the scope's other open-ended rule (the CM-6
     * behaviour); a rule with a period must not overlap another active rule of the same scope. Rate types: percent of
     * the receipt value, an amount per unit (in the rule's unit) or per kg (net or gross weight), optionally in a
     * foreign currency converted at the receipt date.
     */
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
        String rateType = in.rateType() == null ? "percent" : in.rateType();
        if (!List.of("percent", "per_unit", "per_kg").contains(rateType)) {
            throw ApiException.badRequest("rateType must be one of the following values: percent, per_unit, per_kg");
        }
        if ("percent".equals(rateType)) {
            if (in.ratePct() == null || in.ratePct().signum() <= 0
                    || in.ratePct().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw ApiException.badRequest("ratePct must be greater than 0 and at most 100");
            }
        } else if (in.rate() == null || in.rate().signum() <= 0) {
            throw ApiException.badRequest("rate must be greater than 0");
        }
        String weightBasis = in.weightBasis() == null ? "net" : in.weightBasis();
        if (!List.of("net", "gross").contains(weightBasis)) {
            throw ApiException.badRequest("weightBasis must be one of the following values: net, gross");
        }
        java.time.LocalDate from = date(in.effectiveFrom(), "effectiveFrom");
        java.time.LocalDate to = date(in.effectiveTo(), "effectiveTo");
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("effectiveTo must not be before effectiveFrom");
        }
        if (in.uomId() != null && !in.uomId().isBlank() && jdbc.sql("SELECT count(*) FROM \"UnitOfMeasure\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(in.uomId(), org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Unit of measure not found");
        }
        int settlement = in.expectedSettlementDays() == null ? 30 : in.expectedSettlementDays();
        if (settlement < 0) {
            throw ApiException.badRequest("expectedSettlementDays must not be less than 0");
        }
        if (from == null && to == null) {
            jdbc.sql("UPDATE \"LandedCostAccrualRule\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND"
                    + " \"scopeType\" = ? AND \"scopeId\" = ? AND \"isActive\" AND \"effectiveFrom\" IS NULL AND"
                    + " \"effectiveTo\" IS NULL").params(org(), scope, scopeId).update();
        }
        Map<String, Object> clash = SqlRows.one(jdbc.sql("SELECT name, \"effectiveFrom\", \"effectiveTo\" FROM"
                + " \"LandedCostAccrualRule\" WHERE \"organizationId\" = ? AND \"scopeType\" = ? AND \"scopeId\" = ? AND"
                + " \"isActive\" AND coalesce(\"effectiveFrom\", DATE '0001-01-01') <= coalesce(?::date, DATE '9999-12-31')"
                + " AND coalesce(\"effectiveTo\", DATE '9999-12-31') >= coalesce(?::date, DATE '0001-01-01') LIMIT 1")
                .params(org(), scope, scopeId, to == null ? null : to.toString(), from == null ? null
                        : from.toString()));
        if (clash != null) {
            throw ApiException.conflict("An active accrual rule for this scope overlaps " + clash.get("name") + " ("
                    + nz(clash.get("effectiveFrom"), "open") + " – " + nz(clash.get("effectiveTo"), "open") + ")");
        }
        String id = UUID.randomUUID().toString();
        String name = in.name() != null && !in.name().isBlank() ? in.name().trim()
                : scope + (scopeId.isEmpty() ? "" : " " + scopeId);
        jdbc.sql("INSERT INTO \"LandedCostAccrualRule\" (id, \"organizationId\", \"scopeType\", \"scopeId\", \"ratePct\","
                        + " notes, \"createdBy\", name, \"effectiveFrom\", \"effectiveTo\", \"rateType\", rate,"
                        + " \"currencyCode\", \"uomId\", \"weightBasis\", \"expectedSettlementDays\") VALUES (?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?::date, ?::date, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), scope, scopeId, "percent".equals(rateType) ? in.ratePct() : null, in.notes(),
                        TenantContext.userId(), name, from == null ? null : from.toString(), to == null ? null
                                : to.toString(), rateType, "percent".equals(rateType) ? null : in.rate(),
                        blank(in.currencyCode()), blank(in.uomId()), weightBasis, settlement)
                .update();
        audit.recordInTx(AuditInput.of("LandedCostAccrualRule", id, "create", null, Obj.of("scopeType", scope,
                "scopeId", scopeId, "rateType", rateType, "ratePct", in.ratePct(), "rate", in.rate())));
        return SqlRows.one(jdbc.sql("SELECT * FROM \"LandedCostAccrualRule\" WHERE id = ?").param(id));
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Object nz(Object v, String fallback) {
        return v == null ? fallback : v;
    }

    private static java.time.LocalDate date(String v, String field) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be a valid ISO 8601 date string");
        }
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

    /**
     * The rule of a receipt layer on {@code day}: supplier, then product category, then Incoterm, then default — fixed
     * precedence; within a scope the rule whose period covers the day.
     */
    private Map<String, Object> rule(String supplierId, String categoryId, String incoterm, java.time.LocalDate day) {
        for (String[] s : new String[][] {{"supplier", supplierId}, {"category", categoryId}, {"incoterm", incoterm},
                {"default", ""}}) {
            if (s[1] == null) {
                continue;
            }
            Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"LandedCostAccrualRule\" WHERE"
                    + " \"organizationId\" = ? AND \"scopeType\" = ? AND \"scopeId\" = ? AND \"isActive\" AND"
                    + " coalesce(\"effectiveFrom\", DATE '0001-01-01') <= ?::date AND coalesce(\"effectiveTo\","
                    + " DATE '9999-12-31') >= ?::date ORDER BY \"effectiveFrom\" DESC NULLS LAST LIMIT 1")
                    .params(org(), s[0], s[1], day.toString(), day.toString()));
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
                + " g.\"reversedAt\", g.\"partnerId\", g.\"receivedAt\", coalesce(sh.incoterm, po.incoterm) AS incoterm FROM"
                + " \"GoodsReceiptNote\" g LEFT JOIN \"PurchaseOrder\" po ON po.id = g.\"purchaseOrderId\" LEFT JOIN"
                + " \"ShipmentReceipt\" sr ON sr.\"goodsReceiptId\" = g.id LEFT JOIN \"Shipment\" sh ON sh.id = sr.\"shipmentId\""
                + " WHERE g.id = ? AND g.\"organizationId\" = ? AND g.\"deletedAt\" IS NULL")
                .params(goodsReceiptId, org()));
        if (grn == null) {
            throw ApiException.notFound("Goods receipt not found");
        }
        if (!"posted".equals(grn.get("status")) || grn.get("reversedAt") != null) {
            throw ApiException.badRequest("Goods receipt " + grn.get("receiptNumber") + " is not posted");
        }
        if (jdbc.sql("SELECT count(*) FROM \"LandedCostAccrual\" WHERE \"organizationId\" = ? AND \"goodsReceiptId\" = ?"
                + " AND status IN ('posted', 'partially_cleared')").params(org(), goodsReceiptId).query(Long.class)
                .single() > 0) {
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
        Instant received = grn.get("receivedAt") instanceof Instant i ? i : Instant.now();
        java.time.LocalDate day = received.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        String base = posting.baseCurrency();
        int settlementDays = 0;
        java.util.Set<String> missing = new java.util.TreeSet<>();
        for (Layer l : layers) {
            Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT name, \"categoryId\", \"uomId\", weight,"
                    + " \"grossWeight\" FROM \"Product\" WHERE id = ?").param(l.productId()));
            Map<String, Object> rule = rule((String) grn.get("partnerId"), (String) product.get("categoryId"),
                    (String) grn.get("incoterm"), day);
            if (rule == null) {
                throw ApiException.badRequest("No landed-cost accrual rule applies to " + grn.get("receiptNumber")
                        + "; define a default rate");
            }
            settlementDays = Math.max(settlementDays, ((Number) rule.get("expectedSettlementDays")).intValue());
            String rateType = (String) rule.get("rateType");
            BigDecimal qty = jdbc.sql("SELECT \"quantityChange\" FROM \"InventoryLedger\" WHERE id = ?")
                    .param(l.ledgerId()).query(BigDecimal.class).single();
            BigDecimal rate = "percent".equals(rateType) ? SqlRows.dec(rule, "ratePct") : SqlRows.dec(rule, "rate");
            BigDecimal ruleQty = null;
            BigDecimal kg = null;
            BigDecimal inCurrency;
            switch (rateType) {
                case "per_unit" -> {
                    // Quantity in the rule's unit (base quantity converted; no conversion → refused).
                    String uom = (String) rule.get("uomId");
                    BigDecimal perRuleUnit = uom == null ? BigDecimal.ONE
                            : uoms.toBase(BigDecimal.ONE, uom, l.productId(), (String) product.get("uomId"), false);
                    ruleQty = qty.divide(perRuleUnit, 6, RoundingMode.HALF_UP);
                    inCurrency = ruleQty.multiply(rate);
                }
                case "per_kg" -> {
                    Object w = "gross".equals(rule.get("weightBasis")) ? product.get("grossWeight") : product.get("weight");
                    BigDecimal weight = w == null ? BigDecimal.ZERO : SqlRows.dec(Map.of("w", w), "w");
                    if (weight.signum() <= 0) {
                        missing.add(String.valueOf(product.get("name")));
                        continue;
                    }
                    kg = qty.multiply(weight);
                    inCurrency = kg.multiply(rate);
                }
                default -> inCurrency = l.value().multiply(rate).divide(BigDecimal.valueOf(100), 6,
                        RoundingMode.HALF_UP);
            }
            String currency = (String) rule.get("currencyCode");
            BigDecimal fx = BigDecimal.ONE;
            if (!"percent".equals(rateType) && currency != null && !currency.equals(base)) {
                fx = currencies.findRate(currency, base, received);
                if (fx == null) {
                    throw ApiException.badRequest("No FX rate for " + currency + "→" + base + " on " + day
                            + "; enter one before accruing");
                }
            }
            BigDecimal amount = inCurrency.multiply(fx).setScale(2, RoundingMode.HALF_UP);
            basis = basis.add(l.value());
            if (amount.signum() <= 0) {
                continue;
            }
            total = total.add(amount);
            parts.addAll(capitalisation.spreadFollowing(amount, List.of(l)));
            lines.add(new Object[] {l, rule.get("id"), "percent".equals(rateType) ? rate : null, amount, rateType,
                    "percent".equals(rateType) ? null : rate, ruleQty, kg, "percent".equals(rateType) ? null
                            : currency == null ? base : currency, inCurrency, fx});
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("The accrual rule is per kg; set the weight of " + String.join(", ", missing));
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
                + " amount, \"journalEntryId\", \"createdBy\", \"remainingAmount\", \"expectedSettlementDate\") VALUES"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date)").params(id, org(), number, goodsReceiptId,
                basis.setScale(2, RoundingMode.HALF_UP), total, entryId, TenantContext.userId(), total,
                day.plusDays(settlementDays).toString())
                .update();
        for (Object[] l : lines) {
            Layer layer = (Layer) l[0];
            jdbc.sql("INSERT INTO \"LandedCostAccrualLine\" (id, \"organizationId\", \"accrualId\", \"layerLedgerId\","
                    + " \"productId\", \"ruleId\", \"ratePct\", \"basisValue\", amount, \"rateType\", rate, quantity,"
                    + " \"weightKg\", \"currencyCode\", \"currencyAmount\", \"exchangeRate\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, layer.ledgerId(), layer.productId(), l[1], l[2],
                            layer.value().setScale(2, RoundingMode.HALF_UP), l[3], l[4], l[5], l[6], l[7], l[8], l[9],
                            l[10])
                    .update();
        }
        capitalisation.record(parts, "landed_accrual", SOURCE, id, entryId, text);
        audit.recordInTx(AuditInput.of("LandedCostAccrual", id, "post", null, Obj.of("number", number, "amount", total,
                "journalEntryId", entryId)));
        return get(id);
    }

    // ---- clear / reverse ----------------------------------------------------------------------------------------------

    /**
     * Clear (LC-4): the posted voucher covering the receipt clears {@code min(its capitalised share on this receipt,
     * what is still open)} — or {@code amount} when given (never more than either). The cleared part is taken out pro
     * rata over where the estimate went (open adjunct, released since, consumed); the accrual becomes
     * {@code partially_cleared} or {@code cleared}. The clearing row keeps the accrual reversal (receipt-date rate)
     * apart from the actual charge (its currency, amount and recognition rate) and their estimate variance; realised FX
     * belongs to the payment.
     */
    @Transactional
    public Map<String, Object> clear(String id, String voucherId) {
        return clear(id, voucherId, null);
    }

    @Transactional
    public Map<String, Object> clear(String id, String voucherId, BigDecimal requested) {
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
        if (jdbc.sql("SELECT count(*) FROM \"LandedCostAccrualClearing\" WHERE \"accrualId\" = ? AND \"voucherId\" = ?")
                .params(id, voucherId).query(Long.class).single() > 0) {
            throw ApiException.badRequest("Landed cost voucher " + v.get("code") + " already cleared this accrual");
        }
        BigDecimal actual = jdbc.sql("SELECT coalesce(sum(\"allocatedAmount\"), 0) FROM \"LandedCostAllocation\" WHERE"
                + " \"landedCostId\" = ? AND \"goodsReceiptId\" = ?").params(voucherId, a.get("goodsReceiptId"))
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = SqlRows.dec(a, "remainingAmount");
        BigDecimal amount = remaining.min(actual);
        if (requested != null) {
            if (requested.signum() <= 0) {
                throw ApiException.badRequest("amount must be a positive number");
            }
            if (requested.compareTo(remaining) > 0) {
                throw ApiException.badRequest("Clearing " + JsNumbers.dec(requested) + " exceeds the remaining "
                        + JsNumbers.dec(remaining) + " of accrual " + a.get("number"));
            }
            if (requested.compareTo(actual) > 0) {
                throw ApiException.badRequest("Clearing " + JsNumbers.dec(requested) + " exceeds what voucher "
                        + v.get("code") + " capitalised on this receipt (" + JsNumbers.dec(actual) + ")");
            }
            amount = requested;
        }
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Nothing to clear: voucher " + v.get("code")
                    + " capitalised nothing on this receipt");
        }
        String text = "Landed cost accrual " + a.get("number") + " cleared by " + v.get("code");
        Map<String, Object> actualSide = actualSide(voucherId, (String) a.get("goodsReceiptId"));
        String clearing = unwind(a, amount, "clear", voucherId, text, actualSide, actual);
        jdbc.sql("UPDATE \"LandedCostAccrual\" SET \"voucherId\" = ?, \"actualAmount\" = coalesce(\"actualAmount\", 0)"
                + " + ? WHERE id = ?").params(voucherId, actual, id).update();
        audit.recordInTx(AuditInput.of("LandedCostAccrual", id, "update", null, Obj.of("cleared", JsNumbers.dec(amount),
                "voucher", v.get("code"), "clearingId", clearing)));
        return get(id);
    }

    /** Close an accrual: whatever is still open is unwound (no actual will follow); it ends {@code cleared}. */
    @Transactional
    public Map<String, Object> close(String id) {
        Map<String, Object> a = lock(id);
        // The estimate above every actual is a (negative) estimate variance: actual 0 against what is left.
        unwind(a, SqlRows.dec(a, "remainingAmount"), "close", null, "Landed cost accrual " + a.get("number")
                + " closed", null, BigDecimal.ZERO);
        audit.recordInTx(AuditInput.of("LandedCostAccrual", id, "update", null, Obj.of("closed", true)));
        return get(id);
    }

    /** Reverse an accrual that no voucher will clear (nothing cleared yet). */
    @Transactional
    public Map<String, Object> reverse(String id) {
        Map<String, Object> a = lock(id);
        if (SqlRows.dec(a, "clearedAmount").signum() != 0) {
            throw ApiException.badRequest("Accrual " + a.get("number") + " was partly cleared; close it instead");
        }
        unwind(a, SqlRows.dec(a, "remainingAmount"), "reverse", null, "Landed cost accrual " + a.get("number")
                + " reversed", null, null);
        audit.recordInTx(AuditInput.of("LandedCostAccrual", id, "cancel", null, Obj.of("number", a.get("number"))));
        return get(id);
    }

    /** The actual side of a clearing: the voucher's charge currency, amount and recognition rate on this receipt. */
    private Map<String, Object> actualSide(String voucherId, String grnId) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT coalesce(c.\"currencyCode\", '') AS cur,"
                + " sum(a.\"allocatedAmount\") AS functional, sum(a.\"allocatedAmount\" / coalesce(nullif(c.\"exchangeRate\","
                + " 0), 1)) AS amount, max(coalesce(c.\"exchangeRate\", 1)) AS rate FROM \"LandedCostAllocation\" a JOIN"
                + " \"LandedCostCharge\" c ON c.id = a.\"chargeId\" WHERE a.\"landedCostId\" = ? AND a.\"goodsReceiptId\" = ?"
                + " GROUP BY 1").params(voucherId, grnId));
        if (rows.size() != 1) {
            return Obj.of("currency", rows.isEmpty() ? null : "mixed", "amount", null, "rate", null);
        }
        Map<String, Object> r = rows.getFirst();
        String cur = (String) r.get("cur");
        return Obj.of("currency", cur.isEmpty() ? posting.baseCurrency() : cur, "amount", SqlRows.dec(r, "amount"),
                "rate", SqlRows.dec(r, "rate"));
    }

    /**
     * Take {@code amount} of the estimate out where it went, pro rata to the accrual's original amount: per adjustment
     * the cumulative due share of its on-hand bucket (from the open adjunct first, the rest released since → cost of
     * sales) and of its consumed lines (to their targets). The final clear (nothing left after it) takes exactly what
     * is left, so successive clears add up to what was accrued. Dr 2156 / Cr the targets.
     */
    private String unwind(Map<String, Object> a, BigDecimal amount, String kind, String voucherId, String text,
            Map<String, Object> actualSide, BigDecimal actualFunctional) {
        String id = (String) a.get("id");
        BigDecimal estimate = SqlRows.dec(a, "amount");
        BigDecimal cleared = SqlRows.dec(a, "clearedAmount");
        BigDecimal remaining = SqlRows.dec(a, "remainingAmount");
        if (amount.compareTo(remaining) > 0) {
            throw ApiException.badRequest("Clearing " + JsNumbers.dec(amount) + " exceeds the remaining "
                    + JsNumbers.dec(remaining) + " of accrual " + a.get("number"));
        }
        boolean last = amount.compareTo(remaining) == 0;
        BigDecimal fraction = cleared.add(amount).divide(estimate, 12, RoundingMode.HALF_UP);
        String clearingId = UUID.randomUUID().toString();
        Map<String, BigDecimal> credits = new LinkedHashMap<>();
        BigDecimal covered = BigDecimal.ZERO;
        BigDecimal taken = BigDecimal.ZERO;
        List<Object[]> clearingLines = new ArrayList<>();
        for (Map<String, Object> adj : SqlRows.list(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE"
                + " \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ? AND \"reversedAt\" IS NULL ORDER BY"
                + " \"createdAt\", id").params(org(), SOURCE, id))) {
            boolean followed = adj.get("rootAdjustmentId") != null || adj.get("parentAdjustmentId") != null;
            BigDecimal onHand = SqlRows.dec(adj, "onHandAmount");
            BigDecimal open = SqlRows.dec(adj, "openAmount");
            Map<String, BigDecimal> lineTargets = new LinkedHashMap<>();
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT target, amount FROM \"CostValueAdjustmentLine\""
                    + " WHERE \"adjustmentId\" = ? AND target <> 'on_hand'").param(adj.get("id")))) {
                String target = (String) l.get("target");
                if (followed && List.of("transfer", "in_transit").contains(target)) {
                    continue; // LC-1: a followed share is its own child adjustment
                }
                if ("transfer".equals(target)) {
                    onHand = onHand.add(SqlRows.dec(l, "amount")); // legacy: rode on the source pool's adjunct
                    continue;
                }
                lineTargets.merge(target, SqlRows.dec(l, "amount"), BigDecimal::add);
            }
            BigDecimal consumed = lineTargets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> prior = SqlRows.one(jdbc.sql("SELECT coalesce(sum(\"onHandAmount\"), 0) AS on_hand,"
                    + " coalesce(sum(\"consumedAmount\"), 0) AS consumed FROM \"LandedCostAccrualClearingLine\" WHERE"
                    + " \"adjustmentId\" = ?").param(adj.get("id")));
            BigDecimal priorOnHand = SqlRows.dec(prior, "on_hand");
            BigDecimal priorConsumed = SqlRows.dec(prior, "consumed");
            BigDecimal onHandDue = (last ? onHand : onHand.multiply(fraction).setScale(2, RoundingMode.HALF_UP))
                    .subtract(priorOnHand);
            BigDecimal consumedDue = (last ? consumed : consumed.multiply(fraction).setScale(2, RoundingMode.HALF_UP))
                    .subtract(priorConsumed);
            BigDecimal fromAdjunct = last ? open : onHandDue.min(open).max(BigDecimal.ZERO);
            BigDecimal released = onHandDue.subtract(fromAdjunct);
            credits.merge(lineage.priceDifferenceAccount(), fromAdjunct, BigDecimal::add);
            credits.merge(determination.mapped("cogs"), released, BigDecimal::add);
            if (consumed.signum() != 0 && consumedDue.signum() != 0) {
                BigDecimal given = BigDecimal.ZERO;
                List<Map.Entry<String, BigDecimal>> entries = new ArrayList<>(lineTargets.entrySet());
                for (int i = 0; i < entries.size(); i++) {
                    BigDecimal part = i == entries.size() - 1 ? consumedDue.subtract(given)
                            : consumedDue.multiply(entries.get(i).getValue()).divide(consumed, 2, RoundingMode.HALF_UP);
                    given = given.add(part);
                    credits.merge(capitalisation.accountFor(entries.get(i).getKey()), part, BigDecimal::add);
                }
            }
            if (fromAdjunct.signum() != 0) {
                lineage.recordValueOnly((String) adj.get("productId"), (String) adj.get("variantId"),
                        (String) adj.get("locationId"), null, fromAdjunct.negate(), SOURCE + "_clearing", id,
                        "price_difference", text);
                jdbc.sql("UPDATE \"CostValueAdjustment\" SET \"openAmount\" = \"openAmount\" - ? WHERE id = ?")
                        .params(fromAdjunct, adj.get("id")).update();
            }
            clearingLines.add(new Object[] {adj.get("id"), onHandDue, fromAdjunct, consumedDue});
            taken = taken.add(onHandDue).add(consumedDue);
            if (adj.get("parentAdjustmentId") == null) {
                covered = covered.add(SqlRows.dec(adj, "amount"));
            }
        }
        // Whatever the lineage did not cover (no receipt layer) went to cost of sales.
        BigDecimal uncovered = estimate.subtract(covered);
        if (uncovered.signum() != 0) {
            BigDecimal priorRest = jdbc.sql("SELECT coalesce(sum((details->>'uncovered')::numeric), 0) FROM"
                    + " \"LandedCostAccrualClearing\" WHERE \"accrualId\" = ?").param(id).query(BigDecimal.class).single();
            BigDecimal restDue = (last ? uncovered : uncovered.multiply(fraction).setScale(2, RoundingMode.HALF_UP))
                    .subtract(priorRest);
            credits.merge(determination.mapped("cogs"), restDue, BigDecimal::add);
            taken = taken.add(restDue);
            uncovered = restDue;
        }
        // Rounding of the pro-rata parts: the cost of sales line absorbs the cent so Dr 2156 = Σ credits exactly.
        BigDecimal residual = amount.subtract(taken);
        if (residual.signum() != 0) {
            credits.merge(determination.mapped("cogs"), residual, BigDecimal::add);
        }
        if (last) {
            lineage.closeAdjustments(SOURCE, id);
        }
        List<Line> lines = new ArrayList<>();
        lines.add(signed(accrualAccount(), amount, text));
        credits.forEach((acc, v) -> {
            if (v.signum() != 0) {
                lines.add(signed(acc, v.negate(), text));
            }
        });
        Map<String, Object> je = posting.post(new PostingRequest("INV", Instant.now(), text, null, null,
                SOURCE + "_clearing", id, null, "costing:landed_accrual_clearing:" + org() + ":" + id + ":" + clearingId,
                null, null, null, lines));
        String entryId = je == null ? null : (String) je.get("id");
        // Accrual currency side: the rule's currency and rate of the accrued lines (receipt-date rate).
        Map<String, Object> orig = SqlRows.one(jdbc.sql("SELECT CASE WHEN count(DISTINCT coalesce(\"currencyCode\", ''))"
                + " = 1 THEN max(\"currencyCode\") END AS cur, sum(\"currencyAmount\") AS amt, max(\"exchangeRate\") AS rate"
                + " FROM \"LandedCostAccrualLine\" WHERE \"accrualId\" = ?").param(id));
        BigDecimal share = amount.divide(estimate, 12, RoundingMode.HALF_UP);
        BigDecimal origAmount = orig.get("amt") == null ? null : SqlRows.dec(orig, "amt").multiply(share)
                .setScale(6, RoundingMode.HALF_UP);
        jdbc.sql("INSERT INTO \"LandedCostAccrualClearing\" (id, \"organizationId\", \"accrualId\", \"voucherId\", kind,"
                        + " \"functionalAmount\", \"originalCurrency\", \"originalAmount\", \"originalRate\", \"actualCurrency\","
                        + " \"actualAmount\", \"actualRate\", \"actualFunctional\", variance, \"journalEntryId\", details,"
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)")
                .params(clearingId, org(), id, voucherId, kind, amount, orig.get("cur") == null ? posting.baseCurrency()
                        : orig.get("cur"), origAmount, orig.get("rate"), actualSide == null ? null
                                : actualSide.get("currency"), actualSide == null ? null : actualSide.get("amount"),
                        actualSide == null ? null : actualSide.get("rate"), actualFunctional,
                        actualFunctional == null ? null : actualFunctional.subtract(amount), entryId,
                        com.inventoryerp.shared.web.EntityJson.toJson(Obj.of("uncovered", uncovered)),
                        TenantContext.userId())
                .update();
        for (Object[] l : clearingLines) {
            jdbc.sql("INSERT INTO \"LandedCostAccrualClearingLine\" (id, \"organizationId\", \"clearingId\","
                    + " \"adjustmentId\", \"onHandAmount\", \"adjunctAmount\", \"consumedAmount\") VALUES (?, ?, ?, ?, ?, ?,"
                    + " ?)").params(UUID.randomUUID().toString(), org(), clearingId, l[0], l[1], l[2], l[3]).update();
        }
        BigDecimal newCleared = cleared.add(amount);
        BigDecimal newRemaining = remaining.subtract(amount);
        String status = newRemaining.signum() == 0 ? ("reverse".equals(kind) ? "reversed" : "cleared")
                : "partially_cleared";
        jdbc.sql("UPDATE \"LandedCostAccrual\" SET status = ?, \"clearedAmount\" = ?, \"remainingAmount\" = ?,"
                + " \"clearingEntryId\" = ?, \"clearedAt\" = CASE WHEN ? THEN now() ELSE \"clearedAt\" END WHERE id = ?")
                .params(status, "reverse".equals(kind) ? cleared : newCleared, newRemaining, entryId,
                        newRemaining.signum() == 0, id).update();
        return clearingId;
    }

    private Map<String, Object> lock(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"LandedCostAccrual\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Landed cost accrual not found");
        }
        if (!List.of("posted", "partially_cleared").contains((String) a.get("status"))) {
            throw ApiException.badRequest("Only an open accrual can be cleared or reversed");
        }
        return a;
    }

    /**
     * Monitor (LC-4): open and partly cleared accruals with their age bucket, overdue flag (past the expected settlement
     * date), estimate, cleared, remaining, actual and the estimate variance — FX on payment is not part of it.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> monitor() {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.number, g.\"receiptNumber\", a.status,"
                + " a.amount AS estimate, a.\"clearedAmount\" AS cleared, a.\"remainingAmount\" AS remaining,"
                + " a.\"expectedSettlementDate\", (current_date - a.\"createdAt\"::date) AS \"ageDays\","
                + " coalesce((SELECT sum(c.\"actualFunctional\") FROM \"LandedCostAccrualClearing\" c WHERE c.\"accrualId\" ="
                + " a.id), 0) AS actual, coalesce((SELECT sum(c.variance) FROM \"LandedCostAccrualClearing\" c WHERE"
                + " c.\"accrualId\" = a.id), 0) AS variance FROM \"LandedCostAccrual\" a JOIN \"GoodsReceiptNote\" g ON g.id ="
                + " a.\"goodsReceiptId\" WHERE a.\"organizationId\" = ? AND a.status IN ('posted', 'partially_cleared')"
                + " ORDER BY a.\"createdAt\"").param(org()));
        Map<String, BigDecimal> buckets = new LinkedHashMap<>();
        for (String b : List.of("0-30", "31-60", "61-90", "90+")) {
            buckets.put(b, BigDecimal.ZERO);
        }
        int overdue = 0;
        for (Map<String, Object> r : rows) {
            int age = ((Number) r.get("ageDays")).intValue();
            String bucket = age <= 30 ? "0-30" : age <= 60 ? "31-60" : age <= 90 ? "61-90" : "90+";
            r.put("bucket", bucket);
            buckets.merge(bucket, SqlRows.dec(r, "remaining"), BigDecimal::add);
            Object due = r.get("expectedSettlementDate");
            boolean late = due != null && java.time.LocalDate.parse(due.toString().substring(0, 10))
                    .isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
            r.put("overdue", late);
            if (late) {
                overdue++;
            }
        }
        Map<String, Object> byBucket = new LinkedHashMap<>();
        buckets.forEach((k, v) -> byBucket.put(k, JsNumbers.dec(v)));
        return Obj.of("accruals", rows, "buckets", byBucket, "overdue", overdue, "partlyCleared",
                rows.stream().filter(r -> "partially_cleared".equals(r.get("status"))).count());
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
        a.put("clearings", SqlRows.list(jdbc.sql("SELECT * FROM \"LandedCostAccrualClearing\" WHERE \"accrualId\" = ?"
                + " ORDER BY \"createdAt\"").param(id)));
        return a;
    }

    /** Open accruals vs the accrual account: the account must equal what is still open. */
    @Transactional
    public Map<String, Object> reconciliation() {
        BigDecimal open = jdbc.sql("SELECT coalesce(sum(\"remainingAmount\"), 0) FROM \"LandedCostAccrual\" WHERE"
                + " \"organizationId\" = ? AND status IN ('posted', 'partially_cleared')").param(org()).query(BigDecimal.class)
                .single();
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
