package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Promotions, price limits, the combined tier grid of both list systems, and price history (SG-6). */
@Service
public class SalesPricingToolsService {

    private static final Set<String> KINDS = Set.of("percent", "fixed_price", "amount_off");

    private final SalesDb db;
    private final AuditService audit;

    public SalesPricingToolsService(SalesDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> promotions() {
        return db.rows("SELECT s.*, p.name AS \"productName\", c.name AS \"categoryName\" FROM \"SalesPromotion\" s LEFT JOIN \"Product\" p ON p.id ="
                + " s.\"productId\" LEFT JOIN \"ProductCategory\" c ON c.id = s.\"productCategoryId\" WHERE s.\"organizationId\" = ? ORDER BY"
                + " s.\"endDate\" DESC, s.name", org());
    }

    @Transactional
    public Map<String, Object> savePromotion(String id, Map<String, Object> in) {
        String name = str(in.get("name"));
        if (blank(name)) {
            throw ApiException.badRequest("name should not be empty");
        }
        String kind = str(in.get("kind"));
        if (kind == null || !KINDS.contains(kind)) {
            throw ApiException.badRequest("kind must be one of: amount_off, fixed_price, percent");
        }
        BigDecimal value = dec(in.get("value"));
        if (value.signum() < 0 || "percent".equals(kind) && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("value is out of range");
        }
        LocalDate start = SalesDb.date(str(in.get("startDate")));
        LocalDate end = SalesDb.date(str(in.get("endDate")));
        if (start == null || end == null || end.isBefore(start)) {
            throw ApiException.badRequest("Give a start date and an end date on or after it");
        }
        Map<String, Object> d = Obj.of("name", name, "kind", kind, "value", value, "startDate", start, "endDate", end,
                "minQuantity", dec(in.get("minQuantity")), "priority", in.get("priority") instanceof Number n ? n.intValue() : 0,
                "isActive", !Boolean.FALSE.equals(in.get("isActive")));
        for (String k : List.of("productId", "productCategoryId", "partnerCategoryId", "branchId")) {
            d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
        }
        Map<String, Object> row;
        if (id == null) {
            d.put("createdById", user());
            row = db.insert("SalesPromotion", d);
        } else {
            if (!db.exists("SalesPromotion", id)) {
                throw ApiException.notFound("Promotion not found");
            }
            row = db.patch("SalesPromotion", id, d, true);
        }
        audit.record(AuditInput.of("SalesPromotion", str(row.get("id")), id == null ? "create" : "update", null, Obj.of("name", name,
                "kind", kind, "value", value)));
        return row;
    }

    @Transactional
    public Map<String, Object> deletePromotion(String id) {
        if (db.update("DELETE FROM \"SalesPromotion\" WHERE id = ? AND \"organizationId\" = ?", id, org()) == 0) {
            throw ApiException.notFound("Promotion not found");
        }
        audit.record(AuditInput.of("SalesPromotion", id, "delete"));
        return Obj.of("ok", true);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> limits() {
        return db.rows("SELECT l.*, p.name AS \"productName\", c.name AS \"categoryName\" FROM \"SalesPriceLimit\" l LEFT JOIN \"Product\" p ON p.id ="
                + " l.\"productId\" LEFT JOIN \"ProductCategory\" c ON c.id = l.\"productCategoryId\" WHERE l.\"organizationId\" = ? ORDER BY"
                + " p.name NULLS LAST, c.name", org());
    }

    /** Create or replace the limit of a product or category. */
    @Transactional
    public Map<String, Object> saveLimit(Map<String, Object> in) {
        String product = blank(str(in.get("productId"))) ? null : str(in.get("productId"));
        String category = product != null || blank(str(in.get("productCategoryId"))) ? null : str(in.get("productCategoryId"));
        if (product == null && category == null) {
            throw ApiException.badRequest("Give a product or a category");
        }
        BigDecimal min = in.get("minPrice") == null ? null : dec(in.get("minPrice"));
        BigDecimal max = in.get("maxPrice") == null ? null : dec(in.get("maxPrice"));
        if (min == null && max == null) {
            throw ApiException.badRequest("Give a minimum or a maximum price");
        }
        if (min != null && max != null && max.compareTo(min) < 0) {
            throw ApiException.badRequest("The maximum price cannot be below the minimum");
        }
        db.update("DELETE FROM \"SalesPriceLimit\" WHERE \"organizationId\" = ? AND \"productId\" IS NOT DISTINCT FROM ? AND"
                + " \"productCategoryId\" IS NOT DISTINCT FROM ?", org(), product, category);
        Map<String, Object> row = db.insert("SalesPriceLimit", Obj.of("productId", product, "productCategoryId", category, "minPrice",
                min, "maxPrice", max));
        audit.record(AuditInput.of("SalesPriceLimit", str(row.get("id")), "update", null, Obj.of("min", min, "max", max)));
        return row;
    }

    @Transactional
    public Map<String, Object> deleteLimit(String id) {
        if (db.update("DELETE FROM \"SalesPriceLimit\" WHERE id = ? AND \"organizationId\" = ?", id, org()) == 0) {
            throw ApiException.notFound("Price limit not found");
        }
        return Obj.of("ok", true);
    }

    /** Why the given net unit price breaks an absolute limit, or null. */
    String limitBreach(String productId, BigDecimal net, BigDecimal factor, String description) {
        Map<String, Object> l = db.row("SELECT l.\"minPrice\", l.\"maxPrice\" FROM \"SalesPriceLimit\" l JOIN \"Product\" p ON p.id = ? WHERE"
                + " l.\"organizationId\" = ? AND (l.\"productId\" = p.id OR (l.\"productId\" IS NULL AND l.\"productCategoryId\" = p.\"categoryId\"))"
                + " ORDER BY l.\"productId\" NULLS LAST LIMIT 1", productId, org());
        if (l == null) {
            return null;
        }
        BigDecimal f = factor == null || factor.signum() <= 0 ? BigDecimal.ONE : factor;
        if (l.get("minPrice") != null && net.compareTo(dec(l.get("minPrice")).multiply(f)) < 0) {
            return "Price of " + description + " is below the minimum of " + dec(l.get("minPrice")).multiply(f).stripTrailingZeros()
                    .toPlainString();
        }
        if (l.get("maxPrice") != null && net.compareTo(dec(l.get("maxPrice")).multiply(f)) > 0) {
            return "Price of " + description + " is above the maximum of " + dec(l.get("maxPrice")).multiply(f).stripTrailingZeros()
                    .toPlainString();
        }
        return null;
    }

    /** Every quantity break for a product across both list systems, for the tier grid. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> grid(String productId) {
        if (blank(productId)) {
            throw ApiException.badRequest("productId should not be empty");
        }
        List<Map<String, Object>> out = new ArrayList<>(db.rows("""
                SELECT 'pricelist' AS system, l.id AS "listId", l.name AS "listName", l."isActive", i.id AS "itemId", i."minQuantity", i.compute,
                       i.value, u.code AS "uomCode", i."validFrom", i."validTo"
                  FROM "PricelistItem" i JOIN "Pricelist" l ON l.id = i."pricelistId" LEFT JOIN "UnitOfMeasure" u ON u.id = i."uomId"
                 WHERE i."organizationId" = ? AND i."productId" = ? AND l."deletedAt" IS NULL ORDER BY l.name, i."minQuantity\"""", org(), productId));
        out.addAll(db.rows("""
                SELECT 'price_list' AS system, l.id AS "listId", l.name AS "listName", l."isActive", i.id AS "itemId", i."minQuantity",
                       CASE WHEN i."unitPrice" IS NOT NULL THEN 'fixed' ELSE 'discount' END AS compute,
                       COALESCE(i."unitPrice", i."discountPercent") AS value, u.code AS "uomCode", l."validFrom", l."validTo"
                  FROM "PriceListItem" i JOIN "PriceList" l ON l.id = i."priceListId" LEFT JOIN "UnitOfMeasure" u ON u.id = i."uomId"
                 WHERE i."organizationId" = ? AND i."productId" = ? AND l."deletedAt" IS NULL ORDER BY l.name, i."minQuantity\"""", org(), productId));
        return out;
    }

    /** Both list systems side by side, with what depends on each. */
    @Transactional(readOnly = true)
    public Map<String, Object> lists() {
        List<Map<String, Object>> lists = new ArrayList<>(db.rows("SELECT 'pricelist' AS system, l.id, l.code, l.name, l.\"isActive\","
                + " (SELECT count(*) FROM \"PricelistItem\" i WHERE i.\"pricelistId\" = l.id) AS items FROM \"Pricelist\" l WHERE"
                + " l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL ORDER BY l.name", org()));
        lists.addAll(db.rows("SELECT 'price_list' AS system, l.id, l.code, l.name, l.\"isActive\", (SELECT count(*) FROM \"PriceListItem\" i"
                + " WHERE i.\"priceListId\" = l.id) AS items FROM \"PriceList\" l WHERE l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL"
                + " ORDER BY l.name", org()));
        return Obj.of("lists", lists);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(Map<String, String> q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = " WHERE h.\"organizationId\" = ?";
        if (!blank(q.get("productId"))) {
            where += " AND h.\"productId\" = ?";
            params.add(q.get("productId"));
        }
        return db.rows("SELECT h.id, h.system, h.action, h.\"listId\", h.\"productId\", p.name AS \"productName\", h.\"oldValue\", h.\"newValue\","
                + " h.\"changedAt\" FROM \"SalesPriceHistory\" h LEFT JOIN \"Product\" p ON p.id = h.\"productId\"" + where
                + " ORDER BY h.\"changedAt\" DESC, h.id DESC LIMIT 300", params);
    }
}
