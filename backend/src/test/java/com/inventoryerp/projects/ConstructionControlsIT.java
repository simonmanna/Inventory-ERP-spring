package com.inventoryerp.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.projects.application.ProjectBondService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * ADR-S074 construction controls: bonds and guarantees (lifecycle, postings, expiry job and alerts, exposure),
 * subcontractor compliance gating (block / warn / override on valuation certification and on payment, the lien
 * waiver chain), capital projects (cost to CIP, capitalisation into two assets, depreciation from the in-service
 * month, reversal) and tenant isolation of the new records. The authorization matrix of the new routes is part of
 * {@link ProjectAuthorizationIT}.
 */
class ConstructionControlsIT extends ApiTestSupport {

    @Autowired
    ProjectBondService bondService;

    String sfx = UUID.randomUUID().toString().substring(0, 6);
    int projects;
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String customer;

    @BeforeAll
    void fixtures() {
        customer = partner("BCU", true);
    }

    @AfterEach
    void gatesOff() {
        // Gating is organisation-wide: never leave it on for the other suites sharing the demo organisation.
        jdbc.sql("DELETE FROM \"ComplianceRequirement\" WHERE \"organizationId\" = ?").param(demoOrg).update();
    }

    String partner(String tag, boolean isCustomer) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isCustomer\", \"isSupplier\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, tag + "-" + sfx + "-"
                + UUID.randomUUID().toString().substring(0, 4), tag + " " + sfx, isCustomer, !isCustomer).update();
        return id;
    }

    Map<String, String> key() {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    Res post(String path, Object body) {
        return call("POST", path, body, key());
    }

    JsonNode posted(String path, Object body) {
        Res r = post(path, body);
        assertThat(r.status()).as(path + " → " + r.raw().body()).isIn(200, 201);
        return r.body();
    }

    BigDecimal entry(String entryId, String code) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? AND a.code = ?")
                .params(entryId, code).query(BigDecimal.class).single();
    }

    BigDecimal mappedNet(String entryId, String key) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"AccountMapping\" m ON m.\"accountId\" = l.\"accountId\" AND m.\"organizationId\" = ? AND m.key = ?"
                + " WHERE l.\"journalEntryId\" = ?").params(demoOrg, key, entryId).query(BigDecimal.class).single();
    }

    static BigDecimal num(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    /** An active project with one costable WBS element; returns {projectId, wbsId}. */
    String[] project(boolean capital) {
        // a counter, not random characters: codes are unique per organisation (409 on a clash)
        int n = ++projects;
        String pid = ok(201, "POST", "/projects", capital ? Map.of("code", "CAP-" + sfx + "-" + n, "name",
                "Own depot " + sfx) : Map.of("code", "BND-" + sfx + "-" + n, "name", "Road " + sfx, "customerId", customer, "contractValue", 500000))
                .get("id").asString();
        String w = ok(201, "POST", "/projects/" + pid + "/wbs", Map.of("code", "1", "name", "Works")).get("id")
                .asString();
        if (capital) {
            JsonNode cip = ok(200, "PUT", "/projects/" + pid + "/capital", Map.of("isCapital", true));
            assertThat(cip.get("isCapital").asBoolean()).isTrue();
        }
        ok(200, "POST", "/projects/" + pid + "/status", Map.of("status", "active"));
        return new String[] {pid, w};
    }

    String subcontract(String pid, String w, String supplier, int value) {
        return ok(201, "POST", "/projects/" + pid + "/subcontracts", Map.of("supplierId", supplier, "wbsId", w,
                "scope", "Earthworks", "value", value)).get("id").asString();
    }

    Res valuation(String sc, int cumulative) {
        return post("/projects/subcontracts/" + sc + "/valuations", Map.of("cumulativeValue", cumulative));
    }

    void gates(Map<String, String> modes) {
        List<Map<String, String>> items = modes.entrySet().stream().map(e -> Map.of("documentType", e.getKey(), "mode",
                e.getValue())).toList();
        ok(200, "PUT", "/subcontract-compliance/requirements", Map.of("requirements", items));
    }

    String document(Map<String, Object> body, boolean verify) {
        String id = ok(201, "POST", "/subcontract-compliance/documents", body).get("id").asString();
        if (verify) {
            ok(200, "POST", "/subcontract-compliance/documents/" + id + "/verify", null);
        }
        return id;
    }

    Res pay(String supplier, String bill, BigDecimal amount) {
        return call("POST", "/supplier-payments", Map.of("partnerId", supplier, "paymentDate", today.toString(),
                "amount", amount, "paymentMethod", "bank", "allocations", List.of(Map.of("documentId", bill, "amount",
                        amount))), key());
    }

    long gateLog(String sc, String outcome) {
        return jdbc.sql("SELECT count(*) FROM \"ComplianceGateLog\" WHERE \"subcontractId\" = ? AND outcome = ?")
                .params(sc, outcome).query(Long.class).single();
    }

    // ---- bonds --------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("BG-1: issued bond — margin and commission, extend, reduce, release; postings and exposure")
    void issuedBondLifecycle() {
        String[] p = project(false);
        JsonNode b = ok(201, "POST", "/project-bonds", Map.of("bondType", "performance", "direction", "issued",
                "projectId", p[0], "counterpartyId", customer, "issuer", "Stanbic Bank " + sfx, "bondNumber",
                "PB-001", "amount", 50000, "issueDate", today.toString(), "expiryDate", today.plusDays(200).toString()));
        String id = b.get("id").asString();
        assertThat(b.get("status").asString()).isEqualTo("draft");
        assertThat(b.get("number").asString()).startsWith("BG/");
        ok(200, "PATCH", "/project-bonds/" + id, Map.of("bondNumber", "PB-002"));
        assertThat(post("/project-bonds/" + id + "/extend", Map.of("expiryDate", today.plusDays(300).toString()))
                .status()).isEqualTo(400);

        JsonNode active = posted("/project-bonds/" + id + "/activate", Map.of("margin", 1000, "commission", 50));
        assertThat(active.get("status").asString()).isEqualTo("active");
        assertThat(num(active.get("marginHeld"))).isEqualByComparingTo("1000");
        String je = active.get("events").get(1).get("journalEntryId").asString();
        assertThat(entry(je, "1482")).isEqualByComparingTo("1000");
        assertThat(entry(je, "6730")).isEqualByComparingTo("50");
        assertThat(mappedNet(je, "default_bank")).isEqualByComparingTo("-1050");
        assertThat(call("PATCH", "/project-bonds/" + id, Map.of("bondNumber", "x"), adminToken).status())
                .isEqualTo(400);

        JsonNode extended = posted("/project-bonds/" + id + "/extend", Map.of("expiryDate",
                today.plusDays(400).toString(), "commission", 20));
        assertThat(extended.get("status").asString()).isEqualTo("extended");
        assertThat(extended.get("expiryDate").asString()).isEqualTo(today.plusDays(400).toString());
        assertThat(post("/project-bonds/" + id + "/extend", Map.of("expiryDate", today.plusDays(10).toString()))
                .status()).isEqualTo(400);

        JsonNode reduced = posted("/project-bonds/" + id + "/reduce", Map.of("amount", 30000, "margin", 400));
        assertThat(reduced.get("status").asString()).isEqualTo("reduced");
        assertThat(num(reduced.get("amount"))).isEqualByComparingTo("30000");
        assertThat(num(reduced.get("marginHeld"))).isEqualByComparingTo("600");
        assertThat(post("/project-bonds/" + id + "/reduce", Map.of("amount", 40000)).status()).isEqualTo(400);

        JsonNode exposure = ok(200, "GET", "/project-bonds/exposure", null);
        boolean found = false;
        for (JsonNode row : exposure.get("byBank")) {
            if (("Stanbic Bank " + sfx).equals(row.get("issuer").asString())) {
                found = true;
                assertThat(num(row.get("amount"))).isEqualByComparingTo("30000");
                assertThat(num(row.get("marginHeld"))).isEqualByComparingTo("600");
            }
        }
        assertThat(found).isTrue();
        assertThat(exposure.get("byProject").toString()).contains(p[0]);

        JsonNode released = posted("/project-bonds/" + id + "/release", Map.of("reason", "Taking over certificate"));
        assertThat(released.get("status").asString()).isEqualTo("released");
        assertThat(num(released.get("marginHeld"))).isEqualByComparingTo("0");
        JsonNode events = released.get("events");
        assertThat(events.size()).isEqualTo(5);
        String releaseJe = events.get(4).get("journalEntryId").asString();
        assertThat(entry(releaseJe, "1482")).isEqualByComparingTo("-600");
        BigDecimal marginLeft = jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM"
                + " \"JournalLine\" l JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE a.code = '1482' AND"
                + " l.dimensions ->> 'bondId' = ?").param(id).query(BigDecimal.class).single();
        assertThat(marginLeft).isEqualByComparingTo("0");
        assertThat(post("/project-bonds/" + id + "/call", Map.of("reason", "late")).status()).isEqualTo(400);
        assertThat(ok(200, "GET", "/project-bonds?projectId=" + p[0], null).size()).isEqualTo(1);
    }

    @Test
    @DisplayName("BG-2: calls — received bond recovers to project cost or claim income; issued bond uses the margin")
    void calls() {
        String[] p = project(false);
        String supplier = partner("BSU", false);
        String sc = subcontract(p[0], p[1], supplier, 100000);
        String received = ok(201, "POST", "/project-bonds", Map.of("bondType", "performance", "direction", "received",
                "subcontractId", sc, "issuer", "CRDB Bank", "amount", 10000, "issueDate", today.toString(),
                "expiryDate", today.plusDays(90).toString())).get("id").asString();
        JsonNode rb = ok(200, "GET", "/project-bonds/" + received, null);
        assertThat(rb.get("counterpartyId").asString()).isEqualTo(supplier);
        assertThat(rb.get("projectId").asString()).isEqualTo(p[0]);
        Res margin = post("/project-bonds/" + received + "/activate", Map.of("margin", 100));
        assertThat(margin.status()).isEqualTo(400);
        assertThat(margin.raw().body()).contains("Margin and commission are booked only on bonds we issue");
        posted("/project-bonds/" + received + "/activate", Map.of());
        assertThat(post("/project-bonds/" + received + "/call", Map.of("amount", 4000, "reason", "Abandoned site"))
                .status()).isEqualTo(400);
        JsonNode called = posted("/project-bonds/" + received + "/call", Map.of("amount", 4000, "recoveryTo",
                "project_cost", "reason", "Abandoned site"));
        assertThat(called.get("status").asString()).isEqualTo("called");
        JsonNode ev = called.get("events").get(2);
        String je = ev.get("journalEntryId").asString();
        assertThat(entry(je, "1460")).isEqualByComparingTo("-4000");
        assertThat(mappedNet(je, "default_bank")).isEqualByComparingTo("4000");
        BigDecimal line = jdbc.sql("SELECT amount FROM \"ProjectCostLine\" WHERE id = ?").param(ev.get("costLineId")
                .asString()).query(BigDecimal.class).single();
        assertThat(line).isEqualByComparingTo("-4000");
        JsonNode report = ok(200, "GET", "/projects/" + p[0] + "/report", null);
        assertThat(num(report.get("wipTieOut").get("difference"))).isEqualByComparingTo("0");

        String income = ok(201, "POST", "/project-bonds", Map.of("bondType", "retention", "direction", "received",
                "projectId", p[0], "counterpartyId", supplier, "issuer", "Jubilee Insurance", "issuerType", "insurer",
                "amount", 2000, "issueDate", today.toString(), "expiryDate", today.plusDays(60).toString())).get("id")
                .asString();
        posted("/project-bonds/" + income + "/activate", Map.of());
        JsonNode inc = posted("/project-bonds/" + income + "/call", Map.of("recoveryTo", "claim_income", "reason",
                "Defects not remedied"));
        assertThat(num(inc.get("calledAmount"))).isEqualByComparingTo("2000");
        assertThat(entry(inc.get("events").get(2).get("journalEntryId").asString(), "4295"))
                .isEqualByComparingTo("-2000");

        String issued = ok(201, "POST", "/project-bonds", Map.of("bondType", "advance_payment", "direction", "issued",
                "projectId", p[0], "counterpartyId", customer, "issuer", "NMB Bank", "amount", 5000, "issueDate",
                today.toString(), "expiryDate", today.plusDays(60).toString())).get("id").asString();
        posted("/project-bonds/" + issued + "/activate", Map.of("margin", 1500));
        JsonNode ic = posted("/project-bonds/" + issued + "/call", Map.of("amount", 3000, "reason", "Advance claim"));
        String icJe = ic.get("events").get(2).get("journalEntryId").asString();
        assertThat(entry(icJe, "6995")).isEqualByComparingTo("3000");
        assertThat(entry(icJe, "1482")).isEqualByComparingTo("-1500");
        assertThat(mappedNet(icJe, "default_bank")).isEqualByComparingTo("-1500");
        assertThat(num(ic.get("marginHeld"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("BG-3: expiry — 30 / 14 / 7 day alerts for bonds and compliance documents; the job expires bonds")
    void expiry() {
        String supplier = partner("ESU", false);
        String soon = ok(201, "POST", "/project-bonds", Map.of("bondType", "bid", "direction", "issued",
                "counterpartyId", customer, "issuer", "Equity Bank", "amount", 1000, "issueDate", today.minusDays(30)
                        .toString(), "expiryDate", today.plusDays(5).toString())).get("id").asString();
        posted("/project-bonds/" + soon + "/activate", Map.of());
        String mid = ok(201, "POST", "/project-bonds", Map.of("bondType", "bank_guarantee", "direction", "received",
                "counterpartyId", supplier, "issuer", "KCB", "amount", 1000, "issueDate", today.toString(),
                "expiryDate", today.plusDays(20).toString())).get("id").asString();
        posted("/project-bonds/" + mid + "/activate", Map.of());
        String draft = ok(201, "POST", "/project-bonds", Map.of("bondType", "bid", "direction", "issued",
                "counterpartyId", customer, "issuer", "Equity Bank", "amount", 1000, "issueDate", today.toString(),
                "expiryDate", today.plusDays(3).toString())).get("id").asString();
        String doc = document(Map.of("partnerId", supplier, "documentType", "tax_clearance", "documentNumber",
                "TCC-1", "validTo", today.plusDays(10).toString()), true);

        ok(201, "POST", "/alerts/run", Map.of("alertType", "expiring_document"));
        assertThat(alerts("bond:" + soon + ":7")).isEqualTo(1);
        assertThat(alerts("bond:" + mid + ":30")).isEqualTo(1);
        assertThat(alerts("bond:" + draft + ":%")).isZero();
        assertThat(alerts("compliance:" + doc + ":14")).isEqualTo(1);

        String lapsed = ok(201, "POST", "/project-bonds", Map.of("bondType", "performance", "direction", "issued",
                "counterpartyId", customer, "issuer", "Equity Bank", "amount", 1000, "issueDate", today.minusDays(30)
                        .toString(), "expiryDate", today.minusDays(1).toString())).get("id").asString();
        posted("/project-bonds/" + lapsed + "/activate", Map.of());
        int expired = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), bondService::expireDue);
        assertThat(expired).isGreaterThanOrEqualTo(1);
        JsonNode l = ok(200, "GET", "/project-bonds/" + lapsed, null);
        assertThat(l.get("status").asString()).isEqualTo("expired");
        assertThat(ok(200, "GET", "/project-bonds/" + soon, null).get("status").asString()).isEqualTo("active");
        assertThat(posted("/project-bonds/" + lapsed + "/release", Map.of()).get("status").asString())
                .isEqualTo("released");
    }

    long alerts(String subjectLike) {
        return jdbc.sql("SELECT count(*) FROM \"AlertEvent\" WHERE \"organizationId\" = ? AND \"subjectKey\" LIKE ?")
                .params(demoOrg, subjectLike).query(Long.class).single();
    }

    // ---- compliance ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("SC-1: valuation gate — block, warn, verification, expiry and a one-time audited override")
    void valuationGate() {
        String[] p = project(false);
        String supplier = partner("CSU", false);
        String sc = subcontract(p[0], p[1], supplier, 100000);
        assertThat(valuation(sc, 1000).status()).isEqualTo(200); // nothing gated: unchanged behaviour
        gates(Map.of("tax_clearance", "block", "insurance_certificate", "warn"));

        Res blocked = valuation(sc, 2000);
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.raw().body()).contains("Tax clearance certificate is missing")
                .doesNotContain("Insurance certificate");
        assertThat(gateLog(sc, "blocked")).isEqualTo(1);

        String tcc = document(Map.of("partnerId", supplier, "documentType", "tax_clearance", "documentNumber",
                "TCC-9", "issuer", "TRA", "validTo", today.minusDays(1).toString()), false);
        assertThat(valuation(sc, 2000).raw().body()).contains("Tax clearance certificate is not verified yet");
        ok(200, "POST", "/subcontract-compliance/documents/" + tcc + "/verify", null);
        assertThat(valuation(sc, 2000).raw().body()).contains("Tax clearance certificate expired on "
                + today.minusDays(1));
        assertThat(call("PATCH", "/subcontract-compliance/documents/" + tcc, Map.of("validTo", "2099-01-01"),
                adminToken).status()).isEqualTo(400);

        document(Map.of("partnerId", supplier, "documentType", "tax_clearance", "documentNumber", "TCC-10",
                "validFrom", today.minusDays(5).toString(), "validTo", today.plusDays(300).toString()), true);
        Res warned = valuation(sc, 2000);
        assertThat(warned.status()).isEqualTo(200);
        assertThat(warned.body().get("complianceWarnings").toString()).contains("Insurance certificate is missing");
        assertThat(gateLog(sc, "warned")).isEqualTo(1);

        // Override: one pass, audited, consumed.
        gates(Map.of("trade_licence", "block"));
        assertThat(valuation(sc, 3000).status()).isEqualTo(400);
        Res shortReason = call("POST", "/subcontract-compliance/overrides", Map.of("subcontractId", sc, "gate",
                "valuation", "reason", "ok"), adminToken);
        assertThat(shortReason.status()).isEqualTo(400);
        String ov = ok(201, "POST", "/subcontract-compliance/overrides", Map.of("subcontractId", sc, "gate",
                "valuation", "reason", "Licence renewal receipt seen; certificate in the post")).get("id").asString();
        Res passed = valuation(sc, 3000);
        assertThat(passed.status()).isEqualTo(200);
        assertThat(passed.body().get("complianceWarnings").toString()).contains("Override: Licence renewal receipt");
        assertThat(jdbc.sql("SELECT \"consumedAt\" IS NOT NULL FROM \"ComplianceOverride\" WHERE id = ?").param(ov)
                .query(Boolean.class).single()).isTrue();
        assertThat(gateLog(sc, "overridden")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ?").param(ov).query(Long.class)
                .single()).isGreaterThanOrEqualTo(2);
        assertThat(valuation(sc, 4000).status()).isEqualTo(400);

        JsonNode view = ok(200, "GET", "/subcontract-compliance/subcontracts/" + sc, null);
        assertThat(view.get("valuationGate").toString()).contains("Trade licence is missing");
        assertThat(view.get("documents").size()).isEqualTo(2);
        assertThat(view.get("log").size()).isGreaterThanOrEqualTo(4);
        JsonNode supplierView = ok(200, "GET", "/subcontract-compliance/suppliers/" + supplier, null);
        assertThat(supplierView.get("summary").toString()).contains("\"state\":\"valid\"");
    }

    @Test
    @DisplayName("SC-2: payment gate — a subcontract bill is blocked without its lien waiver, an override passes it once")
    void paymentGate() {
        String[] p = project(false);
        String supplier = partner("PSU", false);
        String sc = subcontract(p[0], p[1], supplier, 100000);
        String bill = valuation(sc, 5000).body().get("billId").asString();
        gates(Map.of("lien_waiver", "block"));
        Res blocked = pay(supplier, bill, new BigDecimal("2000"));
        assertThat(blocked.status()).isEqualTo(400);
        assertThat(blocked.raw().body()).contains("Lien waiver for valuation SCV/");
        assertThat(jdbc.sql("SELECT count(*) FROM \"PaymentAllocation\" WHERE \"documentId\" = ?").param(bill)
                .query(Long.class).single()).isZero();
        Res wrongBill = call("POST", "/subcontract-compliance/overrides", Map.of("subcontractId", sc, "gate",
                "payment", "billId", UUID.randomUUID().toString(), "reason", "Paying against a bank guarantee"),
                adminToken);
        assertThat(wrongBill.status()).isEqualTo(400);
        ok(201, "POST", "/subcontract-compliance/overrides", Map.of("subcontractId", sc, "gate", "payment", "billId",
                bill, "reason", "Paying against a bank guarantee"));
        assertThat(pay(supplier, bill, new BigDecimal("2000")).status()).isIn(200, 201);
        assertThat(pay(supplier, bill, new BigDecimal("1000")).status()).isEqualTo(400);
        assertThat(gateLog(sc, "overridden")).isEqualTo(1);
    }

    @Test
    @DisplayName("SC-3: lien waiver chain — conditional per valuation before payment, unconditional per payment after")
    void lienWaiverChain() {
        String[] p = project(false);
        String supplier = partner("LSU", false);
        String sc = subcontract(p[0], p[1], supplier, 100000);
        gates(Map.of("lien_waiver", "block"));
        JsonNode v1 = valuation(sc, 10000).body();
        assertThat(v1.get("billId")).isNotNull();
        String bill1 = v1.get("billId").asString();
        assertThat(pay(supplier, bill1, new BigDecimal("10000")).status()).isEqualTo(400);

        Res noScope = call("POST", "/subcontract-compliance/documents", Map.of("partnerId", supplier,
                "subcontractId", sc, "documentType", "lien_waiver", "waiverType", "conditional", "amount", 10000,
                "throughDate", today.toString()), adminToken);
        assertThat(noScope.status()).isEqualTo(400);
        document(Map.of("partnerId", supplier, "subcontractId", sc, "documentType", "lien_waiver", "waiverType",
                "conditional", "waiverScope", "valuation", "valuationId", v1.get("id").asString(), "amount", 10000,
                "throughDate", today.toString()), true);
        Res paid = pay(supplier, bill1, new BigDecimal("10000"));
        assertThat(paid.status()).as(paid.raw().body()).isIn(200, 201);
        String payment = paid.body().get("id").asString();
        String paymentNumber = paid.body().get("paymentNumber").asString();

        Res v2 = valuation(sc, 20000);
        assertThat(v2.status()).isEqualTo(400);
        assertThat(v2.raw().body()).contains("Unconditional lien waiver for payment " + paymentNumber + " is missing");
        // A conditional waiver for the payment does not release the gate.
        document(Map.of("partnerId", supplier, "subcontractId", sc, "documentType", "lien_waiver", "waiverType",
                "conditional", "waiverScope", "payment", "paymentId", payment, "amount", 10000, "throughDate",
                today.toString()), true);
        assertThat(valuation(sc, 20000).status()).isEqualTo(400);
        document(Map.of("partnerId", supplier, "subcontractId", sc, "documentType", "lien_waiver", "waiverType",
                "unconditional", "waiverScope", "payment", "paymentId", payment, "amount", 10000, "throughDate",
                today.toString()), true);
        JsonNode ok2 = valuation(sc, 20000).body();
        assertThat(ok2.get("complianceWarnings")).isNull();
        JsonNode view = ok(200, "GET", "/subcontract-compliance/subcontracts/" + sc, null);
        assertThat(view.get("lienWaivers").size()).isEqualTo(3);
        assertThat(view.get("payments").size()).isEqualTo(1);
    }

    // ---- CIP ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CIP-1: capital project cost to CIP, capitalisation into two assets, depreciation from in-service,"
            + " reversal")
    void capitalProject() {
        String[] p = project(true);
        String pid = p[0];
        String cat = ok(201, "POST", "/asset-categories", Map.of("name", "Buildings " + sfx, "depreciationMethod",
                "straight_line")).get("id").asString();
        JsonNode eq = posted("/projects/" + pid + "/costs/equipment", Map.of("wbsId", p[1], "equipmentRef", "EXC-9",
                "hours", 100, "ratePerHour", 1000));
        String je = eq.get("journalEntryId").asString();
        assertThat(entry(je, "1690")).isEqualByComparingTo("100000");
        assertThat(entry(je, "1460")).isEqualByComparingTo("0");
        assertThat(call("PUT", "/projects/" + pid + "/capital", Map.of("isCapital", false), adminToken).status())
                .isEqualTo(400);
        JsonNode report = ok(200, "GET", "/projects/" + pid + "/report", null);
        assertThat(num(report.get("wipTieOut").get("difference"))).isEqualByComparingTo("0");
        JsonNode cip = ok(200, "GET", "/projects/" + pid + "/cip", null);
        assertThat(num(cip.get("balance"))).isEqualByComparingTo("100000");
        assertThat(num(cip.get("difference"))).isEqualByComparingTo("0");

        LocalDate inService = today.withDayOfMonth(1).plusMonths(1);
        assertThat(post("/projects/" + pid + "/capitalisations", Map.of("assets", List.of(Map.of("name", "Too much",
                "categoryId", cat, "amount", 100001, "usefulLife", 120, "inServiceDate", inService.toString()))))
                .status()).isEqualTo(400);
        JsonNode cap = posted("/projects/" + pid + "/capitalisations", Map.of("date", today.toString(), "assets",
                List.of(Map.of("name", "Depot building " + sfx, "categoryId", cat, "amount", 60000, "usefulLife", 240,
                        "inServiceDate", inService.toString()), Map.of("name", "Yard paving " + sfx, "categoryId", cat,
                        "amount", 30000, "usefulLife", 120, "inServiceDate", inService.toString()))));
        assertThat(cap.get("assets").size()).isEqualTo(2);
        String capJe = cap.get("journalEntryId").asString();
        assertThat(entry(capJe, "1690")).isEqualByComparingTo("-90000");
        assertThat(mappedNet(capJe, "fixed_asset_valuation")).isEqualByComparingTo("90000");
        String asset = cap.get("assets").get(0).get("assetId").asString();
        JsonNode a = ok(200, "GET", "/fixed-assets/" + asset, null);
        assertThat(a.get("acquisitionMethod").asString()).isEqualTo("constructed");
        assertThat(a.get("status").asString()).isEqualTo("active");
        cip = ok(200, "GET", "/projects/" + pid + "/cip", null);
        assertThat(num(cip.get("capitalised"))).isEqualByComparingTo("90000");
        assertThat(num(cip.get("balance"))).isEqualByComparingTo("10000");
        assertThat(num(cip.get("difference"))).isEqualByComparingTo("0");
        assertThat(num(ok(200, "GET", "/projects/" + pid + "/report", null).get("wipTieOut").get("difference")))
                .isEqualByComparingTo("0");
        JsonNode org = ok(200, "GET", "/projects/cip-report", null);
        assertThat(org.get("projects").toString()).contains(pid);

        // Depreciation starts in the in-service month.
        String before = inService.minusMonths(1).toString().substring(0, 7);
        JsonNode none = ok(201, "POST", "/fixed-assets/" + asset + "/depreciation/run", Map.of("period", before,
                "postEntries", false, "assetId", asset));
        assertThat(none.size()).isZero();
        JsonNode first = ok(201, "POST", "/fixed-assets/" + asset + "/depreciation/run", Map.of("period",
                inService.toString().substring(0, 7), "postEntries", true, "assetId", asset));
        assertThat(first.size()).isEqualTo(1);
        assertThat(num(first.get(0).get("depreciationAmount"))).isEqualByComparingTo("250");

        Res refused = post("/projects/capitalisations/" + cap.get("id").asString() + "/reverse", null);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.raw().body()).contains("already has posted depreciation");

        JsonNode rest = posted("/projects/" + pid + "/capitalisations", Map.of("assets", List.of(Map.of("name",
                "Fence " + sfx, "categoryId", cat, "amount", 10000, "usefulLife", 60, "inServiceDate",
                today.toString()))));
        String fence = rest.get("assets").get(0).get("assetId").asString();
        assertThat(num(ok(200, "GET", "/projects/" + pid + "/cip", null).get("balance"))).isEqualByComparingTo("0");
        JsonNode reversed = posted("/projects/capitalisations/" + rest.get("id").asString() + "/reverse", null);
        assertThat(reversed.get("status").asString()).isEqualTo("reversed");
        assertThat(call("GET", "/fixed-assets/" + fence, null, adminToken).status()).isEqualTo(404);
        cip = ok(200, "GET", "/projects/" + pid + "/cip", null);
        assertThat(num(cip.get("balance"))).isEqualByComparingTo("10000");
        assertThat(num(cip.get("difference"))).isEqualByComparingTo("0");
        assertThat(post("/projects/capitalisations/" + rest.get("id").asString() + "/reverse", null).status())
                .isEqualTo(400);

        // A customer project cannot be capitalised.
        String[] normal = project(false);
        assertThat(post("/projects/" + normal[0] + "/capitalisations", Map.of("assets", List.of(Map.of("name", "x",
                "categoryId", cat, "amount", 1, "inServiceDate", today.toString())))).status()).isEqualTo(400);
    }

    // ---- tenancy ------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CT-1: another organisation gets 404 on every new record route and empty lists")
    void tenantIsolation() {
        String[] p = project(true);
        String supplier = partner("TSU", false);
        String[] c = project(false);
        String sc = subcontract(c[0], c[1], supplier, 1000);
        String bond = ok(201, "POST", "/project-bonds", Map.of("bondType", "bid", "direction", "issued",
                "counterpartyId", customer, "issuer", "Bank", "amount", 10, "issueDate", today.toString(),
                "expiryDate", today.plusDays(30).toString())).get("id").asString();
        String doc = document(Map.of("partnerId", supplier, "documentType", "trade_licence", "validTo",
                today.plusDays(30).toString()), false);
        Map<String, String> other = new HashMap<>();
        other.put("Authorization", "Bearer " + otherToken);
        other.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("GET", "/project-bonds/" + bond, null, otherToken).status()).isEqualTo(404);
        assertThat(call("PATCH", "/project-bonds/" + bond, Map.of("notes", "x"), otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/project-bonds/" + bond + "/activate", Map.of(), other).status()).isEqualTo(404);
        assertThat(call("GET", "/project-bonds", null, otherToken).body().toString()).doesNotContain(bond);
        assertThat(call("GET", "/project-bonds/exposure", null, otherToken).body().toString()).doesNotContain(p[0]);
        assertThat(call("GET", "/subcontract-compliance/documents/" + doc, null, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/subcontract-compliance/documents/" + doc + "/verify", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/subcontract-compliance/documents", null, otherToken).body().toString())
                .doesNotContain(doc);
        assertThat(call("GET", "/subcontract-compliance/subcontracts/" + sc, null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/subcontract-compliance/suppliers/" + supplier, null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/subcontract-compliance/overrides", Map.of("subcontractId", sc, "gate", "valuation",
                "reason", "Cross-tenant attempt here"), otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/projects/" + p[0] + "/cip", null, otherToken).status()).isEqualTo(404);
        assertThat(call("PUT", "/projects/" + p[0] + "/capital", Map.of("isCapital", false), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/projects/cip-report", null, otherToken).body().toString()).doesNotContain(p[0]);
        assertThat(jdbc.sql("SELECT status FROM \"ComplianceDocument\" WHERE id = ?").param(doc).query(String.class)
                .single()).isEqualTo("pending");
    }
}
