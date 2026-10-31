package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.dec;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.core.application.PriceListService;
import com.inventoryerp.core.application.PriceListService.ListPrice;
import com.inventoryerp.core.application.PriceListService.PriceQuery;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer-specific pricing (ADR-S010). Price rules target a customer, a customer category or everyone, and a
 * product, a product category or every product, with a minimum quantity and a validity window; a rule sets a fixed
 * unit price or a discount off the list price. Resolution picks the highest {@code priority}, then the most specific
 * customer match, then the most specific product match, then the largest minimum quantity. Without a rule, the
 * customer's (or its category's) standing discount applies, else the product list price.
 */
@Service
public class CrmPricingService {

    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CrmDb db;
    private final AuditService audit;
    private final com.inventoryerp.sales.application.SalesPricingEngine engine;
    private final PriceListService priceLists;

    public CrmPricingService(CrmDb db, AuditService audit,
            com.inventoryerp.sales.application.SalesPricingEngine engine, PriceListService priceLists) {
        this.engine = engine;
        this.db = db;
        this.audit = audit;
        this.priceLists = priceLists;
    }

    /** One priced line. {@code discountPercent} is the discount off {@code unitPrice} granted by the rule. */
    public record Price(String productId, BigDecimal quantity, BigDecimal listPrice, BigDecimal unitPrice,
            BigDecimal discountPercent, String source, String ruleId, String ruleName, String taxId,
            boolean taxInclusive, String description) {

        BigDecimal net() {
            return unitPrice.multiply(BigDecimal.ONE.subtract(discountPercent.divide(HUNDRED, 10, RoundingMode.HALF_UP)));
        }

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", productId);
            m.put("quantity", JsNumbers.js(quantity));
            m.put("listPrice", JsNumbers.js(listPrice));
            m.put("unitPrice", JsNumbers.js(unitPrice));
            m.put("discountPercent", JsNumbers.js(discountPercent));
            m.put("netPrice", JsNumbers.js(net().setScale(6, RoundingMode.HALF_UP)));
            m.put("source", source);
            m.put("ruleId", ruleId);
            m.put("ruleName", ruleName);
            m.put("taxId", taxId);
            m.put("taxInclusive", taxInclusive);
            m.put("description", description);
            return m;
        }
    }

    // ------------------------------------------------------------------ rules

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rules(Map<String, String> p) {
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!blank(p.get("partnerId"))) {
            where.append(" AND r.\"partnerId\" = ?");
            params.add(p.get("partnerId"));
        }
        if (!blank(p.get("productId"))) {
            where.append(" AND r.\"productId\" = ?");
            params.add(p.get("productId"));
        }
        if ("true".equals(p.get("active"))) {
            where.append(" AND r.\"isActive\"");
        }
        return db.rows("SELECT r.*, pa.name AS \"partnerName\", pc.name AS \"partnerCategoryName\", pr.name AS"
                + " \"productName\", prc.name AS \"productCategoryName\" FROM \"CrmPriceRule\" r LEFT JOIN \"Partner\" pa"
                + " ON pa.id = r.\"partnerId\" LEFT JOIN \"PartnerCategory\" pc ON pc.id = r.\"partnerCategoryId\" LEFT"
                + " JOIN \"Product\" pr ON pr.id = r.\"productId\" LEFT JOIN \"ProductCategory\" prc ON prc.id ="
                + " r.\"productCategoryId\"" + where + " ORDER BY r.priority DESC, r.name", params);
    }

    @Transactional
    public Map<String, Object> saveRule(String id, Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("name")) {
            if (blank(str(in.get("name")))) {
                throw ApiException.badRequest("name should not be empty");
            }
            d.put("name", str(in.get("name")).trim());
        }
        ref(in, d, "partnerId", "Partner", "Partner not found");
        ref(in, d, "partnerCategoryId", "PartnerCategory", "Customer category not found");
        ref(in, d, "productId", "Product", "Product not found");
        ref(in, d, "productCategoryId", "ProductCategory", "Product category not found");
        if (d.get("partnerId") != null && d.get("partnerCategoryId") != null) {
            throw ApiException.badRequest("Choose a customer or a customer category, not both");
        }
        if (d.get("productId") != null && d.get("productCategoryId") != null) {
            throw ApiException.badRequest("Choose a product or a product category, not both");
        }
        if (id == null || in.containsKey("kind")) {
            String kind = blank(str(in.get("kind"))) ? "discount_percent" : str(in.get("kind"));
            if (!List.of("fixed_price", "discount_percent").contains(kind)) {
                throw ApiException.badRequest("kind must be one of: fixed_price, discount_percent");
            }
            d.put("kind", kind);
        }
        if (id == null || in.containsKey("value")) {
            BigDecimal v = dec(in.get("value"));
            if (v.signum() < 0) {
                throw ApiException.badRequest("value must not be less than 0");
            }
            String kind = str(d.getOrDefault("kind", id == null ? "discount_percent"
                    : db.require("CrmPriceRule", id, "Price rule not found").get("kind")));
            if ("discount_percent".equals(kind) && v.compareTo(HUNDRED) > 0) {
                throw ApiException.badRequest("A discount cannot exceed 100%");
            }
            d.put("value", v);
        }
        if (in.containsKey("minQuantity")) {
            BigDecimal q = dec(in.get("minQuantity"));
            if (q.signum() < 0) {
                throw ApiException.badRequest("minQuantity must not be less than 0");
            }
            d.put("minQuantity", q);
        }
        if (in.containsKey("validFrom")) {
            d.put("validFrom", CrmDb.date(str(in.get("validFrom"))));
        }
        if (in.containsKey("validTo")) {
            d.put("validTo", CrmDb.date(str(in.get("validTo"))));
        }
        if (d.get("validFrom") instanceof LocalDate f && d.get("validTo") instanceof LocalDate t && t.isBefore(f)) {
            throw ApiException.badRequest("validTo must be on or after validFrom");
        }
        if (in.containsKey("priority")) {
            d.put("priority", in.get("priority") instanceof Number n ? n.intValue() : 0);
        }
        if (in.containsKey("isActive")) {
            d.put("isActive", Boolean.TRUE.equals(in.get("isActive")));
        }
        if (in.containsKey("notes")) {
            d.put("notes", str(in.get("notes")));
        }
        if (in.containsKey("currencyCode")) {
            d.put("currencyCode", blank(str(in.get("currencyCode"))) ? null : str(in.get("currencyCode")));
        }
        Map<String, Object> row;
        if (id == null) {
            d.put("createdById", user());
            row = db.insert("CrmPriceRule", d);
        } else {
            db.require("CrmPriceRule", id, "Price rule not found");
            row = db.patch("CrmPriceRule", id, d, true);
        }
        audit.record(AuditInput.of("CrmPriceRule", str(row.get("id")), id == null ? "create" : "update", null, in));
        return row;
    }

    @Transactional
    public Map<String, Object> deleteRule(String id) {
        db.require("CrmPriceRule", id, "Price rule not found");
        db.update("DELETE FROM \"CrmPriceRule\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("CrmPriceRule", id, "delete"));
        return Obj.of("ok", true);
    }

    // ------------------------------------------------------------------ resolution

    /** {@code POST /crm/pricing/resolve}: price {@code lines [{productId, quantity}]} for {@code partnerId}. */
    @Transactional(readOnly = true)
    public Map<String, Object> resolve(Map<String, Object> in) {
        // ADR-S011 S2: one pricing policy — the sales engine (it applies these CRM rules as one of its tiers).
        return engine.resolve(in);
    }

    /** Price one product for one customer. */
    Price price(String partnerId, String productId, BigDecimal qty, LocalDate on) {
        Map<String, Object> product = db.row("SELECT id, name, \"salesPrice\", \"categoryId\", \"taxId\","
                + " \"taxInclusive\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                productId, org());
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        BigDecimal list = dec(product.get("salesPrice"));
        Map<String, Object> partner = blank(partnerId) ? null : db.row("SELECT p.id, p.\"categoryId\","
                + " p.\"discountPercentOverride\" AS disc, c.\"discountPercentOverride\" AS \"catDisc\" FROM \"Partner\" p"
                + " LEFT JOIN \"PartnerCategory\" c ON c.id = p.\"categoryId\" WHERE p.id = ? AND p.\"organizationId\" = ?",
                partnerId, org());
        String partnerCat = partner == null ? null : str(partner.get("categoryId"));
        Map<String, Object> rule = db.row("""
                SELECT id, name, kind, value FROM "CrmPriceRule"
                 WHERE "organizationId" = ? AND "isActive" AND "minQuantity" <= ?
                   AND ("validFrom" IS NULL OR "validFrom" <= ?) AND ("validTo" IS NULL OR "validTo" >= ?)
                   AND (("partnerId" IS NULL AND "partnerCategoryId" IS NULL) OR "partnerId" = ? OR "partnerCategoryId" = ?)
                   AND (("productId" IS NULL AND "productCategoryId" IS NULL) OR "productId" = ? OR "productCategoryId" = ?)
                 ORDER BY priority DESC,
                          CASE WHEN "partnerId" IS NOT NULL THEN 3 WHEN "partnerCategoryId" IS NOT NULL THEN 2 ELSE 1 END DESC,
                          CASE WHEN "productId" IS NOT NULL THEN 3 WHEN "productCategoryId" IS NOT NULL THEN 2 ELSE 1 END DESC,
                          "minQuantity" DESC
                 LIMIT 1""", org(), qty, on, on, partnerId == null ? "" : partnerId, partnerCat == null ? "" : partnerCat,
                productId, product.get("categoryId") == null ? "" : product.get("categoryId"));
        String taxId = str(product.get("taxId"));
        boolean incl = Boolean.TRUE.equals(product.get("taxInclusive"));
        String name = str(product.get("name"));
        if (rule != null) {
            BigDecimal v = dec(rule.get("value"));
            if ("fixed_price".equals(rule.get("kind"))) {
                return new Price(productId, qty, list, v, BigDecimal.ZERO, "rule", str(rule.get("id")),
                        str(rule.get("name")), taxId, incl, name);
            }
            return new Price(productId, qty, list, list, v, "rule", str(rule.get("id")), str(rule.get("name")), taxId,
                    incl, name);
        }
        ListPrice listed = priceLists.resolve(new PriceQuery(partnerId, productId, qty, null, null, null, on));
        if (listed != null) {
            return new Price(productId, qty, list, listed.unitPrice() != null ? listed.unitPrice() : list,
                    listed.discountPercent(), "price_list", listed.priceListId(), listed.priceListName(), taxId, incl,
                    name);
        }
        BigDecimal standing = partner == null ? BigDecimal.ZERO : dec(partner.get("disc"));
        if (standing.signum() == 0 && partner != null) {
            standing = dec(partner.get("catDisc"));
        }
        if (standing.signum() > 0) {
            return new Price(productId, qty, list, list, standing.min(HUNDRED), "customer", null, null, taxId, incl,
                    name);
        }
        return new Price(productId, qty, list, list, BigDecimal.ZERO, "list", null, null, taxId, incl, name);
    }

    private void ref(Map<String, Object> in, Map<String, Object> d, String key, String table, String message) {
        if (!in.containsKey(key)) {
            return;
        }
        String v = str(in.get(key));
        if (!blank(v) && !db.exists(table, v)) {
            throw ApiException.notFound(message);
        }
        d.put(key, blank(v) ? null : v);
    }
}
