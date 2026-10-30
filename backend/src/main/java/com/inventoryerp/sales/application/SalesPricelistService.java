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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pricelists, their rules, customer / category assignment and margin floors (ADR-S011 phase S2). */
@Service
public class SalesPricelistService {

    static final Set<String> TYPES = Set.of("retail", "wholesale", "contractor", "distributor", "contract", "other");
    static final Set<String> COMPUTES = Set.of("fixed", "discount", "surcharge", "cost_plus");

    private final SalesDb db;
    private final AuditService audit;

    public SalesPricelistService(SalesDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ pricelists

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return db.rows("SELECT p.*, (SELECT count(*) FROM \"PricelistItem\" i WHERE i.\"pricelistId\" = p.id) AS"
                + " \"itemCount\", (SELECT count(*) FROM \"Partner\" pa WHERE pa.\"pricelistId\" = p.id AND"
                + " pa.\"organizationId\" = p.\"organizationId\") AS \"customerCount\" FROM \"Pricelist\" p WHERE"
                + " p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL ORDER BY p.\"isDefault\" DESC, p.code", org());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> p = require(id);
        p.put("items", db.rows("SELECT i.*, pr.name AS \"productName\", pa.name AS \"partnerName\" FROM \"PricelistItem\" i"
                + " LEFT JOIN \"Product\" pr ON pr.id = i.\"productId\" LEFT JOIN \"Partner\" pa ON pa.id = i.\"partnerId\""
                + " WHERE i.\"pricelistId\" = ? AND i.\"organizationId\" = ? ORDER BY i.priority DESC, i.\"createdAt\"", id,
                org()));
        p.put("customers", db.rows("SELECT id, name FROM \"Partner\" WHERE \"pricelistId\" = ? AND \"organizationId\" = ?"
                + " ORDER BY name LIMIT 500", id, org()));
        p.put("categories", db.rows("SELECT id, name FROM \"PartnerCategory\" WHERE \"pricelistId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY name", id, org()));
        return p;
    }

    @Transactional
    public Map<String, Object> save(String id, Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("code")) {
            String code = str(in.get("code"));
            if (blank(code)) {
                throw ApiException.badRequest("code should not be empty");
            }
            if (db.count("SELECT count(*) FROM \"Pricelist\" WHERE \"organizationId\" = ? AND lower(code) = lower(?) AND"
                    + " \"deletedAt\" IS NULL AND id <> ?", List.of(org(), code.trim(), id == null ? "" : id)) > 0) {
                throw ApiException.conflict("A pricelist with this code already exists");
            }
            d.put("code", code.trim());
        }
        if (id == null || in.containsKey("name")) {
            if (blank(str(in.get("name")))) {
                throw ApiException.badRequest("name should not be empty");
            }
            d.put("name", str(in.get("name")).trim());
        }
        if (in.containsKey("type")) {
            String t = str(in.get("type"));
            if (!TYPES.contains(t)) {
                throw ApiException.badRequest("type must be one of: " + String.join(", ", TYPES.stream().sorted().toList()));
            }
            d.put("type", t);
        }
        for (String k : List.of("currencyCode", "branchId", "notes")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        if (d.get("branchId") != null && !db.exists("Branch", str(d.get("branchId")))) {
            throw ApiException.notFound("Branch not found");
        }
        if (in.containsKey("basePricelistId")) {
            String b = blank(str(in.get("basePricelistId"))) ? null : str(in.get("basePricelistId"));
            if (b != null && (b.equals(id) || !db.exists("Pricelist", b))) {
                throw ApiException.badRequest("Invalid base pricelist");
            }
            d.put("basePricelistId", b);
        }
        dates(in, d);
        for (String k : List.of("isDefault", "isActive")) {
            if (in.containsKey(k)) {
                d.put(k, Boolean.TRUE.equals(in.get(k)));
            }
        }
        if (Boolean.TRUE.equals(d.get("isDefault"))) {
            Object branch = d.containsKey("branchId") ? d.get("branchId") : id == null ? null : require(id).get("branchId");
            db.update("UPDATE \"Pricelist\" SET \"isDefault\" = false WHERE \"organizationId\" = ? AND COALESCE(\"branchId\","
                    + " '') = ? AND id <> ?", org(), branch == null ? "" : str(branch), id == null ? "" : id);
        }
        Map<String, Object> row;
        if (id == null) {
            d.put("createdById", user());
            row = db.insert("Pricelist", d);
        } else {
            require(id);
            row = db.patch("Pricelist", id, d, true);
        }
        audit.record(AuditInput.of("Pricelist", str(row.get("id")), id == null ? "create" : "update", null, in));
        return get(str(row.get("id")));
    }

    /** Soft-delete; customers and categories using it fall back to the next pricing tier. */
    @Transactional
    public Map<String, Object> delete(String id) {
        require(id);
        db.update("UPDATE \"Pricelist\" SET \"deletedAt\" = " + NOW + ", \"isDefault\" = false WHERE id = ? AND"
                + " \"organizationId\" = ?", id, org());
        db.update("UPDATE \"Partner\" SET \"pricelistId\" = NULL WHERE \"pricelistId\" = ? AND \"organizationId\" = ?", id,
                org());
        db.update("UPDATE \"PartnerCategory\" SET \"pricelistId\" = NULL WHERE \"pricelistId\" = ? AND"
                + " \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("Pricelist", id, "delete"));
        return Obj.of("ok", true);
    }

    /** Assign customers / customer categories to this pricelist ({@code unassign: true} removes them). */
    @Transactional
    public Map<String, Object> assign(String id, Map<String, Object> in) {
        require(id);
        boolean un = Boolean.TRUE.equals(in.get("unassign"));
        for (String p : SalesDb.strings(in.get("partnerIds"))) {
            if (!db.exists("Partner", p)) {
                throw ApiException.notFound("Partner not found");
            }
            if (un) {
                db.update("UPDATE \"Partner\" SET \"pricelistId\" = NULL, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                        + " \"organizationId\" = ? AND \"pricelistId\" = ?", p, org(), id);
            } else {
                db.update("UPDATE \"Partner\" SET \"pricelistId\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                        + " \"organizationId\" = ?", id, p, org());
            }
        }
        for (String c : SalesDb.strings(in.get("partnerCategoryIds"))) {
            if (!db.exists("PartnerCategory", c)) {
                throw ApiException.notFound("Customer category not found");
            }
            db.update("UPDATE \"PartnerCategory\" SET \"pricelistId\" = ? WHERE id = ? AND \"organizationId\" = ?",
                    un ? null : id, c, org());
        }
        audit.record(AuditInput.of("Pricelist", id, "update", null, in));
        return get(id);
    }

    // ------------------------------------------------------------------ rules

    @Transactional
    public Map<String, Object> saveItem(String pricelistId, String id, Map<String, Object> in) {
        require(pricelistId);
        Map<String, Object> cur = id == null ? Map.of() : requireItem(pricelistId, id);
        Map<String, Object> d = new LinkedHashMap<>();
        String scope = str(in.getOrDefault("scope", cur.getOrDefault("scope", "product")));
        if (!Set.of("product", "category", "all").contains(scope)) {
            throw ApiException.badRequest("scope must be one of: all, category, product");
        }
        d.put("scope", scope);
        ref(in, d, "productId", "Product", "Product not found");
        ref(in, d, "productCategoryId", "ProductCategory", "Product category not found");
        ref(in, d, "partnerId", "Partner", "Partner not found");
        ref(in, d, "partnerCategoryId", "PartnerCategory", "Customer category not found");
        ref(in, d, "uomId", "UnitOfMeasure", "Unit not found");
        if ("product".equals(scope) && d.getOrDefault("productId", cur.get("productId")) == null) {
            throw ApiException.badRequest("Choose the product of this rule");
        }
        if ("category".equals(scope) && d.getOrDefault("productCategoryId", cur.get("productCategoryId")) == null) {
            throw ApiException.badRequest("Choose the product category of this rule");
        }
        if (!"product".equals(scope)) {
            d.put("productId", null);
        }
        if (!"category".equals(scope)) {
            d.put("productCategoryId", null);
        }
        if (d.getOrDefault("partnerId", cur.get("partnerId")) != null
                && d.getOrDefault("partnerCategoryId", cur.get("partnerCategoryId")) != null) {
            throw ApiException.badRequest("Choose a customer or a customer category, not both");
        }
        String compute = str(in.getOrDefault("compute", cur.getOrDefault("compute", "fixed")));
        if (!COMPUTES.contains(compute)) {
            throw ApiException.badRequest("compute must be one of: cost_plus, discount, fixed, surcharge");
        }
        d.put("compute", compute);
        if (id == null || in.containsKey("value")) {
            BigDecimal v = dec(in.get("value"));
            if (v.signum() < 0 || ("discount".equals(compute) && v.compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw ApiException.badRequest("value is out of range");
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
        for (String k : List.of("siteZone", "branchId", "notes")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)).trim());
            }
        }
        if (in.containsKey("priority")) {
            d.put("priority", in.get("priority") instanceof Number n ? n.intValue() : 0);
        }
        dates(in, d);
        Map<String, Object> row;
        if (id == null) {
            d.put("pricelistId", pricelistId);
            row = db.insert("PricelistItem", d);
        } else {
            row = db.patch("PricelistItem", id, d, true);
        }
        audit.record(AuditInput.of("Pricelist", pricelistId, "update", null, Obj.of("item", row.get("id"), "change", in)));
        return row;
    }

    @Transactional
    public Map<String, Object> deleteItem(String pricelistId, String id) {
        requireItem(pricelistId, id);
        db.update("DELETE FROM \"PricelistItem\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("Pricelist", pricelistId, "update", null, Obj.of("deletedItem", id)));
        return Obj.of("ok", true);
    }

    // ------------------------------------------------------------------ margin floors

    @Transactional(readOnly = true)
    public List<Map<String, Object>> floors() {
        return db.rows("SELECT f.*, pr.name AS \"productName\" FROM \"MarginFloor\" f LEFT JOIN \"Product\" pr ON pr.id ="
                + " f.\"productId\" WHERE f.\"organizationId\" = ? ORDER BY f.\"createdAt\"", org());
    }

    @Transactional
    public Map<String, Object> saveFloor(String id, Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        ref(in, d, "productId", "Product", "Product not found");
        ref(in, d, "productCategoryId", "ProductCategory", "Product category not found");
        ref(in, d, "partnerCategoryId", "PartnerCategory", "Customer category not found");
        ref(in, d, "branchId", "Branch", "Branch not found");
        if (d.get("productId") != null && d.get("productCategoryId") != null) {
            throw ApiException.badRequest("Choose a product or a product category, not both");
        }
        if (id == null || in.containsKey("minMarginPercent")) {
            BigDecimal m = dec(in.get("minMarginPercent"));
            if (m.signum() < 0 || m.compareTo(BigDecimal.valueOf(100)) >= 0) {
                throw ApiException.badRequest("minMarginPercent must be between 0 and 100");
            }
            d.put("minMarginPercent", m);
        }
        if (in.containsKey("isActive")) {
            d.put("isActive", Boolean.TRUE.equals(in.get("isActive")));
        }
        dates(in, d);
        Map<String, Object> row;
        if (id == null) {
            row = db.insert("MarginFloor", d);
        } else {
            db.require("MarginFloor", id, "Margin floor not found");
            row = db.patch("MarginFloor", id, d, true);
        }
        audit.record(AuditInput.of("MarginFloor", str(row.get("id")), id == null ? "create" : "update", null, in));
        return row;
    }

    @Transactional
    public Map<String, Object> deleteFloor(String id) {
        db.require("MarginFloor", id, "Margin floor not found");
        db.update("DELETE FROM \"MarginFloor\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("MarginFloor", id, "delete"));
        return Obj.of("ok", true);
    }

    // ------------------------------------------------------------------ helpers

    private static void dates(Map<String, Object> in, Map<String, Object> d) {
        if (in.containsKey("validFrom")) {
            d.put("validFrom", SalesDb.date(str(in.get("validFrom"))));
        }
        if (in.containsKey("validTo")) {
            d.put("validTo", SalesDb.date(str(in.get("validTo"))));
        }
        if (d.get("validFrom") instanceof LocalDate f && d.get("validTo") instanceof LocalDate t && t.isBefore(f)) {
            throw ApiException.badRequest("validTo must be on or after validFrom");
        }
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

    private Map<String, Object> require(String id) {
        Map<String, Object> p = db.row("SELECT * FROM \"Pricelist\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL", id, org());
        if (p == null) {
            throw ApiException.notFound("Pricelist not found");
        }
        return p;
    }

    private Map<String, Object> requireItem(String pricelistId, String id) {
        Map<String, Object> i = db.row("SELECT * FROM \"PricelistItem\" WHERE id = ? AND \"pricelistId\" = ? AND"
                + " \"organizationId\" = ?", id, pricelistId, org());
        if (i == null) {
            throw ApiException.notFound("Price rule not found");
        }
        return i;
    }
}
