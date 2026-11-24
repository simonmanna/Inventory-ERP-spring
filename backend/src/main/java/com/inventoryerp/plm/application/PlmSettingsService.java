package com.inventoryerp.plm.application;

import com.inventoryerp.core.application.ProductLifecycleGuard;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.plm.application.PlmCommands.SaveSettings;
import com.inventoryerp.plm.domain.PlmSettings;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The PLM application's settings (ADR-S086 §6): the lifecycle policy — per lifecycle state, whether purchasing,
 * selling and replenishing a product is allowed or blocked — the default cost policy of new BOM changes and whether
 * BOM / routing changes ask for a first-article inspection by default. An organization without a settings row runs on
 * the defaults.
 */
@Service
public class PlmSettingsService {

    public static final List<String> STATES = List.of("concept", "design", "prototype", "released", "phase_out",
            "obsolete");

    public static final List<String> ACTIVITIES = List.of(ProductLifecycleGuard.PURCHASE, ProductLifecycleGuard.SELL,
            ProductLifecycleGuard.REPLENISH);

    /**
     * Defaults: an obsolete product is neither bought, sold nor replenished; a product being phased out is still sold
     * (its stock runs down) but no longer bought or replenished; everything else is allowed.
     */
    static final Map<String, Map<String, String>> DEFAULTS;

    static {
        Map<String, Map<String, String>> d = new LinkedHashMap<>();
        for (String s : STATES) {
            d.put(s, row("allow", "allow", "allow"));
        }
        d.put("phase_out", row("block", "allow", "block"));
        d.put("obsolete", row("block", "block", "block"));
        DEFAULTS = java.util.Collections.unmodifiableMap(d);
    }

    private static Map<String, String> row(String purchase, String sell, String replenish) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put(ProductLifecycleGuard.PURCHASE, purchase);
        r.put(ProductLifecycleGuard.SELL, sell);
        r.put(ProductLifecycleGuard.REPLENISH, replenish);
        return r;
    }

    private final PlmDb db;

    public PlmSettingsService(PlmDb db) {
        this.db = db;
    }

    /** The effective settings, the defaults and the vocabulary for the settings screen. */
    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        Map<String, Object> row = row();
        return Obj.of("lifecyclePolicy", policy(row), "defaults", DEFAULTS, "defaultCostPolicy",
                row == null ? "none" : row.get("defaultCostPolicy"), "firstArticleDefault",
                row != null && Boolean.TRUE.equals(row.get("firstArticleDefault")), "states", STATES, "activities",
                ACTIVITIES, "updatedAt", row == null ? null : row.get("updatedAt"));
    }

    /** Save settings; a released product stays allowed everywhere. */
    @Transactional
    public Map<String, Object> update(SaveSettings dto) {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)::text").param("plm-settings:" + PlmDb.org())
                .query().listOfRows();
        Map<String, Object> row = row();
        Map<String, Map<String, String>> policy = policy(row);
        if (dto.lifecyclePolicy() != null) {
            for (Map.Entry<String, Map<String, String>> e : dto.lifecyclePolicy().entrySet()) {
                String state = e.getKey();
                if (!STATES.contains(state)) {
                    throw ApiException.badRequest("lifecyclePolicy." + state + " is not a lifecycle state ("
                            + String.join(", ", STATES) + ").");
                }
                if (e.getValue() == null) {
                    continue;
                }
                for (Map.Entry<String, String> a : e.getValue().entrySet()) {
                    String key = "lifecyclePolicy." + state + "." + a.getKey();
                    if (!ACTIVITIES.contains(a.getKey())) {
                        throw ApiException.badRequest(key + " is not an activity (" + String.join(", ", ACTIVITIES)
                                + ").");
                    }
                    if (!"allow".equals(a.getValue()) && !"block".equals(a.getValue())) {
                        throw ApiException.badRequest(key + " must be allow or block.");
                    }
                    if ("released".equals(state) && "block".equals(a.getValue())) {
                        throw ApiException.badRequest("A released product is always allowed to be purchased, sold and"
                                + " replenished.");
                    }
                    policy.get(state).put(a.getKey(), a.getValue());
                }
            }
        }
        Map<String, Object> data = Obj.of("lifecyclePolicy", new PlmDb.Json(policy), "updatedBy",
                TenantContext.userId());
        if (dto.defaultCostPolicy() != null) {
            data.put("defaultCostPolicy", dto.defaultCostPolicy());
        }
        if (dto.firstArticleDefault() != null) {
            data.put("firstArticleDefault", dto.firstArticleDefault());
        }
        if (row == null) {
            db.insert(PlmSettings.class, data);
        } else {
            db.update(PlmSettings.class, (String) row.get("id"), data);
        }
        return get();
    }

    /** The effective lifecycle policy, {@code {state: {activity: allow | block}}}. */
    Map<String, Map<String, String>> policy() {
        return policy(row());
    }

    /** Default cost policy of new BOM changes. */
    String defaultCostPolicy() {
        Map<String, Object> row = row();
        return row == null ? "none" : (String) row.get("defaultCostPolicy");
    }

    /** Whether new BOM / routing changes ask for a first-article inspection. */
    boolean firstArticleDefault() {
        Map<String, Object> row = row();
        return row != null && Boolean.TRUE.equals(row.get("firstArticleDefault"));
    }

    private Map<String, Object> row() {
        List<Map<String, Object>> rows = db.list(PlmSettings.class, " LIMIT 1");
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Map<String, Map<String, String>> policy(Map<String, Object> row) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        DEFAULTS.forEach((state, acts) -> out.put(state, new LinkedHashMap<>(acts)));
        Map<String, Object> saved = row == null ? Map.of() : PlmSupport.object(row.get("lifecyclePolicy"));
        for (String state : STATES) {
            if (saved.get(state) instanceof Map<?, ?> acts) {
                for (String a : ACTIVITIES) {
                    Object v = acts.get(a);
                    if (("allow".equals(v) || "block".equals(v)) && !("released".equals(state) && "block".equals(v))) {
                        out.get(state).put(a, (String) v);
                    }
                }
            }
        }
        return out;
    }
}
