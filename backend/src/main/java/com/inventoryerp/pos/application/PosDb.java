package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;

import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.core.domain.Product;
import com.inventoryerp.core.domain.ProductCategory;
import com.inventoryerp.core.domain.Tax;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.EntityJson;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Prisma-style {@code create} / {@code update} over JdbcClient: a map of column → value (in Prisma {@code data}
 * order), {@code id} / {@code createdAt} / {@code updatedAt} filled like Prisma, rows returned in Prisma shape.
 * Values: {@link Instant} → timestamp, {@link Json} → jsonb, collections → text[]; everything else is bound as is
 * (the datasource uses {@code stringtype=unspecified}, so strings cast to enums / uuid / json implicitly).
 */
@Component
public class PosDb {

    /** Marks a value for a {@code jsonb} column. */
    public record Json(Object value) {}

    /** Marks a raw SQL expression (e.g. {@code "quantity" + 1}). */
    public record Raw(String sql, Object... params) {}

    private final JdbcClient jdbc;

    /** Rows {@link #first} may memoize inside a {@link ReadMemo} scope; writes through this class clear the memo. */
    private static final Set<Class<?>> MASTER_DATA = Set.of(Product.class, Tax.class, ProductCategory.class,
            Partner.class);

    public PosDb(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public static Json json(Object v) {
        return new Json(v);
    }

    public static Raw raw(String sql, Object... params) {
        return new Raw(sql, params);
    }

    /** {@code prisma.<model>.create({ data })}. */
    public Map<String, Object> insert(Class<?> entity, Map<String, Object> data) {
        forget(entity);
        String table = table(entity);
        List<String> cols = EntityJson.columnNames(entity);
        Map<String, Object> d = new LinkedHashMap<>();
        if (cols.contains("id") && data.get("id") == null) {
            d.put("id", UUID.randomUUID().toString());
        }
        d.putAll(data);
        StringBuilder names = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : d.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            append(names, vals, e.getKey());
            bind(vals, params, e.getValue());
        }
        for (String ts : List.of("createdAt", "updatedAt")) {
            if (cols.contains(ts) && !d.containsKey(ts)) {
                append(names, vals, ts);
                vals.append(NOW);
            }
        }
        return SqlRows.one(entity, jdbc.sql("INSERT INTO \"" + table + "\" (" + names + ") VALUES (" + vals
                + ") RETURNING *").params(params));
    }

    /**
     * {@code UPDATE ... SET data WHERE where RETURNING *}; {@code updatedAt} is bumped when the model has it. Returns
     * the updated rows.
     */
    public List<Map<String, Object>> updateWhere(Class<?> entity, Map<String, Object> data, String where,
            Object... whereParams) {
        forget(entity);
        String table = table(entity);
        List<String> cols = EntityJson.columnNames(entity);
        StringBuilder set = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (set.length() > 0) {
                set.append(", ");
            }
            set.append('"').append(e.getKey()).append("\" = ");
            if (e.getValue() == null) {
                set.append("NULL");
            } else {
                bind(set, params, e.getValue());
            }
        }
        if (cols.contains("updatedAt") && !data.containsKey("updatedAt")) {
            if (set.length() > 0) {
                set.append(", ");
            }
            set.append("\"updatedAt\" = ").append(NOW);
        }
        if (set.length() == 0) {
            return SqlRows.list(entity, jdbc.sql("SELECT * FROM \"" + table + "\" WHERE " + where).params(whereParams));
        }
        params.addAll(List.of(whereParams));
        return SqlRows.list(entity, jdbc.sql("UPDATE \"" + table + "\" SET " + set + " WHERE " + where + " RETURNING *")
                .params(params));
    }

    /** {@code prisma.<model>.update({ where: { id }, data })} — P2025 (500) when nothing matched. */
    public Map<String, Object> update(Class<?> entity, Map<String, Object> data, String where, Object... whereParams) {
        List<Map<String, Object>> rows = updateWhere(entity, data, where, whereParams);
        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "An operation failed because it depends on one or more records that were required but not found."
                            + " No record was found for an update.");
        }
        return rows.getFirst();
    }

    /** {@code updateMany} — row count. */
    public int updateMany(Class<?> entity, Map<String, Object> data, String where, Object... whereParams) {
        return updateWhere(entity, data, where, whereParams).size();
    }

    /** {@code SELECT * ... LIMIT 1} in Prisma shape, or {@code null}. */
    public Map<String, Object> first(Class<?> entity, String where, Object... params) {
        if (MASTER_DATA.contains(entity) && ReadMemo.active()) {
            // Master data a sale only reads: found rows are memoized for the request, each caller gets its own copy.
            Map<String, Object> row = ReadMemo.get(() -> load(entity, where, params), "PosDb.first", entity, where,
                    Arrays.asList(params));
            return row == null ? null : new LinkedHashMap<>(row);
        }
        return load(entity, where, params);
    }

    private static void forget(Class<?> entity) {
        if (MASTER_DATA.contains(entity)) {
            ReadMemo.clear();
        }
    }

    private Map<String, Object> load(Class<?> entity, String where, Object... params) {
        return SqlRows.one(entity, jdbc.sql("SELECT * FROM \"" + table(entity) + "\" WHERE " + where + " LIMIT 1")
                .params(params));
    }

    /** {@code SELECT * ...} rows in Prisma shape. */
    public List<Map<String, Object>> list(Class<?> entity, String whereAndOrder, Object... params) {
        return SqlRows.list(entity, jdbc.sql("SELECT * FROM \"" + table(entity) + "\" WHERE " + whereAndOrder)
                .params(params));
    }

    public long count(Class<?> entity, String where, Object... params) {
        return jdbc.sql("SELECT count(*) FROM \"" + table(entity) + "\" WHERE " + where).params(params)
                .query(Long.class).single();
    }

    public static String table(Class<?> entity) {
        jakarta.persistence.Table t = entity.getAnnotation(jakarta.persistence.Table.class);
        return t == null ? entity.getSimpleName() : t.name();
    }

    private static void append(StringBuilder names, StringBuilder vals, String col) {
        if (names.length() > 0) {
            names.append(", ");
            vals.append(", ");
        }
        names.append('"').append(col).append('"');
    }

    private static void bind(StringBuilder sql, List<Object> params, Object v) {
        if (v instanceof Raw r) {
            sql.append(r.sql());
            params.addAll(List.of(r.params()));
            return;
        }
        if (v instanceof Json j) {
            sql.append("?::jsonb");
            params.add(j.value() == null ? null : EntityJson.toJson(j.value()));
            return;
        }
        sql.append('?');
        params.add(param(v));
    }

    /** JDBC parameter for a Prisma value. */
    public static Object param(Object v) {
        if (v instanceof Instant i) {
            return SqlRows.ts(i);
        }
        if (v instanceof LocalDate d) {
            return java.sql.Date.valueOf(d);
        }
        if (v instanceof Collection<?> c) {
            return c.stream().map(o -> o == null ? null : o.toString()).toArray(String[]::new);
        }
        if (v instanceof Double d) {
            return PosSql.decOf(d);
        }
        return v;
    }
}
