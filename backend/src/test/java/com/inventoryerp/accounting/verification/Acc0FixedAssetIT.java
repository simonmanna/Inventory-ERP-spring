package com.inventoryerp.accounting.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import com.inventoryerp.accounting.support.LedgerInvariants;
import com.inventoryerp.fixedasset.application.DepreciationCalculator;
import com.inventoryerp.fixedasset.application.DepreciationCalculator.Input;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ACC-0 reproduction: fixed-asset accounting (finding B11). */
class Acc0FixedAssetIT extends AccountingFixtures {

    String asset(int cost, int salvage, int lifeMonths, String method) {
        String cat = ok(201, "POST", "/asset-categories", Map.of("name", "ACC0 " + UUID.randomUUID().toString()
                .substring(0, 6), "depreciationMethod", method)).get("id").asString();
        return ok(201, "POST", "/fixed-assets", Map.of("name", "ACC0 crusher " + UUID.randomUUID().toString()
                .substring(0, 6), "purchaseCost", cost, "salvageValue", salvage, "usefulLife", lifeMonths, "categoryId",
                cat, "purchaseDate", today())).get("id").asString();
    }

    /** Entries whose source is the asset or one of its acquisition / disposal / revaluation records. */
    List<String> assetEntries(String assetId) {
        return jdbc.sql("SELECT e.id FROM \"JournalEntry\" e WHERE e.\"organizationId\" = ? AND (e.\"sourceId\" = ? OR"
                + " e.\"sourceId\" IN (SELECT id FROM \"AssetAcquisition\" WHERE \"assetId\" = ?) OR e.\"sourceId\" IN"
                + " (SELECT id FROM \"AssetDisposal\" WHERE \"assetId\" = ?) OR e.\"sourceId\" IN (SELECT id FROM"
                + " \"AssetDepreciation\" WHERE \"assetId\" = ?))").params(demoOrg, assetId, assetId, assetId, assetId)
                .query(String.class).list();
    }

    BigDecimal netOn(String key, List<String> entries) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l WHERE"
                + " l.\"journalEntryId\" = ANY(?::text[]) AND l.\"accountId\" = ?").params(entries.toArray(String[]::new),
                mapped(demoOrg, key)).query(BigDecimal.class).single();
    }

    @Test
    @DisplayName("[B11][owner:accounting][ACC-5] acquisition capitalises the asset in the GL")
    void acquisitionPostsToGl() {
        String id = asset(1200, 0, 12, "straight_line");
        Res acq = as("POST", "/fixed-assets/" + id + "/acquisition", Map.of("vendor", "ACC0 Plant Ltd"));
        assertThat(acq.status()).as(acq.raw().body()).isEqualTo(201);
        List<String> entries = assetEntries(id);
        assertThat(netOn("fixed_asset_valuation", entries)).as("asset cost capitalised (entries " + entries + ")")
                .isEqualByComparingTo("1200");
    }

    @Test
    @DisplayName("[B11][owner:accounting][ACC-5] disposal removes cost and accumulated depreciation, books gain/loss")
    void disposalPostsGainLoss() {
        String id = asset(1200, 0, 12, "straight_line");
        ok(201, "POST", "/fixed-assets/" + id + "/depreciation/run", Map.of("period", "2026-01", "assetId", id,
                "postEntries", true));
        ok(201, "POST", "/fixed-assets/" + id + "/depreciation/run", Map.of("period", "2026-02", "assetId", id,
                "postEntries", true));
        ok(201, "POST", "/fixed-assets/" + id + "/disposal", Map.of("disposalMethod", "sold", "disposalValue", 1050));
        List<String> entries = assetEntries(id);
        LedgerInvariants.entriesBalanced(jdbc, demoOrg, entries);
        // Cost 1,200, accumulated 200, NBV 1,000, proceeds 1,050 → gain 50.
        assertThat(netOn("accumulated_depreciation", entries)).as("accumulated depreciation cleared")
                .isEqualByComparingTo("0");
        assertThat(netOn("asset_gain_loss", entries)).as("gain on disposal (credit)").isEqualByComparingTo("-50");
    }

    @Test
    @DisplayName("[B11][owner:accounting][ACC-5] revaluation: increase to surplus, decrease releases it then loses")
    void revaluationSurplusThenLoss() {
        String id = asset(1000, 0, 60, "straight_line");
        ok(201, "POST", "/fixed-assets/" + id + "/acquisition", Map.of("vendor", "ACC0"));
        ok(201, "POST", "/fixed-assets/" + id + "/revaluations", Map.of("previousValue", 1000, "newValue", 1200));
        List<String> entries = assetEntries(id);
        assertThat(netOn("fixed_asset_valuation", revaluations(id))).isEqualByComparingTo("200");
        assertThat(netOn("asset_revaluation_surplus", revaluations(id))).as("surplus (credit)")
                .isEqualByComparingTo("-200");
        ok(201, "POST", "/fixed-assets/" + id + "/revaluations", Map.of("previousValue", 1200, "newValue", 900));
        assertThat(netOn("asset_revaluation_surplus", revaluations(id))).as("surplus fully released")
                .isEqualByComparingTo("0");
        assertThat(netOn("asset_gain_loss", revaluations(id))).as("loss beyond the surplus")
                .isEqualByComparingTo("100");
        assertThat(netOn("fixed_asset_valuation", revaluations(id))).isEqualByComparingTo("-100");
        LedgerInvariants.entriesBalanced(jdbc, demoOrg, entries);
        // Re-posting the acquisition record (an update) does not capitalise twice.
        ok(201, "POST", "/fixed-assets/" + id + "/acquisition", Map.of("vendor", "ACC0 again"));
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceType\" = 'asset_acquisition' AND"
                + " \"sourceId\" = ?").param(id).query(Long.class).single()).isEqualTo(1);
    }

    List<String> revaluations(String assetId) {
        return jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"sourceType\" = 'asset_revaluation' AND \"sourceId\" = ?")
                .param(assetId).query(String.class).list();
    }

    @Test
    @DisplayName("[B11][owner:accounting][ACC-5] double-declining uses twice the straight-line rate")
    void doubleDecliningRate() {
        // 1,200 over 60 months (5 years): straight-line rate 20 %/yr → DDB 40 %/yr → first month 1,200 × 0.4 / 12 = 40.
        var r = DepreciationCalculator.calculate("double_declining", new Input(new BigDecimal("1200"), BigDecimal.ZERO,
                60, new BigDecimal("1200"), BigDecimal.ZERO));
        assertThat(r.depreciationAmount()).as("first-month DDB charge").isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("[B11][owner:accounting][ACC-5] depreciation never takes book value below the residual value")
    void residualFloor() {
        BigDecimal book = new BigDecimal("1000");
        BigDecimal acc = BigDecimal.ZERO;
        for (int m = 0; m < 200; m++) {
            for (String method : List.of("straight_line", "declining_balance")) {
                var r = DepreciationCalculator.calculate(method, new Input(new BigDecimal("1000"), new BigDecimal("100"),
                        24, book, acc));
                assertThat(r.bookValue()).as(method + " month " + m).isGreaterThanOrEqualTo(new BigDecimal("100"));
            }
            var r = DepreciationCalculator.calculate("straight_line", new Input(new BigDecimal("1000"),
                    new BigDecimal("100"), 24, book, acc));
            book = r.bookValue();
            acc = r.accumulatedDepreciation();
        }
        assertThat(book).isEqualByComparingTo("100");
    }
}
