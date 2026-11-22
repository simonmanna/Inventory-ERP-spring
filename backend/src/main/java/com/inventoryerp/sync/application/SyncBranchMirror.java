package com.inventoryerp.sync.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.EntityJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-S083 O7: HQ's pull scopes as a branch node last received them ({@code SyncBranchMirror}). A till's pull at the
 * branch is answered from here: full-set scopes in full, delta scopes by the time the branch stored each row (with
 * the same 5-second overlap as HQ), soft-deleted rows only to protocol-2 clients. Messages are not mirrored.
 */
@Component
public class SyncBranchMirror {

    /** Scopes HQ sends in full on every pull: the branch replaces them, rows HQ no longer sends are dropped. */
    static final Set<String> FULL_SETS = Set.of("paymentMethods", "ledgerAccounts", "expenseCategories", "stockLocations",
            "openPurchaseOrders", "transfers", "uomConversions", "priceLists", "paymentTerms", "customerCredit",
            "openSalesOrders", "openInvoices");

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final SyncBranch branch;

    public SyncBranchMirror(JdbcClient jdbc, JsonMapper mapper, SyncBranch branch) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.branch = branch;
    }

    /** True on a branch node: pulls and snapshots are served from the mirror. */
    public boolean active() {
        return branch.enabled();
    }

    /** Every scope the branch mirrors (all of HQ's except messages). */
    public static List<String> mirroredScopes() {
        List<String> all = new ArrayList<>();
        for (String s : SyncPullService.SCOPES) {
            if (!"messages".equals(s)) {
                all.add(s);
            }
        }
        all.addAll(SyncPullService.EXTENDED_SCOPES);
        return all;
    }

    /** Stores one scope of an HQ pull (inside the caller's transaction). */
    void store(String org, String scope, Object rowsIn) {
        if (!(rowsIn instanceof List<?> rows)) {
            return;
        }
        boolean full = FULL_SETS.contains(scope);
        List<String> kept = new ArrayList<>();
        int ord = 0;
        for (Object o : rows) {
            if (!(o instanceof Map<?, ?> row)) {
                continue;
            }
            String id = row.get("id") instanceof String s && !s.isEmpty() ? s : digest(EntityJson.toJson(row));
            boolean deleted = row.get("deletedAt") != null;
            jdbc.sql("INSERT INTO \"SyncBranchMirror\" (\"organizationId\", scope, \"rowId\", \"row\", deleted, ord,"
                    + " \"storedAt\") VALUES (?, ?, ?, ?::jsonb, ?, ?, now()) ON CONFLICT (\"organizationId\", scope,"
                    + " \"rowId\") DO UPDATE SET \"row\" = EXCLUDED.\"row\", deleted = EXCLUDED.deleted, ord = EXCLUDED.ord,"
                    + " \"storedAt\" = now() WHERE \"SyncBranchMirror\".\"row\" IS DISTINCT FROM EXCLUDED.\"row\""
                    + " OR \"SyncBranchMirror\".ord <> EXCLUDED.ord")
                    .params(org, scope, id, EntityJson.toJson(row), deleted, full ? ord++ : 0).update();
            kept.add(id);
        }
        if (full) {
            if (kept.isEmpty()) {
                jdbc.sql("DELETE FROM \"SyncBranchMirror\" WHERE \"organizationId\" = ? AND scope = ?").params(org, scope)
                        .update();
            } else {
                jdbc.sql("DELETE FROM \"SyncBranchMirror\" WHERE \"organizationId\" = ? AND scope = ? AND NOT (\"rowId\""
                        + " = ANY (?))").params(org, scope, kept.toArray(new String[0])).update();
            }
        }
    }

    /** The rows a till's pull gets for {@code scope}: full sets whole, deltas stored after {@code since}. */
    public List<Map<String, Object>> read(String scope, Instant since, boolean tombstones) {
        String org = branch.organizationId();
        boolean full = FULL_SETS.contains(scope);
        StringBuilder sql = new StringBuilder("SELECT \"row\"::text AS r FROM \"SyncBranchMirror\" WHERE"
                + " \"organizationId\" = ? AND scope = ?");
        List<Object> params = new ArrayList<>(List.of(org, scope));
        if (!full && since != null) {
            sql.append(" AND \"storedAt\" > ?");
            params.add(SqlRows.ts(since));
        }
        if (full || since == null || !tombstones) {
            sql.append(" AND NOT deleted");
        }
        sql.append(full ? " ORDER BY ord, \"rowId\"" : " ORDER BY \"storedAt\", \"rowId\"");
        List<Map<String, Object>> out = new ArrayList<>();
        for (String text : jdbc.sql(sql.toString()).params(params).query(String.class).list()) {
            out.add(parse(text));
        }
        return out;
    }

    /** HQ's last pull metadata ({@code currency}, {@code offlinePolicy}, {@code license}, HQ's {@code serverId}). */
    public Object meta(String key) {
        String text = jdbc.sql("SELECT meta::text FROM \"SyncBranchState\" WHERE \"organizationId\" = ?")
                .param(branch.organizationId()).query(String.class).optional().orElse(null);
        return text == null ? null : parse(text).get(key);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> parse(String text) {
        Object v = mapper.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(text);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    static String digest(String text) {
        try {
            return "h:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
