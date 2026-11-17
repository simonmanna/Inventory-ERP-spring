package com.inventoryerp.lossprevention.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpCommands.SettingsInput;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settling times, retention and back-fill per organization, with an optional override per branch. A window is
 * reconciled only after it has been quiet for its engine's settling time, so late events (an offline till) are in.
 */
@Service
public class LpSettingsService {

    public static final String CASHIER_SETTLE = "cashierSettleMinutes";
    public static final String BAR_SETTLE = "barSettleMinutes";
    public static final String DAILY_SETTLE = "dailySettleMinutes";
    public static final String RETENTION_MONTHS = "retentionMonths";
    public static final String BACKFILL_DAYS = "backfillDays";

    /** Minutes after the last change before an engine runs; minutes after the day ends before it is final. */
    public static final Map<String, Integer> DEFAULTS = defaults();

    private static Map<String, Integer> defaults() {
        Map<String, Integer> d = new LinkedHashMap<>();
        d.put(CASHIER_SETTLE, 10);
        d.put(BAR_SETTLE, 0);
        d.put(DAILY_SETTLE, 120);
        d.put(RETENTION_MONTHS, 24);
        d.put(BACKFILL_DAYS, 30);
        return java.util.Collections.unmodifiableMap(d);
    }

    private final JdbcClient jdbc;
    private final BranchScopeService branches;
    private final LpScope scope;

    public LpSettingsService(JdbcClient jdbc, BranchScopeService branches, LpScope scope) {
        this.jdbc = jdbc;
        this.branches = branches;
        this.scope = scope;
    }

    private Map<String, Map<String, Object>> stored() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"branchId\", \"values\" FROM \"LpSetting\" WHERE"
                + " \"organizationId\" = ?").param(LpScope.org()))) {
            out.put((String) r.get("branchId"), LpJson.map(r.get("values")));
        }
        return out;
    }

    /** The values that apply to a branch ({@code ''} or {@code null}: the organization's). */
    @Transactional(readOnly = true)
    public Map<String, Integer> effective(String branchId) {
        Map<String, Map<String, Object>> stored = stored();
        Map<String, Integer> out = new LinkedHashMap<>(DEFAULTS);
        for (String key : new String[] {"", branchId == null ? "" : branchId}) {
            stored.getOrDefault(key, Map.of()).forEach((k, v) -> {
                BigDecimal n = LpNumbers.decimal(v);
                if (n != null && DEFAULTS.containsKey(k)) {
                    out.put(k, n.intValue());
                }
            });
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        Map<String, Map<String, Object>> stored = new LinkedHashMap<>(stored());
        Map<String, Object> organization = stored.remove("");
        return Obj.of("defaults", DEFAULTS, "organization", organization == null ? Map.of() : organization,
                "branches", stored, "effective", effective(null));
    }

    /** Replaces the values of the organization, or of one branch when {@code branchId} is given. */
    @Transactional
    public Map<String, Object> save(SettingsInput dto) {
        String branch = LpScope.has(dto.branchId()) ? dto.branchId() : "";
        branches.assertBranchExists(branch);
        Map<String, Object> values = new LinkedHashMap<>();
        if (dto.values() != null) {
            for (Map.Entry<String, Object> e : dto.values().entrySet()) {
                if (!DEFAULTS.containsKey(e.getKey())) {
                    throw ApiException.badRequest("Unknown setting '" + e.getKey() + "'");
                }
                BigDecimal n = LpNumbers.decimal(e.getValue());
                if (n == null || n.signum() < 0 || n.stripTrailingZeros().scale() > 0) {
                    throw ApiException.badRequest("Setting '" + e.getKey() + "' must be a whole number of 0 or more");
                }
                values.put(e.getKey(), n.intValue());
            }
        }
        jdbc.sql("INSERT INTO \"LpSetting\" (\"organizationId\", \"branchId\", \"values\", \"updatedBy\") VALUES (?, ?,"
                + " ?::jsonb, ?) ON CONFLICT (\"organizationId\", \"branchId\") DO UPDATE SET \"values\" ="
                + " EXCLUDED.\"values\", \"updatedBy\" = EXCLUDED.\"updatedBy\", \"updatedAt\" = CURRENT_TIMESTAMP")
                .params(LpScope.org(), branch, scope.json(values), TenantContext.userId()).update();
        return get();
    }
}
