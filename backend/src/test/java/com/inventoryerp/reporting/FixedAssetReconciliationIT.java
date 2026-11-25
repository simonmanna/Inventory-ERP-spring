package com.inventoryerp.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * R15: {@code fa.reconciliation} compares the register (cost with revaluations; posted depreciation plus impairment)
 * with the accounts fixed-asset postings use, not with every non-current asset account. Construction in progress and
 * an intangible asset carry balances that must stay out of the cost side.
 */
class FixedAssetReconciliationIT extends ApiTestSupport {

    @Autowired
    PostingService posting;

    @Autowired
    AccountResolverService accounts;

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static JsonNode row(JsonNode result, String id) {
        for (JsonNode r : result.get("rows")) {
            if (r.get("id").asString().equals(id)) {
                return r;
            }
        }
        throw new AssertionError("no row " + id + " in " + result.get("rows"));
    }

    static JsonNode check(JsonNode result, String code) {
        for (JsonNode c : result.get("checks")) {
            if (c.get("code").asString().equals(code)) {
                return c;
            }
        }
        throw new AssertionError("no check " + code + " in " + result.get("checks"));
    }

    @Test
    @DisplayName("[R15] register cost, depreciation and impairment tie to the fixed-asset accounts only")
    void reconcilesAgainstFixedAssetAccountsOnly() {
        String code = "far-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "FA Recon Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner", "adminPassword",
                "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String org = boot.body().get("organization").get("id").asString();
        String token = login(code, "owner@" + code + ".test", "Owner@12345");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant now = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Timestamp nowTs = Timestamp.valueOf(now.atOffset(ZoneOffset.UTC).toLocalDateTime());
        String period = today.toString().substring(0, 7);

        Map<String, String> acc = TenantContext.run(TenantContext.TenantStore.organization(org), () -> {
            Map<String, String> m = new HashMap<>();
            m.put("fa", accounts.ensureByCode("1595", "Test plant", "non_current_asset", "fixed_asset_valuation"));
            m.put("accDep", accounts.ensureByCode("1596", "Test accumulated depreciation", "contra_asset",
                    "accumulated_depreciation"));
            m.put("accImp", accounts.ensureByCode("ACCUM-IMPAIRMENT", "Accumulated Impairment", "contra_asset",
                    "accumulated_impairment"));
            m.put("cip", accounts.ensureByCode("1597", "Test construction in progress", "non_current_asset", null));
            m.put("intangible", accounts.ensureByCode("1598", "Test software licence", "non_current_asset", null));
            m.put("eq", accounts.ensureByCode("3955", "Test capital", "equity", null));
            m.put("surplus", accounts.ensureByCode("3956", "Test revaluation surplus", "equity", null));
            m.put("depExp", accounts.ensureByCode("6955", "Test depreciation", "operating_expense", null));
            m.put("impLoss", accounts.ensureByCode("6956", "Test impairment loss", "other_expense", null));
            return m;
        });

        // Register: a purchased asset (1,000) revalued +200, depreciated 100 (posted), impaired 50; a constructed asset
        // capitalised from CIP at 300.
        String plant = UUID.randomUUID().toString();
        String built = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Asset\" (id, \"organizationId\", \"assetCode\", name, \"purchaseDate\","
                + " \"purchaseCost\", \"currentValue\", \"updatedAt\") VALUES (?, ?, 'FAR-1', 'Crusher', ?, 1000, 1050,"
                + " now())").params(plant, org, nowTs).update();
        jdbc.sql("INSERT INTO \"Asset\" (id, \"organizationId\", \"assetCode\", name, \"purchaseDate\","
                + " \"purchaseCost\", \"capitalizedCost\", \"currentValue\", \"acquisitionMethod\", \"updatedAt\")"
                + " VALUES (?, ?, 'FAR-2', 'Yard', ?, 300, 300, 300, 'constructed', now())").params(built, org, nowTs)
                .update();
        jdbc.sql("INSERT INTO \"AssetRevaluation\" (id, \"organizationId\", \"assetId\", \"previousValue\","
                + " \"newValue\", \"revaluationDate\", \"updatedAt\") VALUES (?, ?, ?, 1000, 1200, ?, now())")
                .params(UUID.randomUUID().toString(), org, plant, nowTs).update();
        jdbc.sql("INSERT INTO \"AssetDepreciation\" (id, \"organizationId\", \"assetId\", period, \"assetCost\","
                + " \"usefulLife\", \"depreciationAmount\", \"accumulatedDepr\", \"bookValue\", \"isPosted\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 1000, 60, 100, 100, 1100, true, now())")
                .params(UUID.randomUUID().toString(), org, plant, period).update();
        jdbc.sql("INSERT INTO \"AssetImpairment\" (id, \"organizationId\", \"assetId\", kind, \"impairmentDate\","
                + " \"carryingBefore\", \"recoverableAmount\", amount, \"carryingAfter\", \"remainingLifeMonths\","
                + " \"depreciationCount\") VALUES (?, ?, ?, 'impairment', ?, 1100, 1050, 50, 1050, 59, 1)")
                .params(UUID.randomUUID().toString(), org, plant, nowTs).update();

        TenantContext.run(TenantContext.TenantStore.organization(org), () -> {
            Object[][] entries = {
                // debit, credit, amount, sourceType
                {"fa", "eq", 1000, "asset_acquisition"},
                {"fa", "surplus", 200, "asset_revaluation"},
                {"depExp", "accDep", 100, "asset_depreciation"},
                {"impLoss", "accImp", 50, "asset_impairment"},
                {"cip", "eq", 5000, "test"},
                {"fa", "cip", 300, "project_capitalisation"},
                {"intangible", "eq", 700, "test"}};
            for (Object[] e : entries) {
                posting.post(new PostingRequest("GEN", now, "far", null, null, (String) e[3],
                        UUID.randomUUID().toString(), null, null, null, null, null, List.of(
                                new Line(acc.get((String) e[0]), e[2], null, null, "dr", null, null, null),
                                new Line(acc.get((String) e[1]), null, e[2], null, "cr", null, null, null))));
            }
            return null;
        });

        Res r = call("POST", "/reports/run/fa.reconciliation", Map.of("asOf", today.toString()), token);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode result = r.body();
        JsonNode cost = row(result, "COST").get("values");
        assertThat(dec(cost.get("register"))).as("1,000 + 200 revaluation + 300 constructed")
                .isEqualByComparingTo("1500");
        assertThat(dec(cost.get("gl"))).as("CIP (4,700) and the intangible (700) stay out")
                .isEqualByComparingTo("1500");
        assertThat(dec(cost.get("difference"))).isEqualByComparingTo("0");
        JsonNode accumulated = row(result, "ACC").get("values");
        assertThat(dec(accumulated.get("register"))).as("100 depreciation + 50 impairment").isEqualByComparingTo("150");
        assertThat(dec(accumulated.get("gl"))).isEqualByComparingTo("150");
        assertThat(dec(row(result, "NBV").get("values").get("difference"))).isEqualByComparingTo("0");
        assertThat(check(result, "COST_RECONCILES").get("ok").asBoolean()).isTrue();
        assertThat(check(result, "DEPRECIATION_RECONCILES").get("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("[R15] an asset capitalised before its in-service date is held from the capitalisation date")
    void capitalisedBeforeInService() {
        String code = "fac-" + UUID.randomUUID().toString().substring(0, 8);
        Res boot = call("POST", "/organizations/bootstrap", Map.of("organizationCode", code, "organizationName",
                "FA Capitalisation Co", "adminEmail", "owner@" + code + ".test", "adminFirstName", "Owner",
                "adminPassword", "Owner@12345"), (String) null);
        assertThat(boot.status()).as(boot.raw().body()).isEqualTo(201);
        String org = boot.body().get("organization").get("id").asString();
        String token = login(code, "owner@" + code + ".test", "Owner@12345");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate inService = today.withDayOfMonth(1).plusMonths(1);
        Instant now = today.atTime(12, 0).toInstant(ZoneOffset.UTC);
        Map<String, String> acc = TenantContext.run(TenantContext.TenantStore.organization(org), () -> Map.of(
                "fa", accounts.ensureByCode("1595", "Test plant", "non_current_asset", "fixed_asset_valuation"),
                "cip", accounts.ensureByCode("1597", "Test construction in progress", "non_current_asset", null),
                "eq", accounts.ensureByCode("3955", "Test capital", "equity", null)));

        // A capital project capitalised today into a depot that goes into service next month: the asset's purchase
        // date is its in-service date, the cost moves from CIP to the fixed-asset account today.
        String project = UUID.randomUUID().toString();
        String capitalisation = UUID.randomUUID().toString();
        String depot = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Project\" (id, \"organizationId\", code, name) VALUES (?, ?, 'CAP-1', 'Depot')")
                .params(project, org).update();
        jdbc.sql("INSERT INTO \"Asset\" (id, \"organizationId\", \"assetCode\", name, \"purchaseDate\","
                + " \"inServiceDate\", \"purchaseCost\", \"capitalizedCost\", \"currentValue\", \"acquisitionMethod\","
                + " \"updatedAt\") VALUES (?, ?, 'FAC-1', 'Depot', ?, ?::date, 800, 800, 800, 'constructed', now())")
                .params(depot, org, Timestamp.valueOf(inService.atStartOfDay()), inService.toString()).update();
        jdbc.sql("INSERT INTO \"ProjectCapitalisation\" (id, \"organizationId\", \"projectId\", number,"
                + " \"capitalisationDate\", amount, status) VALUES (?, ?, ?, 'CAP/00001', ?::date, 800, 'posted')")
                .params(capitalisation, org, project, today.toString()).update();
        jdbc.sql("INSERT INTO \"ProjectCapitalisationAsset\" (id, \"organizationId\", \"capitalisationId\","
                + " \"assetId\", amount, \"inServiceDate\") VALUES (?, ?, ?, ?, 800, ?::date)")
                .params(UUID.randomUUID().toString(), org, capitalisation, depot, inService.toString()).update();
        TenantContext.run(TenantContext.TenantStore.organization(org), () -> {
            for (Object[] e : new Object[][] {{"cip", "eq", "test"}, {"fa", "cip", "project_capitalisation"}}) {
                posting.post(new PostingRequest("GEN", now, "fac", null, null, (String) e[2],
                        UUID.randomUUID().toString(), null, null, null, null, null, List.of(
                                new Line(acc.get((String) e[0]), 800, null, null, "dr", null, null, null),
                                new Line(acc.get((String) e[1]), null, 800, null, "cr", null, null, null))));
            }
            return null;
        });

        Res r = call("POST", "/reports/run/fa.reconciliation", Map.of("asOf", today.toString()), token);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode cost = row(r.body(), "COST").get("values");
        assertThat(dec(cost.get("register"))).as("held from the capitalisation date").isEqualByComparingTo("800");
        assertThat(dec(cost.get("gl"))).isEqualByComparingTo("800");
        assertThat(check(r.body(), "COST_RECONCILES").get("ok").asBoolean()).isTrue();
        Res register = call("POST", "/reports/run/fa.register", Map.of("asOf", today.toString()), token);
        assertThat(register.status()).as(register.raw().body()).isEqualTo(200);
        assertThat(register.raw().body()).contains("FAC-1");
        Res before = call("POST", "/reports/run/fa.reconciliation", Map.of("asOf", today.minusDays(1).toString()),
                token);
        assertThat(before.status()).as(before.raw().body()).isEqualTo(200);
        assertThat(dec(row(before.body(), "COST").get("values").get("register"))).isEqualByComparingTo("0");
        assertThat(dec(row(before.body(), "COST").get("values").get("gl"))).isEqualByComparingTo("0");
    }
}
