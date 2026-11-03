package com.inventoryerp.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.BooksGuard;
import com.inventoryerp.kernel.settings.SettingResolverService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Landed cost LC-2 financial controls (ADR-S053): strict approval and limits, the approval hash, the per-target
 * re-approval tolerance (OR rule), reopen, normalised duplicate supplier invoices with one-use overrides, and the
 * allocation validation.
 */
@ExtendWith(BooksGuard.class)
class LandedCostControlsIT extends LandedCostFixtures {

    @Autowired
    SettingResolverService settingResolver;

    String setting(String key, String jsonValue) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", key, value, \"scopeType\", \"updatedAt\") VALUES"
                + " (?, ?, ?, ?::jsonb, 'organization', now())").params(id, demoOrg, key, jsonValue).update();
        settingResolver.invalidate(demoOrg);
        return id;
    }

    void unset(String id) {
        jdbc.sql("DELETE FROM \"Setting\" WHERE id = ?").param(id).update();
        settingResolver.invalidate(demoOrg);
    }

    Res action(String v, String a) {
        return call("POST", "/procurement/landed-cost-vouchers/" + v + "/" + a, null, key());
    }

    String userWith(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Controls " + role, permissions).update();
        String email = "controls-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Controls@12345", role);
        return login("DEMO", email, "Controls@12345");
    }

    // ------------------------------------------------------------------ approval

    @Test
    @DisplayName("approval: draft → approve refused (submit first); allowDirectApproval relaxes it; reopen clears it")
    void strictApproval() {
        String p = product("AVCO", false, 0);
        String v = voucher(List.of(receive(p, 10, 50)), List.of(charge("kind", "freight", "amount", 100)));
        Res direct = action(v, "approve");
        assertThat(direct.status()).isEqualTo(400);
        assertThat(direct.body().get("message").asString()).contains("submit it first");
        assertThat(action(v, "submit").status()).isEqualTo(200);
        JsonNode approved = action(v, "approve").body();
        assertThat(approved.get("status").asString()).isEqualTo("approved");
        assertThat(approved.get("approvedHash").asString()).hasSize(64);
        JsonNode reopened = action(v, "reopen").body();
        assertThat(reopened.get("status").asString()).isEqualTo("draft");
        assertThat(reopened.get("approvedHash").isNull()).isTrue();
        String s = setting("costing.landedCost.allowDirectApproval", "true");
        try {
            assertThat(action(v, "approve").status()).isEqualTo(200);
        } finally {
            unset(s);
        }
    }

    @Test
    @DisplayName("approval limit: an approver limited to 500 cannot approve a 1000 voucher")
    void approvalLimit() {
        String limit = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ApprovalLimit\" (id, \"organizationId\", name, \"entityType\", \"userId\", \"maxAmount\")"
                + " VALUES (?, ?, 'LC limit', 'landed_cost_voucher', ?, 500)").params(limit, demoOrg, adminId).update();
        try {
            String p = product("AVCO", false, 0);
            String v = voucher(List.of(receive(p, 10, 50)), List.of(charge("kind", "freight", "amount", 1000)));
            action(v, "submit");
            Res r = action(v, "approve");
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).isEqualTo("This amount is above your approval limit of 500");
        } finally {
            jdbc.sql("DELETE FROM \"ApprovalLimit\" WHERE id = ?").param(limit).update();
        }
    }

    @Test
    @DisplayName("approval hash: any money driver changed after approval refuses the post")
    void hashRefusesChangedInputs() {
        String p = product("AVCO", false, 0);
        String v = voucher(List.of(receive(p, 10, 50)), List.of(charge("kind", "freight", "amount", 100)));
        action(v, "submit");
        action(v, "approve");
        jdbc.sql("UPDATE \"LandedCostCharge\" SET amount = 120, \"baseAmount\" = 120 WHERE \"landedCostId\" = ?")
                .param(v).update();
        Res r = action(v, "post");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("Voucher changed since approval; reopen and re-approve");
        jdbc.sql("UPDATE \"LandedCostCharge\" SET amount = 100, \"baseAmount\" = 100 WHERE \"landedCostId\" = ?")
                .param(v).update();
        assertThat(action(v, "post").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("tolerance: 1 of 100 sold after approval → stock 1000 → 990 (1 %), COGS new 10 (< 1000) → posts")
    void smallMovementPosts() {
        String p = product("AVCO", false, 0);
        String v = voucher(List.of(receive(p, 100, 50)), List.of(charge("kind", "freight", "amount", 1000)));
        action(v, "submit");
        action(v, "approve");
        sell(p, w1, 1);
        Snap s = snap();
        assertThat(action(v, "post").status()).isEqualTo(200);
        assertBooks(s, moves("stock", "990", "cogs", "10", "clearing", "-1000"));
        JsonNode posted = ok(200, "GET", "/procurement/landed-cost-vouchers/" + v, null);
        assertThat(new BigDecimal(posted.get("postedDisposition").get("cogs:" + p).asString()))
                .isEqualByComparingTo("10");
        assertThat(new BigDecimal(posted.get("approvedDisposition").get("stock:" + p + ":" + w1).asString()))
                .isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("tolerance (OR): 10 of 100 sold after approval → stock −10 % > 5 % → refused; reopen, re-approve, post")
    void largeMovementNeedsReapproval() {
        String p = product("AVCO", false, 0);
        String v = voucher(List.of(receive(p, 100, 50)), List.of(charge("kind", "freight", "amount", 1000)));
        action(v, "submit");
        action(v, "approve");
        sell(p, w1, 10);
        Res r = action(v, "post");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).isEqualTo("Inventory moved since approval (stock:" + p + ":" + w1
                + ": approved 1000, now 900); reopen and re-approve");
        assertThat(action(v, "reopen").status()).isEqualTo(200);
        action(v, "submit");
        action(v, "approve");
        Snap s = snap();
        assertThat(action(v, "post").status()).isEqualTo(200);
        assertBooks(s, moves("stock", "900", "cogs", "100", "clearing", "-1000"));
    }

    @Test
    @DisplayName("tolerance amount 0: any change at all needs re-approval")
    void zeroAmountThreshold() {
        String s = setting("costing.landedCost.reapprovalToleranceAmount", "0");
        try {
            String p = product("AVCO", false, 0);
            String v = voucher(List.of(receive(p, 100, 50)), List.of(charge("kind", "freight", "amount", 1000)));
            action(v, "submit");
            action(v, "approve");
            sell(p, w1, 1);
            Res r = action(v, "post");
            assertThat(r.status()).isEqualTo(400);
            assertThat(r.body().get("message").asString()).contains("reopen and re-approve");
        } finally {
            unset(s);
        }
    }

    // ------------------------------------------------------------------ LC-5 preview

    @Test
    @DisplayName("preview (LC-5): balanced journal, disposition, hash that changes when stock moves, parked warning")
    void previewJournalHashAndWarnings() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 100, 50);
        String v = voucher(List.of(grn), List.of(charge("kind", "freight", "amount", 1000)));
        JsonNode first = ok(200, "GET", "/procurement/landed-cost-vouchers/" + v + "/preview", null);
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (JsonNode l : first.get("journal")) {
            debit = debit.add(l.get("debit").isNull() ? BigDecimal.ZERO : new BigDecimal(l.get("debit").asString()));
            credit = credit.add(l.get("credit").isNull() ? BigDecimal.ZERO : new BigDecimal(l.get("credit").asString()));
        }
        assertThat(debit).isEqualByComparingTo("1000").isEqualByComparingTo(credit);
        assertThat(new BigDecimal(first.get("disposition").get("stock:" + p + ":" + w1).asString()))
                .isEqualByComparingTo("1000");
        assertThat(first.get("warnings")).isEmpty();
        assertThat(first.get("computedAt").asString()).isNotBlank();
        sell(p, w1, 10);
        JsonNode second = ok(200, "GET", "/procurement/landed-cost-vouchers/" + v + "/preview", null);
        assertThat(second.get("previewHash").asString()).isNotEqualTo(first.get("previewHash").asString());
        assertThat(second.get("inputsHash").asString()).isEqualTo(first.get("inputsHash").asString());
        // A transfer with no lineage → the share would be parked: the preview says so before posting.
        asAdmin(() -> stock.transfer(com.inventoryerp.inventory.application.StockMove.transfer(p, w1, w2,
                new BigDecimal(30)).source("audit", suffix())));
        jdbc.sql("DELETE FROM \"InventoryTransferLineage\" WHERE \"sourceLedgerId\" IN (SELECT id FROM"
                + " \"InventoryLedger\" WHERE \"productId\" = ? AND type = 'transfer_out')").param(p).update();
        JsonNode third = ok(200, "GET", "/procurement/landed-cost-vouchers/" + v + "/preview", null);
        assertThat(third.get("warnings").get(0).asString()).contains("will be parked in 1396");
    }

    // ------------------------------------------------------------------ duplicates

    Map<String, Object> invoiceCharge(String ref, int amount) {
        return charge("kind", "freight", "amount", amount, "supplierId", forwarder, "supplierInvoiceRef", ref,
                "billMode", "none");
    }

    @Test
    @DisplayName("duplicates: AB-12 then 'ab 12' → 409 naming the original; one invoice on two lines of a voucher is fine")
    void normalisedDuplicates() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 10, 50);
        String base = "AB-" + suffix().substring(0, 4).toUpperCase();
        String first = voucher(List.of(grn), List.of(invoiceCharge(base, 10), invoiceCharge(base, 5)));
        Res dup = createVoucher(List.of(grn), List.of(invoiceCharge(" " + base.toLowerCase().replace("-", " ") + " ",
                10)));
        assertThat(dup.status()).isEqualTo(409);
        String code = ok(200, "GET", "/procurement/landed-cost-vouchers/" + first, null).get("code").asString();
        assertThat(dup.body().get("message").asString()).contains("is already on landed cost " + code)
                .contains("(entered as " + base + ")");
        // exact matching (numbers entered under it): separators count, case and padding do not.
        String s = setting("procurement.invoiceRef.normalization", "\"exact\"");
        try {
            String exact = "XY-" + suffix().substring(0, 4).toUpperCase();
            assertThat(createVoucher(List.of(grn), List.of(invoiceCharge(exact, 10))).status()).isEqualTo(200);
            assertThat(createVoucher(List.of(grn), List.of(invoiceCharge(exact.replace("-", ""), 10))).status())
                    .isEqualTo(200);
            assertThat(createVoucher(List.of(grn), List.of(invoiceCharge(" " + exact.toLowerCase(), 10))).status())
                    .isEqualTo(409);
        } finally {
            unset(s);
        }
        // Cancelled vouchers no longer hold their invoices.
        action(first, "cancel");
        assertThat(createVoucher(List.of(grn), List.of(invoiceCharge(base, 10))).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("override: approved with a reason, used once, not revocable once used; 403 without the permission")
    void duplicateOverride() {
        String p = product("AVCO", false, 0);
        String grn = receive(p, 10, 50);
        String ref = "OV-" + suffix();
        voucher(List.of(grn), List.of(invoiceCharge(ref, 10)));
        String clerk = userWith("landed_cost:read", "landed_cost:create");
        Map<String, Object> req = Map.of("supplierId", forwarder, "supplierInvoiceRef", ref, "reason",
                "Second shipment billed on the same invoice number");
        Map<String, String> clerkKey = new LinkedHashMap<>(Map.of("Authorization", "Bearer " + clerk,
                "Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(call("POST", "/procurement/invoice-duplicate-overrides", req, clerkKey).status()).isEqualTo(403);
        Res o = call("POST", "/procurement/invoice-duplicate-overrides", req, key());
        assertThat(o.status()).as(o.raw().body()).isIn(200, 201);
        String override = o.body().get("id").asString();
        Map<String, Object> withOverride = invoiceCharge(ref, 10);
        withOverride.put("duplicateOverrideId", override);
        assertThat(createVoucher(List.of(grn), List.of(withOverride)).status()).isEqualTo(200);
        Res again = createVoucher(List.of(grn), List.of(withOverride));
        assertThat(again.status()).isEqualTo(400);
        assertThat(again.body().get("message").asString()).contains("has already been used");
        assertThat(call("POST", "/procurement/invoice-duplicate-overrides/" + override + "/revoke", null, key())
                .status()).isEqualTo(400);
        boolean listed = false;
        for (JsonNode n : ok(200, "GET", "/procurement/invoice-duplicate-overrides", null)) {
            listed |= override.equals(n.get("id").asString()) && !n.get("usedByVoucherCode").isNull();
        }
        assertThat(listed).isTrue();
    }

    // ------------------------------------------------------------------ allocation validation

    @Test
    @DisplayName("allocation: by weight with a product missing its weight → 400 naming it; weight used is stored")
    void weightValidation() {
        String heavy = product("AVCO", false, 0);
        String light = product("AVCO", false, 0);
        jdbc.sql("UPDATE \"Product\" SET weight = 2 WHERE id = ?").param(heavy).update();
        String grnHeavy = receive(heavy, 10, 50);
        String grnLight = receive(light, 10, 50);
        String name = jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?").param(light).query(String.class).single();
        String v = voucher(List.of(grnHeavy, grnLight), List.of(charge("kind", "freight", "amount", 100,
                "allocationMethod", "weight")));
        action(v, "submit");
        Res r = action(v, "approve");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("message").asString()).contains("set the weight of " + name);
        jdbc.sql("UPDATE \"Product\" SET weight = 3 WHERE id = ?").param(light).update();
        assertThat(action(v, "approve").status()).isEqualTo(200);
        assertThat(action(v, "post").status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT \"weightUsed\" FROM \"LandedCostAllocation\" WHERE \"landedCostId\" = ? AND"
                + " \"productId\" = ?").params(v, light).query(BigDecimal.class).single()).isEqualByComparingTo("3");
        // 10 × 2 = 20 vs 10 × 3 = 30 → 40 / 60.
        assertThat(jdbc.sql("SELECT \"allocatedAmount\" FROM \"LandedCostAllocation\" WHERE \"landedCostId\" = ? AND"
                + " \"productId\" = ?").params(v, heavy).query(BigDecimal.class).single()).isEqualByComparingTo("40");
    }
}
