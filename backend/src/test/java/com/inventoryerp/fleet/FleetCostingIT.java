package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.fleet.web.costing.FleetFreightBillController;
import com.inventoryerp.fleet.web.costing.FleetReminderController;
import com.inventoryerp.fleet.web.costing.FleetReportController;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.extension.ExtendWith;
import com.inventoryerp.BooksGuard;

/**
 * Fleet 1.9 — costing, delivery profitability, reports and transporter freight bills over HTTP on a seeded scenario:
 * one own-fleet tipper with every cost source on one business day (depreciation, fuel, labour, tyre, insurance
 * premium, company fine, trip allowance) and an own-fleet trip; a transporter trip; the exact TCO / unit rates, trip
 * allocation, margin per trip / customer, deliveries on time, tonnes, transport cost per tonne, utilisation, fuel,
 * renewals and KPIs; CSV / XLSX / PDF exports; freight bills generated once per trip, matched within tolerance (AP
 * draft bill + balanced accrual, Idempotency-Key replay), disputed → variance approval (permission), cancel with
 * reversal; inbound freight capitalised as landed cost on a posted receipt; tenant isolation; authorization matrix.
 */
@ExtendWith(BooksGuard.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FleetCostingIT extends ApiTestSupport {

    @Autowired
    StockService stock;

    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYP"
            + "hfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    LocalDate today;
    String adminId;
    String tipper;
    String costCenter;
    String yard;
    String aggregate;
    String customer;
    String transporter;
    String site;
    String truck;
    String driver;
    String driverEmployee;
    String ownTrip;
    String ownOrder;
    String transporterTrip;
    int seq;

    @BeforeAll
    void costingFixtures() {
        today = LocalDate.now(BusinessZone.get());
        adminId = jdbc.sql("SELECT id FROM \"User\" WHERE \"organizationId\" = ? AND email = 'admin@demo.test'")
                .param(demoOrg).query(String.class).single();
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if ("TIPPER".equals(c.get("code").asString())) {
                tipper = c.get("id").asString();
            }
        }
        costCenter = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"CostCenter\" (id, \"organizationId\", code, name, category, \"updatedAt\") VALUES"
                + " (?, ?, ?, ?, 'vehicle', now())").params(costCenter, demoOrg, "CC-C" + sfx, "Haulage " + sfx)
                .update();
        yard = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"updatedAt\") VALUES"
                + " (?, ?, ?, 'Costing yard', 'storage_room', now())").params(yard, demoOrg, "CYD-" + sfx).update();
        aggregate = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable',"
                + " true, 'AVCO'::\"CostingMethod\", 0, 45000, now())").params(aggregate, demoOrg, "CST-" + sfx,
                "Aggregate 20mm " + sfx).update();
        TenantContext.run(TenantStore.user(demoOrg, adminId), () -> new TransactionTemplate(txm).executeWithoutResult(
                s -> stock.receive(StockMove.of(aggregate, yard, new BigDecimal("400")).unitCost(new BigDecimal("30000")))));
        ok(200, "PUT", "/fleet/product-load-factors/" + aggregate, Map.of("tonnesPerUnit", 1));
        customer = partner("Builder", false);
        transporter = partner("Haulier", true);
        site = ok(201, "POST", "/fleet/customer-sites", Map.of("partnerId", customer, "name", "Costing site " + sfx,
                "zone", "Zone " + sfx, "distanceKm", 42)).get("id").asString();
        ok(201, "POST", "/fleet/delivery-charge-rules", Map.of("name", "Per tonne " + sfx, "partnerId", customer,
                "basis", "per_tonne", "rate", 5000));

        // The tipper: linked fixed asset with this month's posted depreciation (100,000 a day).
        String asset = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Asset\" (id, \"organizationId\", \"assetCode\", name, \"purchaseCost\", \"currentValue\","
                + " \"updatedAt\") VALUES (?, ?, ?, 'Costing tipper', 300000000, 280000000, now())")
                .params(asset, demoOrg, "FA-C" + sfx).update();
        YearMonth month = YearMonth.from(today);
        jdbc.sql("INSERT INTO \"AssetDepreciation\" (id, \"organizationId\", \"assetId\", period, \"assetCost\","
                + " \"usefulLife\", \"depreciationAmount\", \"accumulatedDepr\", \"bookValue\", \"isPosted\","
                + " \"updatedAt\") VALUES (?, ?, ?, ?, 300000000, 60, ?, 20000000, 280000000, true, now())")
                .params(UUID.randomUUID().toString(), demoOrg, asset, month.toString(),
                        new BigDecimal(100_000L * month.lengthOfMonth())).update();
        truck = ok(201, "POST", "/fleet/vehicles", Obj.of("categoryId", tipper, "registrationPlate", "UCT " + sfx,
                "costCenterId", costCenter, "payloadCapacityT", 25, "openingOdometer", 1000, "assetId", asset))
                .get("id").asString();
        driverEmployee = ok(201, "POST", "/hr/employees", Map.of("firstName", "Costing", "lastName", "Driver " + sfx,
                "baseSalary", 500000)).get("id").asString();
        driver = ok(201, "POST", "/fleet/drivers", Map.of("employeeId", driverEmployee, "licenceNumber", "DLC-" + sfx,
                "licenceExpiresOn", today.plusYears(2).toString(), "qualifiedCategoryIds", List.of(tipper)))
                .get("id").asString();

        // Insurance premium 3,650,000 for 365 days → 10,000 a day.
        ok(201, "POST", "/fleet/vehicles/" + truck + "/compliance-documents", Map.of("docType", "insurance",
                "documentNumber", "POL-" + sfx, "issuedOn", today.minusDays(100).toString(), "expiresOn",
                today.minusDays(100).plusDays(364).toString(), "costAmount", 3650000));
        // Fuel 500,000 (posted station purchase), tyre 800,000 first fitted today, company fine 50,000.
        jdbc.sql("INSERT INTO \"FleetFuelLog\" (id, \"organizationId\", code, \"vehicleId\", \"fuelledAt\", source,"
                + " litres, \"unitPrice\", amount, \"paymentMethod\", status, \"postedAt\", \"updatedAt\") VALUES (?, ?,"
                + " ?, ?, now(), 'station', 100, 5000, 500000, 'cash', 'posted', now(), now())")
                .params(UUID.randomUUID().toString(), demoOrg, "FLC-" + sfx, truck).update();
        String tyre = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FleetTyre\" (id, \"organizationId\", \"serialNumber\", \"purchaseCost\", status,"
                + " \"vehicleId\", position, \"updatedAt\") VALUES (?, ?, ?, 800000, 'fitted', ?, 'A1L', now())")
                .params(tyre, demoOrg, "TYC-" + sfx, truck).update();
        jdbc.sql("INSERT INTO \"FleetTyreEvent\" (id, \"organizationId\", \"tyreId\", \"eventType\", \"vehicleId\","
                + " \"toPosition\", \"eventAt\") VALUES (?, ?, ?, 'fitted', ?, 'A1L', now())")
                .params(UUID.randomUUID().toString(), demoOrg, tyre, truck).update();
        jdbc.sql("INSERT INTO \"FleetFine\" (id, \"organizationId\", \"fineNumber\", \"vehicleId\", \"offenceAt\","
                + " offence, amount, status, \"chargedTo\", \"updatedAt\") VALUES (?, ?, ?, ?, now(), 'Speeding',"
                + " 50000, 'settled', 'company', now())").params(UUID.randomUUID().toString(), demoOrg, "FNC-" + sfx,
                truck).update();
        // Workshop labour 4 h × 25,000 = 100,000 on a work order.
        String wo = ok(201, "POST", "/fleet/work-orders", Map.of("vehicleId", truck, "type", "corrective", "title",
                "Brake check " + sfx)).get("id").asString();
        if (!"open".equals(ok(200, "GET", "/fleet/work-orders/" + wo, null).get("status").asString())) {
            ok(201, "POST", "/fleet/work-orders/" + wo + "/open", null);
        }
        Res labour = call("POST", "/fleet/work-orders/" + wo + "/labour", Map.of("employeeId", driverEmployee,
                "hours", 4, "rate", 25000, "workDate", today.toString()), key(adminToken));
        assertThat(labour.status()).as(labour.raw().body()).isEqualTo(200);

        // Own-fleet trip: 20 t to the site, odometer 1000 → 1084, invoiced with the 5,000 / t transport charge.
        JsonNode order = order(20);
        ownOrder = order.get("id").asString();
        ownTrip = ok(201, "POST", "/fleet/delivery-orders/" + ownOrder + "/plan-loads", Map.of("vehicleId", truck,
                "driverId", driver)).get("createdTripIds").get(0).asString();
        JsonNode d = money("/fleet/trips/" + ownTrip + "/dispatch", Map.of());
        money("/fleet/trips/" + ownTrip + "/proof-of-delivery", pod(d, 20, 1084));
        ok(201, "POST", "/fleet/trips/" + ownTrip + "/close", Map.of("distanceKm", 84));
        money("/fleet/delivery-orders/" + ownOrder + "/invoice", Map.of());
        // A driver allowance of 20,000 claimed for the trip (approved).
        jdbc.sql("INSERT INTO \"FleetDriverClaim\" (id, \"organizationId\", code, \"driverId\", \"vehicleId\", \"tripId\","
                + " \"claimDate\", status, \"totalAmount\", \"currencyCode\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                + " 'approved', 20000, 'UGX', now())").params(UUID.randomUUID().toString(), demoOrg, "CLC-" + sfx,
                driver, truck, ownTrip, java.sql.Date.valueOf(today)).update();

        // Transporter trip: 10 t, 4,000 / t.
        transporterTrip = transporterTrip(10, 4000);
    }

    // ------------------------------------------------------------------ helpers

    String partner(String name, boolean supplier) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, ?, ?, now())").params(id, demoOrg, "PC-" + UUID.randomUUID().toString()
                        .substring(0, 8), name + " " + sfx + "-" + (++seq), supplier, !supplier).update();
        return id;
    }

    JsonNode order(Object qty) {
        return ok(201, "POST", "/fleet/delivery-orders", Map.of("partnerId", customer, "siteId", site, "locationId",
                yard, "requestedDate", today.toString(), "lines", List.of(Map.of("productId", aggregate, "quantity",
                        qty, "unitPrice", 45000))));
    }

    String transporterTrip(Object qty, Object rate) {
        JsonNode o = order(qty);
        String t = ok(201, "POST", "/fleet/delivery-orders/" + o.get("id").asString() + "/plan-loads",
                Map.of("payloadT", 30)).get("createdTripIds").get(0).asString();
        ok(201, "POST", "/fleet/trips/" + t + "/assign", Map.of("carrier", "transporter", "transporterPartnerId",
                transporter, "freightBasis", "per_tonne", "freightRate", rate, "transporterPlate", "UBX " + sfx));
        JsonNode d = money("/fleet/trips/" + t + "/dispatch", Map.of());
        money("/fleet/trips/" + t + "/proof-of-delivery", pod(d, qty, null));
        ok(201, "POST", "/fleet/trips/" + t + "/close", Map.of());
        return t;
    }

    Map<String, Object> pod(JsonNode trip, Object delivered, Object endOdometer) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("receiverName", "Site agent", "signature", PNG, "lines",
                List.of(Map.of("loadLineId", trip.get("lines").get(0).get("id").asString(), "deliveredQty",
                        delivered))));
        if (endOdometer != null) {
            body.put("endOdometer", endOdometer);
        }
        return body;
    }

    Map<String, String> key(String token) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    JsonNode money(String path, Object body) {
        Res r = call("POST", path, body, key(adminToken));
        assertThat(r.status()).as(path + " → " + r.raw().body()).isEqualTo(200);
        return r.body();
    }

    String user(String... permissions) {
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                + " VALUES (?, ?, ?, false, ?, now())").params(role, demoOrg, "Costing " + role.substring(0, 8),
                permissions).update();
        String email = "cst-" + role.substring(0, 8) + "@demo.test";
        createUser(demoOrg, email, "Cost@12345", role);
        return login("DEMO", email, "Cost@12345");
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    String q() {
        return "from=" + today + "&to=" + today;
    }

    JsonNode row(JsonNode report, String key) {
        for (JsonNode r : report.get("rows")) {
            if (key.equals(r.get("key").asString())) {
                return r;
            }
        }
        throw new AssertionError("no row " + key + " in " + report);
    }

    void assertBalanced(String entryId) {
        Map<String, Object> sums = jdbc.sql("SELECT COALESCE(SUM(debit), 0) AS d, COALESCE(SUM(credit), 0) AS c FROM"
                + " \"JournalLine\" WHERE \"journalEntryId\" = ?").param(entryId).query().singleRow();
        assertThat((BigDecimal) sums.get("d")).isPositive();
        assertThat((BigDecimal) sums.get("d")).isEqualByComparingTo((BigDecimal) sums.get("c"));
    }

    String account(String code) {
        return jdbc.sql("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ? AND \"deletedAt\" IS NULL")
                .params(demoOrg, code).query(String.class).single();
    }

    // ------------------------------------------------------------------ costing

    @Test
    @Order(1)
    @DisplayName("TCO of a unit: every cost source, km / tonnes / trips and the unit rates; grouping; vehicle summary")
    void vehicleCosts() {
        JsonNode r = ok(200, "GET", "/fleet/reports/vehicle-costs?" + q() + "&vehicleId=" + truck, null);
        JsonNode v = row(r, truck);
        assertThat(dec(v.get("depreciation"))).isEqualByComparingTo("100000");
        assertThat(dec(v.get("fuel"))).isEqualByComparingTo("500000");
        assertThat(dec(v.get("maintenance"))).isEqualByComparingTo("100000");
        assertThat(dec(v.get("tyres"))).isEqualByComparingTo("800000");
        assertThat(dec(v.get("insurance"))).isEqualByComparingTo("10000");
        assertThat(dec(v.get("compliance"))).isEqualByComparingTo("0");
        assertThat(dec(v.get("fines"))).isEqualByComparingTo("50000");
        assertThat(dec(v.get("allowances"))).isEqualByComparingTo("20000");
        assertThat(dec(v.get("total"))).isEqualByComparingTo("1580000");
        assertThat(dec(v.get("running"))).isEqualByComparingTo("1480000");
        assertThat(dec(v.get("km"))).isEqualByComparingTo("84");
        assertThat(dec(v.get("tonnes"))).isEqualByComparingTo("20");
        assertThat(v.get("trips").asInt()).isEqualTo(1);
        assertThat(dec(v.get("perKm"))).isEqualByComparingTo("18809.5238");
        assertThat(dec(v.get("perTonne"))).isEqualByComparingTo("79000");
        assertThat(dec(v.get("perTrip"))).isEqualByComparingTo("1580000");
        assertThat(dec(r.get("totals").get("total"))).isEqualByComparingTo("1580000");

        JsonNode byClass = ok(200, "GET", "/fleet/reports/vehicle-costs?" + q() + "&vehicleId=" + truck
                + "&groupBy=category", null);
        assertThat(dec(row(byClass, tipper).get("total"))).isEqualByComparingTo("1580000");
        JsonNode byMonth = ok(200, "GET", "/fleet/reports/vehicle-costs?" + q() + "&vehicleId=" + truck
                + "&groupBy=month", null);
        assertThat(dec(row(byMonth, YearMonth.from(today).toString()).get("total"))).isEqualByComparingTo("1580000");
        JsonNode summary = ok(200, "GET", "/fleet/vehicles/" + truck + "/cost-summary?" + q(), null);
        assertThat(dec(summary.get("totals").get("total"))).isEqualByComparingTo("1580000");
        assertThat(as("GET", "/fleet/vehicles/" + UUID.randomUUID() + "/cost-summary", null).status()).isEqualTo(404);
        assertThat(as("GET", "/fleet/reports/vehicle-costs?from=" + today + "&to=" + today.minusDays(1), null)
                .status()).isEqualTo(400);
        assertThat(as("GET", "/fleet/reports/vehicle-costs?groupBy=nope", null).status()).isEqualTo(400);
    }

    @Test
    @Order(2)
    @DisplayName("delivery profitability per trip / customer: revenue + charge − product cost − trip cost (allocated"
            + " per km + allowance, or freight)")
    void profitability() {
        JsonNode r = ok(200, "GET", "/fleet/reports/delivery-profitability?" + q() + "&partnerId=" + customer, null);
        JsonNode own = row(r, ownTrip);
        assertThat(own.get("carrier").asString()).isEqualTo("own");
        assertThat(dec(own.get("revenue"))).isEqualByComparingTo("900000");
        assertThat(dec(own.get("transportCharge"))).isEqualByComparingTo("100000");
        assertThat(dec(own.get("productCost"))).isEqualByComparingTo("600000");
        // Pool 1,580,000 − 20,000 trip allowance = 1,560,000 over 84 km; × 84 km + 20,000 allowance.
        assertThat(dec(own.get("tripCost"))).isEqualByComparingTo("1580000");
        assertThat(own.get("costBasis").asString()).isEqualTo("km");
        assertThat(dec(own.get("margin"))).isEqualByComparingTo("-1180000");
        JsonNode third = row(r, transporterTrip);
        assertThat(third.get("carrier").asString()).isEqualTo("transporter");
        assertThat(dec(third.get("revenue"))).isEqualByComparingTo("450000");
        assertThat(dec(third.get("productCost"))).isEqualByComparingTo("300000");
        assertThat(dec(third.get("tripCost"))).isEqualByComparingTo("40000");
        assertThat(dec(third.get("margin"))).isEqualByComparingTo("110000");
        assertThat(dec(third.get("marginPct"))).isEqualByComparingTo("24.44");
        JsonNode byCustomer = ok(200, "GET", "/fleet/reports/delivery-profitability?" + q() + "&partnerId=" + customer
                + "&groupBy=customer", null);
        JsonNode c = row(byCustomer, customer);
        assertThat(c.get("trips").asInt()).isEqualTo(2);
        assertThat(dec(c.get("margin"))).isEqualByComparingTo("-1070000");
        JsonNode byOrder = ok(200, "GET", "/fleet/reports/delivery-profitability?" + q() + "&deliveryOrderId="
                + ownOrder + "&groupBy=delivery_order", null);
        assertThat(byOrder.get("rows")).hasSize(1);
        JsonNode ownOnly = ok(200, "GET", "/fleet/reports/delivery-profitability?" + q() + "&partnerId=" + customer
                + "&carrier=transporter", null);
        assertThat(ownOnly.get("rows")).hasSize(1);
    }

    @Test
    @Order(3)
    @DisplayName("deliveries on time, tonnes per truck / product, transport cost per tonne, utilisation, fuel, renewals,"
            + " KPIs")
    void reports() {
        JsonNode d = ok(200, "GET", "/fleet/reports/deliveries?" + q() + "&partnerId=" + customer + "&groupBy=site",
                null);
        JsonNode s = row(d, site);
        assertThat(s.get("trips").asInt()).isEqualTo(2);
        assertThat(s.get("onTime").asInt()).isEqualTo(2);
        assertThat(dec(s.get("onTimePct"))).isEqualByComparingTo("100");
        assertThat(dec(s.get("tonnes"))).isEqualByComparingTo("30");

        JsonNode t = ok(200, "GET", "/fleet/reports/tonnes?" + q() + "&partnerId=" + customer, null);
        assertThat(dec(row(t, truck).get("tonnes"))).isEqualByComparingTo("20");
        assertThat(dec(row(t, "transporter:" + transporter).get("tonnes"))).isEqualByComparingTo("10");
        JsonNode byProduct = ok(200, "GET", "/fleet/reports/tonnes?" + q() + "&partnerId=" + customer
                + "&groupBy=product", null);
        assertThat(dec(row(byProduct, aggregate).get("tonnes"))).isEqualByComparingTo("30");

        JsonNode c = ok(200, "GET", "/fleet/reports/transport-cost?" + q() + "&partnerId=" + customer, null);
        JsonNode own = row(c, "own");
        assertThat(dec(own.get("costPerTonne"))).isEqualByComparingTo("79000");
        assertThat(dec(own.get("transportCharge"))).isEqualByComparingTo("100000");
        assertThat(dec(row(c, "transporter").get("costPerTonne"))).isEqualByComparingTo("4000");
        assertThat(dec(c.get("totals").get("transportCost"))).isEqualByComparingTo("1620000");

        JsonNode u = ok(200, "GET", "/fleet/reports/utilisation?" + q() + "&vehicleId=" + truck, null);
        JsonNode ur = row(u, truck);
        assertThat(ur.get("activeDays").asInt()).isEqualTo(1);
        assertThat(ur.get("periodDays").asInt()).isEqualTo(1);
        assertThat(dec(ur.get("utilisationPct"))).isEqualByComparingTo("100");
        assertThat(ur.get("trips").asInt()).isEqualTo(1);
        assertThat(dec(ur.get("km"))).isEqualByComparingTo("84");

        JsonNode f = ok(200, "GET", "/fleet/reports/fuel?" + q() + "&vehicleId=" + truck, null);
        JsonNode fr = row(f, truck);
        assertThat(dec(fr.get("litres"))).isEqualByComparingTo("100");
        assertThat(dec(fr.get("cost"))).isEqualByComparingTo("500000");
        assertThat(dec(fr.get("costPerKm"))).isEqualByComparingTo("5952.3810");
        assertThat(dec(fr.get("costPerLitre"))).isEqualByComparingTo("5000");
        assertThat(ok(200, "GET", "/fleet/reports/fuel?" + q() + "&vehicleId=" + truck + "&groupBy=month", null)
                .get("rows")).hasSize(1);

        // Renewals: the insurance (265 days left) is outside 30 days, inside 300.
        JsonNode soon = ok(200, "GET", "/fleet/reports/renewals?kind=compliance&days=30", null);
        assertThat(soon.get("rows").toString()).doesNotContain("UCT " + sfx);
        JsonNode later = ok(200, "GET", "/fleet/reports/renewals?kind=compliance&days=300", null);
        assertThat(later.get("rows").toString()).contains("UCT " + sfx);
        assertThat(as("GET", "/fleet/reports/renewals?kind=nope", null).status()).isEqualTo(400);

        JsonNode k = ok(200, "GET", "/fleet/reports/kpis?" + q(), null);
        assertThat(k.get("deliveries").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(dec(k.get("tonnes"))).isGreaterThanOrEqualTo(new BigDecimal("30"));
        assertThat(k.has("transportCostPerTonne")).isTrue();
        assertThat(k.has("availabilityPct")).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("CSV / XLSX / PDF exports of every report")
    void exports() {
        for (String report : List.of("vehicle-costs", "delivery-profitability", "deliveries", "tonnes",
                "transport-cost", "utilisation", "fuel", "renewals")) {
            for (String format : List.of("csv", "xlsx", "pdf")) {
                java.net.http.HttpResponse<byte[]> r = download("/fleet/reports/" + report + "/export?format=" + format
                        + "&" + q());
                assertThat(r.statusCode()).as(report + " " + format).isEqualTo(200);
                assertThat(r.body().length).isPositive();
                String type = r.headers().firstValue("Content-Type").orElse("");
                assertThat(type).contains(switch (format) {
                    case "csv" -> "text/csv";
                    case "xlsx" -> "spreadsheetml";
                    default -> "application/pdf";
                });
                assertThat(r.headers().firstValue("Content-Disposition").orElse("")).contains("fleet-" + report);
            }
        }
        String body = new String(download("/fleet/reports/vehicle-costs/export?format=csv&" + q() + "&vehicleId="
                + truck).body(), StandardCharsets.UTF_8);
        assertThat(body).contains("\"Total cost\"").contains("\"1580000.00\"").contains("UCT " + sfx);
        assertThat(as("GET", "/fleet/reports/nope/export", null).status()).isEqualTo(404);
        assertThat(as("GET", "/fleet/reports/fuel/export?format=doc", null).status()).isEqualTo(400);
        assertThat(body).startsWith("\uFEFF");
    }

    java.net.http.HttpResponse<byte[]> download(String path) {
        try {
            return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port
                    + "/api/v1" + path)).header("Authorization", "Bearer " + adminToken).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ freight bills

    @Test
    @Order(5)
    @DisplayName("freight bill: a trip billed once, matched within tolerance → AP draft bill + balanced accrual, replay;"
            + " disputed → variance approval; cancel reverses and releases")
    void freightBills() {
        JsonNode pickers = ok(200, "GET", "/fleet/freight-bills/lookups?search=Haulier%20" + sfx, null);
        assertThat(pickers.get("transporters").toString()).contains(transporter);
        JsonNode cand = ok(200, "GET", "/fleet/freight-bills/candidates?transporterPartnerId=" + transporter + "&"
                + q(), null);
        assertThat(cand.get("transporters").get(0).get("trips")).hasSize(1);
        assertThat(dec(cand.get("transporters").get(0).get("total"))).isEqualByComparingTo("40000");
        JsonNode gen = ok(201, "POST", "/fleet/freight-bills/generate", Map.of("transporterPartnerId", transporter,
                "from", today.toString(), "to", today.toString()));
        assertThat(gen.get("created")).hasSize(1);
        String bill = gen.get("created").get(0).get("id").asString();
        // A trip is billed once: generating again finds nothing.
        assertThat(ok(201, "POST", "/fleet/freight-bills/generate", Map.of("transporterPartnerId", transporter,
                "from", today.toString(), "to", today.toString())).get("created")).isEmpty();
        JsonNode detail = ok(200, "GET", "/fleet/freight-bills/" + bill, null);
        assertThat(detail.get("status").asString()).isEqualTo("draft");
        assertThat(detail.get("lines").get(0).get("tripId").asString()).isEqualTo(transporterTrip);
        assertThat(dec(detail.get("totalAmount"))).isEqualByComparingTo("40000");

        assertThat(call("POST", "/fleet/freight-bills/" + bill + "/match", Map.of("supplierReference", "INV-1",
                "supplierInvoiceDate", today.toString(), "supplierAmount", 40300), authOnly()).status())
                .isEqualTo(400);
        Map<String, String> h = key(adminToken);
        Map<String, Object> matchBody = Map.of("supplierReference", "INV-" + sfx, "supplierInvoiceDate",
                today.toString(), "supplierAmount", 40300);
        Res matched = call("POST", "/fleet/freight-bills/" + bill + "/match", matchBody, h);
        assertThat(matched.status()).as(matched.raw().body()).isEqualTo(200);
        assertThat(matched.body().get("status").asString()).isEqualTo("billed");
        assertThat(dec(matched.body().get("varianceAmount"))).isEqualByComparingTo("300");
        assertThat(matched.body().get("varianceAccepted").asBoolean()).isFalse();
        String je = matched.body().get("journalEntryId").asString();
        assertBalanced(je);
        BigDecimal expense = jdbc.sql("SELECT COALESCE(sum(debit), 0) - COALESCE(sum(credit), 0) FROM \"JournalLine\""
                + " WHERE \"journalEntryId\" = ? AND \"accountId\" = ?").params(je, account("6460"))
                .query(BigDecimal.class).single();
        assertThat(expense).isEqualByComparingTo("40300");
        assertThat(jdbc.sql("SELECT credit FROM \"JournalLine\" WHERE \"journalEntryId\" = ? AND \"accountId\" = ?")
                .params(je, account("2169")).query(BigDecimal.class).single()).isEqualByComparingTo("40300");
        JsonNode vb = matched.body().get("vendorBill");
        assertThat(vb.get("status").asString()).isEqualTo("draft");
        assertThat(dec(vb.get("totalAmount"))).isEqualByComparingTo("40300");
        assertThat(vb.get("reference").asString()).isEqualTo("INV-" + sfx);
        // Replay with the same key: same bill, one journal entry, one vendor bill.
        Res replay = call("POST", "/fleet/freight-bills/" + bill + "/match", matchBody, h);
        assertThat(replay.body().get("journalEntryId").asString()).isEqualTo(je);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"sourceType\" = 'fleet_freight_bill' AND"
                + " \"sourceId\" = ?").param(bill).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM \"EventOutbox\" WHERE \"eventName\" = 'fleet.freight_bill.matched'"
                + " AND payload->>'freightBillId' = ?").param(bill).query(Long.class).single()).isEqualTo(1);
        // Profitability now uses the billed freight.
        JsonNode p = ok(200, "GET", "/fleet/reports/delivery-profitability?" + q() + "&partnerId=" + customer, null);
        assertThat(row(p, transporterTrip).get("costBasis").asString()).isEqualTo("freight_bill");

        // Disputed: 20 % over → disputed; acceptance needs fleet_freight:approve_variance.
        String t2 = transporterTrip(5, 2000);
        String bill2 = ok(201, "POST", "/fleet/freight-bills/generate", Map.of("transporterPartnerId", transporter,
                "from", today.toString(), "to", today.toString())).get("created").get(0).get("id").asString();
        JsonNode disputed = money("/fleet/freight-bills/" + bill2 + "/match", Map.of("supplierReference", "INV2-" + sfx,
                "supplierInvoiceDate", today.toString(), "supplierAmount", 12000));
        assertThat(disputed.get("status").asString()).isEqualTo("disputed");
        assertThat(dec(disputed.get("variancePct"))).isEqualByComparingTo("20");
        assertThat(disputed.get("journalEntryId").isNull()).isTrue();
        String clerk = user("fleet_freight:read", "fleet_freight:bill");
        Res denied = call("POST", "/fleet/freight-bills/" + bill2 + "/match", Map.of("supplierReference", "INV2-" + sfx,
                "supplierInvoiceDate", today.toString(), "supplierAmount", 12000, "acceptVariance", true), key(clerk));
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).contains("fleet_freight:approve_variance");
        JsonNode accepted = money("/fleet/freight-bills/" + bill2 + "/match", Map.of("supplierReference",
                "INV2-" + sfx, "supplierInvoiceDate", today.toString(), "supplierAmount", 12000, "acceptVariance", true));
        assertThat(accepted.get("status").asString()).isEqualTo("billed");
        assertThat(accepted.get("varianceAccepted").asBoolean()).isTrue();
        assertBalanced(accepted.get("journalEntryId").asString());

        // Cancel: the accrual is reversed (balanced), the trip can be billed again.
        JsonNode cancelled = money("/fleet/freight-bills/" + bill2 + "/cancel", Map.of("reason", "Wrong rate"));
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertBalanced(cancelled.get("reversalEntryId").asString());
        assertThat(jdbc.sql("SELECT status::text FROM \"JournalEntry\" WHERE id = ?").param(accepted.get(
                "journalEntryId").asString()).query(String.class).single()).isEqualTo("reversed");
        JsonNode again = ok(200, "GET", "/fleet/freight-bills/candidates?transporterPartnerId=" + transporter + "&"
                + q(), null);
        assertThat(again.get("transporters").get(0).get("trips").toString()).contains(t2);
        assertThat(msg(call("POST", "/fleet/freight-bills/" + bill2 + "/match", matchBody, key(adminToken))))
                .contains("cannot be changed");
        // A draft bill: a line can be taken off, then the bill cancelled.
        String bill3 = ok(201, "POST", "/fleet/freight-bills/generate", Map.of("transporterPartnerId", transporter,
                "from", today.toString(), "to", today.toString())).get("created").get(0).get("id").asString();
        String line = ok(200, "GET", "/fleet/freight-bills/" + bill3, null).get("lines").get(0).get("id").asString();
        JsonNode emptied = ok(200, "DELETE", "/fleet/freight-bills/" + bill3 + "/lines/" + line, null);
        assertThat(emptied.get("tripCount").asInt()).isZero();
        assertThat(msg(money2("/fleet/freight-bills/" + bill3 + "/match", matchBody))).contains("has no trips");
        assertThat(ok(200, "GET", "/fleet/freight-bills?transporterPartnerId=" + transporter, null).get("meta")
                .get("total").asInt()).isEqualTo(3);
    }

    Res money2(String path, Object body) {
        return call("POST", path, body, key(adminToken));
    }

    Map<String, String> authOnly() {
        return Map.of("Authorization", "Bearer " + adminToken);
    }

    @Test
    @Order(6)
    @DisplayName("inbound freight on a posted goods receipt is capitalised as landed cost (voucher + supplier bill),"
            + " once per receipt + invoice; cancel reverses the voucher")
    void inboundLandedCost() {
        String sand = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, name, \"productType\", \"trackInventory\","
                + " \"costingMethod\", \"costPrice\", \"salesPrice\", \"updatedAt\") VALUES (?, ?, ?, ?, 'stockable',"
                + " true, 'AVCO'::\"CostingMethod\", 0, 0, now())").params(sand, demoOrg, "SND-" + sfx, "Sand " + sfx)
                .update();
        String supplier = partner("Sand pit", true);
        String warehouse = jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code ="
                + " 'MAIN'").param(demoOrg).query(String.class).single();
        JsonNode grn = ok(200, "POST", "/procurement/goods-receipts/adhoc", Map.of("partnerId", supplier,
                "warehouseId", warehouse, "receivedAt", java.time.Instant.now().toString(), "lines",
                List.of(Map.of("productId", sand, "description", "Sand", "quantity", 100, "unitCost", 20000))));
        String grnId = grn.get("id").asString();
        BigDecimal avgBefore = jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(sand, warehouse).query(BigDecimal.class).single();
        assertThat(avgBefore).isEqualByComparingTo("20000");
        Map<String, Object> body = Map.of("goodsReceiptId", grnId, "transporterPartnerId", transporter,
                "supplierReference", "FRT-" + sfx, "invoiceDate", today.toString(), "amount", 150000);
        Map<String, String> h = key(adminToken);
        Res r = call("POST", "/fleet/freight-bills/inbound", body, h);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(200);
        JsonNode b = r.body();
        assertThat(b.get("direction").asString()).isEqualTo("inbound");
        assertThat(b.get("status").asString()).isEqualTo("billed");
        assertThat(b.get("landedCost").get("status").asString()).isEqualTo("posted");
        assertThat(b.get("vendorBillId").isNull()).isFalse();
        assertBalanced(b.get("journalEntryId").asString());
        // 150,000 over 100 units on hand → average cost 20,000 + 1,500.
        assertThat(jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(sand, warehouse).query(BigDecimal.class).single())
                .isEqualByComparingTo("21500");
        // Same receipt + supplier invoice again (new key): the existing charge, no second voucher.
        JsonNode again = money("/fleet/freight-bills/inbound", body);
        assertThat(again.get("id").asString()).isEqualTo(b.get("id").asString());
        assertThat(jdbc.sql("SELECT count(*) FROM \"LandedCostReceipt\" WHERE \"goodsReceiptId\" = ?").param(grnId)
                .query(Long.class).single()).isEqualTo(1);
        JsonNode cancelled = money("/fleet/freight-bills/" + b.get("id").asString() + "/cancel", Map.of("reason",
                "Duplicate invoice"));
        assertThat(cancelled.get("landedCost").get("status").asString()).isEqualTo("reversed");
        assertThat(jdbc.sql("SELECT \"runningAverageCost\" FROM \"StockItem\" WHERE \"productId\" = ? AND"
                + " \"locationId\" = ?").params(sand, warehouse).query(BigDecimal.class).single())
                .isEqualByComparingTo("20000");
        Res noGrn = money2("/fleet/freight-bills/inbound", Map.of("goodsReceiptId", UUID.randomUUID().toString(),
                "transporterPartnerId", transporter, "supplierReference", "X", "invoiceDate", today.toString(),
                "amount", 1));
        assertThat(noGrn.status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ isolation + authorization

    @Test
    @Order(7)
    @DisplayName("tenant isolation: another organization sees none of these costs, bills or reminders")
    void tenantIsolation() {
        String bill = jdbc.sql("SELECT id FROM \"FleetFreightBill\" WHERE \"organizationId\" = ? AND"
                + " \"transporterPartnerId\" = ? LIMIT 1").params(demoOrg, transporter).query(String.class).single();
        assertThat(call("GET", "/fleet/freight-bills/" + bill, null, otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/freight-bills", null, otherToken).body().get("meta").get("total").asInt())
                .isZero();
        JsonNode costs = call("GET", "/fleet/reports/vehicle-costs?" + q(), null, otherToken).body();
        assertThat(costs.get("rows").toString()).doesNotContain(truck);
        JsonNode p = call("GET", "/fleet/reports/delivery-profitability?" + q(), null, otherToken).body();
        assertThat(p.get("rows")).isEmpty();
        assertThat(call("GET", "/fleet/vehicles/" + truck + "/cost-summary", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/fleet/reminders", null, otherToken).body().get("meta").get("total").asInt())
                .isZero();
    }

    @Test
    @Order(8)
    @DisplayName("authorization matrix: every 1.9 route is 401 anonymous and 403 without its permission")
    void authorizationMatrix() {
        int checked = assertAuthorizationMatrix(FleetReportController.class, FleetFreightBillController.class,
                FleetReminderController.class);
        assertThat(checked).isEqualTo(22);
    }
}
