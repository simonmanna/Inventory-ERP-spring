package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.LedgerIntegrityService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.support.AccountingFixtures;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Finance readiness audit, gate G1: integrity checks I10 (fixed-asset register against its accounts) and I12 (each
 * closed cash session's own journals against its count), each on fresh books: clean first, then broken.
 */
@com.inventoryerp.accounting.support.LedgerInvariantsCheck.Skip("creates register / GL differences on purpose to prove I10 and I12 find them")
class AssetAndDrawerIntegrityIT extends AccountingFixtures {

    @Autowired
    AccountResolverService accounts;

    @Autowired
    PostingService posting;

    @Test
    @DisplayName("I10: the register ties to the fixed-asset accounts; a cost posting with no asset behind it fails")
    void fixedAssets() {
        Books b = freshBooks();
        Map<String, String> acc = inTenant(b.org(), b.ownerId(), () -> Map.of(
                "fa", accounts.ensureByCode("1595", "I10 plant", "non_current_asset", "fixed_asset_valuation"),
                "accDep", accounts.ensureByCode("1596", "I10 accumulated depreciation", "contra_asset",
                        "accumulated_depreciation"),
                "eq", accounts.ensureByCode("3955", "I10 capital", "equity", null),
                "depExp", accounts.ensureByCode("6955", "I10 depreciation", "operating_expense", null)));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String asset = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Asset\" (id, \"organizationId\", \"assetCode\", name, \"purchaseDate\","
                + " \"purchaseCost\", \"currentValue\", \"updatedAt\") VALUES (?, ?, 'I10-1', 'Generator', ?, 1000, 900,"
                + " now())").params(asset, b.org(), Timestamp.valueOf(today.minusDays(40).atTime(12, 0))).update();
        jdbc.sql("INSERT INTO \"AssetDepreciation\" (id, \"organizationId\", \"assetId\", period, \"assetCost\","
                + " \"usefulLife\", \"depreciationAmount\", \"accumulatedDepr\", \"bookValue\", \"isPosted\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 1000, 60, 100, 100, 900, true, now())")
                .params(UUID.randomUUID().toString(), b.org(), asset, today.toString().substring(0, 7)).update();
        post(b, "asset_acquisition", null, acc.get("fa"), acc.get("eq"), 1000);
        post(b, "asset_depreciation", null, acc.get("depExp"), acc.get("accDep"), 100);

        JsonNode clean = check(report(b), LedgerIntegrityService.FIXED_ASSETS);
        assertThat(clean.get("ok").asBoolean()).as(clean.toString()).isTrue();
        assertThat(clean.get("skipped").asBoolean()).isFalse();

        post(b, "acc0", null, acc.get("fa"), acc.get("eq"), 400);
        JsonNode broken = check(report(b), LedgerIntegrityService.FIXED_ASSETS);
        assertThat(broken.get("ok").asBoolean()).isFalse();
        assertThat(broken.get("failures").asLong()).as("only the cost line").isEqualTo(1);
        JsonNode cost = broken.get("rows").get(0);
        assertThat(cost.get("line").asString()).isEqualTo("COST");
        assertThat(dec(cost.get("register"))).isEqualByComparingTo("1000");
        assertThat(dec(cost.get("gl"))).isEqualByComparingTo("1400");
        assertThat(dec(cost.get("difference"))).isEqualByComparingTo("-400");
    }

    @Test
    @DisplayName("I12: a closed shift's journals moved its drawer by the count less the float; a second over / short"
            + " entry fails it")
    void cashDrawers() {
        Books b = freshBooks();
        String register = okAs(b, 201, "POST", "/cash-registers", Map.of("code", "I12-" + UUID.randomUUID()
                .toString().substring(0, 6), "name", "I12 till")).get("id").asString();
        Res opened = moneyAs(b, "POST", "/cash-sessions/open", Map.of("cashRegisterId", register, "openingFloat", 0));
        assertThat(opened.status()).as(opened.raw().body()).isEqualTo(200);
        String session = opened.body().get("id").asString();
        Res paidIn = moneyAs(b, "POST", "/cash-sessions/movement", Map.of("sessionId", session, "movementType",
                "pay_in", "amount", 1000, "reason", "Change float from the bank", "counterpartAccountId",
                account(b.org(), "1200")));
        assertThat(paidIn.status()).as(paidIn.raw().body()).isBetween(200, 201);
        Res closed = moneyAs(b, "POST", "/cash-sessions/close", Map.of("sessionId", session, "closingCounted", 990,
                "varianceReason", "Short in the count"));
        assertThat(closed.status()).as(closed.raw().body()).isEqualTo(200);
        assertThat(new BigDecimal(closed.body().get("closingDifference").asString())).isEqualByComparingTo("-10");

        JsonNode clean = check(report(b), LedgerIntegrityService.CASH_DRAWERS);
        assertThat(clean.get("ok").asBoolean()).as("pay-in 1,000 less 10 short = 990 counted: " + clean).isTrue();

        // The shift's over / short posted a second time: its drawer moved 25 more than was counted.
        String drawer = jdbc.sql("SELECT \"drawerAccountId\" FROM \"CashSession\" WHERE id = ?").param(session)
                .query(String.class).single();
        post(b, "cash_session_variance", session, drawer, mapped(b.org(), "cash_short_over"), 25);
        JsonNode broken = check(report(b), LedgerIntegrityService.CASH_DRAWERS);
        assertThat(broken.get("ok").asBoolean()).isFalse();
        assertThat(broken.get("failures").asLong()).isEqualTo(1);
        JsonNode row = broken.get("rows").get(0);
        assertThat(row.get("sessionId").asString()).isEqualTo(session);
        assertThat(dec(row.get("cash"))).isEqualByComparingTo("990");
        assertThat(dec(row.get("gl"))).isEqualByComparingTo("1015");
        assertThat(dec(row.get("difference"))).isEqualByComparingTo("25");
    }

    // ------------------------------------------------------------------ helpers

    private void post(Books b, String sourceType, String sourceId, String debit, String credit, int amount) {
        inTenant(b.org(), b.ownerId(), () -> posting.post(new PostingRequest("GEN", Instant.now(), "G1 " + sourceType,
                null, null, sourceType, sourceId != null ? sourceId : UUID.randomUUID().toString(), null, null, null,
                null, null, List.of(PostingRequest.Line.debit(debit, amount, null),
                        PostingRequest.Line.credit(credit, amount, null)))));
    }

    private JsonNode report(Books b) {
        return okAs(b, 200, "GET", "/reports/accounting/integrity", null);
    }

    private static JsonNode check(JsonNode report, String key) {
        for (JsonNode c : report.get("checks")) {
            if (key.equals(c.get("key").asString())) {
                return c;
            }
        }
        throw new AssertionError("no check " + key + " in " + report);
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }
}
