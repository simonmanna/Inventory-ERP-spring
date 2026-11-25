package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.CompleteOrder;
import com.inventoryerp.manufacturing.application.ProductionService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Shared fixtures of the PLM integration tests (the manufacturing ones, kept separate so PLM tests do not reach into
 * manufacturing's test package): locations, products, stock, BOMs, GL balances.
 */
abstract class PlmTestSupport extends ApiTestSupport {

    @Autowired
    ProductionService production;

    String adminId;
    String bakery;
    String warehouse;
    String wipAccount;
    String scrapAccount;
    String overheadAccount;

    @BeforeAll
    void fixtures() {
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        // The production rules a real org gets from OrganizationsService (the Nest spec seeds them explicitly).
        ok(201, "POST", "/inventory/posting-rules/restore-defaults", null);
        bakery = location("production");
        warehouse = location("warehouse");
        // Orders without an explicit/BOM output location land in the POS warehouse; make it resolvable.
        pinPosWarehouse();
        wipAccount = mapped("wip");
        scrapAccount = mapped("stock_adjustment_expense");
        overheadAccount = mapped("overhead_absorbed");
    }

    private String approverToken;
    private String approverId;

    /**
     * A second DEMO administrator: since 2026-11-24 (ML-4, {@code plm.allowSelfApproval} off by default) the person
     * who raised a change cannot approve it.
     */
    String approver() {
        if (approverToken == null) {
            String role = jdbc.sql("SELECT r.id FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id"
                    + " JOIN \"User\" u ON u.id = ur.\"B\" WHERE u.email = 'admin@demo.test' LIMIT 1")
                    .query(String.class).single();
            String email = "plm-approver-" + suffix() + "@demo.test";
            approverId = createUser(demoOrg, email, "Approver@12345", role);
            approverToken = login("DEMO", email, "Approver@12345");
        }
        return approverToken;
    }

    /** The user id of {@link #approver()}. */
    String approverId() {
        approver();
        return approverId;
    }

    /** Call as the second administrator; must answer {@code status}. */
    JsonNode okAs(String token, int status, String method, String path, Object body) {
        Res r = call(method, path, body, token);
        assertThat(r.status()).as(method + " " + path + " → " + r.raw().body()).isEqualTo(status);
        return r.body();
    }

    String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String mapped(String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ?"
                + " AND \"deletedAt\" IS NULL").params(demoOrg, key).query(String.class).single();
    }

    String location(String type) {
        return ok(201, "POST", "/inventory/locations", Map.of("code", "MFG-" + suffix(), "name", "Mfg " + suffix(),
                "type", type)).get("id").asString();
    }

    /** The ECO's "applied" activity entry (newest first; the cost step logs its own entry after it), or null. */
    static JsonNode appliedEntry(JsonNode eco) {
        for (JsonNode a : eco.get("activity")) {
            if ("applied".equals(a.get("kind").asString())) {
                return a;
            }
        }
        return null;
    }

    String product(String code) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"costingMethod\","
                        + " \"costPrice\", \"salesPrice\", \"manufacturingRole\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, 'stockable', 'AVCO', 0, 15, 'finished', now())")
                .params(id, demoOrg, code + "-" + suffix(), code + " " + suffix()).update();
        return id;
    }

    void stockIn(String loc, String productId, int qty, int unitCost) {
        ok(200, "POST", "/inventory/direct-stock/in", Map.of("locationId", loc, "responsibleById", adminId,
                "approvedById", adminId, "items", List.of(Map.of("productId", productId, "quantity", qty,
                        "unitCost", unitCost))));
    }

    BigDecimal onHand(String productId, String loc) {
        return jdbc.sql("SELECT quantity FROM \"StockItem\" WHERE \"productId\" = ? AND \"locationId\" = ?")
                .params(productId, loc).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    BigDecimal balance(String accountId) {
        return jdbc.sql("SELECT coalesce(sum(debit), 0) - coalesce(sum(credit), 0) FROM \"JournalLine\""
                + " WHERE \"organizationId\" = ? AND \"accountId\" = ?").params(demoOrg, accountId)
                .query(BigDecimal.class).single();
    }

    /** Draft + activate a one-line BOM. */
    String bom(String output, String component, int outputQty, int qty, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Recipe " + suffix(), "outputProductId", output,
                "outputQuantity", outputQty, "lines", List.of(Map.of("componentProductId", component, "quantity", qty))));
        body.putAll(extra);
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", body);
        assertThat(draft.get("status").asString()).isEqualTo("draft");
        assertThat(draft.get("code").asString()).startsWith("BOM-");
        JsonNode active = ok(201, "POST", "/manufacturing/boms/" + draft.get("id").asString() + "/activate", null);
        assertThat(active.get("status").asString()).isEqualTo("active");
        return draft.get("id").asString();
    }

    /** Create + confirm + start an order for one run. */
    JsonNode started(String bomId) {
        JsonNode order = ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse));
        assertThat(order.get("orderCode").asString()).startsWith("MO-");
        String id = order.get("id").asString();
        assertThat(ok(201, "POST", "/manufacturing/production-orders/" + id + "/confirm", null).get("status")
                .asString()).isEqualTo("confirmed");
        return ok(201, "POST", "/manufacturing/production-orders/" + id + "/start", Map.of());
    }

}
