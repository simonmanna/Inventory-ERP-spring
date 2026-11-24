package com.inventoryerp.plm.application;

import com.inventoryerp.core.application.ProductService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Product master data under engineering change (ADR-S086 §4, Odoo PLM "product" ECOs): a product ECO proposes new
 * values for some of the product's master data and applying it writes them through the core product service — the
 * same rules, event and audit as an edit on the product form.
 */
@Service
public class PlmProductDataService {

    /** The fields an ECO may set, in display order. */
    public static final List<String> FIELDS = ProductService.ENGINEERING_FIELDS;

    private static final Map<String, Integer> MAX_LENGTH = Map.of("name", 200, "description", 4000, "barcode", 64,
            "image", 1000);

    private final PlmDb db;
    private final ProductService products;

    public PlmProductDataService(PlmDb db, ProductService products) {
        this.db = db;
        this.products = products;
    }

    /**
     * Validate proposed master data: known fields only; a non-empty name; text within its length; weight and volume a
     * number {@code ≥ 0} (or null to clear); a barcode no other product carries. Blank text becomes null.
     */
    Map<String, Object> normalize(Map<String, Object> in, String productId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String f = e.getKey();
            Object v = e.getValue();
            if (!FIELDS.contains(f)) {
                throw ApiException.badRequest("productChanges." + f + " is not a product field an engineering change can"
                        + " set (" + String.join(", ", FIELDS) + ").");
            }
            switch (f) {
                case "weight", "volume" -> {
                    BigDecimal n = number(v, f);
                    if (n != null && n.signum() < 0) {
                        throw ApiException.badRequest("productChanges." + f + " must not be less than 0");
                    }
                    out.put(f, n);
                }
                default -> {
                    if (v != null && !(v instanceof String)) {
                        throw ApiException.badRequest("productChanges." + f + " must be a string");
                    }
                    String t = v == null ? null : PlmSupport.blank((String) v);
                    if (t != null) {
                        t = t.trim();
                    }
                    if ("name".equals(f) && t == null) {
                        throw ApiException.badRequest("productChanges.name should not be empty");
                    }
                    if (t != null && t.length() > MAX_LENGTH.get(f)) {
                        throw ApiException.badRequest("productChanges." + f + " must be shorter than or equal to "
                                + MAX_LENGTH.get(f) + " characters");
                    }
                    out.put(f, t);
                }
            }
        }
        if (out.get("barcode") != null) {
            requireFreeBarcode((String) out.get("barcode"), productId);
        }
        return out;
    }

    /** The proposed data next to the product's current values (and, once applied, what it was before). */
    List<Map<String, Object>> view(String productId, Map<String, Object> changes, Map<String, Object> applied) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (changes.isEmpty() || productId == null) {
            return out;
        }
        Map<String, Object> current = current(productId);
        for (String f : FIELDS) {
            if (!changes.containsKey(f)) {
                continue;
            }
            Map<String, Object> row = Obj.of("field", f, "current", current.get(f), "proposed", changes.get(f));
            Object was = applied.get(f);
            if (was instanceof Map<?, ?> m) {
                row.put("before", m.get("from"));
            }
            row.put("changes", !BomRebase.sameValue(current.get(f), changes.get(f)));
            out.add(row);
        }
        return out;
    }

    /** Write the proposed data to the product; returns {@code {field: {from, to}}} for the fields that changed. */
    Map<String, Object> apply(String productId, Map<String, Object> changes) {
        if (changes.get("barcode") != null) {
            requireFreeBarcode((String) changes.get("barcode"), productId);
        }
        Map<String, Object> before = current(productId);
        Map<String, Object> diff = new LinkedHashMap<>();
        Map<String, Object> write = new LinkedHashMap<>();
        for (String f : FIELDS) {
            if (changes.containsKey(f) && !BomRebase.sameValue(before.get(f), changes.get(f))) {
                diff.put(f, Obj.of("from", before.get(f), "to", changes.get(f)));
                write.put(f, changes.get(f));
            }
        }
        if (!write.isEmpty()) {
            products.applyEngineeringChange(productId, write);
        }
        return diff;
    }

    private Map<String, Object> current(String productId) {
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT name, description, barcode, weight, volume, image FROM"
                + " \"Product\" WHERE id = ? AND \"organizationId\" = ?").params(productId, PlmDb.org()));
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        return p;
    }

    private void requireFreeBarcode(String barcode, String productId) {
        // The unique index covers deleted products too.
        String other = db.jdbc().sql("SELECT code FROM \"Product\" WHERE \"organizationId\" = ? AND barcode = ? AND"
                + " id <> ? LIMIT 1").params(PlmDb.org(), barcode, productId)
                .query(String.class).optional().orElse(null);
        if (other != null) {
            throw ApiException.badRequest("Barcode " + barcode + " is already used by product " + other + ".");
        }
    }

    private static BigDecimal number(Object v, String field) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                // reported below
            }
        }
        throw ApiException.badRequest("productChanges." + field + " must be a number");
    }
}
