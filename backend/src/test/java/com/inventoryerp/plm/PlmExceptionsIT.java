package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.common.OfflineReplay;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosOrdersService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Owner decisions of 2026-11-24 on the PLM application (ADR-S086 §5–§7, §12): the shipped lifecycle matrix, authorized
 * lifecycle exceptions, the till and hand-entered invoices under the policy (sales already rung up never interrupted),
 * mandatory first-article inspection, valuation figures only with {@code inventory_valuation:read}.
 */
class PlmExceptionsIT extends PlmTestSupport {

    static final String PLM = "/manufacturing/plm";

    @Autowired
    PosOrdersService posOrders;

    /** Every test starts and ends on the shipped defaults (no stored settings row for DEMO). */
    @BeforeEach
    void shippedDefaultsBefore() {
        jdbc.sql("DELETE FROM \"PlmSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
    }

    @AfterEach
    void shippedDefaultsAfter() {
        jdbc.sql("DELETE FROM \"PlmSettings\" WHERE \"organizationId\" = ?").param(demoOrg).update();
    }

    String msg(Res r) {
        return r.body().get("message").asString();
    }

    String register(String productId, String state) {
        return ok(201, "POST", PLM + "/items", Map.of("productId", productId, "lifecycleState", state))
                .get("id").asString();
    }

    String code(String productId) {
        return jdbc.sql("SELECT code FROM \"Product\" WHERE id = ?").param(productId).query(String.class).single();
    }

    String partner(String flag) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"" + flag + "\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "PLX-" + suffix(), "PLM exc partner "
                        + suffix()).update();
        return id;
    }

    /** A product under PLM taken to {@code obsolete} (registered in design, then retired). */
    String obsolete(String code) {
        String p = product(code);
        String item = register(p, "design");
        ok(201, "POST", PLM + "/items/" + item + "/transition", Map.of("toState", "obsolete"));
        return p;
    }

    static Map<String, Object> line(String productId, int qty) {
        return Map.of("componentProductId", productId, "quantity", qty);
    }

    static Map<String, Object> tillLine(String productId, int qty) {
        return Map.of("productId", productId, "description", "Item", "quantity", qty, "unitPrice", 15);
    }

    JsonNode activeBom(String output, List<Map<String, Object>> lines) {
        JsonNode draft = ok(201, "POST", "/manufacturing/boms", Map.of("name", "Recipe " + suffix(),
                "outputProductId", output, "outputQuantity", 1, "lines", lines));
        return ok(201, "POST", "/manufacturing/boms/" + draft.get("id").asString() + "/activate", null);
    }

    JsonNode approve(String ecoId) {
        JsonNode detail = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        String review = null;
        for (JsonNode s : detail.get("stages")) {
            if (s.get("requiresApproval").asBoolean()) {
                review = s.get("id").asString();
                break;
            }
        }
        ok(201, "POST", PLM + "/changes/" + ecoId + "/stage", Map.of("stageId", review));
        return okAs(approver(), 201, "POST", PLM + "/changes/" + ecoId + "/decisions", Map.of("decision", "approved"));
    }

    Map<String, Object> purchaseOrder(String supplier, String productId) {
        return Map.of("partnerId", supplier, "warehouseId", warehouse, "paymentType", "credit", "currencyCode", "UGX",
                "lines", List.of(Map.of("productId", productId, "description", "Part", "quantity", 2, "unitPrice", 10)));
    }

    String today() {
        return LocalDate.now(ZoneId.of("Africa/Kampala")).toString();
    }

    /** A DEMO user who may read PLM but not inventory valuation. */
    static JsonNode byId(JsonNode rows, String id) {
        for (JsonNode r : rows) {
            if (id.equals(r.get("id").asString())) {
                return r;
            }
        }
        return null;
    }

    String plmReader(String... extra) {
        String role = UUID.randomUUID().toString();
        List<String> perms = new ArrayList<>(List.of("plm:read"));
        perms.addAll(Arrays.asList(extra));
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "PLM reader " + role,
                        perms.toArray(String[]::new)).update();
        String email = "plm-reader-" + suffix() + "@demo.test";
        createUser(demoOrg, email, "Reader@12345", role);
        return login("DEMO", email, "Reader@12345");
    }

    @Test
    @DisplayName("shipped matrix: not released → no sale (till included), phase-out → sells stock but is not procured,"
            + " obsolete → nothing; cost policy propose, first article optional")
    void shippedDefaults() {
        JsonNode s = ok(200, "GET", PLM + "/settings", null);
        assertThat(s.get("activities")).extracting(n -> n.asString())
                .containsExactly("purchase", "produce", "sell", "pos", "replenish");
        assertThat(s.get("defaultCostPolicy").asString()).isEqualTo("propose");
        assertThat(s.get("firstArticleDefault").asBoolean()).isFalse();
        assertThat(s.get("firstArticleRequired").asBoolean()).isFalse();
        Map<String, String> expected = new HashMap<>();
        for (String state : List.of("concept", "design")) {
            expected.put(state, "allow,block,block,block,allow");
        }
        expected.put("prototype", "allow,allow,block,block,allow");
        expected.put("released", "allow,allow,allow,allow,allow");
        expected.put("phase_out", "block,block,allow,allow,block");
        expected.put("obsolete", "block,block,block,block,block");
        expected.forEach((state, cells) -> {
            List<String> got = new ArrayList<>();
            for (String a : List.of("purchase", "produce", "sell", "pos", "replenish")) {
                got.add(s.get("lifecyclePolicy").get(state).get(a).asString());
            }
            assertThat(String.join(",", got)).as(state).isEqualTo(cells);
        });
        assertThat(msg(as("PUT", PLM + "/settings", Map.of("lifecyclePolicy", Map.of("released", Map.of("pos",
                "block")))))).isEqualTo("A released product is always allowed to be purchased, produced, sold and"
                        + " replenished.");

        // Not released: no sale. Prototype: may be produced.
        String customer = partner("isCustomer");
        String proto = product("XPROTO");
        register(proto, "prototype");
        assertThat(msg(as("POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of("productId",
                proto, "quantity", 1)))))).isEqualTo("Product " + code(proto) + " is a prototype and cannot be sold.");
        String flour = product("XFLOUR");
        String protoBom = activeBom(proto, List.of(line(flour, 1))).get("id").asString();
        ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", protoBom, "runs", 1, "locationId", bakery,
                "outputLocationId", warehouse));

        // Phase-out: sells its stock, is no longer made or bought.
        String fading = product("XFADE");
        String fadingBom = activeBom(fading, List.of(line(flour, 1))).get("id").asString();
        String fadingItem = register(fading, "released");
        ok(201, "POST", PLM + "/items/" + fadingItem + "/transition", Map.of("toState", "phase_out"));
        ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "lines", List.of(Map.of("productId", fading,
                "quantity", 1))));
        assertThat(msg(as("POST", "/manufacturing/production-orders", Map.of("bomId", fadingBom, "runs", 1,
                "locationId", bakery, "outputLocationId", warehouse)))).isEqualTo("Product " + code(fading)
                        + " is phase out in its lifecycle and cannot be produced.");
        assertThat(msg(as("POST", "/procurement/purchase-orders", purchaseOrder(partner("isSupplier"), fading))))
                .isEqualTo("Product " + code(fading) + " is being phased out and cannot be purchased.");
    }

    @Test
    @DisplayName("lifecycle exceptions: plm:exception lifts one block for one product until a last day; one in force"
            + " at a time; revoked or expired exceptions stop applying; the product page shows the effect")
    void exceptions() {
        String old = obsolete("XOLD");
        String item = jdbc.sql("SELECT id FROM \"PlmItem\" WHERE \"productId\" = ?").param(old).query(String.class)
                .single();
        String supplier = partner("isSupplier");
        String refused = "Product " + code(old) + " is obsolete and cannot be purchased.";
        assertThat(msg(as("POST", "/procurement/purchase-orders", purchaseOrder(supplier, old)))).isEqualTo(refused);
        JsonNode before = ok(200, "GET", PLM + "/items/" + item, null);
        assertThat(before.get("policy").get("purchase").get("allowed").asBoolean()).isFalse();
        assertThat(before.get("policy").get("purchase").get("policy").asString()).isEqualTo("block");
        assertThat(before.get("exceptions")).isEmpty();

        // Validation.
        String until = LocalDate.now().plusDays(10).toString();
        assertThat(as("POST", PLM + "/lifecycle-exceptions", Map.of("productId", old, "activity", "purchase",
                "validUntil", until, "reason", " ")).status()).isEqualTo(400);
        assertThat(validationMessages(as("POST", PLM + "/lifecycle-exceptions", Map.of("productId", old, "activity",
                "purchase", "validUntil", "31/12/2026", "reason", "r")))).contains("validUntil must be a date (YYYY-MM-DD)");
        assertThat(as("POST", PLM + "/lifecycle-exceptions", Map.of("productId", old, "activity", "ship",
                "validUntil", until, "reason", "r")).status()).isEqualTo(400);
        assertThat(msg(as("POST", PLM + "/lifecycle-exceptions", Map.of("productId", old, "activity", "purchase",
                "validUntil", LocalDate.now().minusDays(2).toString(), "reason", "r"))))
                .isEqualTo("validUntil must not be in the past.");
        assertThat(msg(as("POST", PLM + "/lifecycle-exceptions", Map.of("productId", old, "activity", "purchase",
                "validUntil", LocalDate.now().plusDays(400).toString(), "reason", "r"))))
                .startsWith("An exception lasts at most a year: validUntil must be on or before ");
        String plain = product("XPLAIN");
        assertThat(msg(as("POST", PLM + "/lifecycle-exceptions", Map.of("productId", plain, "activity", "purchase",
                "validUntil", until, "reason", "r")))).isEqualTo("Product " + code(plain)
                        + " is not under lifecycle management.");
        assertThat(as("POST", PLM + "/lifecycle-exceptions", Map.of("productId", UUID.randomUUID().toString(),
                "activity", "purchase", "validUntil", until, "reason", "r")).status()).isEqualTo(404);

        // Granted: the purchase goes through, the product page says why.
        JsonNode x = ok(201, "POST", PLM + "/lifecycle-exceptions", Map.of("productId", old, "activity", "purchase",
                "validUntil", until, "reason", "Last batch for the service contract"));
        assertThat(x.get("status").asString()).isEqualTo("active");
        assertThat(x.get("productCode").asString()).isEqualTo(code(old));
        assertThat(x.get("createdBy").asString()).isEqualTo(adminId);
        ok(201, "POST", "/procurement/purchase-orders", purchaseOrder(supplier, old));
        JsonNode during = ok(200, "GET", PLM + "/items/" + item, null);
        assertThat(during.get("policy").get("purchase").get("allowed").asBoolean()).isTrue();
        assertThat(during.get("policy").get("purchase").get("exception").get("id").asString())
                .isEqualTo(x.get("id").asString());
        // Only the activity it names: selling stays blocked.
        assertThat(during.get("policy").get("sell").get("allowed").asBoolean()).isFalse();
        assertThat(msg(as("POST", "/sales/orders", Map.of("partnerId", partner("isCustomer"), "lines",
                List.of(Map.of("productId", old, "quantity", 1)))))).startsWith("Product " + code(old)
                        + " is obsolete and cannot be sold.");
        assertThat(msg(as("POST", PLM + "/lifecycle-exceptions", Map.of("productId", old, "activity", "purchase",
                "validUntil", until, "reason", "again")))).isEqualTo("An exception for purchasing " + code(old)
                        + " is already in force until " + until + "; revoke it first.");
        assertThat(ok(200, "GET", PLM + "/lifecycle-exceptions?active=true&productId=" + old, null)).hasSize(1);

        // Revoked: blocked again, and the row stays as history.
        JsonNode revoked = ok(201, "POST", PLM + "/lifecycle-exceptions/" + x.get("id").asString() + "/revoke",
                Map.of("reason", "Contract cancelled"));
        assertThat(revoked.get("status").asString()).isEqualTo("revoked");
        assertThat(revoked.get("revokeReason").asString()).isEqualTo("Contract cancelled");
        assertThat(msg(as("POST", "/procurement/purchase-orders", purchaseOrder(supplier, old)))).isEqualTo(refused);
        assertThat(msg(as("POST", PLM + "/lifecycle-exceptions/" + x.get("id").asString() + "/revoke", null)))
                .isEqualTo("The exception is already revoked.");
        assertThat(as("POST", PLM + "/lifecycle-exceptions/" + UUID.randomUUID() + "/revoke", null).status())
                .isEqualTo(404);
        assertThat(ok(200, "GET", PLM + "/lifecycle-exceptions?active=true&productId=" + old, null)).isEmpty();
        assertThat(ok(200, "GET", PLM + "/lifecycle-exceptions?productId=" + old, null)).hasSize(1);

        // Expired: its last day is behind us, so it no longer applies.
        jdbc.sql("INSERT INTO \"PlmLifecycleException\" (id, \"organizationId\", \"productId\", activity, reason,"
                + " \"validUntil\", \"createdBy\") VALUES (?, ?, ?, 'purchase', 'old', current_date - 2, ?)")
                .params(UUID.randomUUID().toString(), demoOrg, old, adminId).update();
        assertThat(msg(as("POST", "/procurement/purchase-orders", purchaseOrder(supplier, old)))).isEqualTo(refused);
        JsonNode history = ok(200, "GET", PLM + "/lifecycle-exceptions?productId=" + old, null);
        assertThat(history).extracting(h -> h.get("status").asString()).containsExactlyInAnyOrder("revoked", "expired");
    }

    @Test
    @DisplayName("the till and hand-entered invoices: new lines follow the policy, offline replays included; lines"
            + " already on a ticket or invoice are never refused")
    void tillAndInvoices() {
        String design = product("XDESIGN");
        register(design, "design");
        String retiring = product("XRETIRE");
        String retiringItem = register(retiring, "released");

        // Till: a design product is not put on a new ticket.
        assertThat(msg(as("POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1, "lines",
                List.of(tillLine(design, 1)))))).isEqualTo("Product " + code(design) + " is in design and cannot be"
                        + " sold at the point of sale.");
        // A ticket rung up while the product was released keeps its line after the product is retired.
        JsonNode ticket = ok(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1, "lines",
                List.of(tillLine(retiring, 1))));
        String ticketId = ticket.get("id").asString();
        ok(201, "POST", PLM + "/items/" + retiringItem + "/transition", Map.of("toState", "obsolete"));
        JsonNode resumed = ok(200, "GET", "/pos/orders/" + ticketId + "/resume", null);
        ok(200, "PUT", "/pos/orders/" + ticketId + "/items", Map.of("expectedVersion", resumed.get("version").asInt(),
                "lines", List.of(tillLine(retiring, 2))));
        // POST /pos/orders/{id}/items requires an Idempotency-Key (the test helper adds one only on its listed routes).
        assertThat(msg(call("POST", "/pos/orders/" + ticketId + "/items", Map.of("lines", List.of(tillLine(design,
                1))), Map.of("Authorization", "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()))))
                .isEqualTo("Product " + code(design) + " is in design and cannot be sold at the point of sale.");
        // Owner decision 2026-11-25: an offline replay is checked like an online sale (the sync push then holds it as a
        // LIFECYCLE_BLOCKED dead letter for a supervisor; PlmOfflineTillIT covers that path end to end).
        String[] perms = jdbc.sql("SELECT r.permissions FROM \"Role\" r JOIN \"_UserRoles\" ur ON ur.\"A\" = r.id"
                + " WHERE ur.\"B\" = ? LIMIT 1").param(adminId).query((rs, i) -> (String[]) rs.getArray(1).getArray())
                .single();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantContext.run(
                TenantContext.TenantStore.user(demoOrg, adminId), () -> {
                    TenantContext.setPermissions(Arrays.asList(perms));
                    return OfflineReplay.run(() -> posOrders.createOrder(new HashMap<>(Map.of("orderType",
                            "takeaway", "guestCount", 1, "lines", List.of(tillLine(design, 1))))));
                })).isInstanceOf(com.inventoryerp.shared.web.ApiException.class).hasMessage("Product " + code(design)
                        + " is in design and cannot be sold at the point of sale.");
        // The organization can relax the till on its own (e.g. samples of a design at the counter).
        ok(200, "PUT", PLM + "/settings", Map.of("lifecyclePolicy", Map.of("design", Map.of("pos", "allow"))));
        ok(201, "POST", "/pos/orders", Map.of("orderType", "takeaway", "guestCount", 1, "lines",
                List.of(tillLine(design, 1))));
        releaseHeldOrders();

        // Hand-entered invoices: a new sale.
        String customer = partner("isCustomer");
        String released = product("XREL");
        register(released, "released");
        assertThat(msg(as("POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", design, "quantity", 1, "unitPrice", 15)))))).isEqualTo("Product "
                        + code(design) + " is in design and cannot be sold.");
        JsonNode inv = ok(200, "POST", "/invoices", Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", released, "quantity", 1, "unitPrice", 15))));
        String invId = inv.get("id").asString();
        assertThat(msg(as("PATCH", "/invoices/" + invId, Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", released, "quantity", 1, "unitPrice", 15), Map.of("productId", design,
                        "quantity", 1, "unitPrice", 15)))))).isEqualTo("Product " + code(design) + " is in design and"
                                + " cannot be sold.");
        // The invoice's own lines stay editable even once the product is retired.
        jdbc.sql("UPDATE \"PlmItem\" SET \"lifecycleState\" = 'obsolete' WHERE \"productId\" = ?").param(released)
                .update();
        ok(200, "PATCH", "/invoices/" + invId, Map.of("partnerId", customer, "issueDate", today(), "lines",
                List.of(Map.of("productId", released, "quantity", 2, "unitPrice", 15))));
    }

    @Test
    @DisplayName("mandatory first article: per organization or per ECO type, an opt-out is refused; a change raised"
            + " before the policy is inspected when applied")
    void mandatoryFirstArticle() {
        String flour = product("YFLOUR");
        String loaf = product("YLOAF");
        JsonNode v1 = activeBom(loaf, List.of(line(flour, 1)));
        register(loaf, "released");
        String bomId = v1.get("id").asString();

        // Raised while optional, without inspection.
        JsonNode early = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "firstArticle", false,
                "stockDisposition", "none"));
        assertThat(early.get("firstArticle").asBoolean()).isFalse();
        ok(200, "PATCH", "/manufacturing/boms/" + early.get("newBomId").asString(), Map.of("lines",
                List.of(line(flour, 2))));

        JsonNode s = ok(200, "PUT", PLM + "/settings", Map.of("firstArticleRequired", true));
        assertThat(s.get("firstArticleRequired").asBoolean()).isTrue();
        // One open change per BOM: make room for the next ones.
        ok(201, "POST", PLM + "/changes/" + early.get("id").asString() + "/cancel", Map.of("reason", "replaced"));
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "firstArticle", false))))
                .isEqualTo("A first-article inspection is mandatory for every BOM and routing change of the"
                        + " organization.");
        JsonNode forced = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "stockDisposition",
                "none"));
        assertThat(forced.get("firstArticle").asBoolean()).isTrue();
        assertThat(msg(as("PATCH", PLM + "/changes/" + forced.get("id").asString(), Map.of("firstArticle", false))))
                .isEqualTo("A first-article inspection is mandatory for every BOM and routing change of the"
                        + " organization.");
        ok(201, "POST", PLM + "/changes/" + forced.get("id").asString() + "/cancel", Map.of("reason", "test"));

        // Per ECO type.
        ok(200, "PUT", PLM + "/settings", Map.of("firstArticleRequired", false));
        String typeName = "NPI " + suffix();
        JsonNode type = ok(201, "POST", PLM + "/change-types", Map.of("name", typeName, "firstArticleRequired", true));
        assertThat(type.get("firstArticleRequired").asBoolean()).isTrue();
        assertThat(msg(as("POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "changeTypeId",
                type.get("id").asString(), "firstArticle", false)))).isEqualTo("A first-article inspection is"
                        + " mandatory for changes of type " + typeName + ".");
        JsonNode optional = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", bomId, "firstArticle",
                false, "stockDisposition", "none"));
        assertThat(optional.get("firstArticle").asBoolean()).isFalse();

        // The policy turns mandatory after the change was raised: it is inspected anyway.
        ok(200, "PATCH", "/manufacturing/boms/" + optional.get("newBomId").asString(), Map.of("lines",
                List.of(line(flour, 3))));
        approve(optional.get("id").asString());
        ok(200, "PUT", PLM + "/settings", Map.of("firstArticleRequired", true));
        JsonNode applied = ok(201, "POST", PLM + "/changes/" + optional.get("id").asString() + "/apply", null);
        assertThat(applied.get("firstArticle").asBoolean()).isTrue();
        assertThat(applied.get("faiStatus").asString()).isEqualTo("pending");
    }

    @Test
    @DisplayName("valuation figures need inventory_valuation:read: ECO cost result, impact, dispositions, revisions"
            + " (list, read, compare)")
    void costMasking() {
        String flour = product("ZFLOUR");
        String sugar = product("ZSUGAR");
        String cake = product("ZCAKE");
        jdbc.sql("UPDATE \"Product\" SET \"costPrice\" = 12 WHERE id = ?").param(cake).update();
        JsonNode v1 = activeBom(cake, List.of(line(flour, 1)));
        String item = register(cake, "released");
        JsonNode eco = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", v1.get("id").asString(),
                "stockDisposition", "none"));
        String ecoId = eco.get("id").asString();
        ok(200, "PATCH", "/manufacturing/boms/" + eco.get("newBomId").asString(), Map.of("lines",
                List.of(line(flour, 1), line(sugar, 1))));
        approve(ecoId);
        ok(201, "POST", PLM + "/changes/" + ecoId + "/apply", null);
        jdbc.sql("UPDATE \"EngineeringChange\" SET \"costResult\" = ?::jsonb WHERE id = ?").params("{\"policy\":"
                + "\"activate\",\"status\":\"active\",\"version\":3,\"unitCost\":12.5,\"revaluationAmount\":40,"
                + "\"note\":null}", ecoId).update();
        String reader = plmReader("bom:read");

        // ECO detail.
        JsonNode adminView = ok(200, "GET", PLM + "/changes/" + ecoId, null);
        assertThat(adminView.get("costVisible").asBoolean()).isTrue();
        assertThat(adminView.get("costResult").get("unitCost").asDouble()).isEqualTo(12.5);
        JsonNode readerView = okAs(reader, 200, "GET", PLM + "/changes/" + ecoId, null);
        assertThat(readerView.get("costVisible").asBoolean()).isFalse();
        assertThat(readerView.get("costResult").get("status").asString()).isEqualTo("active");
        assertThat(readerView.get("costResult").get("version").asInt()).isEqualTo(3);
        assertThat(readerView.get("costResult").has("unitCost")).isFalse();
        assertThat(readerView.get("costResult").has("revaluationAmount")).isFalse();

        // Change list (audit 2026-11-24), with the cost-status filter for investigation (ADR-S086 §5).
        JsonNode adminRow = byId(ok(200, "GET", PLM + "/changes?costStatus=active", null), ecoId);
        assertThat(adminRow.get("costResult").get("unitCost").asDouble()).isEqualTo(12.5);
        JsonNode readerRow = byId(okAs(reader, 200, "GET", PLM + "/changes?costStatus=active", null), ecoId);
        assertThat(readerRow.get("costResult").get("status").asString()).isEqualTo("active");
        assertThat(readerRow.get("costResult").has("unitCost")).isFalse();
        assertThat(byId(ok(200, "GET", PLM + "/changes?costStatus=failed", null), ecoId)).isNull();

        // The ADR-S006 engineering-change reads (bom:read) mask the same figures.
        JsonNode legacy = okAs(reader, 200, "GET", "/manufacturing/engineering-changes/" + ecoId, null);
        assertThat(legacy.get("costResult").get("version").asInt()).isEqualTo(3);
        assertThat(legacy.get("costResult").has("unitCost")).isFalse();
        JsonNode legacyRow = byId(okAs(reader, 200, "GET", "/manufacturing/engineering-changes", null), ecoId);
        assertThat(legacyRow.get("costResult").has("revaluationAmount")).isFalse();
        assertThat(ok(200, "GET", "/manufacturing/engineering-changes/" + ecoId, null).get("costResult")
                .get("unitCost").asDouble()).isEqualTo(12.5);

        // Revisions.
        List<JsonNode> adminRevs = new ArrayList<>();
        ok(200, "GET", PLM + "/items/" + item + "/revisions", null).forEach(adminRevs::add);
        assertThat(adminRevs).hasSize(2);
        JsonNode a = adminRevs.get(1);
        assertThat(a.get("unitCost").asDouble()).isEqualTo(12.0);
        okAs(reader, 200, "GET", PLM + "/items/" + item + "/revisions", null)
                .forEach(r -> assertThat(r.get("unitCost").isNull()).isTrue());
        JsonNode readerRev = okAs(reader, 200, "GET", PLM + "/revisions/" + a.get("id").asString(), null);
        assertThat(readerRev.get("costVisible").asBoolean()).isFalse();
        assertThat(readerRev.get("unitCost").isNull()).isTrue();
        assertThat(readerRev.get("snapshot").get("cost").get("unitCost").isNull()).isTrue();
        assertThat(readerRev.get("snapshot").get("bom").get("lines")).hasSize(1);
        assertThat(ok(200, "GET", PLM + "/revisions/" + a.get("id").asString(), null).get("snapshot").get("cost")
                .get("unitCost").asDouble()).isEqualTo(12.0);
        String compare = PLM + "/revisions/compare?from=" + a.get("id").asString() + "&to="
                + adminRevs.get(0).get("id").asString();
        assertThat(ok(200, "GET", compare, null).get("cost").get("from").asDouble()).isEqualTo(12.0);
        JsonNode readerCmp = okAs(reader, 200, "GET", compare, null);
        assertThat(readerCmp.get("costVisible").asBoolean()).isFalse();
        assertThat(readerCmp.get("cost").get("from").isNull()).isTrue();
        assertThat(readerCmp.get("cost").get("delta").isNull()).isTrue();
        assertThat(readerCmp.get("from").get("unitCost").isNull()).isTrue();
        assertThat(readerCmp.get("to").get("unitCost").isNull()).isTrue();
        assertThat(readerCmp.get("bom").get("added")).hasSize(1);

        // Impact of an open change.
        JsonNode next = ok(201, "POST", PLM + "/changes", Map.of("type", "bom", "bomId", eco.get("newBomId").asString(),
                "stockDisposition", "scrap"));
        String impact = PLM + "/changes/" + next.get("id").asString() + "/impact";
        assertThat(ok(200, "GET", impact, null).get("costVisible").asBoolean()).isTrue();
        JsonNode readerImpact = okAs(reader, 200, "GET", impact, null);
        assertThat(readerImpact.get("costVisible").asBoolean()).isFalse();
        assertThat(readerImpact.get("cost").isNull()).isTrue();
        readerImpact.get("dispositionPlan").get("stock").forEach(r -> assertThat(r.get("unitCost").isNull()).isTrue());
        ok(201, "POST", PLM + "/changes/" + next.get("id").asString() + "/cancel", Map.of("reason", "test"));
    }
}
