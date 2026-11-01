package com.inventoryerp.manufacturing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.inventory.application.TransferIncomingSupply;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.manufacturing.application.MrpSettingsService;
import com.inventoryerp.manufacturing.application.ProductionIncomingSupply;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** PR-5 (ADR-S027): replenishment routes (transfer / manufacture), MRP subcontracting, rough-cut capacity, settings. */
class ReplenishmentMrpIT extends MfgTestSupport {

    private static final String R = "/inventory/replenishment";

    @Autowired
    TransferIncomingSupply transferSupply;

    @Autowired
    ProductionIncomingSupply productionSupply;

    @Autowired
    MrpSettingsService mrpSettings;

    String rule(String product, String location, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("productId", product, "locationId", location, "minQty", 10,
                "maxQty", 20));
        body.putAll(extra);
        return ok(201, "POST", R + "/rules", body).get("id").asString();
    }

    JsonNode liveAlert(String location) {
        JsonNode rows = ok(200, "GET", R + "/alerts?status=live&locationId=" + location, null).get("data");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** Scan, then ask for the requisition of the location's open alert. */
    String requisition(String location) {
        ok(201, "POST", R + "/scan", null);
        String alertId = liveAlert(location).get("id").asString();
        ok(201, "POST", R + "/requisition", Map.of("alertIds", List.of(alertId)));
        return alertId;
    }

    BigDecimal supply(java.util.function.Supplier<BigDecimal> s) {
        return TenantContext.run(TenantStore.organization(demoOrg), s);
    }

    @Test
    @DisplayName("route validation: unknown route, transfer without or with the same source")
    void routeValidation() {
        String p = product("RTV");
        String l = location("warehouse");
        assertThat(as("POST", R + "/rules", Map.of("productId", p, "locationId", l, "minQty", 5, "route", "steal"))
                .body().get("message").asString()).isEqualTo("route must be one of: buy, manufacture, transfer");
        assertThat(as("POST", R + "/rules", Map.of("productId", p, "locationId", l, "minQty", 5, "route", "transfer"))
                .body().get("message").asString()).isEqualTo("A transfer rule needs a sourceLocationId");
        assertThat(as("POST", R + "/rules", Map.of("productId", p, "locationId", l, "minQty", 5, "route", "transfer",
                "sourceLocationId", l)).body().get("message").asString())
                .isEqualTo("The source location must differ from the rule's location");
        String id = rule(p, l, Map.of("route", "manufacture"));
        JsonNode updated = ok(200, "PATCH", R + "/rules/" + id, Map.of("route", "transfer", "sourceLocationId",
                warehouse));
        assertThat(updated.get("route").asString()).isEqualTo("transfer");
        assertThat(updated.get("sourceLocationId").asString()).isEqualTo(warehouse);
        // Back to buy drops the source.
        updated = ok(200, "PATCH", R + "/rules/" + id, Map.of("route", "buy"));
        assertThat(updated.get("sourceLocationId").isNull()).isTrue();
    }

    @Test
    @DisplayName("a transfer rule raises a pending stock transfer from its source; the transfer counts as incoming")
    void transferRoute() {
        String p = product("RTT");
        String store = location("warehouse");
        stockIn(warehouse, p, 50, 3);
        stockIn(store, p, 4, 3);
        rule(p, store, Map.of("route", "transfer", "sourceLocationId", warehouse));
        String alertId = requisition(store);
        JsonNode alert = liveAlert(store);
        assertThat(alert.get("id").asString()).isEqualTo(alertId);
        assertThat(alert.get("status").asString()).isEqualTo("requisitioned");
        assertThat(alert.get("route").asString()).isEqualTo("transfer");
        assertThat(alert.get("documentType").asString()).isEqualTo("stock_transfer");
        assertThat(alert.get("documentNumber").asString()).startsWith("TRF-");
        String transferId = alert.get("documentId").asString();
        JsonNode transfer = ok(200, "GET", "/inventory/transfers/" + transferId, null);
        assertThat(transfer.get("status").asString()).isEqualTo("pending");
        assertThat(transfer.get("fromLocId").asString()).isEqualTo(warehouse);
        // Order up to 20 from 4 available.
        assertThat(new BigDecimal(transfer.get("items").get(0).get("qtyRequested").asString()))
                .isEqualByComparingTo("16");
        assertThat(supply(() -> transferSupply.incoming(p, store, false))).isEqualByComparingTo("16");
        // Nothing went to procurement.
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchaseRequestLine\" WHERE \"demandRef\" = ?").param(alertId)
                .query(Long.class).single()).isZero();

        Res approved = call("POST", "/inventory/transfers/" + transferId + "/approve", null, Map.of("Authorization",
                "Bearer " + adminToken, "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(approved.status()).as(approved.raw().body()).isIn(200, 201);
        assertThat(supply(() -> transferSupply.incoming(p, store, false))).isEqualByComparingTo("0");
        ok(201, "POST", R + "/scan", null);
        assertThat(ok(200, "GET", R + "/alerts?status=live&locationId=" + store, null).get("data")).isEmpty();
        assertThat(onHand(p, store)).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("a manufacture rule raises a submitted production request delivering to the rule's location")
    void manufactureRoute() {
        String flour = product("RTMF");
        String bread = product("RTMB");
        String bomId = bom(bread, flour, 1, 2, Map.of());
        String shop = location("warehouse");
        rule(bread, shop, Map.of("route", "manufacture", "leadTimeDays", 2));
        String alertId = requisition(shop);
        drainOutboxFully();
        JsonNode alert = liveAlert(shop);
        assertThat(alert.get("status").asString()).isEqualTo("requisitioned");
        assertThat(alert.get("documentType").asString()).isEqualTo("production_request");
        assertThat(alert.get("documentNumber").asString()).startsWith("PRQ-");
        String requestId = alert.get("documentId").asString();
        JsonNode request = ok(200, "GET", "/manufacturing/requests/" + requestId, null);
        assertThat(request.get("status").asString()).isEqualTo("submitted");
        assertThat(request.get("sourceType").asString()).isEqualTo("replenishment");
        assertThat(request.get("lines").get(0).get("bomId").asString()).isEqualTo(bomId);
        assertThat(new BigDecimal(request.get("lines").get(0).get("quantity").asString())).isEqualByComparingTo("20");
        assertThat(jdbc.sql("SELECT \"deliveryLocationId\" FROM \"ProductionRequestLine\" WHERE \"requestId\" = ?")
                .param(requestId).query(String.class).single()).isEqualTo(shop);
        assertThat(supply(() -> productionSupply.incoming(bread, shop, false))).isEqualByComparingTo("20");
        // Procurement left the manufacture line alone; a redelivered event raises nothing more.
        assertThat(jdbc.sql("SELECT count(*) FROM \"PurchaseRequestLine\" WHERE \"demandRef\" = ?").param(alertId)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM \"ProductionRequest\" WHERE \"sourceType\" = 'replenishment' AND"
                + " notes LIKE 'Replenishment:%' AND id = ?").param(requestId).query(Long.class).single()).isOne();

        // An open order into the location counts too.
        ok(201, "POST", "/manufacturing/production-orders", Map.of("bomId", bomId, "runs", 5, "locationId", bakery,
                "outputLocationId", shop));
        assertThat(supply(() -> productionSupply.incoming(bread, shop, false))).isEqualByComparingTo("25");
    }

    @Test
    @DisplayName("a buy rule's purchase request line delivers to the rule's location")
    void buyRouteDeliveryLocation() {
        String p = product("RTB");
        String l = location("warehouse");
        rule(p, l, Map.of());
        String alertId = requisition(l);
        drainOutboxFully();
        JsonNode alert = liveAlert(l);
        assertThat(alert.get("documentType").asString()).isEqualTo("purchase_request");
        assertThat(alert.get("documentId").asString()).isEqualTo(alert.get("purchaseRequestId").asString());
        Map<String, Object> line = jdbc.sql("SELECT \"deliveryLocationId\", \"demandSource\", \"demandRef\" FROM"
                + " \"PurchaseRequestLine\" WHERE \"purchaseRequestId\" = ?").param(alert.get("documentId").asString())
                .query().singleRow();
        assertThat(line.get("deliveryLocationId")).isEqualTo(l);
        assertThat(line.get("demandSource")).isEqualTo("reorder");
        assertThat(line.get("demandRef")).isEqualTo(alertId);
    }

    @Test
    @DisplayName("MRP: subcontract products explode like make and firm into a production order plus a SUBPR request")
    void subcontract() {
        String sub = ok(201, "POST", "/partners", Map.of("name", "Coater " + suffix(), "isSupplier", true)).get("id")
                .asString();
        String raw = product("SCRAW");
        String coated = product("SCOUT");
        bom(coated, raw, 1, 3, Map.of());
        ok(200, "PUT", "/manufacturing/planning-params/" + coated, Map.of("procurement", "subcontract", "supplierId",
                sub, "leadTimeDays", 4, "locationId", bakery));
        Instant due = Instant.now().plus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        JsonNode req = ok(201, "POST", "/manufacturing/requests", Map.of("neededBy", due.toString(), "lines",
                List.of(Map.of("productId", coated, "quantity", 5))));
        ok(201, "POST", "/manufacturing/requests/" + req.get("id").asString() + "/submit", null);

        JsonNode run = ok(201, "POST", "/manufacturing/mrp/runs", Map.of("horizonDays", 30));
        assertThat(run.get("summary").get("subcontract").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode po = MrpPlanningIT.find(run.get("plannedOrders"), coated);
        assertThat(po.get("kind").asString()).isEqualTo("subcontract");
        assertThat(po.get("supplierId").asString()).isEqualTo(sub);
        // The components still have to be provided: 5 × 3 raw planned as a buy.
        JsonNode rawOrder = MrpPlanningIT.find(run.get("plannedOrders"), raw);
        assertThat(new BigDecimal(rawOrder.get("quantity").asString())).isEqualByComparingTo("15");

        JsonNode firmed = ok(201, "POST", "/manufacturing/mrp/planned-orders/" + po.get("id").asString() + "/firm",
                null);
        assertThat(firmed.get("firmedDocType").asString()).isEqualTo("production_order");
        String prId = firmed.get("subcontractRequestId").asString();
        Map<String, Object> pr = jdbc.sql("SELECT \"requestNumber\", \"partnerId\", status::text AS status FROM"
                + " \"PurchaseRequest\" WHERE id = ?").param(prId).query().singleRow();
        assertThat((String) pr.get("requestNumber")).startsWith("SUBPR-");
        assertThat(pr.get("partnerId")).isEqualTo(sub);
        assertThat(pr.get("status")).isEqualTo("draft");
        Map<String, Object> line = jdbc.sql("SELECT \"productId\", quantity FROM \"PurchaseRequestLine\" WHERE"
                + " \"purchaseRequestId\" = ?").param(prId).query().singleRow();
        assertThat(line.get("productId")).isNull();
        assertThat((BigDecimal) line.get("quantity")).isEqualByComparingTo("5");
        assertThat(ok(200, "GET", "/manufacturing/production-orders/" + firmed.get("firmedDocId").asString(), null)
                .get("status").asString()).isEqualTo("draft");
    }

    @Test
    @DisplayName("MRP rough-cut capacity: planned make orders load their routing's work centres")
    void roughCutCapacity() {
        String wc = ok(201, "POST", "/manufacturing/work-centers", Map.of("code", "RCC-" + suffix(), "name",
                "Oven " + suffix())).get("id").asString();
        String routing = ok(201, "POST", "/manufacturing/routings", Map.of("name", "Oven line " + suffix(),
                "operations", List.of(Map.of("sequence", 1, "name", "Bake", "workCenterId", wc, "durationMins", 60,
                        "setupMins", 30), Map.of("sequence", 2, "name", "Glaze", "isSubcontracted", true,
                        "subcontractCostPerUnit", 1)))).get("id").asString();
        String dough = product("RCCIN");
        String loaf = product("RCCOUT");
        bom(loaf, dough, 1, 1, Map.of("routingId", routing));
        ok(200, "PUT", "/manufacturing/planning-params/" + loaf, Map.of("procurement", "make", "leadTimeDays", 2,
                "locationId", bakery));
        Instant due = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        JsonNode req = ok(201, "POST", "/manufacturing/requests", Map.of("neededBy", due.toString(), "lines",
                List.of(Map.of("productId", loaf, "quantity", 10))));
        ok(201, "POST", "/manufacturing/requests/" + req.get("id").asString() + "/submit", null);
        JsonNode run = ok(201, "POST", "/manufacturing/mrp/runs", Map.of("horizonDays", 30));
        assertThat(MrpPlanningIT.find(run.get("plannedOrders"), loaf)).isNotNull();

        JsonNode cap = ok(200, "GET", "/manufacturing/mrp/runs/" + run.get("id").asString() + "/capacity", null);
        assertThat(cap.get("bucket").asString()).isEqualTo("week");
        JsonNode row = null;
        for (JsonNode c : cap.get("workCenters")) {
            if (c.get("workCenterId").asString().equals(wc)) {
                row = c;
            }
        }
        assertThat(row).isNotNull();
        // 30 min setup + 60 min × 10 = 630 min = 10.5 h; the subcontracted glaze loads nobody.
        assertThat(new BigDecimal(row.get("plannedLoadHours").asString())).isEqualByComparingTo("10.50");
        BigDecimal bucketSum = BigDecimal.ZERO;
        for (JsonNode b : row.get("buckets")) {
            bucketSum = bucketSum.add(new BigDecimal(b.get("plannedLoadHours").asString()));
        }
        assertThat(bucketSum).isBetween(new BigDecimal("10.48"), new BigDecimal("10.52"));
        assertThat(ok(200, "GET", "/manufacturing/mrp/runs/" + run.get("id").asString() + "/capacity?bucket=day",
                null).get("bucket").asString()).isEqualTo("day");
        assertThat(as("GET", "/manufacturing/mrp/runs/" + run.get("id").asString() + "/capacity?bucket=year", null)
                .status()).isEqualTo(400);
        assertThat(as("GET", "/manufacturing/mrp/runs/00000000-0000-0000-0000-000000000000/capacity", null)
                .status()).isEqualTo(404);
    }

    @Test
    @DisplayName("MRP settings: defaults, validation, nightly run remembered")
    void settings() {
        JsonNode s = ok(200, "GET", "/manufacturing/mrp/settings", null);
        assertThat(s.has("autoRun")).isTrue();
        assertThat(as("PUT", "/manufacturing/mrp/settings", Map.of("horizonDays", 0)).body().get("message")
                .asString()).isEqualTo("horizonDays must be between 1 and 730");
        assertThat(as("PUT", "/manufacturing/mrp/settings", Map.of("locationId", "nope")).status()).isEqualTo(404);
        s = ok(200, "PUT", "/manufacturing/mrp/settings", Map.of("autoRun", true, "horizonDays", 14, "locationId",
                bakery));
        assertThat(s.get("autoRun").asBoolean()).isTrue();
        assertThat(s.get("horizonDays").asInt()).isEqualTo(14);
        assertThat(mrpSettings.autoRunOrganizations()).contains(demoOrg);
        Map<String, Object> run = TenantContext.run(TenantStore.organization(demoOrg), mrpSettings::autoRun);
        assertThat(((Number) run.get("horizonDays")).intValue()).isEqualTo(14);
        s = ok(200, "GET", "/manufacturing/mrp/settings", null);
        assertThat(s.get("lastAutoRunId").asString()).isEqualTo(run.get("id"));
        s = ok(200, "PUT", "/manufacturing/mrp/settings", Map.of("autoRun", false, "locationId", ""));
        assertThat(s.get("autoRun").asBoolean()).isFalse();
        assertThat(s.get("locationId").isNull()).isTrue();
        assertThat(s.get("horizonDays").asInt()).isEqualTo(14);
    }
}
