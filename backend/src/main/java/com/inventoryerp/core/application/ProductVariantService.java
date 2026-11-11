package com.inventoryerp.core.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product attributes &amp; variants (ADR-S058). Variant attributes and their values are organisation masters; a
 * product's attribute lines pick the values it is offered in (each with a price extra), and the variants are the
 * generated combinations. Variants are archived, never deleted, because stock and documents reference them.
 *
 * <p>Other modules call {@link #resolve} to validate a line's variant and read its effective price and cost.
 */
@Service
public class ProductVariantService {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";
    static final int MAX_COMBINATIONS = 1000;
    private static final List<String> DISPLAY_TYPES = List.of("radio", "pills", "select", "color");
    /** Effective sales price of variant {@code v} of product {@code p}. */
    public static final String PRICE_SQL = "COALESCE(v.\"salesPrice\", COALESCE(p.\"salesPrice\", 0) + v.\"priceExtra\")";
    /** Effective cost price of variant {@code v} of product {@code p}. */
    public static final String COST_SQL = "COALESCE(v.\"costPrice\", p.\"costPrice\")";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProductVariantService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** A validated line variant: id, display name, effective sales price and cost (base unit). */
    public record VariantRef(String id, String productId, String name, BigDecimal salesPrice, BigDecimal costPrice) {}

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static BigDecimal dec(Object v) {
        if (blank(v)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid number: " + v);
        }
    }

    private static BigDecimal decOrNull(Object v) {
        return blank(v) ? null : dec(v);
    }

    private static int intOf(Object v, int fallback) {
        return blank(v) ? fallback : dec(v).intValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object v) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw ApiException.badRequest("Expected an array");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?>)) {
                throw ApiException.badRequest("Expected an array of objects");
            }
            out.add((Map<String, Object>) o);
        }
        return out;
    }

    private static List<String> strings(Object v) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> list)) {
            throw ApiException.badRequest("Expected an array");
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!blank(o) && !out.contains(o.toString())) {
                out.add(o.toString());
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────── attributes

    /** Attributes with their values (active only when {@code activeOnly=true}). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAttributes(Map<String, String> q) {
        boolean activeOnly = "true".equals(q.get("activeOnly"));
        List<Map<String, Object>> attrs = SqlRows.list(jdbc.sql("SELECT a.*, (SELECT count(DISTINCT l.\"productId\") FROM"
                + " \"ProductAttributeLine\" l WHERE l.\"attributeId\" = a.id) AS \"productCount\" FROM \"VariantAttribute\""
                + " a WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL" + (activeOnly ? " AND a.\"isActive\"" : "")
                + " ORDER BY a.sequence, a.name").param(org()));
        Map<String, List<Map<String, Object>>> values = new HashMap<>();
        for (Map<String, Object> v : SqlRows.list(jdbc.sql("SELECT * FROM \"VariantAttributeValue\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL" + (activeOnly ? " AND \"isActive\"" : "")
                + " ORDER BY sequence, name").param(org()))) {
            values.computeIfAbsent(str(v.get("attributeId")), k -> new ArrayList<>()).add(v);
        }
        for (Map<String, Object> a : attrs) {
            a.put("productCount", ((Number) a.get("productCount")).intValue());
            a.put("values", values.getOrDefault(str(a.get("id")), List.of()));
        }
        return attrs;
    }

    @Transactional
    public Map<String, Object> createAttribute(Map<String, Object> dto) {
        if (blank(dto.get("name"))) {
            throw ApiException.badRequest("name is required");
        }
        String name = str(dto.get("name")).trim();
        assertAttributeNameFree(name, null);
        String type = displayType(dto.get("displayType"), "pills");
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"VariantAttribute\" (id, \"organizationId\", name, \"displayType\", sequence, \"createdBy\")"
                + " VALUES (?, ?, ?, ?, ?, ?)").params(id, org(), name, type, intOf(dto.get("sequence"), 10),
                        TenantContext.userId()).update();
        saveValues(id, maps(dto.get("values")), false);
        Map<String, Object> out = attribute(id);
        audit.recordInTx(AuditInput.of("VariantAttribute", id, "create", null, out));
        return out;
    }

    @Transactional
    public Map<String, Object> updateAttribute(String id, Map<String, Object> dto) {
        Map<String, Object> before = attribute(id);
        String name = dto.containsKey("name") ? str(dto.get("name")) : str(before.get("name"));
        if (blank(name)) {
            throw ApiException.badRequest("name is required");
        }
        assertAttributeNameFree(name.trim(), id);
        jdbc.sql("UPDATE \"VariantAttribute\" SET name = ?, \"displayType\" = ?, sequence = ?, \"isActive\" = ?,"
                + " \"updatedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(name.trim(), displayType(dto.get("displayType"), str(before.get("displayType"))),
                        intOf(dto.get("sequence"), (Integer) before.get("sequence")),
                        dto.containsKey("isActive") ? Boolean.TRUE.equals(dto.get("isActive")) : before.get("isActive"),
                        TenantContext.userId(), id, org()).update();
        if (dto.containsKey("values")) {
            saveValues(id, maps(dto.get("values")), true);
        }
        // Value names feed variant names: refresh every product that uses the attribute.
        for (String productId : jdbc.sql("SELECT DISTINCT \"productId\" FROM \"ProductAttributeLine\" WHERE"
                + " \"organizationId\" = ? AND \"attributeId\" = ?").params(org(), id).query(String.class).list()) {
            regenerate(productId);
        }
        Map<String, Object> out = attribute(id);
        audit.recordInTx(AuditInput.of("VariantAttribute", id, "update", before, out));
        return out;
    }

    @Transactional
    public Map<String, Object> deleteAttribute(String id) {
        attribute(id);
        long used = jdbc.sql("SELECT count(DISTINCT \"productId\") FROM \"ProductAttributeLine\" WHERE"
                + " \"organizationId\" = ? AND \"attributeId\" = ?").params(org(), id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("This attribute is used by " + used + " product(s)");
        }
        jdbc.sql("UPDATE \"VariantAttribute\" SET \"deletedAt\" = " + NOW + ", \"isActive\" = false, \"updatedBy\" = ?"
                + " WHERE id = ? AND \"organizationId\" = ?").params(TenantContext.userId(), id, org()).update();
        jdbc.sql("UPDATE \"VariantAttributeValue\" SET \"deletedAt\" = " + NOW + ", \"isActive\" = false WHERE"
                + " \"attributeId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()).update();
        audit.recordInTx(AuditInput.of("VariantAttribute", id, "delete", null, null));
        return Obj.of("ok", true);
    }

    private Map<String, Object> attribute(String id) {
        Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT * FROM \"VariantAttribute\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (a == null) {
            throw ApiException.notFound("Attribute not found");
        }
        a.put("values", SqlRows.list(jdbc.sql("SELECT * FROM \"VariantAttributeValue\" WHERE \"attributeId\" = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY sequence, name").params(id, org())));
        return a;
    }

    private void assertAttributeNameFree(String name, String exceptId) {
        long n = jdbc.sql("SELECT count(*) FROM \"VariantAttribute\" WHERE \"organizationId\" = ? AND lower(name) ="
                + " lower(?) AND \"deletedAt\" IS NULL AND id IS DISTINCT FROM ?").params(org(), name, exceptId)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("Attribute \"" + name + "\" already exists");
        }
    }

    private static String displayType(Object v, String fallback) {
        String t = blank(v) ? fallback : str(v);
        if (!DISPLAY_TYPES.contains(t)) {
            throw ApiException.badRequest("displayType must be one of: " + String.join(", ", DISPLAY_TYPES));
        }
        return t;
    }

    /** Upsert values by id (or create); with {@code replace}, values missing from the list are removed. */
    private void saveValues(String attributeId, List<Map<String, Object>> values, boolean replace) {
        Set<String> names = new HashSet<>();
        Set<String> kept = new HashSet<>();
        int seq = 0;
        for (Map<String, Object> v : values) {
            if (blank(v.get("name"))) {
                throw ApiException.badRequest("Each value needs a name");
            }
            String name = str(v.get("name")).trim();
            if (!names.add(name.toLowerCase())) {
                throw ApiException.badRequest("Duplicate value \"" + name + "\"");
            }
            seq += 10;
            String color = blank(v.get("htmlColor")) ? null : str(v.get("htmlColor")).trim();
            if (color != null && !color.matches("#[0-9a-fA-F]{3,8}")) {
                throw ApiException.badRequest("htmlColor must be a hex colour like #1F2937");
            }
            boolean active = !Boolean.FALSE.equals(v.get("isActive"));
            String id = str(v.get("id"));
            if (id != null) {
                int n = jdbc.sql("UPDATE \"VariantAttributeValue\" SET name = ?, \"htmlColor\" = ?, sequence = ?,"
                        + " \"isActive\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"attributeId\" = ? AND"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(name, color, intOf(v.get("sequence"),
                                seq), active, id, attributeId, org()).update();
                if (n == 0) {
                    throw ApiException.notFound("Attribute value not found");
                }
            } else {
                id = UUID.randomUUID().toString();
                jdbc.sql("INSERT INTO \"VariantAttributeValue\" (id, \"organizationId\", \"attributeId\", name,"
                        + " \"htmlColor\", sequence, \"isActive\") VALUES (?, ?, ?, ?, ?, ?, ?)").params(id, org(),
                                attributeId, name, color, intOf(v.get("sequence"), seq), active).update();
            }
            kept.add(id);
        }
        if (!replace) {
            return;
        }
        for (Map<String, Object> old : SqlRows.list(jdbc.sql("SELECT id, name FROM \"VariantAttributeValue\" WHERE"
                + " \"attributeId\" = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(attributeId, org()))) {
            String id = str(old.get("id"));
            if (kept.contains(id)) {
                continue;
            }
            long used = jdbc.sql("SELECT count(DISTINCT l.\"productId\") FROM \"ProductAttributeLineValue\" lv JOIN"
                    + " \"ProductAttributeLine\" l ON l.id = lv.\"lineId\" WHERE lv.\"valueId\" = ? AND"
                    + " lv.\"organizationId\" = ?").params(id, org()).query(Long.class).single();
            if (used > 0) {
                throw ApiException.badRequest("Value \"" + old.get("name") + "\" is used by " + used + " product(s)");
            }
            jdbc.sql("UPDATE \"VariantAttributeValue\" SET \"deletedAt\" = " + NOW + ", \"isActive\" = false WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(id, org()).update();
        }
    }

    // ─────────────────────────────────────────────────────────── product configuration

    /** Attribute lines (with values and price extras) and variants of a product. */
    @Transactional(readOnly = true)
    public Map<String, Object> config(String productId) {
        Map<String, Object> product = product(productId);
        return Obj.of("productId", productId, "hasVariants", Boolean.TRUE.equals(product.get("hasVariants")),
                "salesPrice", product.get("salesPrice"), "costPrice", product.get("costPrice"), "lines",
                lines(productId), "variants", variants(productId, true));
    }

    private List<Map<String, Object>> lines(String productId) {
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.id, l.\"attributeId\", l.sequence, a.name AS"
                + " \"attributeName\", a.\"displayType\" FROM \"ProductAttributeLine\" l JOIN \"VariantAttribute\" a ON"
                + " a.id = l.\"attributeId\" WHERE l.\"productId\" = ? AND l.\"organizationId\" = ? ORDER BY l.sequence,"
                + " a.name").params(productId, org()));
        for (Map<String, Object> l : lines) {
            l.put("values", SqlRows.list(jdbc.sql("SELECT lv.\"valueId\", v.name, v.\"htmlColor\", lv.\"priceExtra\""
                    + " FROM \"ProductAttributeLineValue\" lv JOIN \"VariantAttributeValue\" v ON v.id = lv.\"valueId\""
                    + " WHERE lv.\"lineId\" = ? AND lv.\"organizationId\" = ? ORDER BY v.sequence, v.name")
                    .params(l.get("id"), org())));
        }
        return lines;
    }

    /**
     * Replace a product's attribute lines ({@code {lines:[{attributeId, valueIds[]}]}}) and regenerate its variants.
     * Price extras of values that stay are kept.
     */
    @Transactional
    public Map<String, Object> saveLines(String productId, Map<String, Object> dto) {
        product(productId);
        List<Map<String, Object>> input = maps(dto.get("lines"));
        Map<String, Object> before = Obj.of("lines", lines(productId));
        Set<String> attrs = new HashSet<>();
        long combos = 1;
        for (Map<String, Object> l : input) {
            String attributeId = str(l.get("attributeId"));
            if (blank(attributeId)) {
                throw ApiException.badRequest("attributeId is required");
            }
            if (!attrs.add(attributeId)) {
                throw ApiException.badRequest("An attribute can only be added once");
            }
            Map<String, Object> a = SqlRows.one(jdbc.sql("SELECT name FROM \"VariantAttribute\" WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(attributeId, org()));
            if (a == null) {
                throw ApiException.notFound("Attribute not found");
            }
            List<String> valueIds = strings(l.get("valueIds"));
            if (valueIds.isEmpty()) {
                throw ApiException.badRequest("Select at least one value for " + a.get("name"));
            }
            long ok = jdbc.sql("SELECT count(*) FROM \"VariantAttributeValue\" WHERE \"attributeId\" = ? AND"
                    + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND id = ANY(?)").params(attributeId, org(),
                            valueIds.toArray(String[]::new)).query(Long.class).single();
            if (ok != valueIds.size()) {
                throw ApiException.badRequest("Value does not belong to attribute " + a.get("name"));
            }
            combos *= valueIds.size();
            if (combos > MAX_COMBINATIONS) {
                throw ApiException.badRequest("Too many variants (more than " + MAX_COMBINATIONS + " combinations)");
            }
        }
        Map<String, String> existing = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, \"attributeId\" FROM \"ProductAttributeLine\""
                + " WHERE \"productId\" = ? AND \"organizationId\" = ?").params(productId, org()))) {
            existing.put(str(r.get("attributeId")), str(r.get("id")));
        }
        int seq = 0;
        for (Map<String, Object> l : input) {
            String attributeId = str(l.get("attributeId"));
            String lineId = existing.remove(attributeId);
            seq += 10;
            if (lineId == null) {
                lineId = UUID.randomUUID().toString();
                jdbc.sql("INSERT INTO \"ProductAttributeLine\" (id, \"organizationId\", \"productId\", \"attributeId\","
                        + " sequence) VALUES (?, ?, ?, ?, ?)").params(lineId, org(), productId, attributeId, seq).update();
            } else {
                jdbc.sql("UPDATE \"ProductAttributeLine\" SET sequence = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                        .params(seq, lineId).update();
            }
            List<String> valueIds = strings(l.get("valueIds"));
            jdbc.sql("DELETE FROM \"ProductAttributeLineValue\" WHERE \"lineId\" = ? AND NOT (\"valueId\" = ANY(?))")
                    .params(lineId, valueIds.toArray(String[]::new)).update();
            for (String valueId : valueIds) {
                jdbc.sql("INSERT INTO \"ProductAttributeLineValue\" (id, \"organizationId\", \"lineId\", \"valueId\")"
                        + " VALUES (?, ?, ?, ?) ON CONFLICT (\"lineId\", \"valueId\") DO NOTHING").params(
                                UUID.randomUUID().toString(), org(), lineId, valueId).update();
            }
        }
        for (String lineId : existing.values()) {
            jdbc.sql("DELETE FROM \"ProductAttributeLine\" WHERE id = ? AND \"organizationId\" = ?").params(lineId, org())
                    .update();
        }
        regenerate(productId);
        Map<String, Object> out = config(productId);
        audit.recordInTx(AuditInput.of("Product", productId, "update", before, Obj.of("lines", out.get("lines"))));
        return out;
    }

    /** Configure: price extras of a line's values ({@code {values:[{valueId, priceExtra}]}}). */
    @Transactional
    public Map<String, Object> saveLineValues(String productId, String lineId, Map<String, Object> dto) {
        product(productId);
        Long line = jdbc.sql("SELECT count(*) FROM \"ProductAttributeLine\" WHERE id = ? AND \"productId\" = ? AND"
                + " \"organizationId\" = ?").params(lineId, productId, org()).query(Long.class).single();
        if (line == 0) {
            throw ApiException.notFound("Attribute line not found");
        }
        for (Map<String, Object> v : maps(dto.get("values"))) {
            BigDecimal extra = dec(v.get("priceExtra")).setScale(6, RoundingMode.HALF_UP);
            int n = jdbc.sql("UPDATE \"ProductAttributeLineValue\" SET \"priceExtra\" = ? WHERE \"lineId\" = ? AND"
                    + " \"valueId\" = ? AND \"organizationId\" = ?").params(extra, lineId, str(v.get("valueId")), org())
                    .update();
            if (n == 0) {
                throw ApiException.notFound("Attribute value not found");
            }
        }
        regenerate(productId);
        Map<String, Object> out = config(productId);
        audit.recordInTx(AuditInput.of("Product", productId, "update", null, Obj.of("priceExtras", lineId)));
        return out;
    }

    /**
     * Create missing combinations, refresh names / price extras of existing ones, archive dropped ones and set
     * {@code Product.hasVariants}.
     */
    void regenerate(String productId) {
        List<Map<String, Object>> lines = lines(productId);
        List<List<Map<String, Object>>> combos = new ArrayList<>();
        if (!lines.isEmpty()) {
            combos.add(List.of());
            for (Map<String, Object> l : lines) {
                List<List<Map<String, Object>>> next = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> values = (List<Map<String, Object>>) l.get("values");
                for (List<Map<String, Object>> c : combos) {
                    for (Map<String, Object> v : values) {
                        List<Map<String, Object>> n = new ArrayList<>(c);
                        Map<String, Object> withAttr = new LinkedHashMap<>(v);
                        withAttr.put("attributeName", l.get("attributeName"));
                        n.add(withAttr);
                        next.add(n);
                    }
                }
                combos = next;
            }
        }
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for (Map<String, Object> v : SqlRows.list(jdbc.sql("SELECT id, name, \"combinationKey\" FROM \"ProductVariant\""
                + " WHERE \"productId\" = ? AND \"organizationId\" = ? AND \"combinationKey\" <> ''").params(productId,
                        org()))) {
            byKey.put(str(v.get("combinationKey")), v);
        }
        Set<String> live = new HashSet<>();
        for (List<Map<String, Object>> c : combos) {
            List<String> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            Map<String, Object> attrs = new LinkedHashMap<>();
            BigDecimal extra = BigDecimal.ZERO;
            for (Map<String, Object> v : c) {
                ids.add(str(v.get("valueId")));
                names.add(str(v.get("name")));
                attrs.put(str(v.get("attributeName")), v.get("name"));
                extra = extra.add(dec(v.get("priceExtra")));
            }
            ids.sort(null);
            String key = String.join(",", ids);
            String name = String.join(", ", names);
            live.add(key);
            Map<String, Object> current = byKey.get(key);
            String currentId = current == null ? null : str(current.get("id"));
            freeName(productId, name, currentId);
            String json = toJson(attrs);
            if (current == null) {
                jdbc.sql("INSERT INTO \"ProductVariant\" (id, \"organizationId\", \"productId\", name, attributes,"
                        + " \"combinationKey\", \"priceExtra\", \"isActive\", \"createdBy\", \"updatedAt\") VALUES (?, ?, ?,"
                        + " ?, ?::jsonb, ?, ?, true, ?, " + NOW + ")").params(UUID.randomUUID().toString(), org(),
                                productId, name, json, key, extra, TenantContext.userId()).update();
            } else {
                // A combination archived because it was dropped comes back; one archived by hand stays archived.
                jdbc.sql("UPDATE \"ProductVariant\" SET name = ?, attributes = ?::jsonb, \"priceExtra\" = ?,"
                        + " \"isActive\" = \"isActive\" OR \"autoArchived\", \"autoArchived\" = false, \"updatedAt\" = "
                        + NOW + " WHERE id = ?").params(name, json, extra, currentId).update();
            }
        }
        for (Map.Entry<String, Map<String, Object>> e : byKey.entrySet()) {
            if (!live.contains(e.getKey())) {
                jdbc.sql("UPDATE \"ProductVariant\" SET \"autoArchived\" = \"autoArchived\" OR \"isActive\","
                        + " \"isActive\" = false, \"updatedAt\" = " + NOW + " WHERE id = ?").params(e.getValue().get("id"))
                        .update();
            }
        }
        jdbc.sql("UPDATE \"Product\" SET \"hasVariants\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ?").params(!lines.isEmpty(), productId, org()).update();
    }

    /** Rename another variant of the product that holds {@code name} (archived combination) so the name is free. */
    private void freeName(String productId, String name, String exceptId) {
        for (Map<String, Object> v : SqlRows.list(jdbc.sql("SELECT id FROM \"ProductVariant\" WHERE \"productId\" = ? AND"
                + " \"organizationId\" = ? AND name = ? AND id IS DISTINCT FROM ?").params(productId, org(), name,
                        exceptId))) {
            String id = str(v.get("id"));
            jdbc.sql("UPDATE \"ProductVariant\" SET name = name || ' (' || left(id, 6) || ')' WHERE id = ?").params(id)
                    .update();
        }
    }

    private static String toJson(Map<String, Object> attrs) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(quote(str(e.getValue())));
        }
        return sb.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : (s == null ? "" : s).toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ─────────────────────────────────────────────────────────── variants

    /** Variants of a product with effective price, cost and on-hand (archived ones too when {@code all}). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> variants(String productId, boolean all) {
        return SqlRows.list(jdbc.sql("SELECT v.id, v.\"productId\", v.name, v.sku, v.barcode, v.attributes,"
                + " v.\"combinationKey\", v.\"priceExtra\", v.\"salesPrice\" AS \"fixedPrice\", v.\"costPrice\" AS"
                + " \"fixedCost\", " + PRICE_SQL + " AS \"salesPrice\", " + COST_SQL + " AS \"costPrice\", v.\"isActive\","
                + " v.length, v.width, v.height, v.description,"
                + " COALESCE((SELECT sum(s.quantity) FROM \"StockItem\" s WHERE s.\"variantId\" = v.id AND"
                + " s.\"organizationId\" = v.\"organizationId\"), 0) AS \"onHand\", COALESCE((SELECT sum(s.quantity *"
                + " s.\"runningAverageCost\") FROM \"StockItem\" s WHERE s.\"variantId\" = v.id AND s.\"organizationId\" ="
                + " v.\"organizationId\"), 0) AS \"stockValue\" FROM \"ProductVariant\" v JOIN \"Product\" p ON p.id ="
                + " v.\"productId\" WHERE v.\"productId\" = ? AND v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL"
                + (all ? "" : " AND v.\"isActive\"") + " ORDER BY v.\"isActive\" DESC, v.name").params(productId, org()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listVariants(String productId, Map<String, String> q) {
        product(productId);
        return variants(productId, !"true".equals(q.get("activeOnly")));
    }

    /** Variant picker for document lines: active variants matching {@code search} (product, variant, SKU, barcode). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(Map<String, String> q) {
        StringBuilder where = new StringBuilder("v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL AND v.\"isActive\""
                + " AND p.\"deletedAt\" IS NULL AND p.\"hasVariants\"");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!blank(q.get("productId"))) {
            where.append(" AND v.\"productId\" = ?");
            params.add(q.get("productId"));
        }
        if (!blank(q.get("search"))) {
            where.append(" AND (p.name ILIKE ? OR p.code ILIKE ? OR v.name ILIKE ? OR v.sku ILIKE ? OR v.barcode = ?)");
            String like = "%" + q.get("search").trim() + "%";
            params.addAll(List.of(like, like, like, like, q.get("search").trim()));
        }
        int limit = Math.min(500, Math.max(1, intOf(q.get("limit"), 200)));
        return SqlRows.list(jdbc.sql("SELECT v.id, v.\"productId\", p.code AS \"productCode\", p.name AS \"productName\","
                + " v.name, p.name || ' (' || v.name || ')' AS \"displayName\", v.sku, v.barcode, " + PRICE_SQL
                + " AS \"salesPrice\", " + COST_SQL + " AS \"costPrice\", v.length, v.width, v.height, v.description FROM \"ProductVariant\" v JOIN \"Product\" p ON"
                + " p.id = v.\"productId\" WHERE " + where + " ORDER BY p.name, v.name LIMIT " + limit).params(params));
    }

    /**
     * Edit a variant: SKU, barcode, fixed sales price / cost (null clears = computed), active, and its details
     * (ADR-S066): standard length / width / height (sizing unit of the product) and sales description.
     */
    @Transactional
    public Map<String, Object> updateVariant(String productId, String variantId, Map<String, Object> dto) {
        product(productId);
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT * FROM \"ProductVariant\" WHERE id = ? AND \"productId\" = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(variantId, productId, org()));
        if (v == null) {
            throw ApiException.notFound("Variant not found");
        }
        String sku = dto.containsKey("sku") ? (blank(dto.get("sku")) ? null : str(dto.get("sku")).trim()) : str(v.get("sku"));
        String barcode = dto.containsKey("barcode") ? (blank(dto.get("barcode")) ? null : str(dto.get("barcode")).trim())
                : str(v.get("barcode"));
        if (sku != null && !Objects.equals(sku, v.get("sku")) && jdbc.sql("SELECT count(*) FROM \"ProductVariant\" WHERE"
                + " \"organizationId\" = ? AND sku = ? AND id <> ? AND \"deletedAt\" IS NULL").params(org(), sku, variantId)
                .query(Long.class).single() > 0) {
            throw ApiException.conflict("SKU \"" + sku + "\" is already used by another variant");
        }
        if (barcode != null && !Objects.equals(barcode, v.get("barcode")) && jdbc.sql("SELECT count(*) FROM"
                + " \"ProductVariant\" WHERE \"organizationId\" = ? AND barcode = ? AND id <> ? AND \"deletedAt\" IS NULL")
                .params(org(), barcode, variantId).query(Long.class).single() > 0) {
            throw ApiException.conflict("Barcode \"" + barcode + "\" is already used by another variant");
        }
        BigDecimal price = dto.containsKey("salesPrice") ? decOrNull(dto.get("salesPrice")) : (BigDecimal) v.get("salesPrice");
        BigDecimal cost = dto.containsKey("costPrice") ? decOrNull(dto.get("costPrice")) : (BigDecimal) v.get("costPrice");
        if ((price != null && price.signum() < 0) || (cost != null && cost.signum() < 0)) {
            throw ApiException.badRequest("Prices must not be negative");
        }
        boolean active = dto.containsKey("isActive") ? Boolean.TRUE.equals(dto.get("isActive"))
                : Boolean.TRUE.equals(v.get("isActive"));
        BigDecimal length = size(dto, v, "length");
        BigDecimal width = size(dto, v, "width");
        BigDecimal height = size(dto, v, "height");
        String description = dto.containsKey("description") ? (blank(dto.get("description")) ? null
                : str(dto.get("description")).trim()) : str(v.get("description"));
        jdbc.sql("UPDATE \"ProductVariant\" SET sku = ?, barcode = ?, \"salesPrice\" = ?, \"costPrice\" = ?,"
                + " \"isActive\" = ?, length = ?, width = ?, height = ?, description = ?, \"autoArchived\" = false,"
                + " \"updatedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(sku, barcode, price, cost, active, length, width, height, description, TenantContext.userId(),
                        variantId).update();
        Map<String, Object> out = variants(productId, true).stream().filter(r -> variantId.equals(r.get("id")))
                .findFirst().orElseThrow();
        audit.recordInTx(AuditInput.of("ProductVariant", variantId, "update", v, out));
        return out;
    }

    private static BigDecimal size(Map<String, Object> dto, Map<String, Object> v, String key) {
        if (!dto.containsKey(key)) {
            return (BigDecimal) v.get(key);
        }
        BigDecimal d = decOrNull(dto.get(key));
        if (d != null && d.signum() <= 0) {
            throw ApiException.badRequest(key + " must be greater than 0");
        }
        return d;
    }

    // ─────────────────────────────────────────────────────────── cross-module

    /**
     * Validate the variant of a document line. Returns null for a product without variants (a given
     * {@code variantId} is then refused); a product with variants requires an active variant of its own.
     */
    @Transactional(readOnly = true)
    public VariantRef resolve(String productId, String variantId) {
        if (blank(productId)) {
            if (!blank(variantId)) {
                throw ApiException.badRequest("Variant not found");
            }
            return null;
        }
        if (blank(variantId)) {
            Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT name, \"hasVariants\" FROM \"Product\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(productId, org()));
            if (p != null && Boolean.TRUE.equals(p.get("hasVariants"))) {
                throw ApiException.badRequest("Choose a variant for " + p.get("name"));
            }
            return null;
        }
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT v.id, v.\"productId\", v.name, v.\"isActive\", " + PRICE_SQL
                + " AS price, " + COST_SQL + " AS cost FROM \"ProductVariant\" v JOIN \"Product\" p ON p.id ="
                + " v.\"productId\" WHERE v.id = ? AND v.\"organizationId\" = ? AND v.\"deletedAt\" IS NULL").params(
                        variantId, org()));
        if (v == null || !productId.equals(v.get("productId")) || !Boolean.TRUE.equals(v.get("isActive"))) {
            throw ApiException.badRequest("Variant not found");
        }
        return new VariantRef(variantId, productId, str(v.get("name")), (BigDecimal) v.get("price"),
                (BigDecimal) v.get("cost"));
    }

    /** {@link #resolve} that keeps archived variants (for documents copied from older ones). */
    @Transactional(readOnly = true)
    public VariantRef find(String variantId) {
        if (blank(variantId)) {
            return null;
        }
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT v.id, v.\"productId\", v.name, " + PRICE_SQL + " AS price, "
                + COST_SQL + " AS cost FROM \"ProductVariant\" v JOIN \"Product\" p ON p.id = v.\"productId\" WHERE"
                + " v.id = ? AND v.\"organizationId\" = ?").params(variantId, org()));
        return v == null ? null : new VariantRef(variantId, str(v.get("productId")), str(v.get("name")),
                (BigDecimal) v.get("price"), (BigDecimal) v.get("cost"));
    }

    private Map<String, Object> product(String id) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT id, name, \"hasVariants\", \"salesPrice\", \"costPrice\""
                + " FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        return p;
    }
}
