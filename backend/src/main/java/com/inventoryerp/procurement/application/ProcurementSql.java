package com.inventoryerp.procurement.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** SQL / JS-semantics helpers shared by the procurement services. */
final class ProcurementSql {

    /** Prisma {@code new Date()} / {@code @updatedAt} at millisecond precision (UTC wall clock). */
    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private ProcurementSql() {}

    static String org() {
        return TenantContext.organizationId();
    }

    static String user() {
        return TenantContext.userId();
    }

    /** JS {@code new Date(iso)} for a validated ISO string; {@code null} stays {@code null}. */
    static Instant date(String iso) {
        return iso == null || iso.isEmpty() ? null : EntityPatch.toInstant(iso);
    }

    /** JS {@code d.setHours(23, 59, 59, 999)} in the server's local zone. */
    static Instant endOfLocalDay(Instant d) {
        return LocalDateTime.of(d.atZone(ZoneId.systemDefault()).toLocalDate(), LocalTime.of(23, 59, 59, 999_000_000))
                .atZone(ZoneId.systemDefault()).toInstant();
    }

    /** A JS double stored into a Prisma Decimal column ({@code new Decimal(n)} = {@code String(n)}). */
    static BigDecimal num(double n) {
        return new BigDecimal(JsNumbers.toJsString(n));
    }

    /** JSON object literal that drops {@code undefined} (null) values, like {@code JSON.stringify}. */
    static Map<String, Object> defined(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    /** Untyped body field as a string (Nest interface bodies are not validated). */
    static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }

    /** JS truthiness of a body value. */
    static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
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

    /** JS {@code Number(v)}. */
    static double jsNumber(Object v) {
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
}
