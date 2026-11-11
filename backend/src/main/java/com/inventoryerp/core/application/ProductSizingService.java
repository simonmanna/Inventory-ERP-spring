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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sell by size (ADR-S066). A product sold by {@code length}, {@code area} or {@code volume} takes, on each document
 * line, the number of pieces and their length [× width [× height]] typed in the product's sizing unit; the line
 * quantity is computed here, in metres / m² / m³: {@code pieces × length × width × height}. Granite kerbs of 3 m: 4
 * pieces × 3 m = 12 m; slabs 2 pieces × 240 cm × 120 cm = 5.76 m².
 *
 * <p>Document services call {@link #resolve} (or {@link #fromLine}) before pricing: a line without size fields is a
 * plain quantity line, so existing clients are unaffected.
 */
@Service
public class ProductSizingService {

    public static final List<String> MODES = List.of("length", "area", "volume");
    public static final List<String> UNITS = List.of("m", "cm", "mm");
    /** Line fields that carry the size. */
    public static final Set<String> LINE_FIELDS = Set.of("pieces", "length", "width", "height");
    static final int QTY_SCALE = 6;

    /**
     * The size of a document line. Before {@link #resolve}, {@code quantity} is null and values are as typed; after
     * it, only the dimensions the mode uses are kept and {@code quantity} is the computed line quantity.
     */
    public record Sizing(BigDecimal pieces, BigDecimal length, BigDecimal width, BigDecimal height, BigDecimal quantity) {

        /** The size fields of a request line, or null when it carries none. */
        public static Sizing of(Map<String, Object> line) {
            if (line == null) {
                return null;
            }
            return raw(line.get("pieces"), line.get("length"), line.get("width"), line.get("height"));
        }

        /** Size from loose values (request or stored row), or null when all are empty. */
        public static Sizing raw(Object pieces, Object length, Object width, Object height) {
            if (blank(pieces) && blank(length) && blank(width) && blank(height)) {
                return null;
            }
            return new Sizing(dec(pieces, "pieces"), dec(length, "length"), dec(width, "width"),
                    dec(height, "height"), null);
        }

        /** Stored size of a document row (null when the row was not sold by size). */
        public static Sizing stored(Map<String, Object> row) {
            if (row == null || row.get("pieces") == null) {
                return null;
            }
            return raw(row.get("pieces"), row.get("length"), row.get("width"), row.get("height"));
        }

        /** Column values for a line insert (all null for a plain line). */
        public static Map<String, Object> columns(Sizing s) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pieces", s == null ? null : s.pieces());
            m.put("length", s == null ? null : s.length());
            m.put("width", s == null ? null : s.width());
            m.put("height", s == null ? null : s.height());
            return m;
        }
    }

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ProductSizingService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static BigDecimal dec(Object v, String field) {
        if (blank(v)) {
            return null;
        }
        try {
            return v instanceof BigDecimal b ? b : new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be a number");
        }
    }

    // ─────────────────────────────────────────────────────────── product configuration

    /** The sizing configuration of a product. */
    @Transactional(readOnly = true)
    public Map<String, Object> config(String productId) {
        Map<String, Object> p = product(productId);
        return view(p);
    }

    /**
     * Set how a product is sold by size: {@code sizingMode} (null = plain quantity), {@code sizingUnit} and the default
     * length / width / height offered on new lines.
     */
    @Transactional
    public Map<String, Object> save(String productId, Map<String, Object> dto) {
        Map<String, Object> before = product(productId);
        for (String k : dto.keySet()) {
            if (!Set.of("sizingMode", "sizingUnit", "defaultLength", "defaultWidth", "defaultHeight").contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
        String mode = dto.containsKey("sizingMode") ? (blank(dto.get("sizingMode")) ? null
                : dto.get("sizingMode").toString()) : (String) before.get("sizingMode");
        if (mode != null && !MODES.contains(mode)) {
            throw ApiException.badRequest("sizingMode must be one of the following values: length, area, volume");
        }
        String unit = dto.containsKey("sizingUnit") && !blank(dto.get("sizingUnit")) ? dto.get("sizingUnit").toString()
                : (String) before.get("sizingUnit");
        if (!UNITS.contains(unit)) {
            throw ApiException.badRequest("sizingUnit must be one of the following values: m, cm, mm");
        }
        BigDecimal length = positive(dto, before, "defaultLength");
        BigDecimal width = positive(dto, before, "defaultWidth");
        BigDecimal height = positive(dto, before, "defaultHeight");
        jdbc.sql("UPDATE \"Product\" SET \"sizingMode\" = ?, \"sizingUnit\" = ?, \"defaultLength\" = ?, \"defaultWidth\" = ?,"
                + " \"defaultHeight\" = ?, \"updatedAt\" = date_trunc('milliseconds', clock_timestamp())::timestamp(3)"
                + " WHERE id = ? AND \"organizationId\" = ?").params(mode, unit, length, width, height, productId, org())
                .update();
        Map<String, Object> out = config(productId);
        audit.recordInTx(AuditInput.of("Product", productId, "update", view(before), out));
        return out;
    }

    private static BigDecimal positive(Map<String, Object> dto, Map<String, Object> before, String key) {
        if (!dto.containsKey(key)) {
            return (BigDecimal) before.get(key);
        }
        BigDecimal v = dec(dto.get(key), key);
        if (v != null && v.signum() <= 0) {
            throw ApiException.badRequest(key + " must be greater than 0");
        }
        return v;
    }

    private static Map<String, Object> view(Map<String, Object> p) {
        return Obj.of("productId", p.get("id"), "sizingMode", p.get("sizingMode"), "sizingUnit", p.get("sizingUnit"),
                "defaultLength", p.get("defaultLength"), "defaultWidth", p.get("defaultWidth"), "defaultHeight",
                p.get("defaultHeight"), "uomCode", p.get("uomCode"));
    }

    private Map<String, Object> product(String id) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT p.id, p.name, p.\"sizingMode\", p.\"sizingUnit\","
                + " p.\"defaultLength\", p.\"defaultWidth\", p.\"defaultHeight\", u.code AS \"uomCode\" FROM \"Product\" p"
                + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = COALESCE(p.\"salesUomId\", p.\"uomId\") WHERE p.id = ? AND"
                + " p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL").params(id, org()));
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        return p;
    }

    // ─────────────────────────────────────────────────────────── document lines

    /** {@link #resolve} on the size fields of a request line; null for a plain quantity line. */
    @Transactional(readOnly = true)
    public Sizing fromLine(String productId, Map<String, Object> line) {
        return resolve(productId, Sizing.of(line));
    }

    /**
     * Validate a line's size against its product and compute the quantity. Null in, null out (plain quantity line).
     * The product must be sold by size; pieces default to 1; every dimension of the mode is required and positive.
     */
    @Transactional(readOnly = true)
    public Sizing resolve(String productId, Sizing in) {
        if (in == null) {
            return null;
        }
        if (blank(productId)) {
            throw ApiException.badRequest("Only product lines can be sold by size");
        }
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT name, \"sizingMode\", \"sizingUnit\" FROM \"Product\" WHERE"
                + " id = ? AND \"organizationId\" = ?").params(productId, org()));
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        String mode = (String) p.get("sizingMode");
        if (mode == null) {
            throw ApiException.badRequest(p.get("name") + " is not sold by size");
        }
        return compute(mode, (String) p.get("sizingUnit"), in);
    }

    /** Pure size math (unit-tested): validated dimensions of {@code mode} and the quantity in m / m² / m³. */
    public static Sizing compute(String mode, String unit, Sizing in) {
        BigDecimal pieces = in.pieces() == null ? BigDecimal.ONE : in.pieces();
        if (pieces.signum() <= 0) {
            throw ApiException.badRequest("pieces must be greater than 0");
        }
        BigDecimal length = require(in.length(), "length");
        BigDecimal width = "length".equals(mode) ? null : require(in.width(), "width");
        BigDecimal height = "volume".equals(mode) ? require(in.height(), "height") : null;
        BigDecimal factor = switch (unit == null ? "m" : unit) {
            case "cm" -> new BigDecimal("0.01");
            case "mm" -> new BigDecimal("0.001");
            default -> BigDecimal.ONE;
        };
        BigDecimal qty = pieces.multiply(length.multiply(factor));
        if (width != null) {
            qty = qty.multiply(width.multiply(factor));
        }
        if (height != null) {
            qty = qty.multiply(height.multiply(factor));
        }
        qty = qty.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("quantity must be greater than 0");
        }
        return new Sizing(pieces, length, width, height, qty);
    }

    private static BigDecimal require(BigDecimal v, String field) {
        if (v == null) {
            throw ApiException.badRequest(field + " is required for a product sold by size");
        }
        if (v.signum() <= 0) {
            throw ApiException.badRequest(field + " must be greater than 0");
        }
        return v;
    }

    /** Printable size, e.g. {@code 4 pcs × 3 m} or {@code 2 pcs × 240 × 120 cm}; null for a plain line. */
    public static String label(Sizing s, String unit) {
        if (s == null || s.length() == null) {
            return null;
        }
        List<String> dims = new ArrayList<>();
        for (BigDecimal d : new BigDecimal[] {s.length(), s.width(), s.height()}) {
            if (d != null) {
                dims.add(plain(d));
            }
        }
        BigDecimal pieces = s.pieces() == null ? BigDecimal.ONE : s.pieces();
        return plain(pieces) + " pcs × " + String.join(" × ", dims) + " " + (unit == null ? "m" : unit);
    }

    private static String plain(BigDecimal d) {
        return d.stripTrailingZeros().toPlainString();
    }

    /** The sizing unit of a product ({@code m} when unknown). */
    @Transactional(readOnly = true)
    public String unitOf(String productId) {
        if (blank(productId)) {
            return "m";
        }
        return jdbc.sql("SELECT \"sizingUnit\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(productId, org()).query(String.class).optional().orElse("m");
    }
}
