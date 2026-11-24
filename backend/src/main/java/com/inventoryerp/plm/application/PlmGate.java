package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.manufacturing.spi.ProductionLifecycleGate;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Lifecycle gates on production (ADR-S063), plugged into manufacturing through {@link ProductionLifecycleGate}
 * (ADR-S086). Only products registered in PLM are gated, and only while the PLM application is on for the
 * organization, so organizations that do not use PLM keep the exact ADR-S006 behaviour:
 * <ul>
 *   <li>a production order cannot be created for a product in {@code concept}, {@code design} or
 *       {@code obsolete} (prototype runs are allowed);</li>
 *   <li>a BOM cannot be activated for an obsolete product, or with an obsolete component.</li>
 * </ul>
 */
@Component
public class PlmGate implements ProductionLifecycleGate {

    private static final Set<String> NOT_PRODUCIBLE = Set.of("concept", "design", "obsolete");

    private final PlmDb db;
    private final PlmApp app;

    public PlmGate(PlmDb db, PlmApp app) {
        this.db = db;
        this.app = app;
    }

    /** Whether the PLM application is on for the current organization (its gates apply only then). */
    boolean active() {
        return app.on();
    }

    /** The product's lifecycle state, or null when it is not under PLM. */
    String state(String productId) {
        if (productId == null) {
            return null;
        }
        return db.jdbc().sql("SELECT \"lifecycleState\" FROM \"PlmItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ?").params(PlmDb.org(), productId).query(String.class).optional().orElse(null);
    }

    /** Production orders: refused for concept / design / obsolete products. */
    @Override
    public void requireProducible(String productId) {
        if (!active()) {
            return;
        }
        String state = state(productId);
        if (state != null && NOT_PRODUCIBLE.contains(state)) {
            throw ApiException.badRequest("Product " + code(productId) + " is " + state.replace('_', ' ')
                    + " in its lifecycle and cannot be produced.");
        }
    }

    /** BOM activation: refused for an obsolete output or with obsolete components. */
    @Override
    @SuppressWarnings("unchecked")
    public void requireActivatable(Map<String, Object> bom) {
        if (!active()) {
            return;
        }
        String output = str(bom, "outputProductId");
        if ("obsolete".equals(state(output))) {
            throw ApiException.badRequest("Product " + code(output) + " is obsolete; its BOMs cannot be activated.");
        }
        List<String> components = new ArrayList<>();
        Object lines = bom.get("lines");
        if (lines instanceof List<?> l) {
            for (Object o : l) {
                components.add(str((Map<String, Object>) o, "componentProductId"));
            }
        }
        if (components.isEmpty()) {
            return;
        }
        List<Map<String, Object>> obsolete = SqlRows.list(db.jdbc().sql("SELECT p.code FROM \"PlmItem\" i JOIN"
                + " \"Product\" p ON p.id = i.\"productId\" WHERE i.\"organizationId\" = ? AND i.\"lifecycleState\""
                + " = 'obsolete' AND i.\"productId\" = ANY(?::text[]) ORDER BY p.code")
                .params(PlmDb.org(), components.toArray(String[]::new)));
        if (!obsolete.isEmpty()) {
            throw ApiException.badRequest("BOM " + bom.get("code") + " uses obsolete component(s): "
                    + String.join(", ", obsolete.stream().map(r -> str(r, "code")).toList()) + ".");
        }
    }

    private String code(String productId) {
        return db.jdbc().sql("SELECT code FROM \"Product\" WHERE id = ?").param(productId).query(String.class)
                .optional().orElse(productId);
    }
}
