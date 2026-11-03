package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Landed cost LC-7 (ADR-S053): versioned customs tariffs with immutable, reproducible calculations; the Incoterm
 * responsibility warning; allocation templates; freight cost history.
 */
class LandedCostCustomsIT extends LandedCostFixtures {

    String yesterday = LocalDate.now().minusDays(1).toString();
    String today = LocalDate.now().toString();

    String hs() {
        return "84" + (10 + (int) (Math.random() * 89)) + "." + suffix().substring(0, 4);
    }

    JsonNode tariff(Object... kv) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("importCountry", "TZ", "effectiveFrom", yesterday));
        for (int i = 0; i < kv.length; i += 2) {
            body.put((String) kv[i], kv[i + 1]);
        }
        Res r = call("POST", "/procurement/customs/tariffs", body, key());
        assertThat(r.status()).as(r.raw().body()).isIn(200, 201);
        return r.body();
    }

    Res calculate(Object... kv) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("importCountry", "TZ", "declarationDate", today));
        for (int i = 0; i < kv.length; i += 2) {
            body.put((String) kv[i], kv[i + 1]);
        }
        return call("POST", "/procurement/customs/calculations", body, key());
    }

    static BigDecimal dec(JsonNode n, String field) {
        return new BigDecimal(n.get(field).asString());
    }

    @Test
    @DisplayName("versioning: CIF 10,000 + 800 + 200 at 25 % → 2,750; revised to 10 % → v2 1,100; v1 result unchanged")
    void versionedTariffAndReproducibleCalculation() {
        String hs = hs();
        JsonNode v1 = tariff("hsCode", hs, "dutyType", "ad_valorem", "adValoremPct", 25, "valuationBasis", "cif",
                "legalReference", "EAC CET 2022 ch. 84");
        assertThat(call("POST", "/procurement/customs/tariffs", Map.of("importCountry", "TZ", "hsCode", hs,
                "effectiveFrom", today, "dutyType", "ad_valorem", "adValoremPct", 30), key()).status())
                .as("overlapping active line").isEqualTo(409);
        Res first = calculate("hsCode", hs, "fobValue", 10000, "freight", 800, "insurance", 200);
        assertThat(first.status()).as(first.raw().body()).isIn(200, 201);
        assertThat(dec(first.body(), "customsValue")).isEqualByComparingTo("11000");
        assertThat(dec(first.body(), "dutyAmount")).isEqualByComparingTo("2750");
        assertThat(first.body().get("tariffVersion").asInt()).isEqualTo(1);

        Map<String, Object> revised = new LinkedHashMap<>(Map.of("importCountry", "TZ", "hsCode", hs, "effectiveFrom",
                yesterday, "dutyType", "ad_valorem", "adValoremPct", 10, "valuationBasis", "cif", "legalReference",
                "Finance Act amendment"));
        JsonNode v2 = ok(200, "PUT", "/procurement/customs/tariffs/" + v1.get("id").asString(), revised);
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        Res second = calculate("hsCode", hs, "fobValue", 10000, "freight", 800, "insurance", 200);
        assertThat(dec(second.body(), "dutyAmount")).isEqualByComparingTo("1100");
        assertThat(second.body().get("legalReference").asString()).isEqualTo("Finance Act amendment");

        JsonNode again = ok(200, "GET", "/procurement/customs/calculations/" + first.body().get("id").asString(), null);
        assertThat(dec(again, "dutyAmount")).isEqualByComparingTo("2750");
        assertThat(dec(again, "adValoremPct")).isEqualByComparingTo("25");
        assertThat(again.get("legalReference").asString()).isEqualTo("EAC CET 2022 ch. 84");
        assertThat(ok(200, "GET", "/procurement/customs/tariffs?history=true&hsCode=" + hs, null)).hasSize(2);

        String calc = first.body().get("id").asString();
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"CustomsCalculation\" SET \"dutyAmount\" = 1 WHERE id = ?").param(calc)
                .update()).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM \"CustomsCalculation\" WHERE id = ?").param(calc).update())
                .hasMessageContaining("immutable");
    }

    @Test
    @DisplayName("compound: 100 × 2 + 5 % of 4,000 = 400; additional 1 % = 40; excise 10 % on 4,400 = 440 → 880")
    void compoundDuty() {
        String hs = hs();
        tariff("hsCode", hs, "dutyType", "compound", "adValoremPct", 5, "specificAmount", 2, "valuationBasis",
                "transaction", "additionalDutyPct", 1, "excisePct", 10);
        JsonNode c = calculate("hsCode", hs, "transactionValue", 4000, "quantity", 100).body();
        assertThat(dec(c, "dutyAmount")).isEqualByComparingTo("400");
        assertThat(dec(c, "additionalAmount")).isEqualByComparingTo("40");
        assertThat(dec(c, "exciseAmount")).isEqualByComparingTo("440");
        assertThat(dec(c, "totalAmount")).isEqualByComparingTo("880");
    }

    @Test
    @DisplayName("preference: an EAC-origin line at 0 % wins over the general 25 %; no line on the date → 400")
    void preferenceAndDateValidation() {
        String hs = hs();
        tariff("hsCode", hs, "dutyType", "ad_valorem", "adValoremPct", 25, "valuationBasis", "fob");
        tariff("hsCode", hs, "dutyType", "exempt", "valuationBasis", "fob", "preferenceCode", "EAC", "originCountry",
                "KE");
        assertThat(dec(calculate("hsCode", hs, "fobValue", 1000, "originCountry", "KE", "preferenceCode", "EAC")
                .body(), "totalAmount")).isEqualByComparingTo("0");
        assertThat(dec(calculate("hsCode", hs, "fobValue", 1000, "originCountry", "CN").body(), "totalAmount"))
                .isEqualByComparingTo("250");
        Res early = calculate("hsCode", hs, "fobValue", 1000, "declarationDate", LocalDate.now().minusYears(1)
                .toString());
        assertThat(early.status()).isEqualTo(400);
        assertThat(early.body().get("message").asString()).startsWith("No tariff line for HS " + hs);
    }

    @Test
    @DisplayName("manual adjustment: 403 without landed_cost:customs_adjust, 400 without a reason, else audited")
    void manualAdjustment() {
        String hs = hs();
        tariff("hsCode", hs, "dutyType", "ad_valorem", "adValoremPct", 10, "valuationBasis", "fob");
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Customs " + role,
                new String[] {"landed_cost:read", "landed_cost:create"}).update();
        String email = "customs-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Customs@12345", role);
        String clerk = login("DEMO", email, "Customs@12345");
        Map<String, Object> body = Map.of("importCountry", "TZ", "hsCode", hs, "declarationDate", today, "fobValue",
                1000, "manualAdjustment", 15, "adjustmentReason", "Assessment notice 77");
        assertThat(call("POST", "/procurement/customs/calculations", body, Map.of("Authorization", "Bearer " + clerk,
                "Idempotency-Key", UUID.randomUUID().toString())).status()).isEqualTo(403);
        assertThat(calculate("hsCode", hs, "fobValue", 1000, "manualAdjustment", 15).status()).isEqualTo(400);
        JsonNode ok = calculate("hsCode", hs, "fobValue", 1000, "manualAdjustment", 15, "adjustmentReason",
                "Assessment notice 77").body();
        assertThat(dec(ok, "totalAmount")).isEqualByComparingTo("115");
    }

    @Test
    @DisplayName("templates: freight 5 % of goods → 250 on a 5,000 receipt; duplicate name 409; freight history lists it")
    void templatesAndHistory() {
        String name = "Sea import " + suffix();
        Res t = call("POST", "/procurement/landed-cost-templates", Map.of("name", name, "charges", List.of(
                Map.of("kind", "freight", "treatment", "capitalize", "allocationMethod", "weight", "ratePct", 5),
                Map.of("kind", "duty", "treatment", "capitalize"))), key());
        assertThat(t.status()).as(t.raw().body()).isIn(200, 201);
        assertThat(call("POST", "/procurement/landed-cost-templates", Map.of("name", name.toUpperCase(), "charges",
                List.of(Map.of("kind", "freight"))), key()).status()).isEqualTo(409);
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        JsonNode drafts = ok(200, "POST", "/procurement/landed-cost-templates/" + t.body().get("id").asString()
                + "/apply", Map.of("goodsReceiptIds", List.of(grn)));
        assertThat(drafts).hasSize(2);
        assertThat(new BigDecimal(drafts.get(0).get("amount").asString())).isEqualByComparingTo("250");
        assertThat(drafts.get(0).get("allocationMethod").asString()).isEqualTo("weight");

        approveAndPost(voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 250, "supplierId", forwarder,
                "supplierInvoiceRef", "HIST-" + suffix(), "billMode", "none"))));
        JsonNode history = ok(200, "GET", "/procurement/landed-cost-reports/analysis/freight-history?from=" + today
                + "&to=" + today, null);
        boolean found = false;
        for (JsonNode r : history) {
            found |= "freight".equals(r.get("kind").asString()) && r.get("supplier").asString().startsWith("Audit forwarder");
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("Incoterm: freight on goods shipped CIF → preview warns the seller bears freight; duty is fine")
    void incotermWarning() {
        Res bad = call("POST", "/procurement/shipments", Map.of("reference", "BL-" + suffix(), "incoterm", "XYZ"),
                key());
        assertThat(bad.status()).isEqualTo(400);
        Res shipment = call("POST", "/procurement/shipments", Map.of("reference", "BL-" + suffix(), "incoterm", "cif"),
                key());
        assertThat(shipment.status()).as(shipment.raw().body()).isIn(200, 201);
        assertThat(shipment.body().get("incoterm").asString()).isEqualTo("CIF");
        String p = product("AVCO", false, 0);
        String grn = receive(p, 10, 50);
        Res attach = call("POST", "/procurement/shipments/" + shipment.body().get("id").asString() + "/receipts",
                Map.of("goodsReceiptId", grn), key());
        assertThat(attach.status()).as(attach.raw().body()).isIn(200, 201);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 100), charge("kind", "duty",
                "amount", 50)));
        JsonNode warnings = ok(200, "GET", "/procurement/landed-cost-vouchers/" + v + "/preview", null).get("warnings");
        assertThat(warnings.toString()).contains("the seller bears freight under CIF").doesNotContain("(duty)");
    }
}
