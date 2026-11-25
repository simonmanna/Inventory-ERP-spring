package com.inventoryerp.plm.application;

import com.inventoryerp.core.application.ProductLifecycleGuard;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The PLM lifecycle policy behind {@link ProductLifecycleGuard} (ADR-S086 §6): purchasing, production, sales, the
 * till and replenishment ask it before they use a product, and it answers from the product's lifecycle state, the
 * organization's policy matrix ({@link PlmSettingsService}) and the authorized exceptions in force
 * ({@link PlmExceptionService}). Products not under PLM, and every product while PLM is switched off, are allowed.
 */
@Component
public class PlmLifecyclePolicy implements ProductLifecycleGuard {

    private final PlmDb db;
    private final PlmApp app;
    private final PlmSettingsService settings;
    private final PlmSupport plm;

    public PlmLifecyclePolicy(PlmDb db, PlmApp app, PlmSettingsService settings, PlmSupport plm) {
        this.db = db;
        this.app = app;
        this.settings = settings;
        this.plm = plm;
    }

    @Override
    public void requireAllowed(String activity, Collection<String> productIds) {
        List<Map<String, Object>> hits = blockedRows(activity, productIds);
        if (!hits.isEmpty()) {
            throw ApiException.badRequest(message(hits.getFirst(), activity));
        }
    }

    @Override
    public Set<String> blocked(String activity, Collection<String> productIds) {
        Set<String> out = new LinkedHashSet<>();
        blockedRows(activity, productIds).forEach(r -> out.add((String) r.get("productId")));
        return out;
    }

    /**
     * {@code Product P-100 is obsolete and cannot be purchased. Replacement: P-200.} Production keeps the ADR-S063
     * wording: {@code Product P-100 is design in its lifecycle and cannot be produced.}
     */
    static String message(Map<String, Object> row, String activity) {
        String lifecycleState = String.valueOf(row.get("lifecycleState"));
        if (PRODUCE.equals(activity)) {
            return "Product " + row.get("code") + " is " + lifecycleState.replace('_', ' ')
                    + " in its lifecycle and cannot be produced.";
        }
        String state = switch (lifecycleState) {
            case "concept" -> "a concept";
            case "design" -> "in design";
            case "prototype" -> "a prototype";
            case "phase_out" -> "being phased out";
            case "obsolete" -> "obsolete";
            default -> lifecycleState;
        };
        String verb = switch (activity) {
            case PURCHASE -> "purchased";
            case SELL -> "sold";
            case POS -> "sold at the point of sale";
            default -> "replenished";
        };
        Object replacement = row.get("replacementCode");
        return "Product " + row.get("code") + " is " + state + " and cannot be " + verb + "."
                + (replacement == null ? "" : " Replacement: " + replacement + ".");
    }

    /**
     * What the policy allows for one product, activity by activity: {@code {activity: {allowed, policy, exception}}}
     * where {@code policy} is the matrix cell for the product's state and {@code exception} the authorized exception
     * in force ({@code id}, {@code validUntil}) that lifts a block; null when the product is not under PLM.
     */
    Map<String, Object> effective(String productId, String lifecycleState) {
        if (lifecycleState == null) {
            return null;
        }
        Map<String, String> row = settings.policy().get(lifecycleState);
        Map<String, Map<String, Object>> active = new LinkedHashMap<>();
        for (Map<String, Object> x : SqlRows.list(db.jdbc().sql("SELECT id, activity, \"validUntil\" FROM"
                + " \"PlmLifecycleException\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"revokedAt\" IS NULL"
                + " AND \"validUntil\" >= ? ORDER BY \"validUntil\" DESC").params(PlmDb.org(), productId,
                        plm.orgToday()))) {
            active.putIfAbsent((String) x.get("activity"), Obj.of("id", x.get("id"), "validUntil",
                    x.get("validUntil")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String activity : PlmSettingsService.ACTIVITIES) {
            String cell = row == null ? "allow" : row.getOrDefault(activity, "allow");
            Map<String, Object> exception = "block".equals(cell) ? active.get(activity) : null;
            out.put(activity, Obj.of("allowed", "allow".equals(cell) || exception != null, "policy", cell,
                    "exception", exception));
        }
        return out;
    }

    private List<Map<String, Object>> blockedRows(String activity, Collection<String> productIds) {
        if (productIds == null) {
            return List.of();
        }
        List<String> wanted = productIds.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty() || !app.on()) {
            return List.of();
        }
        List<String> states = settings.policy().entrySet().stream()
                .filter(e -> "block".equals(e.getValue().get(activity))).map(Map.Entry::getKey).toList();
        if (states.isEmpty()) {
            return List.of();
        }
        LocalDate today = plm.orgToday();
        return SqlRows.list(db.jdbc().sql("SELECT i.\"productId\", i.\"lifecycleState\", p.code, r.code AS"
                + " \"replacementCode\" FROM \"PlmItem\" i JOIN \"Product\" p ON p.id = i.\"productId\" LEFT JOIN"
                + " \"Product\" r ON r.id = i.\"replacementProductId\" WHERE i.\"organizationId\" = ? AND i.\"productId\""
                + " = ANY(?::text[]) AND i.\"lifecycleState\" = ANY(?::text[]) AND NOT EXISTS (SELECT 1 FROM"
                + " \"PlmLifecycleException\" x WHERE x.\"organizationId\" = i.\"organizationId\" AND x.\"productId\" ="
                + " i.\"productId\" AND x.activity = ? AND x.\"revokedAt\" IS NULL AND x.\"validUntil\" >= ?)"
                + " ORDER BY p.code").params(PlmDb.org(), wanted.toArray(String[]::new), states.toArray(String[]::new),
                        activity, today));
    }
}
