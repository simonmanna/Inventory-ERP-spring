package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.core.application.PriceListService;
import com.inventoryerp.core.application.PriceListService.ListPrice;
import com.inventoryerp.core.application.PriceListService.PriceQuery;
import com.inventoryerp.core.application.PricingEngine;
import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one sales pricing engine (ADR-S011 phase S2, ADR-S022) for every channel: sales quotations and orders directly,
 * POS orders, invoices and manual delivery orders through {@link PricingEngine} ({@code PriceListService.resolve}).
 * Tiers are tried in the organization's {@code PricingPolicy.precedence} (default below); the first tier that prices the
 * line wins, the catalogue {@code list} price is always last:
 * <ol>
 *   <li>{@code agreement}: the sales agreement the document releases against (active on the date, S9);</li>
 *   <li>{@code customer_rule}: pricelist rules bound to the customer or its category (any active pricelist);</li>
 *   <li>{@code crm_rule}: CRM customer price rules (ADR-S010);</li>
 *   <li>{@code customer_pricelist}, {@code category_pricelist}: the customer's, then its category's pricelist;</li>
 *   <li>{@code price_list}: named price lists (SALES-3, {@link PriceListService#namedListPrice});</li>
 *   <li>{@code branch_default}, {@code org_default}: the default pricelists;</li>
 *   <li>{@code standing_discount}: the customer's / category's standing discount.</li>
 * </ol>
 * Inside a pricelist the best rule wins by priority, then product &gt; category &gt; all, then the highest minimum
 * quantity. Rules can be bound to a unit, a minimum quantity, a delivery zone, a branch and dates. Every result carries
 * the margin floor (minimum price) that drives approvals.
 */
@Service
@Primary
public class SalesPricingEngine implements QuotationPricingPort, PricingEngine {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Tiers in their default order (ADR-S022 §4); {@code list} is implicit and always last. */
    public static final List<String> TIERS = List.of("agreement", "customer_rule", "crm_rule", "customer_pricelist",
            "category_pricelist", "price_list", "branch_default", "org_default", "standing_discount");

    private final SalesDb db;
    private final UomConversionService uoms;
    private final PriceListService namedLists;

    public SalesPricingEngine(SalesDb db, UomConversionService uoms, PriceListService namedLists) {
        this.db = db;
        this.uoms = uoms;
        this.namedLists = namedLists;
    }

    /** Pricing context of one line. */
    public record Context(String partnerId, String productId, BigDecimal quantity, String uomId, String siteId,
            String branchId, String currencyCode, LocalDate on, String agreementId) {

        public Context(String partnerId, String productId, BigDecimal quantity, String uomId, String siteId,
                String branchId, String currencyCode, LocalDate on) {
            this(partnerId, productId, quantity, uomId, siteId, branchId, currencyCode, on, null);
        }
    }

    /** Resolved price with provenance and margin information. */
    public record Result(String productId, BigDecimal quantity, BigDecimal listPrice, BigDecimal unitPrice,
            BigDecimal discountPercent, String source, String pricelistId, String pricelistName, String itemId,
            String taxId, boolean taxInclusive, String description, BigDecimal cost, BigDecimal minMarginPercent,
            BigDecimal floorPrice) {

        public BigDecimal net() {
            return unitPrice.multiply(BigDecimal.ONE.subtract(discountPercent.divide(HUNDRED, 10, RoundingMode.HALF_UP)))
                    .setScale(6, RoundingMode.HALF_UP);
        }

        /** Margin % of the net price over cost, or null without cost. */
        public BigDecimal marginPercent() {
            BigDecimal net = net();
            if (cost == null || net.signum() <= 0) {
                return null;
            }
            return net.subtract(cost).multiply(HUNDRED).divide(net, 2, RoundingMode.HALF_UP);
        }

        public Map<String, Object> json() {
            Map<String, Object> m = Obj.of("productId", productId, "quantity", JsNumbers.js(quantity), "listPrice",
                    JsNumbers.js(listPrice), "unitPrice", JsNumbers.js(unitPrice), "discountPercent",
                    JsNumbers.js(discountPercent), "net", JsNumbers.js(net()), "netPrice", JsNumbers.js(net()), "source",
                    source, "pricelistId", pricelistId, "pricelistName", pricelistName, "itemId", itemId, "ruleId", itemId);
            m.put("ruleName", pricelistName);
            m.put("taxId", taxId);
            m.put("taxInclusive", taxInclusive);
            m.put("description", description);
            m.put("cost", cost == null ? null : JsNumbers.js(cost));
            m.put("minMarginPercent", minMarginPercent == null ? null : JsNumbers.js(minMarginPercent));
            m.put("floorPrice", floorPrice == null ? null : JsNumbers.js(floorPrice));
            m.put("marginPercent", marginPercent() == null ? null : JsNumbers.js(marginPercent()));
            return m;
        }
    }

    @Override
    public Price price(String partnerId, String productId, BigDecimal qty, LocalDate on) {
        Result r = resolve(new Context(partnerId, productId, qty, null, null, null, null, on));
        return new Price(r.listPrice(), r.unitPrice(), r.discountPercent(), r.source(), r.itemId(), r.taxId(),
                r.taxInclusive(), r.description());
    }


    /** A resolved line and whether a named list left the price at the catalogue price (discount only). */
    private record Priced(Result result, boolean catalogueUnit) {}

    /** {@link PricingEngine}: one line for POS orders, invoices, delivery orders and CRM (in the line's sales unit). */
    @Override
    @Transactional(readOnly = true)
    public Quote quote(PriceQuery q) {
        Map<String, Object> product = db.row("SELECT \"uomId\", \"salesUomId\" FROM \"Product\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL", q.productId(), org());
        if (product == null) {
            return null;
        }
        String uomId = !blank(q.uomId()) ? q.uomId() : product.get("salesUomId") != null ? str(product.get("salesUomId"))
                : str(product.get("uomId"));
        BigDecimal qty = q.quantity() == null ? BigDecimal.ONE : q.quantity();
        Priced p = price(new Context(q.partnerId(), q.productId(), qty, uomId, q.siteId(), q.branchId(), null, q.on()),
                q);
        Result r = p.result();
        return new Quote(r.source(), p.catalogueUnit() ? null : r.unitPrice(), r.discountPercent(), r.itemId(),
                r.pricelistName(), breakdown(r));
    }

    /** {@code POST /sales/pricing/resolve}: price {@code lines [{productId, quantity, uomId}]}, with the breakdown. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolve(Map<String, Object> in) {
        String partnerId = blank(str(in.get("partnerId"))) ? null : str(in.get("partnerId"));
        LocalDate on = in.get("date") == null ? LocalDate.now() : SalesDb.date(str(in.get("date")));
        List<Map<String, Object>> out = new ArrayList<>();
        if (in.get("lines") instanceof List<?> lines) {
            for (Object o : lines) {
                Map<String, Object> l = (Map<String, Object>) o;
                BigDecimal qty = l.get("quantity") == null ? BigDecimal.ONE : dec(l.get("quantity"));
                Result r = resolve(new Context(partnerId, str(l.get("productId")), qty, str(l.get("uomId")),
                        str(in.get("siteId")), str(in.get("branchId")), str(in.get("currencyCode")), on));
                Map<String, Object> j = r.json();
                j.put("breakdown", breakdown(r));
                out.add(j);
            }
        }
        return Obj.of("partnerId", partnerId, "lines", out);
    }

    /** Resolve one line. */
    @Transactional(readOnly = true)
    public Result resolve(Context c) {
        return price(c, null).result();
    }

    /** {@code named}: the original query of a core caller, so the named-list tier sees its unit and site as given. */
    private Priced price(Context c, PriceQuery named) {
        Map<String, Object> product = db.row("SELECT id, name, \"salesPrice\", \"costPrice\", \"categoryId\", \"taxId\","
                + " \"taxInclusive\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                c.productId(), org());
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        LocalDate on = c.on() == null ? LocalDate.now() : c.on();
        BigDecimal factor = blank(c.uomId()) ? BigDecimal.ONE : uoms.factorToBase(c.productId(), c.uomId());
        BigDecimal list = dec(product.get("salesPrice")).multiply(factor).setScale(6, RoundingMode.HALF_UP);
        BigDecimal cost = product.get("costPrice") == null || dec(product.get("costPrice")).signum() == 0 ? null
                : dec(product.get("costPrice")).multiply(factor).setScale(6, RoundingMode.HALF_UP);
        Map<String, Object> partner = c.partnerId() == null ? null : db.row("SELECT p.\"categoryId\", p.\"pricelistId\","
                + " p.\"discountPercentOverride\" AS disc, pc.\"discountPercentOverride\" AS \"catDisc\", pc.\"pricelistId\""
                + " AS \"catPricelistId\" FROM \"Partner\" p LEFT JOIN \"PartnerCategory\" pc ON pc.id = p.\"categoryId\""
                + " WHERE p.id = ? AND p.\"organizationId\" = ?", c.partnerId(), org());
        String partnerCat = partner == null ? null : str(partner.get("categoryId"));
        String zone = blank(c.siteId()) ? null : str(value("SELECT zone FROM \"FleetCustomerSite\" WHERE id = ? AND"
                + " \"organizationId\" = ?", c.siteId(), org()));
        Line line = new Line(c, product, factor, list, cost, partnerCat, zone, on);
        String taxId = str(product.get("taxId"));
        boolean incl = Boolean.TRUE.equals(product.get("taxInclusive"));
        String name = str(product.get("name"));
        Floor floor = floor(c, product, partnerCat, cost, on);
        for (String tier : precedence()) {
            Priced p = switch (tier) {
                case "agreement" -> agreement(line, taxId, incl, name, floor);
                case "customer_rule" -> c.partnerId() == null ? null
                        : pricelist(line, bestItem(line, null, true), taxId, incl, name, floor);
                case "crm_rule" -> crm(line, taxId, incl, name, floor);
                case "customer_pricelist" -> partner == null || partner.get("pricelistId") == null ? null
                        : pricelist(line, bestItem(line, str(partner.get("pricelistId")), false), taxId, incl, name, floor);
                case "category_pricelist" -> partner == null || partner.get("catPricelistId") == null ? null
                        : pricelist(line, bestItem(line, str(partner.get("catPricelistId")), false), taxId, incl, name,
                                floor);
                case "price_list" -> named(line, named, taxId, incl, name, floor);
                case "branch_default" -> blank(c.branchId()) ? null
                        : defaultList(line, c.branchId(), taxId, incl, name, floor);
                case "org_default" -> defaultList(line, null, taxId, incl, name, floor);
                case "standing_discount" -> standing(line, partner, taxId, incl, name, floor);
                default -> null;
            };
            if (p != null) {
                return p;
            }
        }
        return new Priced(new Result(c.productId(), c.quantity(), list, list, BigDecimal.ZERO, "list", null, null, null,
                taxId, incl, name, cost, floor.min, floor.price), false);
    }

    private Priced agreement(Line l, String taxId, boolean incl, String name, Floor floor) {
        Context c = l.c();
        if (blank(c.agreementId())) {
            return null;
        }
        Map<String, Object> a = db.row("SELECT l.id, l.\"uomId\", l.\"unitPrice\", l.\"discountPercent\", a.name FROM"
                + " \"SalesAgreementLine\" l JOIN \"SalesAgreement\" a ON a.id = l.\"agreementId\" WHERE a.id = ? AND"
                + " a.\"organizationId\" = ? AND a.status = 'active' AND a.\"startDate\" <= ? AND a.\"endDate\" >= ? AND"
                + " l.\"productId\" = ?", c.agreementId(), org(), l.on(), l.on(), c.productId());
        if (a == null) {
            return null;
        }
        BigDecimal agreed = blank(str(a.get("uomId"))) ? BigDecimal.ONE
                : uoms.factorToBase(c.productId(), str(a.get("uomId")));
        BigDecimal unit = dec(a.get("unitPrice")).multiply(l.factor()).divide(agreed, 6, RoundingMode.HALF_UP);
        return new Priced(new Result(c.productId(), c.quantity(), l.list(), unit, dec(a.get("discountPercent")),
                "agreement", null, str(a.get("name")), str(a.get("id")), taxId, incl, name, l.cost(), floor.min,
                floor.price), false);
    }

    private Priced pricelist(Line l, Map<String, Object> item, String taxId, boolean incl, String name, Floor floor) {
        return item == null ? null : new Priced(result(l, item, taxId, incl, name, floor, 0), false);
    }

    private Priced defaultList(Line l, String branchId, String taxId, boolean incl, String name, Floor floor) {
        Object id = branchId == null
                ? value("SELECT id FROM \"Pricelist\" WHERE \"organizationId\" = ? AND \"branchId\" IS NULL AND"
                        + " \"isDefault\" AND \"isActive\" AND \"deletedAt\" IS NULL ORDER BY \"createdAt\", id LIMIT 1",
                        org())
                : value("SELECT id FROM \"Pricelist\" WHERE \"organizationId\" = ? AND \"branchId\" = ? AND"
                        + " \"isDefault\" AND \"isActive\" AND \"deletedAt\" IS NULL ORDER BY \"createdAt\", id LIMIT 1",
                        org(), branchId);
        return id == null ? null : pricelist(l, bestItem(l, str(id), false), taxId, incl, name, floor);
    }

    private Priced crm(Line l, String taxId, boolean incl, String name, Floor floor) {
        Map<String, Object> rule = crmRule(l);
        if (rule == null) {
            return null;
        }
        Context c = l.c();
        BigDecimal v = dec(rule.get("value"));
        boolean fixed = "fixed_price".equals(rule.get("kind"));
        return new Priced(new Result(c.productId(), c.quantity(), l.list(), fixed ? v.multiply(l.factor()) : l.list(),
                fixed ? BigDecimal.ZERO : v, "rule", null, str(rule.get("name")), str(rule.get("id")), taxId, incl, name,
                l.cost(), floor.min, floor.price), false);
    }

    /** SALES-3 named price lists, priced by {@link PriceListService#namedListPrice} in the caller's own unit. */
    private Priced named(Line l, PriceQuery original, String taxId, boolean incl, String name, Floor floor) {
        Context c = l.c();
        PriceQuery q = original != null ? original : new PriceQuery(c.partnerId(), c.productId(), c.quantity(),
                c.uomId(), c.branchId(), c.siteId(), l.on());
        ListPrice lp = namedLists.namedListPrice(q);
        if (lp == null) {
            return null;
        }
        BigDecimal unit = lp.unitPrice() != null ? lp.unitPrice() : l.list();
        BigDecimal disc = lp.discountPercent() == null ? BigDecimal.ZERO : lp.discountPercent();
        return new Priced(new Result(c.productId(), c.quantity(), l.list(), unit, disc, "price_list", lp.priceListId(),
                lp.priceListName(), lp.priceListId(), taxId, incl, name, l.cost(), floor.min, floor.price),
                lp.unitPrice() == null);
    }

    private Priced standing(Line l, Map<String, Object> partner, String taxId, boolean incl, String name, Floor floor) {
        BigDecimal standing = partner == null ? BigDecimal.ZERO : dec(partner.get("disc"));
        if (standing.signum() == 0 && partner != null) {
            standing = dec(partner.get("catDisc"));
        }
        if (standing.signum() <= 0) {
            return null;
        }
        Context c = l.c();
        return new Priced(new Result(c.productId(), c.quantity(), l.list(), l.list(), standing.min(HUNDRED), "customer",
                null, null, null, taxId, incl, name, l.cost(), floor.min, floor.price), false);
    }

    // ------------------------------------------------------------------ precedence and breakdown

    /** The organization's tier order (ADR-S022 §4); the default when none is configured. */
    public List<String> precedence() {
        Map<String, Object> p = db.row("SELECT precedence FROM \"PricingPolicy\" WHERE \"organizationId\" = ?", org());
        List<String> configured = p == null ? List.of() : SalesDb.strings(p.get("precedence"));
        return configured.isEmpty() ? TIERS : configured;
    }

    /** {@code PUT /sales/pricing/policy}: an order of {@link #TIERS} (a subset skips the tiers left out). */
    @Transactional
    public Map<String, Object> savePrecedence(List<String> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            throw ApiException.badRequest("precedence should not be empty");
        }
        if (new LinkedHashSet<>(tiers).size() != tiers.size()) {
            throw ApiException.badRequest("precedence must not repeat a tier");
        }
        for (String t : tiers) {
            if (!TIERS.contains(t)) {
                throw ApiException.badRequest("Unknown pricing tier: " + t);
            }
        }
        db.update("INSERT INTO \"PricingPolicy\" (\"organizationId\", precedence, \"updatedById\") VALUES (?, ?, ?)"
                + " ON CONFLICT (\"organizationId\") DO UPDATE SET precedence = EXCLUDED.precedence, \"updatedById\" ="
                + " EXCLUDED.\"updatedById\", \"updatedAt\" = now()", org(), tiers.toArray(String[]::new), SalesDb.user());
        return pricingPolicy();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> pricingPolicy() {
        return Obj.of("precedence", precedence(), "tiers", TIERS, "last", "list");
    }

    /**
     * Price breakdown of a resolved line (ADR-S022 §4): base (list) price, unit price, discount, net, tax and final
     * price per unit and for the line. A tax-inclusive price carries its tax inside the net.
     */
    public Map<String, Object> breakdown(Result r) {
        BigDecimal rate = BigDecimal.ZERO;
        if (r.taxId() != null) {
            Object v = value("SELECT rate FROM \"Tax\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                    r.taxId(), org());
            rate = v == null ? BigDecimal.ZERO : dec(v);
        }
        BigDecimal net = r.net();
        BigDecimal tax;
        BigDecimal fin;
        if (r.taxInclusive()) {
            BigDecimal excl = net.divide(BigDecimal.ONE.add(rate.divide(HUNDRED, 10, RoundingMode.HALF_UP)), 6,
                    RoundingMode.HALF_UP);
            tax = net.subtract(excl);
            fin = net;
        } else {
            tax = net.multiply(rate).divide(HUNDRED, 6, RoundingMode.HALF_UP);
            fin = net.add(tax);
        }
        BigDecimal qty = r.quantity() == null ? BigDecimal.ONE : r.quantity();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("source", r.source());
        b.put("ruleId", r.itemId());
        b.put("ruleName", r.pricelistName());
        b.put("basePrice", JsNumbers.js(r.listPrice()));
        b.put("unitPrice", JsNumbers.js(r.unitPrice()));
        b.put("discountPercent", JsNumbers.js(r.discountPercent()));
        b.put("discountAmount", JsNumbers.js(r.unitPrice().subtract(net).setScale(6, RoundingMode.HALF_UP)));
        b.put("netPrice", JsNumbers.js(net));
        b.put("taxId", r.taxId());
        b.put("taxRate", JsNumbers.js(rate));
        b.put("taxInclusive", r.taxInclusive());
        b.put("taxAmount", JsNumbers.js(tax));
        b.put("finalPrice", JsNumbers.js(fin));
        b.put("quantity", JsNumbers.js(qty));
        b.put("lineNet", JsNumbers.js(net.multiply(qty).setScale(2, RoundingMode.HALF_UP)));
        b.put("lineTax", JsNumbers.js(tax.multiply(qty).setScale(2, RoundingMode.HALF_UP)));
        b.put("lineTotal", JsNumbers.js(fin.multiply(qty).setScale(2, RoundingMode.HALF_UP)));
        return b;
    }

    /**
     * Commercial policy breaches of document lines ({@code productId, quantity, uomId, unitPrice, discountPercent}):
     * extra discount beyond the authorised (resolved) price above {@code maxDiscount}, and net prices below the margin
     * floor (when {@code marginCheck}). Returned as human-readable approval reasons.
     */
    @Transactional(readOnly = true)
    public List<String> breaches(String partnerId, String branchId, LocalDate on, List<Map<String, Object>> lines,
            BigDecimal maxDiscount, boolean marginCheck) {
        BigDecimal extra = BigDecimal.ZERO;
        List<String> out = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            if (l.get("productId") == null) {
                continue;
            }
            Result r = resolve(new Context(partnerId, str(l.get("productId")), dec(l.get("quantity")), str(l.get("uomId")),
                    null, branchId, null, on));
            BigDecimal net = dec(l.get("unitPrice")).multiply(BigDecimal.ONE.subtract(dec(l.get("discountPercent"))
                    .divide(HUNDRED, 10, RoundingMode.HALF_UP)));
            BigDecimal authorised = r.net();
            if (authorised.signum() > 0 && net.compareTo(authorised) < 0) {
                extra = extra.max(BigDecimal.ONE.subtract(net.divide(authorised, 10, RoundingMode.HALF_UP)).multiply(HUNDRED));
            }
            if (marginCheck && r.floorPrice() != null && net.compareTo(r.floorPrice()) < 0) {
                out.add("Price of " + l.get("description") + " is below the " + r.minMarginPercent().stripTrailingZeros()
                        .toPlainString() + "% margin floor");
            }
        }
        if (extra.compareTo(maxDiscount) > 0) {
            out.addFirst("Discount " + extra.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                    + "% exceeds the " + maxDiscount.stripTrailingZeros().toPlainString() + "% limit");
        }
        return out;
    }

    private record Line(Context c, Map<String, Object> product, BigDecimal factor, BigDecimal list, BigDecimal cost,
            String partnerCat, String zone, LocalDate on) {}

    private record Floor(BigDecimal min, BigDecimal price) {}

    /**
     * Best matching rule of one pricelist ({@code pricelistId}), or among customer-bound rules of every active
     * pricelist ({@code customerOnly}).
     */
    private Map<String, Object> bestItem(Line l, String pricelistId, boolean customerOnly) {
        Context c = l.c();
        BigDecimal baseQty = c.quantity().multiply(l.factor());
        List<Object> params = new ArrayList<>(List.of(org(), l.on(), l.on(), l.on(), l.on()));
        StringBuilder sql = new StringBuilder("""
                SELECT i.*, p.name AS "pricelistName", p."basePricelistId"
                  FROM "PricelistItem" i JOIN "Pricelist" p ON p.id = i."pricelistId"
                 WHERE i."organizationId" = ? AND p."isActive" AND p."deletedAt" IS NULL
                   AND (p."validFrom" IS NULL OR p."validFrom" <= ?) AND (p."validTo" IS NULL OR p."validTo" >= ?)
                   AND (i."validFrom" IS NULL OR i."validFrom" <= ?) AND (i."validTo" IS NULL OR i."validTo" >= ?)
                """);
        sql.append(" AND (p.\"currencyCode\" IS NULL OR ?::text IS NULL OR p.\"currencyCode\" = ?)");
        params.add(c.currencyCode());
        params.add(c.currencyCode());
        sql.append(" AND ((i.scope = 'product' AND i.\"productId\" = ?) OR (i.scope = 'category' AND"
                + " i.\"productCategoryId\" = ?) OR i.scope = 'all')");
        params.add(c.productId());
        params.add(str(l.product().get("categoryId")) == null ? "" : str(l.product().get("categoryId")));
        // Unit: a rule in the line's unit compares the line quantity; a base-unit rule compares base quantity.
        sql.append(" AND ((i.\"uomId\" IS NULL AND i.\"minQuantity\" <= ?) OR (i.\"uomId\" = ? AND i.\"minQuantity\" <= ?))");
        params.add(baseQty);
        params.add(c.uomId() == null ? "" : c.uomId());
        params.add(c.quantity());
        sql.append(" AND (i.\"siteZone\" IS NULL OR i.\"siteZone\" = ?) AND (i.\"branchId\" IS NULL OR i.\"branchId\" = ?)");
        params.add(l.zone() == null ? "" : l.zone());
        params.add(c.branchId() == null ? "" : c.branchId());
        if (customerOnly) {
            sql.append(" AND (i.\"partnerId\" = ? OR i.\"partnerCategoryId\" = ?)");
            params.add(c.partnerId());
            params.add(l.partnerCat() == null ? "" : l.partnerCat());
        } else {
            sql.append(" AND i.\"pricelistId\" = ? AND ((i.\"partnerId\" IS NULL AND i.\"partnerCategoryId\" IS NULL) OR"
                    + " i.\"partnerId\" = ? OR i.\"partnerCategoryId\" = ?)");
            params.add(pricelistId);
            params.add(c.partnerId() == null ? "" : c.partnerId());
            params.add(l.partnerCat() == null ? "" : l.partnerCat());
        }
        sql.append(" ORDER BY i.priority DESC, CASE i.scope WHEN 'product' THEN 3 WHEN 'category' THEN 2 ELSE 1 END DESC,"
                + " CASE WHEN i.\"partnerId\" IS NOT NULL THEN 2 WHEN i.\"partnerCategoryId\" IS NOT NULL THEN 1 ELSE 0 END"
                + " DESC, i.\"minQuantity\" DESC, i.\"createdAt\" LIMIT 1");
        return db.row(sql.toString(), params.toArray());
    }

    private Result result(Line l, Map<String, Object> item, String taxId, boolean incl, String name, Floor floor,
            int depth) {
        BigDecimal unitFactor = item.get("uomId") == null ? l.factor() : BigDecimal.ONE;
        BigDecimal v = dec(item.get("value"));
        BigDecimal base = base(l, str(item.get("basePricelistId")), depth);
        BigDecimal unit;
        BigDecimal disc = BigDecimal.ZERO;
        switch (str(item.get("compute"))) {
            case "fixed" -> unit = v.multiply(unitFactor);
            case "discount" -> {
                unit = base;
                disc = v;
            }
            case "surcharge" -> unit = base.multiply(BigDecimal.ONE.add(v.divide(HUNDRED, 10, RoundingMode.HALF_UP)));
            default -> unit = (l.cost() == null ? BigDecimal.ZERO : l.cost())
                    .multiply(BigDecimal.ONE.add(v.divide(HUNDRED, 10, RoundingMode.HALF_UP)));
        }
        return new Result(l.c().productId(), l.c().quantity(), l.list(), unit.setScale(6, RoundingMode.HALF_UP), disc,
                "pricelist", str(item.get("pricelistId")), str(item.get("pricelistName")), str(item.get("id")), taxId,
                incl, name, l.cost(), floor.min, floor.price);
    }

    /** Base price for % rules: the base pricelist's price (max depth 3) or the list price. */
    private BigDecimal base(Line l, String basePricelistId, int depth) {
        if (basePricelistId == null || depth >= 3) {
            return l.list();
        }
        Map<String, Object> item = bestItem(l, basePricelistId, false);
        if (item == null) {
            return l.list();
        }
        return result(l, item, null, false, null, new Floor(null, null), depth + 1).net();
    }

    private Map<String, Object> crmRule(Line l) {
        Context c = l.c();
        return db.row("""
                SELECT id, name, kind, value FROM "CrmPriceRule"
                 WHERE "organizationId" = ? AND "isActive" AND "minQuantity" <= ?
                   AND ("validFrom" IS NULL OR "validFrom" <= ?) AND ("validTo" IS NULL OR "validTo" >= ?)
                   AND (("partnerId" IS NULL AND "partnerCategoryId" IS NULL) OR "partnerId" = ? OR "partnerCategoryId" = ?)
                   AND (("productId" IS NULL AND "productCategoryId" IS NULL) OR "productId" = ? OR "productCategoryId" = ?)
                 ORDER BY priority DESC,
                          CASE WHEN "partnerId" IS NOT NULL THEN 3 WHEN "partnerCategoryId" IS NOT NULL THEN 2 ELSE 1 END DESC,
                          CASE WHEN "productId" IS NOT NULL THEN 3 WHEN "productCategoryId" IS NOT NULL THEN 2 ELSE 1 END DESC,
                          "minQuantity" DESC
                 LIMIT 1""", org(), c.quantity(), l.on(), l.on(), c.partnerId() == null ? "" : c.partnerId(),
                l.partnerCat() == null ? "" : l.partnerCat(), c.productId(), l.product().get("categoryId") == null ? ""
                        : l.product().get("categoryId"));
    }

    /** Most specific active margin floor (product &gt; category &gt; all; customer category; branch). */
    private Floor floor(Context c, Map<String, Object> product, String partnerCat, BigDecimal cost, LocalDate on) {
        Map<String, Object> f = db.row("""
                SELECT "minMarginPercent" FROM "MarginFloor"
                 WHERE "organizationId" = ? AND "isActive"
                   AND ("validFrom" IS NULL OR "validFrom" <= ?) AND ("validTo" IS NULL OR "validTo" >= ?)
                   AND (("productId" IS NULL AND "productCategoryId" IS NULL) OR "productId" = ? OR "productCategoryId" = ?)
                   AND ("partnerCategoryId" IS NULL OR "partnerCategoryId" = ?) AND ("branchId" IS NULL OR "branchId" = ?)
                 ORDER BY CASE WHEN "productId" IS NOT NULL THEN 3 WHEN "productCategoryId" IS NOT NULL THEN 2 ELSE 1 END DESC,
                          ("partnerCategoryId" IS NOT NULL) DESC, ("branchId" IS NOT NULL) DESC
                 LIMIT 1""", org(), on, on, c.productId(), product.get("categoryId") == null ? ""
                : product.get("categoryId"), partnerCat == null ? "" : partnerCat, c.branchId() == null ? ""
                        : c.branchId());
        if (f == null) {
            return new Floor(null, null);
        }
        BigDecimal m = dec(f.get("minMarginPercent"));
        BigDecimal price = cost == null ? null : cost.divide(BigDecimal.ONE.subtract(m.divide(HUNDRED, 10,
                RoundingMode.HALF_UP)), 6, RoundingMode.HALF_UP);
        return new Floor(m, price);
    }

    private Object value(String sql, Object... params) {
        Map<String, Object> r = db.row(sql, params);
        return r == null ? null : r.values().iterator().next();
    }
}
