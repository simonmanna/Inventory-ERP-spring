package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.settings.SettingResolverService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review 2026-11-17 (ACR-19): with "Production Needs Materials On Hand" on, a production order cannot consume a
 * material that is not there (it would be issued at a stale cost, and the later receipt's correction would land in cost
 * of sales instead of the product). Off (the default) keeps the inventory settings in charge.
 */
class ProductionMaterialsOnHandIT extends MfgTestSupport {

    @Autowired
    SettingResolverService settingResolver;

    String order(String bomId) {
        return ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1, "locationId",
                bakery, "outputLocationId", warehouse)).get("id").asString();
    }

    Res start(String orderId) {
        ok(201, "POST", "/manufacturing/production-orders/" + orderId + "/confirm", null);
        return as("POST", "/manufacturing/production-orders/" + orderId + "/start", Map.of());
    }

    @Test
    @DisplayName("on: 4 needed, 2 on hand → refused and nothing moves; off: the order starts")
    void materialsMustBeOnHand() {
        String flour = product("FLOUR-OH");
        String cake = product("CAKE-OH");
        stockIn(bakery, flour, 2, 100);
        String bomId = bom(cake, flour, 10, 4, Map.of());
        String setting = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, 'costing.production.requireMaterialsOnHand', 'true'::jsonb, 'organization', now())")
                .params(setting, demoOrg).update();
        settingResolver.invalidate(demoOrg);
        try {
            String strict = order(bomId);
            Res refused = start(strict);
            assertThat(refused.status()).as(refused.raw().body()).isEqualTo(400);
            assertThat(refused.body().get("message").asString()).startsWith("Insufficient stock for ")
                    .endsWith(": on hand 2, requested 4.");
            assertThat(onHand(flour, bakery)).isEqualByComparingTo("2");
        } finally {
            jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(setting).update();
            settingResolver.invalidate(demoOrg);
        }
        Res started = start(order(bomId));
        assertThat(started.status()).as(started.raw().body()).isEqualTo(201);
    }
}
