package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL / JS-semantics helpers shared by the POS services. */
public final class PosSql {

    /** Prisma {@code new Date()} / {@code @updatedAt} at millisecond precision (UTC wall clock). */
    public static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private PosSql() {}

    public static String org() {
        return TenantContext.organizationId();
    }

    /** {@code tenant.userId} (the POS cashier when an {@code X-Pos-User} token is present). */
    public static String user() {
        return TenantContext.userId();
    }

    /** JS {@code new Date(iso)} for a validated ISO string; {@code null} stays {@code null}. */
    public static Instant date(String iso) {
        return iso == null || iso.isEmpty() ? null : EntityPatch.toInstant(iso);
    }

    /** JSON object literal that drops {@code undefined} (null) values, like {@code JSON.stringify}. */
    public static Map<String, Object> defined(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    /** Untyped body field as a string. */
    public static String str(Map<String, ?> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }

    /** JS truthiness. */
    public static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof BigDecimal d) {
            return d.signum() != 0;
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d != 0 && !Double.isNaN(d);
        }
        if (v instanceof String s) {
            return !s.isEmpty();
        }
        return true;
    }

    /** JS {@code Number(v)} ({@code null} → 0). */
    public static double num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return JsNumbers.number(v.toString());
    }

    /** JS {@code Number(v ?? 0)} for JSON output (integral → Long). */
    public static Number js(Object v) {
        return JsNumbers.js(num(v));
    }

    /** A JS double as a decimal ({@code new Decimal(n)} = {@code String(n)}). */
    public static BigDecimal decOf(double n) {
        return new BigDecimal(JsNumbers.toJsString(n));
    }

    /** {@code dec(v)} from a number / string / Decimal ({@code null} → 0). */
    public static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Double || v instanceof Float) {
            return decOf(((Number) v).doubleValue());
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        return new BigDecimal(v.toString().trim());
    }

    /** {@code dec(v)} that keeps {@code null}. */
    public static BigDecimal decOrNull(Object v) {
        return v == null ? null : dec(v);
    }

    /** Round-trip a decimal through a JS number ({@code Number(d)} then {@code dec}). */
    public static BigDecimal viaJs(BigDecimal v) {
        return v == null ? null : decOf(v.doubleValue());
    }

    /** JS {@code Math.round(n * 100) / 100}. */
    public static double round2(double n) {
        return Math.floor(n * 100 + 0.5) / 100;
    }

    /** JS {@code String(x)} of a value that may be a Decimal, number or string. */
    public static String jsString(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof BigDecimal d) {
            return JsNumbers.dec(d);
        }
        if (v instanceof Double || v instanceof Float) {
            return JsNumbers.toJsString(((Number) v).doubleValue());
        }
        return v.toString();
    }

    /** Postgres text[] literal parameter. */
    public static String[] arr(Collection<String> values) {
        return values == null ? new String[0] : values.toArray(String[]::new);
    }

    /** A list-typed field of an untyped map ({@code ?? []}). */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Object v) {
        if (v instanceof List<?> l) {
            return (List<Object>) l;
        }
        if (v instanceof Collection<?> c) {
            return new ArrayList<>(c);
        }
        return new ArrayList<>();
    }

    /** {@code (v ?? []).map(String)}. */
    public static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : list(v)) {
            out.add(o == null ? null : o.toString());
        }
        return out;
    }

    /** {@code ?, ?, ?} placeholders. */
    public static String marks(int n) {
        return n == 0 ? "NULL" : String.join(", ", java.util.Collections.nCopies(n, "?"));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** Integer-typed column value. */
    public static int intOf(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }

    private static final tools.jackson.databind.json.JsonMapper MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /** A JSON column value (tree or string) as plain Java maps / lists / scalars. */
    public static Object java(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            v = MAPPER.readTree(s);
        }
        if (v instanceof tools.jackson.databind.JsonNode n) {
            return n.isNull() || n.isMissingNode() ? null : MAPPER.convertValue(n, Object.class);
        }
        return v;
    }

    /**
     * Single-column scalar row mapper. {@code JdbcClient.query(PosSql.SCALAR)} is NOT a scalar query — {@code Object} is
     * not a "simple" type, so it maps each row as a bean and yields {@code new Object()}. jsonb comes back as its text.
     */
    public static final org.springframework.jdbc.core.RowMapper<Object> SCALAR = (rs, n) -> {
        Object o = rs.getObject(1);
        return o != null && "org.postgresql.util.PGobject".equals(o.getClass().getName()) ? o.toString() : o;
    };

    /** Prisma {@code contains} pattern (wildcards escaped) for {@code ILIKE ?}. */
    public static String contains(String s) {
        return "%" + com.inventoryerp.kernel.crud.BaseCrudService.escapeLike(s) + "%";
    }
}
