package com.inventoryerp.repair.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.crud.TenancyScope;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.repair.domain.RepairDiagnosis;
import com.inventoryerp.repair.domain.RepairJob;
import com.inventoryerp.repair.domain.RepairOrder;
import com.inventoryerp.repair.domain.RepairQuotation;
import com.inventoryerp.repair.domain.RepairWarranty;
import com.inventoryerp.shared.web.EntityJson;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared data access for the repair services: Prisma-shaped reads under the tenancy extension (organization scope +
 * soft delete), {@code create}/{@code update} with Prisma's input coercion for the untyped ({@code dto: any}) bodies,
 * {@code $transaction}, row locks, the {@code take}/{@code skip} coercions, JS number semantics on {@link BigDecimal}
 * and the in-app notification every repair flow sends.
 *
 * <p>Hardening over the Nest original (docs/spec/repair.md → Deviations): every written key must be a column of the
 * model (Prisma's "Unknown argument" error, and no column name from a request body ever reaches SQL unchecked), an
 * {@code organizationId} in a body cannot move a row to another tenant, and a body that re-points a row at a parent
 * ({@code repairOrderId}, {@code quotationId}, ...) must name a parent of the caller's organization.
 */
@Component
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairDb {

    private final PosDb db;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final NotificationsService notifications;

    public RepairDb(PosDb db, PlatformTransactionManager txm, NotificationsService notifications) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.tx = new TransactionTemplate(txm);
        this.notifications = notifications;
    }

    public PosDb db() {
        return db;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public static String org() {
        return PosSql.org();
    }

    public static String user() {
        return PosSql.user();
    }

    /** {@code prisma.$transaction(async (tx) => ...)} (joins an enclosing transaction). */
    public <T> T inTx(Supplier<T> body) {
        return tx.execute(st -> body.get());
    }

    // ─── reads ───

    /** Tenancy-extension predicate of a model: {@code organizationId} (+ {@code deletedAt IS NULL}). */
    static String scope(Class<?> entity) {
        return TenancyScope.predicate(entity, null);
    }

    /** {@code findFirst({ where: { id } })} under the tenancy extension. */
    public Map<String, Object> byId(Class<?> entity, Object id) {
        return where(entity).eq("id", id).first();
    }

    /** {@link #byId} with the row locked ({@code SELECT ... FOR UPDATE}) for the rest of the transaction. */
    public Map<String, Object> lockById(Class<?> entity, Object id) {
        return where(entity).eq("id", id).lock();
    }

    public Where where(Class<?> entity) {
        return new Where(entity);
    }

    /** Scoped where-clause builder ({@code Prisma where}). */
    public final class Where {
        final Class<?> entity;
        final StringBuilder sql = new StringBuilder();
        final List<Object> params = new ArrayList<>();

        Where(Class<?> entity) {
            this.entity = entity;
            sql.append(scope(entity));
            if (TenancyScope.orgScoped(entity)) {
                params.add(org());
            }
        }

        public Where and(String clause, Object... p) {
            sql.append(" AND (").append(clause).append(')');
            params.addAll(Arrays.asList(p));
            return this;
        }

        /** {@code field: value}; {@code null} means {@code IS NULL}. */
        public Where eq(String column, Object value) {
            if (value == null) {
                return and("\"" + column + "\" IS NULL");
            }
            return and("\"" + column + "\" = ?", PosDb.param(value));
        }

        /** {@code if (query.x) where.x = query.x}. */
        public Where eqIf(String column, Object value) {
            return PosSql.truthy(value) ? eq(column, value) : this;
        }

        public Object[] args() {
            return params.toArray();
        }

        public String sql() {
            return sql.toString();
        }

        public long count() {
            return db.count(entity, sql.toString(), args());
        }

        public Map<String, Object> first() {
            return db.first(entity, sql.toString(), args());
        }

        public Map<String, Object> first(String orderBy) {
            List<Map<String, Object>> rows = list(orderBy, 1, 0);
            return rows.isEmpty() ? null : rows.getFirst();
        }

        /** The first matching row, locked until the transaction ends. */
        public Map<String, Object> lock() {
            return SqlRows.one(entity, jdbc.sql("SELECT * FROM \"" + PosDb.table(entity) + "\" WHERE " + sql
                    + " LIMIT 1 FOR UPDATE").params(args()));
        }

        public List<Map<String, Object>> list(String orderBy) {
            return db.list(entity, sql + (orderBy == null ? "" : " ORDER BY " + orderBy), args());
        }

        public List<Map<String, Object>> list(String orderBy, Integer take, int skip) {
            return db.list(entity, sql + (orderBy == null ? "" : " ORDER BY " + orderBy)
                    + (take == null ? "" : " LIMIT " + take) + (skip > 0 ? " OFFSET " + skip : ""), args());
        }

        /** {@code updateMany}: returns {@code {count}}. */
        public Map<String, Object> updateMany(Map<String, Object> data) {
            return countOf(db.updateMany(entity, coerce(entity, data), sql.toString(), args()));
        }

        /** {@code deleteMany}: hard delete, returns {@code {count}}. */
        public Map<String, Object> deleteMany() {
            int n = jdbc.sql("DELETE FROM \"" + PosDb.table(entity) + "\" WHERE " + sql).params(args()).update();
            return countOf(n);
        }
    }

    /** To-many include ({@code include: { key: { orderBy } }}), unfiltered like a Prisma relation query. */
    public List<Map<String, Object>> many(Class<?> entity, String fkCol, Object id, String orderBy) {
        return db.list(entity, "\"" + fkCol + "\" = ?" + (orderBy == null ? "" : " ORDER BY " + orderBy), id);
    }

    public static Map<String, Object> countOf(long n) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", n);
        return out;
    }

    // ─── writes ───

    /** {@code create({ data })}. */
    public Map<String, Object> insert(Class<?> entity, Map<String, Object> data) {
        return db.insert(entity, coerce(entity, data));
    }

    /** {@code update({ where: { id }, data })} under the tenancy extension — 500 when the row is gone. */
    public Map<String, Object> update(Class<?> entity, String id, Map<String, Object> data) {
        Where w = where(entity).eq("id", id);
        return db.update(entity, coerce(entity, data), w.sql(), w.args());
    }

    /** Soft delete: {@code update({ data: { deletedAt: new Date() } })}. */
    public Map<String, Object> softDelete(Class<?> entity, String id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deletedAt", PosDb.raw(PosSql.NOW));
        return update(entity, id, data);
    }

    /**
     * Prisma input coercion of JS values: ISO strings / epoch numbers to dates, objects and arrays (and scalars) to
     * JSON columns. {@code undefined} (absent) keys never reach here; an explicit {@code null} clears the column.
     * Every key must be a column of {@code entity} (Prisma "Unknown argument" → 500), and {@code organizationId} can
     * only name the caller's organization (under the Nest RLS policy any other value fails the row check → 500).
     */
    static Map<String, Object> coerce(Class<?> entity, Map<String, Object> data) {
        List<String> columns = EntityJson.columnNames(entity);
        Map<String, Object> out = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            if (!columns.contains(k)) {
                throw new IllegalStateException("Invalid `prisma." + model(entity) + "` invocation: Unknown argument `"
                        + k + "`.");
            }
            if ("organizationId".equals(k) && !Objects.equals(v, org())) {
                throw new IllegalStateException("new row violates row-level security policy for table \""
                        + PosDb.table(entity) + "\"");
            }
            Object val = v;
            Field f = field(entity, k);
            if (v != null && !(v instanceof PosDb.Raw) && !(v instanceof PosDb.Json) && f != null) {
                if (isJson(f)) {
                    val = PosDb.json(v);
                } else if (f.getType() == Instant.class && !(v instanceof Instant)) {
                    val = jsDate(v);
                }
            }
            out.put(k, val);
        });
        return out;
    }

    private static String model(Class<?> entity) {
        String n = entity.getSimpleName();
        return Character.toLowerCase(n.charAt(0)) + n.substring(1);
    }

    /** Parents a request body may re-point a repair row at, with the model each key references. */
    private static final Map<String, Class<?>> PARENTS = Map.of("repairOrderId", RepairOrder.class,
            "quotationId", RepairQuotation.class, "diagnosisId", RepairDiagnosis.class,
            "warrantyId", RepairWarranty.class, "jobId", RepairJob.class);

    /**
     * {@code data: { ...dto }} from an untyped request body: a copy whose parent references ({@code repairOrderId},
     * {@code quotationId}, {@code diagnosisId}, {@code warrantyId}, {@code jobId}) must name rows of the caller's
     * organization. Prisma would accept another tenant's parent and the row would then show up in that tenant's
     * {@code include}s; here it is the foreign-key error a missing parent gives (500).
     */
    public Map<String, Object> spread(Class<?> entity, Map<String, Object> dto) {
        Map<String, Object> data = new LinkedHashMap<>(body(dto));
        List<String> columns = EntityJson.columnNames(entity);
        PARENTS.forEach((key, parent) -> {
            Object ref = data.get(key);
            if (ref != null && columns.contains(key) && parent != entity) {
                requireParent(parent, ref, PosDb.table(entity) + "_" + key + "_fkey");
            }
        });
        return data;
    }

    /** A referenced parent row of the caller's organization, else Prisma's P2003 foreign-key error (500). */
    public void requireParent(Class<?> parent, Object id, String constraint) {
        if (id == null || byId(parent, id.toString()) == null) {
            throw new IllegalStateException("Foreign key constraint violated on the constraint: `" + constraint + "`");
        }
    }

    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Field NONE;

    static {
        try {
            NONE = RepairDb.class.getDeclaredField("FIELDS");
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(Class<?> entity, String name) {
        Field f = FIELDS.computeIfAbsent(entity.getName() + "#" + name, k -> {
            for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    return c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    // walk up
                }
            }
            return NONE;
        });
        return f == NONE ? null : f;
    }

    private static boolean isJson(Field f) {
        JdbcTypeCode code = f.getAnnotation(JdbcTypeCode.class);
        return code != null && code.value() == SqlTypes.JSON;
    }

    // ─── JS coercions ───

    /** {@code Math.min(Number(raw ?? def), max)}; {@code NaN} reaches Prisma as an invalid {@code take} (500). */
    public static int take(Object raw, int def, int max) {
        double n = raw == null ? def : PosSql.num(raw);
        return prismaInt(Math.min(n, max), "take");
    }

    /** {@code Number(raw ?? 0)} as a Prisma {@code skip}. */
    public static int skip(Object raw) {
        int v = prismaInt(raw == null ? 0 : PosSql.num(raw), "skip");
        if (v < 0) {
            throw new IllegalStateException("Invalid value for argument `skip`: Expected a non-negative integer.");
        }
        return v;
    }

    private static int prismaInt(double v, String arg) {
        if (Double.isNaN(v) || v != Math.rint(v)) {
            throw new IllegalStateException("Invalid value for argument `" + arg + "`: Expected Int.");
        }
        return (int) v;
    }

    /** JS {@code new Date(v)} for a value Prisma will store; invalid → Prisma validation error (500). */
    public static Instant jsDate(Object v) {
        if (v instanceof Instant i) {
            return i;
        }
        if (v instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        try {
            return EntityPatch.toInstant(v.toString().trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Invalid value for argument: Provided Date object is invalid. Expected Date.");
        }
    }

    /** {@code dto.x ? new Date(dto.x) : null}. */
    public static Instant dateOrNull(Object v) {
        return PosSql.truthy(v) ? jsDate(v) : null;
    }

    /** JS {@code new Date().toISOString()}. */
    public static String isoNow() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .format(Instant.now().atOffset(java.time.ZoneOffset.UTC));
    }

    /** JS {@code a ?? b}. */
    public static Object nz(Object a, Object b) {
        return a != null ? a : b;
    }

    /** JS {@code String(v)} for template literals ({@code undefined}/{@code null} as JS prints them). */
    public static String s(Object v) {
        return v == null ? "null" : PosSql.jsString(v);
    }

    public static Map<String, Object> body(Map<String, Object> dto) {
        return dto == null ? new LinkedHashMap<>() : dto;
    }

    // ─── JS numbers on exact decimals (money and quantities never go through double) ───

    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    private static final BigDecimal HALF_CENT = new BigDecimal("0.005");

    /**
     * JS {@code Number(v)} as an exact decimal: {@code null} → 0, booleans → 1/0, numeric strings parsed exactly,
     * {@code ""} → 0. Returns {@code null} where JS yields {@code NaN} or ±{@code Infinity}.
     */
    public static BigDecimal jsNum(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            return Double.isFinite(d) ? PosSql.decOf(d) : null;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof String str) {
            String t = str.strip();
            if (t.isEmpty()) {
                return BigDecimal.ZERO;
            }
            if (DECIMAL.matcher(t).matches()) {
                return new BigDecimal(t);
            }
            double d = JsNumbers.number(t);
            return Double.isFinite(d) ? PosSql.decOf(d) : null;
        }
        return null;
    }

    /** JS {@code Number(v) || 0}. */
    public static BigDecimal jsNumOrZero(Object v) {
        BigDecimal n = jsNum(v);
        return n == null ? BigDecimal.ZERO : n;
    }

    /** JS {@code n > 0} ({@code NaN} is not). */
    public static boolean positive(BigDecimal n) {
        return n != null && n.signum() > 0;
    }

    /** JS {@code Math.round(n * 100) / 100} on an exact decimal (half rounds towards +∞, like Math.round). */
    public static BigDecimal round2(BigDecimal n) {
        return n.add(HALF_CENT).setScale(2, RoundingMode.FLOOR);
    }

    /** A decimal that must be finite to be stored (JS {@code NaN} has no Postgres-safe Prisma form here) → 500. */
    public static BigDecimal finite(BigDecimal n, String argument) {
        if (n == null) {
            throw new IllegalStateException("Invalid value for argument `" + argument + "`: Expected Decimal, provided"
                    + " NaN.");
        }
        return n;
    }

    // ─── notifications ───

    /** {@code notifications.send({ organizationId, channel: 'in_app', category: 'repair', ... })}. */
    public void notify(String orgId, String title, String body, Map<String, Object> payload) {
        notifications.send(new SendInput(orgId, null, "in_app", "repair", title, body, payload));
    }

    /** A Prisma row as returned by a raw {@code SELECT *}. */
    public Map<String, Object> row(Class<?> entity, String sql, Object... params) {
        return SqlRows.one(entity, jdbc.sql(sql).params(params));
    }
}
