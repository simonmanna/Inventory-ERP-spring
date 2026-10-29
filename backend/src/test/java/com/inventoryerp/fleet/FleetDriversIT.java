package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.fleet.application.FleetDriverService;
import com.inventoryerp.fleet.web.FleetCheckoutController;
import com.inventoryerp.fleet.web.FleetDriverController;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Fleet 1.2 — drivers &amp; operators over HTTP: profiles linked to hr employees, the expiring-documents query,
 * eligibility blocks (licence, class, vehicle status), assignment history, check-out / check-in with meter readings,
 * photos, concurrent check-outs, tenant isolation and the authorization matrix.
 */
class FleetDriversIT extends ApiTestSupport {

    @Autowired
    FleetDriverService driverService;

    String tipper;
    String excavator;
    String costCenter;
    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int seq;
    final LocalDate today = LocalDate.now(BusinessZone.get());

    @BeforeAll
    void fixtures() {
        tipper = category("TIPPER");
        excavator = category("EXCAVATOR");
        costCenter = ok(201, "POST", "/cost-centers", Map.of("code", "FLT-" + sfx, "name", "Fleet " + sfx)).get("id")
                .asString();
    }

    // ------------------------------------------------------------------ helpers

    String category(String code) {
        for (JsonNode c : ok(200, "GET", "/fleet/categories", null)) {
            if (code.equals(c.get("code").asString())) {
                return c.get("id").asString();
            }
        }
        throw new AssertionError("no class " + code);
    }

    String employee() {
        return ok(201, "POST", "/hr/employees", Map.of("firstName", "Driver", "lastName", sfx + "-" + (++seq)))
                .get("id").asString();
    }

    JsonNode vehicle(String categoryId, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("registrationPlate", "UDR " + sfx + "-" + (++seq));
        body.put("costCenterId", costCenter);
        if (categoryId.equals(excavator)) {
            body.put("meterType", "hours");
            body.put("openingHours", 500);
        } else {
            body.put("openingOdometer", 1000);
        }
        body.putAll(extra);
        return ok(201, "POST", "/fleet/vehicles", body);
    }

    JsonNode driver(Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employeeId", employee());
        body.put("licenceNumber", "DL-" + sfx + "-" + (++seq));
        body.put("licenceClasses", List.of("c", "CE", "C"));
        body.put("licenceIssuedOn", today.minusYears(2).toString());
        body.put("licenceExpiresOn", today.plusYears(2).toString());
        body.put("qualifiedCategoryIds", List.of(tipper));
        body.putAll(extra);
        return ok(201, "POST", "/fleet/drivers", body);
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    Res checkOut(String vehicleId, Map<String, Object> body) {
        return as("POST", "/fleet/vehicles/" + vehicleId + "/check-out", body);
    }

    Res photo(String checkoutId, String phase) {
        try {
            String boundary = "fleet-" + UUID.randomUUID();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"phase\"\r\n\r\n" + phase + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"front.png\""
                    + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/fleet/checkouts/"
                            + checkoutId + "/photos"))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ profiles

    @Test
    @DisplayName("profiles: create from an hr employee, validation, uniqueness, filters, detail, patch, delete")
    void profiles() {
        JsonNode d = driver(Map.of("driverType", "driver_operator", "licenceIssuer", "URSB"));
        String id = d.get("id").asString();
        assertThat(d.get("code").asString()).startsWith("DRV-");
        assertThat(d.get("status").asString()).isEqualTo("active");
        assertThat(d.get("licenceClasses")).hasSize(2);
        assertThat(d.get("licenceClasses").get(0).asString()).isEqualTo("C");
        assertThat(d.get("name").asString()).startsWith("Driver ");
        assertThat(d.get("qualifications").get(0).get("code").asString()).isEqualTo("TIPPER");
        assertThat(d.get("documents").get("licence").get("state").asString()).isEqualTo("valid");
        assertThat(d.get("documents").get("medical").isNull()).isTrue();
        assertThat(d.get("documentState").asString()).isEqualTo("valid");

        Res dupEmployee = as("POST", "/fleet/drivers", Map.of("employeeId", d.get("employeeId").asString()));
        assertThat(dupEmployee.status()).isEqualTo(409);
        assertThat(msg(dupEmployee)).contains("already has driver profile " + d.get("code").asString());
        Res dupLicence = as("POST", "/fleet/drivers", Map.of("employeeId", employee(), "licenceNumber",
                d.get("licenceNumber").asString().toLowerCase()));
        assertThat(dupLicence.status()).isEqualTo(409);
        assertThat(msg(dupLicence)).isEqualTo("Licence number " + d.get("licenceNumber").asString()
                + " is already registered to driver " + d.get("code").asString());
        Res noEmployee = as("POST", "/fleet/drivers", Map.of("employeeId", UUID.randomUUID().toString()));
        assertThat(noEmployee.status()).isEqualTo(400);
        assertThat(msg(noEmployee)).isEqualTo("Employee not found");
        Res badDates = as("POST", "/fleet/drivers", Map.of("employeeId", employee(), "licenceIssuedOn",
                today.toString(), "licenceExpiresOn", today.minusDays(1).toString()));
        assertThat(msg(badDates)).isEqualTo("The licence expiry date must be on or after the issue date");
        Res badClass = as("POST", "/fleet/drivers", Map.of("employeeId", employee(), "qualifiedCategoryIds",
                List.of(UUID.randomUUID().toString())));
        assertThat(msg(badClass)).isEqualTo("Vehicle class not found");
        Res badType = as("POST", "/fleet/drivers", Map.of("employeeId", employee(), "driverType", "pilot"));
        assertThat(badType.status()).isEqualTo(400);

        JsonNode list = ok(200, "GET", "/fleet/drivers?search=" + d.get("code").asString() + "&pageSize=5", null);
        assertThat(list.get("meta").get("total").asLong()).isEqualTo(1);
        assertThat(list.get("data").get(0).get("qualifications")).hasSize(1);
        assertThat(ok(200, "GET", "/fleet/drivers?categoryId=" + excavator + "&search=" + sfx, null).toString())
                .doesNotContain(id);
        assertThat(ok(200, "GET", "/fleet/drivers?driverType=driver_operator&search=" + sfx, null).toString())
                .contains(id);
        assertThat(ok(200, "GET", "/fleet/drivers?sortBy=licenceExpiresOn&sortOrder=desc&page=1", null)
                .get("meta").get("page").asInt()).isEqualTo(1);

        Res noReason = as("PATCH", "/fleet/drivers/" + id, Map.of("status", "suspended"));
        assertThat(msg(noReason)).isEqualTo("A reason is required to suspend a driver");
        JsonNode suspended = ok(200, "PATCH", "/fleet/drivers/" + id, Map.of("status", "suspended", "statusReason",
                "Speeding investigation", "medicalExpiresOn", today.plusMonths(6).toString(),
                "qualifiedCategoryIds", List.of(tipper, excavator)));
        assertThat(suspended.get("status").asString()).isEqualTo("suspended");
        assertThat(suspended.get("qualifications")).hasSize(2);
        assertThat(ok(200, "GET", "/fleet/drivers?status=suspended&search=" + sfx, null).toString()).contains(id);
        JsonNode back = ok(200, "PATCH", "/fleet/drivers/" + id, Map.of("status", "active", "clear",
                List.of("medicalExpiresOn")));
        assertThat(back.get("statusReason").isNull()).isTrue();
        assertThat(back.get("medicalExpiresOn").isNull()).isTrue();
        Res badClear = as("PATCH", "/fleet/drivers/" + id, Map.of("clear", List.of("employeeId")));
        assertThat(msg(badClear)).isEqualTo("Field employeeId cannot be cleared");

        JsonNode spare = driver(Map.of());
        assertThat(ok(200, "DELETE", "/fleet/drivers/" + spare.get("id").asString(), null).get("ok").asBoolean())
                .isTrue();
        assertThat(as("GET", "/fleet/drivers/" + spare.get("id").asString(), null).status()).isEqualTo(404);
        JsonNode picker = ok(200, "GET", "/fleet/lookups/employees?search=" + sfx, null);
        assertThat(picker.toString()).contains(d.get("code").asString());
    }

    @Test
    @DisplayName("expiring documents: licence expiring soon, medical expired, permit valid; reminder-job API")
    void documentsDue() {
        JsonNode d = driver(Map.of("licenceExpiresOn", today.plusDays(10).toString(), "medicalExpiresOn",
                today.minusDays(2).toString(), "permitNumber", "OP-" + sfx, "permitExpiresOn",
                today.plusYears(1).toString()));
        String code = d.get("code").asString();
        assertThat(d.get("documents").get("licence").get("state").asString()).isEqualTo("expiring");
        assertThat(d.get("documents").get("medical").get("state").asString()).isEqualTo("expired");
        assertThat(d.get("documents").get("permit").get("state").asString()).isEqualTo("valid");
        assertThat(d.get("documentState").asString()).isEqualTo("expired");

        JsonNode expired = ok(200, "GET", "/fleet/driver-documents?due=expired&pageSize=200", null);
        List<String> expiredDocs = new ArrayList<>();
        expired.get("data").forEach(r -> {
            if (code.equals(r.get("driverCode").asString())) {
                expiredDocs.add(r.get("document").asString());
            }
        });
        assertThat(expiredDocs).containsExactly("medical");
        JsonNode expiring = ok(200, "GET", "/fleet/driver-documents?due=expiring&pageSize=200", null);
        assertThat(expiring.toString()).contains(code);
        JsonNode attention = ok(200, "GET", "/fleet/driver-documents?pageSize=200", null);
        long mine = 0;
        for (JsonNode r : attention.get("data")) {
            if (code.equals(r.get("driverCode").asString())) {
                mine++;
                assertThat(r.get("daysToExpiry").isNumber()).isTrue();
            }
        }
        assertThat(mine).isEqualTo(2);
        assertThat(ok(200, "GET", "/fleet/drivers?documents=expired&search=" + code, null).get("meta").get("total")
                .asLong()).isEqualTo(1);
        // In a week the medical is still expired and the licence still expiring; in a month both are expired.
        JsonNode later = ok(200, "GET", "/fleet/driver-documents?due=expired&pageSize=200&asOf="
                + today.plusMonths(1), null);
        assertThat(later.toString()).contains("\"licence\"");
        List<Map<String, Object>> job = TenantContext.run(TenantStore.organization(demoOrg),
                () -> driverService.documentsDueForOrganization(demoOrg, today));
        assertThat(job).anyMatch(r -> code.equals(r.get("driverCode")) && "medical".equals(r.get("document")));
    }

    // ------------------------------------------------------------------ eligibility + assignments

    @Test
    @DisplayName("eligibility: expired licence, unqualified class, vehicle in workshop, suspended driver block assignment")
    void eligibilityBlocks() {
        String truck = vehicle(tipper, Map.of()).get("id").asString();
        JsonNode lapsed = driver(Map.of("licenceIssuedOn", today.minusYears(5).toString(), "licenceExpiresOn",
                today.minusDays(1).toString()));
        Res expired = as("POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId",
                lapsed.get("id").asString(), "kind", "primary"));
        assertThat(expired.status()).isEqualTo(400);
        assertThat(msg(expired)).isEqualTo("Driver " + lapsed.get("code").asString() + "'s driving licence expired on "
                + today.minusDays(1));
        JsonNode check = ok(200, "GET", "/fleet/drivers/" + lapsed.get("id").asString() + "/eligibility?vehicleId="
                + truck, null);
        assertThat(check.get("eligible").asBoolean()).isFalse();
        assertThat(check.get("reasons")).hasSize(1);

        String digger = vehicle(excavator, Map.of()).get("id").asString();
        JsonNode d = driver(Map.of());
        Res unqualified = as("POST", "/fleet/assignments", Map.of("vehicleId", digger, "driverId",
                d.get("id").asString(), "kind", "primary"));
        assertThat(msg(unqualified)).isEqualTo("Driver " + d.get("code").asString()
                + " is not qualified for vehicle class Excavator (EXCAVATOR)");

        ok(201, "POST", "/fleet/vehicles/" + truck + "/status", Map.of("status", "in_workshop"));
        Res workshop = as("POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId",
                d.get("id").asString(), "kind", "primary"));
        assertThat(msg(workshop)).isEqualTo("Vehicle " + ok(200, "GET", "/fleet/vehicles/" + truck, null).get("code")
                .asString() + " is in workshop and cannot be assigned");
        Res workshopOut = checkOut(truck, Map.of("driverId", d.get("id").asString(), "odometer", 1100));
        assertThat(msg(workshopOut)).endsWith("is in workshop and cannot be checked out");
        ok(201, "POST", "/fleet/vehicles/" + truck + "/status", Map.of("status", "active"));

        ok(200, "PATCH", "/fleet/drivers/" + d.get("id").asString(), Map.of("status", "suspended", "statusReason",
                "Medical review"));
        Res suspended = as("POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId",
                d.get("id").asString(), "kind", "primary"));
        assertThat(msg(suspended)).isEqualTo("Driver " + d.get("code").asString()
                + " is suspended and cannot be assigned");
        ok(200, "PATCH", "/fleet/drivers/" + d.get("id").asString(), Map.of("status", "active"));
        assertThat(ok(200, "GET", "/fleet/drivers/" + d.get("id").asString() + "/eligibility?vehicleId=" + truck
                + "&action=checked%20out", null).get("eligible").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("assignments: primary replaces primary, temporary needs an end, duplicates refused, end, history")
    void assignmentHistory() {
        String truck = vehicle(tipper, Map.of()).get("id").asString();
        JsonNode a = driver(Map.of());
        JsonNode b = driver(Map.of());
        JsonNode c = driver(Map.of());
        Instant t0 = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        JsonNode first = ok(201, "POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId",
                a.get("id").asString(), "kind", "primary", "startsAt", t0.toString(), "notes", "Day shift"));
        assertThat(first.get("status").asString()).isEqualTo("active");
        assertThat(first.get("driverCode").asString()).isEqualTo(a.get("code").asString());
        Res dup = as("POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId", a.get("id").asString(),
                "kind", "temporary", "endsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(msg(dup)).isEqualTo("Driver " + a.get("code").asString() + " is already assigned to vehicle "
                + first.get("vehicleCode").asString());
        Res noEnd = as("POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId", c.get("id").asString(),
                "kind", "temporary"));
        assertThat(msg(noEnd)).isEqualTo("A temporary assignment needs an end date and time");
        Res backwards = as("POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId",
                c.get("id").asString(), "kind", "temporary", "startsAt", t0.toString(), "endsAt",
                t0.minusSeconds(60).toString()));
        assertThat(msg(backwards)).isEqualTo("The assignment must end after it starts");
        JsonNode cover = ok(201, "POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId",
                c.get("id").asString(), "kind", "temporary", "startsAt", t0.plus(1, ChronoUnit.HOURS).toString(),
                "endsAt", t0.plus(10, ChronoUnit.HOURS).toString()));
        assertThat(cover.get("kind").asString()).isEqualTo("temporary");

        Instant t1 = t0.plus(1, ChronoUnit.DAYS);
        JsonNode second = ok(201, "POST", "/fleet/assignments", Map.of("vehicleId", truck, "driverId",
                b.get("id").asString(), "kind", "primary", "startsAt", t1.toString()));
        JsonNode history = ok(200, "GET", "/fleet/assignments?vehicleId=" + truck, null);
        assertThat(history.get("meta").get("total").asLong()).isEqualTo(3);
        JsonNode ended = null;
        for (JsonNode h : history.get("data")) {
            if (h.get("id").asString().equals(first.get("id").asString())) {
                ended = h;
            }
        }
        assertThat(ended).isNotNull();
        assertThat(ended.get("status").asString()).isEqualTo("ended");
        assertThat(Instant.parse(ended.get("endsAt").asString())).isEqualTo(t1);
        assertThat(ended.get("endReason").asString()).isEqualTo("Replaced by driver " + b.get("code").asString());
        assertThat(ok(200, "GET", "/fleet/assignments?vehicleId=" + truck + "&status=active", null).get("meta")
                .get("total").asLong()).isEqualTo(2);
        assertThat(ok(200, "GET", "/fleet/drivers/" + b.get("id").asString(), null).get("assignments")).hasSize(1);

        JsonNode done = ok(201, "POST", "/fleet/assignments/" + second.get("id").asString() + "/end",
                Map.of("reason", "Transferred"));
        assertThat(done.get("status").asString()).isEqualTo("ended");
        Res twice = as("POST", "/fleet/assignments/" + second.get("id").asString() + "/end", Map.of());
        assertThat(msg(twice)).isEqualTo("This assignment has already ended");
        assertThat(ok(200, "GET", "/fleet/assignments?driverId=" + b.get("id").asString(), null).get("data").get(0)
                .get("endReason").asString()).isEqualTo("Transferred");
        // A driver with history cannot be deleted.
        Res del = as("DELETE", "/fleet/drivers/" + a.get("id").asString(), null);
        assertThat(msg(del)).isEqualTo("Driver " + a.get("code").asString()
                + " has assignments, check-outs or claims; mark the profile inactive instead");
    }

    // ------------------------------------------------------------------ check-out / check-in

    @Test
    @DisplayName("check-out / check-in: required meters, monotonic rule, readings with source, distance, photos, breakdown")
    void checkOutAndIn() {
        JsonNode truck = vehicle(tipper, Map.of());
        String vid = truck.get("id").asString();
        String code = truck.get("code").asString();
        JsonNode d = driver(Map.of());
        String did = d.get("id").asString();
        ok(201, "POST", "/fleet/assignments", Map.of("vehicleId", vid, "driverId", did, "kind", "primary",
                "startsAt", Instant.now().minus(1, ChronoUnit.DAYS).toString()));

        Res noMeter = checkOut(vid, Map.of("driverId", did));
        assertThat(msg(noMeter)).isEqualTo("Enter the odometer reading (km) to check out vehicle " + code);
        Res lower = checkOut(vid, Map.of("driverId", did, "odometer", 900));
        assertThat(lower.status()).isEqualTo(400);
        assertThat(msg(lower)).startsWith("Odometer reading 900 km is lower than the previous reading of 1000 km");
        Res badFlag = checkOut(vid, Map.of("driverId", did, "odometer", 1200, "damageFlags", List.of("wings")));
        assertThat(msg(badFlag)).startsWith("Unknown damage flag 'wings'");
        Res future = checkOut(vid, Map.of("driverId", did, "odometer", 1200, "checkedOutAt",
                Instant.now().plus(1, ChronoUnit.HOURS).toString()));
        assertThat(msg(future)).isEqualTo("The check-out time cannot be in the future");

        // After the opening reading (taken when the vehicle was registered).
        Instant outAt = Instant.now();
        JsonNode out = ok(201, "POST", "/fleet/vehicles/" + vid + "/check-out", Map.of("driverId", did, "odometer",
                1200, "fuelLevelPct", 75, "condition", "fair", "damageFlags", List.of("mirrors", "body"), "notes",
                "Scratch on the left door", "purpose", "Deliveries to Kira site", "checkedOutAt", outAt.toString()));
        String cid = out.get("id").asString();
        assertThat(out.get("code").asString()).startsWith("CHK-");
        assertThat(out.get("status").asString()).isEqualTo("open");
        assertThat(out.get("assignmentId").isNull()).isFalse();
        assertThat(out.get("outDamageFlags")).hasSize(2);
        assertThat(jdbc.sql("SELECT value FROM \"FleetMeterReading\" WHERE \"vehicleId\" = ? AND source = 'check_out'"
                + " AND \"sourceId\" = ?").params(vid, cid).query(BigDecimal.class).single()).isEqualByComparingTo("1200");
        assertThat(ok(200, "GET", "/fleet/vehicles/" + vid, null).get("meters").get("odometer").get("value")
                .asString()).startsWith("1200");
        assertThat(ok(200, "GET", "/fleet/vehicles/" + vid + "/open-checkout", null).get("checkout").get("id")
                .asString()).isEqualTo(cid);
        assertThat(ok(200, "GET", "/fleet/drivers/" + did, null).get("openCheckout").get("id").asString())
                .isEqualTo(cid);

        JsonNode other = driver(Map.of());
        Res busy = checkOut(vid, Map.of("driverId", other.get("id").asString(), "odometer", 1300));
        assertThat(busy.status()).isEqualTo(409);
        assertThat(msg(busy)).isEqualTo("Vehicle " + code + " is already checked out to driver "
                + d.get("code").asString() + " (" + out.get("code").asString() + ")");
        JsonNode truck2 = vehicle(tipper, Map.of());
        Res driverBusy = checkOut(truck2.get("id").asString(), Map.of("driverId", did, "odometer", 1300));
        assertThat(driverBusy.status()).isEqualTo(409);
        assertThat(msg(driverBusy)).isEqualTo("Driver " + d.get("code").asString() + " already has vehicle " + code
                + " checked out (" + out.get("code").asString() + ")");

        Res photoOut = photo(cid, "out");
        assertThat(photoOut.status()).as(photoOut.raw().body()).isEqualTo(201);
        assertThat(photoOut.body().get("phase").asString()).isEqualTo("out");

        Res backwards = as("POST", "/fleet/checkouts/" + cid + "/check-in", Map.of("odometer", 1100));
        assertThat(msg(backwards)).startsWith("Odometer reading 1100 km is lower than the previous reading of 1200 km");
        Res early = as("POST", "/fleet/checkouts/" + cid + "/check-in", Map.of("odometer", 1300, "checkedInAt",
                outAt.minusSeconds(60).toString()));
        assertThat(msg(early)).isEqualTo("The check-in time must be after the check-out time");
        JsonNode in = ok(201, "POST", "/fleet/checkouts/" + cid + "/check-in", Map.of("odometer", 1387.5,
                "fuelLevelPct", 25, "condition", "poor", "damageFlags", List.of("tyres"), "notes",
                "Front tyre burst", "reportBreakdown", true));
        assertThat(in.get("status").asString()).isEqualTo("closed");
        assertThat(new BigDecimal(in.get("distanceKm").asString())).isEqualByComparingTo("187.5");
        assertThat(in.get("inDamageFlags").get(0).asString()).isEqualTo("tyres");
        assertThat(photo(cid, "in").status()).isEqualTo(201);
        JsonNode detail = ok(200, "GET", "/fleet/checkouts/" + cid, null);
        assertThat(detail.get("photos")).hasSize(2);
        assertThat(detail.get("photos").get(0).get("url").asString()).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*) FROM \"FleetMeterReading\" WHERE \"vehicleId\" = ? AND source = 'check_in'"
                + " AND \"sourceId\" = ?").params(vid, cid).query(Long.class).single()).isEqualTo(1);
        JsonNode v = ok(200, "GET", "/fleet/vehicles/" + vid, null);
        assertThat(v.get("status").asString()).isEqualTo("breakdown");
        assertThat(v.get("statusReason").asString()).isEqualTo("Front tyre burst");
        assertThat(ok(200, "GET", "/fleet/vehicles/" + vid + "/status-history", null).get(0).get("source").asString())
                .isEqualTo("check_in");
        Res again = as("POST", "/fleet/checkouts/" + cid + "/check-in", Map.of("odometer", 1400));
        assertThat(msg(again)).isEqualTo("Check-out " + out.get("code").asString() + " is already closed");
        JsonNode history = ok(200, "GET", "/fleet/checkouts?vehicleId=" + vid, null);
        assertThat(history.get("meta").get("total").asLong()).isEqualTo(1);
        assertThat(ok(200, "GET", "/fleet/checkouts?driverId=" + did + "&status=closed", null).get("meta").get("total")
                .asLong()).isEqualTo(1);
        assertThat(ok(200, "GET", "/fleet/vehicles/" + vid + "/open-checkout", null).get("checkout").isNull())
                .isTrue();

        // An hour-meter machine needs the engine hours.
        JsonNode digger = vehicle(excavator, Map.of());
        JsonNode op = driver(Map.of("driverType", "operator", "qualifiedCategoryIds", List.of(excavator)));
        Res noHours = checkOut(digger.get("id").asString(), Map.of("driverId", op.get("id").asString()));
        assertThat(msg(noHours)).isEqualTo("Enter the engine hours to check out vehicle " + digger.get("code")
                .asString());
        JsonNode dOut = ok(201, "POST", "/fleet/vehicles/" + digger.get("id").asString() + "/check-out",
                Map.of("driverId", op.get("id").asString(), "hours", 512.5));
        JsonNode dIn = ok(201, "POST", "/fleet/checkouts/" + dOut.get("id").asString() + "/check-in",
                Map.of("hours", 520));
        assertThat(new BigDecimal(dIn.get("hoursUsed").asString())).isEqualByComparingTo("7.5");
    }

    @Test
    @DisplayName("concurrency: one open check-out per vehicle and per driver under parallel requests")
    void concurrentCheckouts() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Two drivers race for one vehicle.
            String vid = vehicle(tipper, Map.of()).get("id").asString();
            String a = driver(Map.of()).get("id").asString();
            String b = driver(Map.of()).get("id").asString();
            assertThat(race(pool, List.of(() -> checkOut(vid, Map.of("driverId", a, "odometer", 1100)).status(),
                    () -> checkOut(vid, Map.of("driverId", b, "odometer", 1100)).status())))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.sql("SELECT count(*) FROM \"FleetCheckout\" WHERE \"vehicleId\" = ? AND status = 'open'")
                    .param(vid).query(Long.class).single()).isEqualTo(1);
            // One driver races for two vehicles.
            String v1 = vehicle(tipper, Map.of()).get("id").asString();
            String v2 = vehicle(tipper, Map.of()).get("id").asString();
            String c = driver(Map.of()).get("id").asString();
            assertThat(race(pool, List.of(() -> checkOut(v1, Map.of("driverId", c, "odometer", 1100)).status(),
                    () -> checkOut(v2, Map.of("driverId", c, "odometer", 1100)).status())))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.sql("SELECT count(*) FROM \"FleetCheckout\" WHERE \"driverId\" = ? AND status = 'open'")
                    .param(c).query(Long.class).single()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    List<Integer> race(ExecutorService pool, List<java.util.concurrent.Callable<Integer>> calls) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (var call : calls) {
            results.add(pool.submit(() -> {
                go.await();
                return call.call();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        return statuses;
    }

    // ------------------------------------------------------------------ isolation + authorization

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's drivers, assignments or check-outs")
    void tenantIsolation() {
        JsonNode truck = vehicle(tipper, Map.of());
        JsonNode d = driver(Map.of());
        String id = d.get("id").asString();
        JsonNode out = ok(201, "POST", "/fleet/vehicles/" + truck.get("id").asString() + "/check-out",
                Map.of("driverId", id, "odometer", 1050));
        assertThat(call("GET", "/fleet/drivers/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("PATCH", "/fleet/drivers/" + id, Map.of("notes", "x"), otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/checkouts/" + out.get("id").asString(), null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/fleet/checkouts/" + out.get("id").asString() + "/check-in", Map.of("odometer", 1100),
                otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/drivers?search=" + sfx, null, otherToken).body().get("meta").get("total")
                .asLong()).isZero();
        assertThat(call("GET", "/fleet/checkouts", null, otherToken).body().toString())
                .doesNotContain(out.get("id").asString());
        assertThat(call("GET", "/fleet/driver-documents?due=all", null, otherToken).body().toString())
                .doesNotContain(id);
        Map<String, Object> body = new HashMap<>(Map.of("vehicleId", truck.get("id").asString(), "driverId", id,
                "kind", "primary"));
        assertThat(call("POST", "/fleet/assignments", body, otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/drivers", Map.of("employeeId", d.get("employeeId").asString()), otherToken)
                .status()).isEqualTo(400);
    }

    @Test
    @DisplayName("authorization matrix: 401 without a token and 403 without the permission on every 1.2 route")
    void authorizationMatrix() {
        assertThat(assertAuthorizationMatrix(FleetDriverController.class, FleetCheckoutController.class))
                .isEqualTo(17);
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Driver reader " + sfx, new String[] {"fleet_driver:read"}).update();
        String email = "driver-reader-" + sfx.toLowerCase() + "@demo.test";
        createUser(demoOrg, email, "Reader@12345", role);
        String reader = login("DEMO", email, "Reader@12345");
        assertThat(call("GET", "/fleet/drivers", null, reader).status()).isEqualTo(200);
        Res denied = call("POST", "/fleet/assignments", Map.of("vehicleId", "x", "driverId", "y", "kind", "primary"),
                reader);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).isEqualTo("Missing required permission(s): fleet_driver:assign");
    }
}
