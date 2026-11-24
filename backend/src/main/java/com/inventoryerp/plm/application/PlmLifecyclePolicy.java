package com.inventoryerp.plm.application;

import com.inventoryerp.core.application.ProductLifecycleGuard;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The PLM lifecycle policy behind {@link ProductLifecycleGuard} (ADR-S086 §6): purchasing, sales and replenishment
 * ask it before they use a product, and it answers from the product's lifecycle state and the organization's policy
 * matrix ({@link PlmSettingsService}). Products not under PLM, and every product while PLM is switched off, are allowed.
 */
@Component
public class PlmLifecyclePolicy implements ProductLifecycleGuard {

    private final PlmDb db;
    private final PlmApp app;
    private final PlmSettingsService settings;

    public PlmLifecyclePolicy(PlmDb db, PlmApp app, PlmSettingsService settings) {
        this.db = db;
        this.app = app;
        this.settings = settings;
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

    /** {@code Product P-100 is obsolete and cannot be purchased. Replacement: P-200.} */
    static String message(Map<String, Object> row, String activity) {
        String state = switch (String.valueOf(row.get("lifecycleState"))) {
            case "concept" -> "a concept";
            case "design" -> "in design";
            case "prototype" -> "a prototype";
            case "phase_out" -> "being phased out";
            case "obsolete" -> "obsolete";
            default -> String.valueOf(row.get("lifecycleState"));
        };
        String verb = switch (activity) {
            case PURCHASE -> "purchased";
            case SELL -> "sold";
            default -> "replenished";
        };
        Object replacement = row.get("replacementCode");
        return "Product " + row.get("code") + " is " + state + " and cannot be " + verb + "."
                + (replacement == null ? "" : " Replacement: " + replacement + ".");
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
        return SqlRows.list(db.jdbc().sql("SELECT i.\"productId\", i.\"lifecycleState\", p.code, r.code AS"
                + " \"replacementCode\" FROM \"PlmItem\" i JOIN \"Product\" p ON p.id = i.\"productId\" LEFT JOIN"
                + " \"Product\" r ON r.id = i.\"replacementProductId\" WHERE i.\"organizationId\" = ? AND i.\"productId\""
                + " = ANY(?::text[]) AND i.\"lifecycleState\" = ANY(?::text[]) ORDER BY p.code")
                .params(PlmDb.org(), wanted.toArray(String[]::new), states.toArray(String[]::new)));
    }
}
