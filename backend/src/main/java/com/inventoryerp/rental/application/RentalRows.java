package com.inventoryerp.rental.application;

import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.core.domain.Product;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.rental.domain.RentalUnit;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Shared row helpers of the rental services: Prisma-shaped reads through {@link PosDb}, the {@code select} briefs
 * Nest includes ({@code product: {select: {id, code, name}}}, ...), JS number / date coercion of the untyped
 * request bodies ({@code @Body() dto: any}) and the {@code {total, page, pageSize, items}} list envelope.
 */
@Component
public class RentalRows {

    /** Prisma {@code new Date()} at millisecond precision. */
    static final String NOW = PosSql.NOW;

    private final PosDb db;

    public RentalRows(PosDb db) {
        this.db = db;
    }

    PosDb db() {
        return db;
    }

    static String org() {
        return PosSql.org();
    }

    static String user() {
        return PosSql.user();
    }

    // ------------------------------------------------------------------ JS coercion

    /** JS {@code Number(v)} ({@code undefined} → NaN, {@code null} → 0). */
    static double num(Map<String, ?> body, String key) {
        if (body == null || !body.containsKey(key)) {
            return Double.NaN;
        }
        return PosSql.num(body.get(key));
    }

    /** JS {@code Number(v)} of a value that may be absent ({@code undefined} → NaN). */
    static double num(Object v) {
        return PosSql.num(v);
    }

    /** {@code dto.key ?? fallback} as a JS number. */
    static double numOr(Map<String, ?> body, String key, double fallback) {
        Object v = body == null ? null : body.get(key);
        return v == null ? fallback : PosSql.num(v);
    }

    /** Untyped body field as a string. */
    static String str(Map<String, ?> body, String key) {
        return PosSql.str(body, key);
    }

    /** JS {@code String(n)} for messages ({@code Cannot refund ${amount} ...}). */
    static String js(double n) {
        return JsNumbers.toJsString(n);
    }

    /** A JS number as a JSON value (integral → Long). */
    static Number jsNum(double n) {
        return JsNumbers.js(n);
    }

    /** A JS number as the decimal Prisma writes for it. */
    static java.math.BigDecimal dec(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) {
            throw new IllegalStateException("Invalid value for argument: Expected decimal, provided " + js(n) + ".");
        }
        return PosSql.decOf(n);
    }

    /**
     * JS {@code new Date(v)}: ISO strings, date-only strings and epoch millis; {@code null} for an Invalid Date (its
     * comparisons are all false). {@code new Date(undefined)} is invalid too.
     */
    static Instant date(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return EntityPatch.toInstant(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@link #date} for the web layer (availability query strings). */
    public static Instant parseDate(Object v) {
        return date(v);
    }

    /** An Invalid Date reaching Prisma: {@code PrismaClientValidationError} (500). */
    static Instant valid(Instant d) {
        if (d == null) {
            throw new IllegalStateException("Invalid value for argument: Provided Date object is invalid.");
        }
        return d;
    }

    /** {@code a > b} on two JS dates (false when either is invalid). */
    static boolean after(Instant a, Instant b) {
        return a != null && b != null && a.isAfter(b);
    }

    /** {@code dto.lines.length} — a missing array is a TypeError (500) in Nest. */
    static List<Map<String, Object>> lines(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (!(v instanceof List<?> l)) {
            throw new IllegalStateException("Cannot read properties of undefined (reading 'length')");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : l) {
            Map<String, Object> m = PosSql.map(o);
            out.add(m == null ? new LinkedHashMap<>() : m);
        }
        return out;
    }

    /** {@code dto.key ?? []} as maps. */
    static List<Map<String, Object>> optLines(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? new ArrayList<>() : lines(body, key);
    }

    // ------------------------------------------------------------------ paging

    /** {@code page = query.page ?? 1; pageSize = query.pageSize ?? 50} (echoed as received). */
    record Paging(Object page, Object pageSize, int offset, int limit) {}

    static Paging paging(Map<String, String> q) {
        String p = q == null ? null : q.get("page");
        String ps = q == null ? null : q.get("pageSize");
        double page = p == null ? 1 : JsNumbers.number(p);
        double size = ps == null ? 50 : JsNumbers.number(ps);
        double skip = (page - 1) * size;
        if (Double.isNaN(skip) || skip < 0 || Double.isNaN(size) || size < 0) {
            throw new IllegalStateException("Invalid value provided for skip/take: expected a non-negative integer.");
        }
        return new Paging(p == null ? (Object) 1 : p, ps == null ? (Object) 50 : ps, (int) skip, (int) size);
    }

    static Map<String, Object> envelope(long total, Paging p, List<Map<String, Object>> items) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("page", p.page());
        out.put("pageSize", p.pageSize());
        out.put("items", items);
        return out;
    }

    // ------------------------------------------------------------------ briefs

    /** {@code select} of the given keys from a row ({@code null} row stays {@code null}). */
    static Map<String, Object> pick(Map<String, Object> row, String... keys) {
        if (row == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) {
            out.put(k, row.get(k));
        }
        return out;
    }

    /** {@code product: {select: {id, code, name}}} (relations are not soft-delete filtered). */
    Map<String, Object> productBrief(Object productId) {
        return pick(db.first(Product.class, "id = ?", productId), "id", "code", "name");
    }

    Map<String, Object> partnerBrief(Object partnerId, String... keys) {
        return pick(db.first(Partner.class, "id = ?", partnerId), keys);
    }

    Map<String, Object> unitBrief(Object unitId, String... keys) {
        return unitId == null ? null : pick(db.first(RentalUnit.class, "id = ?", unitId), keys);
    }

    /** {@code product.findFirst({where: {id}})} in the current org (soft-delete filtered). */
    Map<String, Object> product(Object productId) {
        return db.first(Product.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", productId, org());
    }

    /** {@code product.findFirst({where: {organizationId, code, isActive: true}})}. */
    Map<String, Object> productByCode(String code, boolean activeOnly) {
        return db.first(Product.class, "\"organizationId\" = ? AND code = ? AND \"deletedAt\" IS NULL"
                + (activeOnly ? " AND \"isActive\" = true" : ""), org(), code);
    }

    /** Adds a relation to each row. */
    static List<Map<String, Object>> with(List<Map<String, Object>> rows, String key,
            Function<Map<String, Object>, Object> rel) {
        for (Map<String, Object> r : rows) {
            r.put(key, rel.apply(r));
        }
        return rows;
    }
}
