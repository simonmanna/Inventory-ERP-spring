package com.inventoryerp.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.quality.application.QualityCommands.OpenLot;
import com.inventoryerp.quality.application.QualityGateService;
import com.inventoryerp.quality.application.QualityGateService.GateStatus;
import com.inventoryerp.quality.application.QualityIncomingService;
import com.inventoryerp.quality.application.QualityLotService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Quality 2.6 over HTTP: specification versions, incoming lots opened from a posted GRN (quarantine of exactly the
 * received batch), results with sieve grading → fail → auto-NCR → reject (scrap, idempotent) and downgrade (stock
 * reclassified, GL balanced), pass → partial and full release back to stock, SoD, concession release, the 2.1 stage
 * gate, laboratory CSV import and simulator fetch, the certificate of analysis PDF and a contended decision.
 */
class QualityLotsIT extends QualityTestSupport {

    @Autowired
    QualityIncomingService incoming;

    @Autowired
    QualityLotService lotService;

    @Autowired
    QualityGateService gates;

    @Test
    @DisplayName("specification: validation, activate, draft-only edits, revise → v2, supersede, archive")
    void specificationVersions() {
        String product = product("Sand", false);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("code", "SP-" + next());
        bad.put("name", "Sand spec");
        bad.put("parameters", List.of(Map.of("code", "M", "name", "Moisture", "minValue", 5, "maxValue", 3)));
        assertThat(msg(as("POST", "/quality/specifications", bad))).isEqualTo("Parameter M: minimum is above maximum.");
        bad.put("parameters", List.of(Map.of("code", "M", "name", "a"), Map.of("code", "m", "name", "b")));
        assertThat(msg(as("POST", "/quality/specifications", bad))).isEqualTo("Parameter M is listed twice.");
        bad.put("parameters", List.of(Map.of("code", "G", "name", "Grading", "sieveEnvelope", List.of(Map.of("sieveMm", 5)))));
        assertThat(msg(as("POST", "/quality/specifications", bad))).contains("needs kind sieve");
        bad.put("parameters", List.of(Map.of("code", "G", "name", "Grading", "kind", "sieve")));
        JsonNode empty = ok(201, "POST", "/quality/specifications", bad);
        assertThat(msg(as("POST", "/quality/specifications/" + empty.get("id").asString() + "/activate", null)))
                .isEqualTo("Sieve parameter G needs a grading envelope.");

        JsonNode v1 = activeSpec(product, true);
        String id = v1.get("id").asString();
        assertThat(v1.get("status").asString()).isEqualTo("active");
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        assertThat(v1.get("parameters")).hasSize(3);
        assertThat(v1.get("parameters").get(1).get("sieveEnvelope").get(0).get("sieveMm").asInt()).isEqualTo(20);
        assertThat(as("POST", "/quality/specifications", Map.of("code", v1.get("code").asString(), "name", "dup"))
                .status()).isEqualTo(409);
        assertThat(msg(as("PATCH", "/quality/specifications/" + id, Map.of("name", "x"))))
                .contains("Only a draft specification can be edited");

        JsonNode v2 = ok(201, "POST", "/quality/specifications/" + id + "/revise", null);
        String v2id = v2.get("id").asString();
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("status").asString()).isEqualTo("draft");
        assertThat(v2.get("parameters")).hasSize(3);
        assertThat(as("POST", "/quality/specifications/" + id + "/revise", null).status()).isEqualTo(409);
        JsonNode edited = ok(200, "PATCH", "/quality/specifications/" + v2id, Map.of("parameters", List.of(
                Map.of("code", "MOIST", "name", "Moisture", "maxValue", 2.5))));
        assertThat(edited.get("parameters")).hasSize(1);
        ok(200, "POST", "/quality/specifications/" + v2id + "/activate", null);
        assertThat(ok(200, "GET", "/quality/specifications/" + id, null).get("status").asString()).isEqualTo("superseded");
        JsonNode versions = ok(200, "GET", "/quality/specifications/" + v2id, null).get("versions");
        assertThat(versions).hasSize(2);
        // A plan pointing at v1 opens lots against the active v2.
        JsonNode plan = plan("final", product, id, Map.of());
        assertThat(plan.get("activeSpecificationId").asString()).isEqualTo(v2id);
        assertThat(ok(200, "POST", "/quality/specifications/" + v2id + "/archive", null).get("status").asString())
                .isEqualTo("archived");
        assertThat(msg(as("POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", product,
                "quantity", 10)))).contains("has no active version");
        assertThat(ok(200, "GET", "/quality/specifications?status=archived&search=" + v1.get("code").asString(), null)
                .get("meta").get("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("GRN → incoming lot quarantines the received batch → fail → NCR → reject (idempotent) + downgrade")
    void incomingFailRejectDowngrade() {
        String cement = product("Cement 42.5N", true);
        String secondGrade = product("Cement second grade", true);
        // An older batch already in the yard: quarantine must move the received batch, not the oldest one.
        receive(cement, warehouse, "40", "30000", "OLD-" + sfx);
        JsonNode spec = activeSpec(cement, false);
        plan("incoming", cement, spec.get("id").asString(), Map.of("holdPolicy", "quarantine", "samplingMethod", "fixed",
                "sampleSize", 2));
        String batch = "CEM-" + sfx;
        JsonNode receipt = grn(supplier, cement, 100, 32000, batch);
        String grnId = receipt.get("id").asString();
        drainOutbox();
        JsonNode list = lotsOf("goods_receipt", grnId);
        assertThat(list).hasSize(1);
        String lotId = list.get(0).get("id").asString();
        JsonNode lot = ok(200, "GET", "/quality/inspection-lots/" + lotId, null);
        assertThat(lot.get("lotNumber").asString()).startsWith("QL-");
        assertThat(lot.get("stage").asString()).isEqualTo("incoming");
        assertThat(lot.get("status").asString()).isEqualTo("quarantined");
        assertThat(lot.get("supplierPartnerId").asString()).isEqualTo(supplier);
        assertThat(lot.get("sampleSize").asInt()).isEqualTo(2);
        assertThat(lot.get("sourceRef").asString()).startsWith("GRN ");
        assertThat(dec(lot.get("quantity"))).isEqualByComparingTo("100");
        String qc = quarantine();
        assertThat(inBatch(cement, qc, batch)).isEqualByComparingTo("100");
        assertThat(inBatch(cement, warehouse, batch)).isEqualByComparingTo("0");
        assertThat(inBatch(cement, warehouse, "OLD-" + sfx)).isEqualByComparingTo("40");
        // Redelivered events / the PO-receive path are idempotent per GRN line.
        TenantContext.run(TenantStore.organization(demoOrg), () -> {
            incoming.openForGoodsReceipt(grnId);
            incoming.openForReceiptNumber(receipt.get("receiptNumber").asString());
        });
        assertThat(lotsOf("goods_receipt", grnId)).hasSize(1);

        // Inspector records results: sample 2 moisture out of spec → fail → NCR raised.
        String[] inspector = userWith("quality:read", "quality:inspect");
        Res r1 = call("POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(
                num("MOIST", 1, 2.2), text("CLEAN", 1, "yes"))), inspector[0]);
        assertThat(r1.status()).as(r1.raw().body()).isEqualTo(200);
        assertThat(r1.body().get("result").asString()).isEqualTo("pending");
        assertThat(msg(call("POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(
                num("MOIST", 3, 1))), inspector[0]))).contains("exceeds the sample size");
        assertThat(msg(call("POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(
                num("NOPE", 1, 1))), inspector[0]))).startsWith("Unknown parameter NOPE");
        JsonNode failed = call("POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(
                num("MOIST", 2, 4.1), text("CLEAN", 2, "no"))), inspector[0]).body();
        assertThat(failed.get("result").asString()).isEqualTo("fail");
        assertThat(failed.get("evaluation").get("failures").asInt()).isEqualTo(1);
        assertThat(failed.get("evaluation").get("deviations").asInt()).isEqualTo(1);
        assertThat(failed.get("ncrs")).hasSize(1);
        JsonNode ncr = ok(200, "GET", "/quality/ncrs/" + failed.get("ncrs").get(0).get("id").asString(), null);
        assertThat(ncr.get("title").asString()).contains("MOIST");
        assertThat(ncr.get("source").asString()).isEqualTo("incoming");
        assertThat(ncr.get("supplierPartnerId").asString()).isEqualTo(supplier);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'quality.lot.evaluated' AND"
                + " payload->>'lotId' = ?").param(lotId).query(Long.class).single()).isGreaterThanOrEqualTo(1);
        // The inspector cannot dispose (no permission); release of a failed lot needs a concession.
        assertThat(call("POST", "/quality/inspection-lots/" + lotId + "/reject", Map.of("reason", "x"),
                keyed(inspector[0])).status()).isEqualTo(403);
        assertThat(msg(money("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of())))
                .contains("a concession reason is required");

        // Reject 30 as scrap: Idempotency-Key required, replay returns the same answer without moving stock again.
        assertThat(msg(as("POST", "/quality/inspection-lots/" + lotId + "/reject", Map.of("quantity", 30, "reason",
                "Wet bags")))).contains("Idempotency-Key header is required");
        Map<String, String> h = keyed(adminToken);
        Res rej = call("POST", "/quality/inspection-lots/" + lotId + "/reject", Map.of("quantity", 30, "disposition",
                "scrap", "reason", "Wet bags"), h);
        assertThat(rej.status()).as(rej.raw().body()).isEqualTo(200);
        Res replay = call("POST", "/quality/inspection-lots/" + lotId + "/reject", Map.of("quantity", 30, "disposition",
                "scrap", "reason", "Wet bags"), h);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(dec(replay.body().get("rejectedQty"))).isEqualByComparingTo(dec(rej.body().get("rejectedQty")));
        assertThat(replay.body().get("lotNumber").asString()).isEqualTo(rej.body().get("lotNumber").asString());
        assertThat(dec(rej.body().get("rejectedQty"))).isEqualByComparingTo("30");
        assertThat(dec(rej.body().get("openQty"))).isEqualByComparingTo("70");
        assertThat(inBatch(cement, qc, batch)).isEqualByComparingTo("70");
        assertThat(jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND type::text = 'waste' AND \"referenceType\" = 'quality_lot'").params(demoOrg, cement)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(msg(money("POST", "/quality/inspection-lots/" + lotId + "/reject", Map.of("quantity", 71, "reason",
                "x")))).contains("has only 70 undecided");

        // Downgrade the remaining 70 to second grade at a lower unit cost: stock reclassified, GL write-down balanced.
        Res down = money("POST", "/quality/inspection-lots/" + lotId + "/downgrade", Map.of("productId", secondGrade,
                "unitCost", 20000, "toLocationId", warehouse, "reason", "Moisture above limit"));
        assertThat(down.status()).as(down.raw().body()).isEqualTo(200);
        assertThat(down.body().get("status").asString()).isEqualTo("closed");
        assertThat(dec(down.body().get("downgradedQty"))).isEqualByComparingTo("70");
        assertThat(inBatch(cement, qc, batch)).isEqualByComparingTo("0");
        assertThat(inBatch(secondGrade, warehouse, batch)).isEqualByComparingTo("70");
        assertThat(jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(secondGrade, warehouse).query(BigDecimal.class).single())
                .isEqualByComparingTo("20000");
        JsonNode decision = down.body().get("decisions").get(down.body().get("decisions").size() - 1);
        assertThat(decision.get("decision").asString()).isEqualTo("downgrade");
        assertThat(dec(decision.get("stockValue"))).isEqualByComparingTo("2240000");
        assertThat(dec(decision.get("writeDownValue"))).isEqualByComparingTo("840000");
        String je = decision.get("journalEntryId").asString();
        assertBalanced(je);
        String adjExpense = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND"
                + " key = 'stock_adjustment_expense' AND \"deletedAt\" IS NULL").param(demoOrg).query(String.class).single();
        assertThat(jdbc.sql("SELECT debit FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                .params(je, adjExpense).query(BigDecimal.class).single()).isEqualByComparingTo("840000");
        assertThat(msg(money("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of("concessionReason", "x"))))
                .contains("is closed");
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE entity = 'QualityInspectionLot' AND"
                + " \"entityId\" = ?").param(lotId).query(Long.class).single()).isGreaterThanOrEqualTo(4);

        // Supplier report: one failed lot, its NCR and 100 defective units.
        JsonNode report = ok(200, "GET", "/quality/reports/summary?groupBy=supplier&stage=incoming", null);
        JsonNode row = null;
        for (JsonNode x : report.get("rows")) {
            if (supplier.equals(x.get("key").asString())) {
                row = x;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("failed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(row.get("ncrCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(dec(row.get("defectQty"))).isGreaterThanOrEqualTo(new BigDecimal("100"));
    }

    @Test
    @DisplayName("pass → SoD → partial then full release back to stock; gate passed; simulator lab fetch")
    void incomingPassRelease() {
        String sand = product("River sand", false);
        JsonNode spec = activeSpec(sand, true);
        plan("incoming", sand, spec.get("id").asString(), Map.of("holdPolicy", "quarantine"));
        JsonNode receipt = grn(supplier, sand, 50, 12000, null);
        String grnId = receipt.get("id").asString();
        drainOutbox();
        String lotId = lotsOf("goods_receipt", grnId).get(0).get("id").asString();
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("0");
        assertThat(onHand(sand, quarantine())).isEqualByComparingTo("50");
        GateStatus pending = TenantContext.run(TenantStore.organization(demoOrg), () -> gates.gate("goods_receipt",
                grnId, null));
        assertThat(pending.required()).isTrue();
        assertThat(pending.passed()).isFalse();

        // Admin pulls simulator results → pass; admin recorded them, so admin may not release (SoD).
        JsonNode imp = ok(201, "POST", "/quality/lab-imports/fetch", Map.of("adapter", "simulator", "lotIds",
                List.of(lotId)));
        assertThat(imp.get("status").asString()).isEqualTo("completed");
        assertThat(imp.get("rowsImported").asInt()).isEqualTo(4);
        JsonNode lot = ok(200, "GET", "/quality/inspection-lots/" + lotId, null);
        assertThat(lot.get("result").asString()).isEqualTo("pass");
        assertThat(lot.get("parameters").get(1).get("results").get(0).get("source").asString()).isEqualTo("simulator");
        Res sod = money("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of("quantity", 20));
        assertThat(sod.status()).isEqualTo(403);
        assertThat(msg(sod)).startsWith("Segregation of duties");

        String[] releaser = userWith("quality:read", "quality:release");
        Res part = call("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of("quantity", 20),
                keyed(releaser[0]));
        assertThat(part.status()).as(part.raw().body()).isEqualTo(200);
        assertThat(part.body().get("status").asString()).isEqualTo("quarantined");
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("20");
        Res rest = call("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of(), keyed(releaser[0]));
        assertThat(rest.body().get("status").asString()).isEqualTo("closed");
        assertThat(dec(rest.body().get("releasedQty"))).isEqualByComparingTo("50");
        assertThat(onHand(sand, warehouse)).isEqualByComparingTo("50");
        assertThat(onHand(sand, quarantine())).isEqualByComparingTo("0");
        GateStatus passed = TenantContext.run(TenantStore.organization(demoOrg), () -> gates.gate("goods_receipt",
                grnId, null));
        assertThat(passed.passed()).isTrue();
        JsonNode http = ok(200, "GET", "/quality/gate?sourceType=goods_receipt&sourceId=" + grnId, null);
        assertThat(http.get("passed").asBoolean()).isTrue();
        assertThat(http.get("lotNumber").asString()).isEqualTo(lot.get("lotNumber").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'quality.lot.decided' AND"
                + " payload->>'lotId' = ?").param(lotId).query(Long.class).single()).isGreaterThanOrEqualTo(2);
        assertThat(ok(200, "GET", "/quality/lab-adapters", null).toString()).contains("simulator").contains("csv");
    }

    @Test
    @DisplayName("2.1 stage gate: in-process lot with stockControl = source; concession release; hold; cancel")
    void stageGateAndConcession() {
        String block = product("Block 6in", false);
        JsonNode spec = activeSpec(block, false);
        plan("in_process", block, spec.get("id").asString(), Map.of("stageKey", "CURING", "independentRelease", false,
                "holdPolicy", "hold"));
        String orderId = UUID.randomUUID().toString();
        JsonNode lot = TenantContext.run(TenantStore.user(demoOrg, adminId), () -> mapper.valueToTree(
                new TransactionTemplate(txm).execute(s -> lotService.openLot(new OpenLot("in_process", block, null,
                        "BLK-" + sfx, new BigDecimal("1000"), null, null, "production_order", orderId, null, "MO-test",
                        "CURING", null, null, null, "source", true, null)))));
        String lotId = lot.get("id").asString();
        assertThat(lot.get("status").asString()).isEqualTo("hold");
        assertThat(lot.get("stockControl").asString()).isEqualTo("source");
        JsonNode again = TenantContext.run(TenantStore.user(demoOrg, adminId), () -> mapper.valueToTree(
                new TransactionTemplate(txm).execute(s -> lotService.openLot(new OpenLot("in_process", block, null,
                        "BLK-" + sfx, new BigDecimal("1000"), null, null, "production_order", orderId, null, "MO-test",
                        "CURING", null, null, null, "source", true, null)))));
        assertThat(again.get("id").asString()).isEqualTo(lotId);
        JsonNode gate = ok(200, "GET", "/quality/gate?sourceType=production_order&sourceId=" + orderId
                + "&stageKey=CURING", null);
        assertThat(gate.get("required").asBoolean()).isTrue();
        assertThat(gate.get("passed").asBoolean()).isFalse();
        assertThat(ok(200, "GET", "/quality/gate?sourceType=production_order&sourceId=" + orderId + "&stageKey=PRESS",
                null).get("required").asBoolean()).isFalse();

        // Fail, then release under concession (independentRelease off: the inspector may decide).
        ok(200, "POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(num("MOIST", 1, 9),
                text("CLEAN", 1, "true"))));
        Res noReason = money("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of());
        assertThat(msg(noReason)).contains("concession reason is required");
        Res conc = money("POST", "/quality/inspection-lots/" + lotId + "/release", Map.of("concessionReason",
                "Customer accepted (deviation permit DP-7)"));
        assertThat(conc.status()).as(conc.raw().body()).isEqualTo(200);
        assertThat(conc.body().get("status").asString()).isEqualTo("closed");
        JsonNode rel = conc.body().get("decisions").get(conc.body().get("decisions").size() - 1);
        assertThat(rel.get("concession").asBoolean()).isTrue();
        assertThat(rel.get("ledgerCodes").isNull()).isTrue();
        GateStatus g = TenantContext.run(TenantStore.organization(demoOrg), () -> gates.gate("production_order", orderId,
                "CURING"));
        assertThat(g.passed()).isTrue();
        assertThat(g.reason()).contains("concession");

        // A manual final lot: hold, then cancel (nothing decided yet).
        JsonNode plan = plan("final", block, spec.get("id").asString(), Map.of());
        JsonNode manual = ok(201, "POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", block,
                "quantity", 500, "planId", plan.get("id").asString(), "batchNumber", "BLK-F-" + sfx));
        String mid = manual.get("id").asString();
        assertThat(manual.get("status").asString()).isEqualTo("open");
        assertThat(ok(200, "POST", "/quality/inspection-lots/" + mid + "/hold", Map.of("reason", "Awaiting 28 d test"))
                .get("status").asString()).isEqualTo("hold");
        assertThat(msg(as("POST", "/quality/inspection-lots/" + mid + "/hold", Map.of()))).contains("only an open lot");
        assertThat(money("POST", "/quality/inspection-lots/" + mid + "/cancel", Map.of("reason", "Wrong batch"))
                .body().get("status").asString()).isEqualTo("cancelled");
        assertThat(msg(as("POST", "/quality/inspection-lots/" + mid + "/results", Map.of("results", List.of(
                num("MOIST", 1, 1)))))).contains("results can no longer change");
        assertThat(msg(as("POST", "/quality/inspection-lots", Map.of("stage", "incoming", "productId", block,
                "quantity", 5)))).isEqualTo("No active incoming inspection plan for this product.");
    }

    @Test
    @DisplayName("lab CSV import: rows grouped per lot, sieve rows, bad lot and invalid grading reported (partial)")
    void labCsvImport() {
        String agg = product("Aggregate 20mm", false);
        JsonNode spec = activeSpec(agg, true);
        JsonNode plan = plan("final", agg, spec.get("id").asString(), Map.of());
        String good = ok(201, "POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", agg, "quantity",
                300, "planId", plan.get("id").asString())).get("lotNumber").asString();
        String bad = ok(201, "POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", agg, "quantity",
                300, "planId", plan.get("id").asString())).get("lotNumber").asString();
        String csv = "lotNumber,parameterCode,sampleNo,value,sieveMm,passingPct,textValue,labReference\n"
                + good + ",MOIST,1,1.2,,,,LAB-77\n"
                + good + ",GRADING,1,,20,95,,LAB-77\n"
                + good + ",GRADING,1,,10,12,,LAB-77\n"
                + good + ",CLEAN,1,,,,yes,LAB-77\n"
                + bad + ",GRADING,1,,20,40,,LAB-78\n"
                + bad + ",GRADING,1,,10,60,,LAB-78\n"
                + "QL-NOPE-1,MOIST,1,1,,,,\n"
                + good + ",MOIST,1,abc,,,,\n";
        JsonNode imp = ok(201, "POST", "/quality/lab-imports/csv", Map.of("content", csv, "filename", "lab.csv"));
        assertThat(imp.get("status").asString()).isEqualTo("partial");
        assertThat(imp.get("rowsTotal").asInt()).isEqualTo(8);
        assertThat(imp.get("rowsImported").asInt()).isEqualTo(4);
        assertThat(imp.get("rowsRejected").asInt()).isEqualTo(4);
        String errors = imp.get("errors").toString();
        assertThat(errors).contains("not a number").contains("Unknown lot QL-NOPE-1").contains("cannot increase");
        JsonNode lots = ok(200, "GET", "/quality/inspection-lots?search=" + good, null).get("data");
        JsonNode lot = ok(200, "GET", "/quality/inspection-lots/" + lots.get(0).get("id").asString(), null);
        assertThat(lot.get("result").asString()).isEqualTo("pass");
        assertThat(lot.get("parameters").get(0).get("results").get(0).get("labReference").asString()).isEqualTo("LAB-77");
        assertThat(lot.get("parameters").get(0).get("results").get(0).get("source").asString()).isEqualTo("csv");
        assertThat(ok(200, "GET", "/quality/lab-imports/" + imp.get("id").asString(), null).get("filename").asString())
                .isEqualTo("lab.csv");
        assertThat(ok(200, "GET", "/quality/lab-imports", null).get("meta").get("total").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(msg(as("POST", "/quality/lab-imports/csv", Map.of("content", "a,b\n1,2")))).contains("lotNumber");
        assertThat(msg(as("POST", "/quality/lab-imports/fetch", Map.of("adapter", "rest", "lotIds",
                List.of(lots.get(0).get("id").asString()))))).contains("QUALITY_LAB_REST_URL");
    }

    @Test
    @DisplayName("certificate of analysis: PDF of a lot with numeric and sieve results")
    void certificate() throws Exception {
        String agg = product("Aggregate 10mm", false);
        JsonNode spec = activeSpec(agg, true);
        JsonNode plan = plan("final", agg, spec.get("id").asString(), Map.of("samplingMethod", "fixed", "sampleSize", 2));
        String lotId = ok(201, "POST", "/quality/inspection-lots", Map.of("stage", "final", "productId", agg,
                "quantity", 120, "planId", plan.get("id").asString(), "batchNumber", "AGG-" + sfx)).get("id").asString();
        ok(200, "POST", "/quality/inspection-lots/" + lotId + "/results", Map.of("results", List.of(num("MOIST", 1, 1),
                num("MOIST", 2, 1.4), sieve(1, 92, 10), sieve(2, 90, 14), text("CLEAN", 1, "yes"),
                text("CLEAN", 2, "yes"))));
        java.net.http.HttpResponse<byte[]> pdf = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                "http://localhost:" + port + "/api/v1/quality/inspection-lots/" + lotId + "/certificate"))
                .header("Authorization", "Bearer " + adminToken).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(pdf.headers().firstValue("Content-Type").orElse("")).isEqualTo("application/pdf");
        assertThat(pdf.headers().firstValue("Content-Disposition").orElse("")).contains("CoA-QL-");
        assertThat(new String(pdf.body(), 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        assertThat(pdf.body().length).isGreaterThan(1000);
        assertThat(as("GET", "/quality/inspection-lots/" + UUID.randomUUID() + "/certificate", null).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("concurrency: two full rejects of the same quarantined lot — one wins, stock leaves once")
    void contendedReject() throws Exception {
        String cement = product("Cement 32.5R", true);
        JsonNode spec = activeSpec(cement, false);
        plan("incoming", cement, spec.get("id").asString(), Map.of("holdPolicy", "quarantine"));
        String batch = "CC-" + sfx;
        String grnId = grn(supplier, cement, 60, 30000, batch).get("id").asString();
        drainOutbox();
        String lotId = lotsOf("goods_receipt", grnId).get(0).get("id").asString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Res>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(() -> money("POST", "/quality/inspection-lots/" + lotId + "/reject", Map.of("reason", "Torn")));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Res> f : pool.invokeAll(calls)) {
                statuses.add(f.get().status());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdown();
        }
        assertThat(inBatch(cement, quarantine(), batch)).isEqualByComparingTo("0");
        assertThat(jdbc.sql("SELECT count(*) FROM \"QualityLotDecision\" WHERE \"lotId\" = ? AND decision = 'reject'")
                .param(lotId).query(Long.class).single()).isEqualTo(1);
    }
}
