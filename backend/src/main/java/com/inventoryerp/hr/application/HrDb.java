package com.inventoryerp.hr.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.kernel.crud.TenancyScope;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared data access for the HR services: Prisma-shaped reads (tenant scope + soft delete like the tenancy extension,
 * unfiltered {@code include}s, {@code _count}), the {@code writeAudited} transaction helper ({@code hr-audit.util.ts})
 * and JS value coercions for the untyped ({@code dto: any}) bodies and queries.
 */
@Component
public class HrDb {

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public HrDb(PosDb db, AuditService audit, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
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

    // ─── transactions ───

    public <T> T inTx(Supplier<T> body) {
        return tx.execute(st -> body.get());
    }

    /** {@code writeAudited}: the write and its audit entry in one transaction (audit failure rolls back). */
    public <T> T audited(Supplier<T> write, Function<T, AuditInput> entry) {
        return tx.execute(st -> {
            T result = write.get();
            audit.recordInTx(entry.apply(result));
            return result;
        });
    }

    public void auditInTx(AuditInput input) {
        audit.recordInTx(input);
    }

    // ─── scope ───

    static boolean softDeletes(Class<?> entity) {
        return TenancyScope.softDeletes(entity);
    }

    static boolean orgScoped(Class<?> entity) {
        return TenancyScope.orgScoped(entity);
    }

    /** Tenancy-extension predicate of a model: {@code organizationId} (+ {@code deletedAt IS NULL}). */
    public static String scope(Class<?> entity) {
        String s = orgScoped(entity) ? "\"organizationId\" = ?" : "true";
        return softDeletes(entity) ? s + " AND \"deletedAt\" IS NULL" : s;
    }

    /** {@code findFirst({ where: { id } })} under the tenancy extension. */
    public Map<String, Object> byId(Class<?> entity, Object id) {
        return where(entity).eq("id", id).first();
    }

    /** A scoped query on one model. */
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
            if (orgScoped(entity)) {
                params.add(org());
            }
        }

        public Where and(String clause, Object... p) {
            sql.append(" AND (").append(clause).append(')');
            params.addAll(Arrays.asList(p));
            return this;
        }

        /** {@code field: value}; a {@code null} value means {@code IS NULL}. */
        public Where eq(String column, Object value) {
            if (value == null) {
                return and("\"" + column + "\" IS NULL");
            }
            return and("\"" + column + "\"::text = ?", value instanceof String ? value : PosSql.jsString(value));
        }

        /** {@code eq} only when the JS value is truthy ({@code if (query.x) where.x = query.x}). */
        public Where eqIf(String column, Object value) {
            return PosSql.truthy(value) ? eq(column, value) : this;
        }

        /** {@code OR: [{ col: { contains, mode: 'insensitive' } }, ...]}. */
        public Where search(Object term, String... columns) {
            if (!PosSql.truthy(term)) {
                return this;
            }
            String like = PosSql.contains(String.valueOf(term));
            List<String> ors = new ArrayList<>();
            List<Object> p = new ArrayList<>();
            for (String c : columns) {
                ors.add("\"" + c + "\" ILIKE ?");
                p.add(like);
            }
            return and(String.join(" OR ", ors), p.toArray());
        }

        public Where in(String column, Collection<?> values) {
            if (values.isEmpty()) {
                return and("false");
            }
            return and("\"" + column + "\" IN (" + PosSql.marks(values.size()) + ")", values.toArray());
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

        public List<Map<String, Object>> list(String orderBy) {
            return db.list(entity, sql + (orderBy == null ? "" : " ORDER BY " + orderBy), args());
        }

        public List<Map<String, Object>> list(String orderBy, Integer take, int skip) {
            return db.list(entity, sql + (orderBy == null ? "" : " ORDER BY " + orderBy)
                    + (take == null ? "" : " LIMIT " + take) + (skip > 0 ? " OFFSET " + skip : ""), args());
        }

        /** {@code updateMany} on the scoped rows (bumps {@code updatedAt}); returns the count. */
        public int update(Map<String, Object> data) {
            return db.updateMany(entity, data, sql.toString(), args());
        }
    }

    // ─── writes ───

    /** {@code create({ data })}: values coerced to the column types of {@code entity}. */
    public Map<String, Object> insert(Class<?> entity, Map<String, Object> data) {
        return db.insert(entity, coerce(entity, data));
    }

    /** {@code update({ where: { id }, data })} — 500 when the row is gone. */
    public Map<String, Object> update(Class<?> entity, String id, Map<String, Object> data) {
        Where w = where(entity).eq("id", id);
        return db.update(entity, coerce(entity, data), w.sql(), w.args());
    }

    /** {@code delete({ where: { id } })}: hard delete returning the row. */
    public Map<String, Object> delete(Class<?> entity, String id) {
        Where w = where(entity).eq("id", id);
        Map<String, Object> row = SqlRows.one(entity, jdbc.sql("DELETE FROM \"" + PosDb.table(entity)
                + "\" WHERE " + w.sql() + " RETURNING *").params(w.args()));
        if (row == null) {
            throw new IllegalStateException("An operation failed because it depends on one or more records that were"
                    + " required but not found. Record to delete does not exist.");
        }
        return row;
    }

    /** Soft delete as the tenancy extension does for {@code delete}: sets {@code deletedAt}. */
    public Map<String, Object> softDelete(Class<?> entity, String id, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>(extra);
        data.put("deletedAt", PosDb.raw(PosSql.NOW));
        return update(entity, id, data);
    }

    /** Prisma input coercion of JS values: ISO strings to dates, numbers to the column's numeric type. */
    static Map<String, Object> coerce(Class<?> entity, Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        data.forEach((k, v) -> {
            Object val = v;
            if (v instanceof String s && EntityPatch.hasField(entity, k)) {
                Class<?> type = fieldType(entity, k);
                if (type == Instant.class) {
                    val = jsDate(s);
                }
            }
            if (v instanceof Instant || v instanceof LocalDate || v instanceof PosDb.Raw || v instanceof PosDb.Json) {
                val = v;
            } else if ((v instanceof Map<?, ?> || v instanceof List<?>) && isJson(entity, k)) {
                val = PosDb.json(v);
            }
            out.put(k, val);
        });
        return out;
    }

    private static final Map<String, Class<?>> FIELD_TYPES = new java.util.concurrent.ConcurrentHashMap<>();

    static Class<?> fieldType(Class<?> entity, String name) {
        return FIELD_TYPES.computeIfAbsent(entity.getName() + "#" + name, k -> {
            for (Class<?> c = entity; c != null; c = c.getSuperclass()) {
                try {
                    return c.getDeclaredField(name).getType();
                } catch (NoSuchFieldException e) {
                    // walk up
                }
            }
            return Object.class;
        });
    }

    static boolean isJson(Class<?> entity, String name) {
        Class<?> t = fieldType(entity, name);
        return t != String.class && !Number.class.isAssignableFrom(t) && t != Boolean.class && t != Instant.class
                && t != Object.class && !t.isEnum();
    }

    // ─── includes ───

    /** {@code include: { key: true }} for a to-one relation (unfiltered, like Prisma includes). */
    public void one(Map<String, Object> row, String key, Class<?> entity, Object fk) {
        if (row == null) {
            return;
        }
        row.put(key, fk == null ? null : db.first(entity, "id = ?", fk));
    }

    /** {@code include: { key: { select: { a: true, ... } } }}. */
    public void onePick(Map<String, Object> row, String key, Class<?> entity, Object fk, String... cols) {
        if (row == null) {
            return;
        }
        Map<String, Object> rel = fk == null ? null : db.first(entity, "id = ?", fk);
        row.put(key, rel == null ? null : pick(rel, cols));
    }

    /** To-one include over a list, one query per distinct key. */
    public void oneAll(List<Map<String, Object>> rows, String key, Class<?> entity, String fkCol, String... pickCols) {
        LinkedHashSet<Object> ids = new LinkedHashSet<>();
        rows.forEach(r -> {
            if (r.get(fkCol) != null) {
                ids.add(r.get(fkCol));
            }
        });
        Map<Object, Map<String, Object>> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> rel : db.list(entity, "id IN (" + PosSql.marks(ids.size()) + ")", ids.toArray())) {
                byId.put(rel.get("id"), rel);
            }
        }
        for (Map<String, Object> r : rows) {
            Map<String, Object> rel = r.get(fkCol) == null ? null : byId.get(r.get(fkCol));
            r.put(key, rel == null ? null : pickCols.length == 0 ? new LinkedHashMap<>(rel) : pick(rel, pickCols));
        }
    }

    /**
     * To-many include ({@code include: { key: true }}): rows of {@code entity} whose {@code fkCol} is the id, unfiltered
     * and, without {@code orderBy}, in storage order like the Prisma relation query.
     */
    public List<Map<String, Object>> many(Class<?> entity, String fkCol, Object id, String extraWhere, String orderBy) {
        return db.list(entity, "\"" + fkCol + "\" = ?" + (extraWhere == null ? "" : " AND " + extraWhere)
                + (orderBy == null ? "" : " ORDER BY " + orderBy), id);
    }

    /** {@code _count: { select: { rel: true } }} — counts of child rows, unfiltered. */
    public void counts(List<Map<String, Object>> rows, String... tableFkPairs) {
        for (Map<String, Object> r : rows) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (int i = 0; i < tableFkPairs.length; i += 3) {
                String name = tableFkPairs[i];
                String table = tableFkPairs[i + 1];
                String fk = tableFkPairs[i + 2];
                c.put(name, jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE \"" + fk + "\" = ?")
                        .param(r.get("id")).query(Long.class).single());
            }
            r.put("_count", c);
        }
    }

    public static Map<String, Object> pick(Map<String, Object> row, String... cols) {
        if (row == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String c : cols) {
            if (row.containsKey(c)) {
                out.put(c, row.get(c));
            }
        }
        return out;
    }

    public static Map<String, Object> rows(List<Map<String, Object>> rows, long total) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        return out;
    }

    // ─── JS coercions ───

    /** {@code Math.min(Number(raw ?? def), max)}; {@code NaN} reaches Prisma as an invalid {@code take} (500). */
    public static int take(Object raw, int def, int max) {
        double n = raw == null ? def : jsNumber(raw);
        double v = Math.min(n, max);
        return prismaInt(v, "take");
    }

    /** {@code Number(raw ?? 0)} as a Prisma {@code skip}. */
    public static int skip(Object raw) {
        double n = raw == null ? 0 : jsNumber(raw);
        int v = prismaInt(n, "skip");
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

    /** JS {@code Number(v)}. */
    public static double jsNumber(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseLong(s.substring(2), 16);
            }
            if (s.matches("[+-]?(Infinity)")) {
                return s.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            if (!s.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) {
                return Double.NaN;
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** JS {@code new Date(s)} for a value Prisma will store; invalid → Prisma validation error (500). */
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
            throw new IllegalStateException("Invalid value for argument: Provided Date object is invalid. Expected Date.");
        }
    }

    public static boolean truthy(Object v) {
        return PosSql.truthy(v);
    }

    /** JS {@code a ?? b}. */
    public static Object nz(Object a, Object b) {
        return a != null ? a : b;
    }

    /** {@code dto[k] !== undefined ? dto[k] : fallback} (an explicit JSON null counts as defined). */
    public static Object def(Map<String, Object> dto, String k, Object fallback) {
        return dto.containsKey(k) ? dto.get(k) : fallback;
    }

    // ─── dates (hr-dates.ts) ───

    /** The organisation's IANA zone, or UTC when unset or invalid. */
    public ZoneId timezone(String orgId) {
        String tz = jdbc.sql("SELECT timezone FROM \"Organization\" WHERE id = ?").param(orgId).query(String.class)
                .optional().orElse(null);
        if (tz == null || tz.isEmpty()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /** The attendance day of an instant or a date input: that calendar day at UTC midnight. */
    public static Instant attendanceDay(Object value, ZoneId zone) {
        Instant at;
        if (value instanceof String s) {
            String v = s.trim();
            if (v.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            at = jsDate(v);
        } else {
            at = (Instant) value;
        }
        return at.atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public static Instant addDays(Instant day, long days) {
        return day.plus(java.time.Duration.ofDays(days));
    }

    /** Minutes since local midnight in the zone. */
    public static int minutesOfDay(Instant at, ZoneId zone) {
        var t = at.atZone(zone).toLocalTime();
        return t.getHour() * 60 + t.getMinute();
    }

    public static String escapeLike(String s) {
        return BaseCrudService.escapeLike(s);
    }
}
