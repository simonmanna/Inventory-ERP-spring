package com.inventoryerp.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.fleet.application.FleetComplianceService;
import com.inventoryerp.fleet.web.FleetCategoryController;
import com.inventoryerp.fleet.web.FleetComplianceController;
import com.inventoryerp.fleet.web.FleetMeterController;
import com.inventoryerp.fleet.web.FleetVehicleController;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.events.OutboxWorker;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Fleet 1.1 — vehicle &amp; equipment registry over HTTP: classes, vehicle CRUD and filters, the status machine,
 * monotonic meter readings, approval-gated meter corrections (adjust / replace / reject / no workflow), compliance
 * documents and the expiry query, attachments, the linked fixed asset, lookups, tenant isolation, row locking and the
 * authorization matrix.
 */
class FleetRegistryIT extends ApiTestSupport {

    @Autowired
    OutboxWorker outbox;

    @Autowired
    FleetComplianceService complianceService;

    String tipper;
    String excavator;
    String approverToken;
    final String sfx = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    int plates;

    @BeforeAll
    void fleetFixtures() {
        tipper = category("TIPPER");
        excavator = category("EXCAVATOR");
        String role = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Role\" (id, \"organizationId\", name, \"isSystem\", permissions, \"updatedAt\")"
                        + " VALUES (?, ?, ?, false, ?, now())")
                .params(role, demoOrg, "Fleet approver " + sfx,
                        new String[] {"fleet_vehicle:read", "fleet_vehicle:approve_meter"}).update();
        String email = "fleet-approver-" + sfx.toLowerCase() + "@demo.test";
        createUser(demoOrg, email, "Approver@12345", role);
        approverToken = login("DEMO", email, "Approver@12345");
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

    String plate() {
        return "UAT " + sfx + "-" + (++plates);
    }

    JsonNode tipper(Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", tipper);
        body.put("registrationPlate", plate());
        body.put("make", "Sinotruk");
        body.put("model", "HOWO 371");
        body.put("year", 2022);
        body.put("payloadCapacityT", 25);
        body.put("bodyVolumeM3", 18);
        body.put("axleConfig", "6x4");
        body.put("openingOdometer", 1000);
        body.putAll(extra);
        return ok(201, "POST", "/fleet/vehicles", body);
    }

    JsonNode reading(String vehicleId, String meterType, Object value, Instant at, int status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("meterType", meterType);
        body.put("value", value);
        if (at != null) {
            body.put("readingAt", at.toString());
        }
        Res r = as("POST", "/fleet/vehicles/" + vehicleId + "/meter-readings", body);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(status);
        return r.body();
    }

    static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asString());
    }

    static String msg(Res r) {
        JsonNode m = r.body().get("message");
        return m.isArray() ? m.get(0).asString() : m.asString();
    }

    void decide(String requestId, String status, String token, int expect) {
        Res r = call("POST", "/approvals/" + requestId + "/decide", Map.of("status", status), token);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(expect);
    }

    /** Drains the outbox until the correction leaves {@code pending} (the approval subscriber applied it). */
    JsonNode awaitDecided(String correctionId) {
        long until = System.currentTimeMillis() + 20_000;
        while (true) {
            outbox.tick();
            JsonNode c = ok(200, "GET", "/fleet/meter-corrections/" + correctionId, null);
            if (!"pending".equals(c.get("status").asString()) || System.currentTimeMillis() > until) {
                return c;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return c;
            }
        }
    }

    Res upload(String vehicleId, String filename, String contentType, byte[] content, Map<String, String> fields) {
        try {
            String boundary = "fleet-" + UUID.randomUUID();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Map.Entry<String, String> f : fields.entrySet()) {
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n"
                        + f.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                    + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(content);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/fleet/vehicles/"
                            + vehicleId + "/attachments"))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body().isEmpty() ? null : mapper.readTree(r.body()), r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ classes

    @Test
    @DisplayName("classes: construction defaults seeded, custom class, duplicate code, in-use delete refused")
    void categories() {
        JsonNode all = ok(200, "GET", "/fleet/categories", null);
        List<String> codes = new ArrayList<>();
        all.forEach(c -> codes.add(c.get("code").asString()));
        assertThat(codes).contains("TIPPER", "TRAILER", "PRIME_MOVER", "WATER_BOWSER", "EXCAVATOR", "WHEEL_LOADER",
                "DOZER", "FORKLIFT", "GENERATOR", "LIGHT_VEHICLE", "OTHER");

        String code = "mixer " + sfx;
        JsonNode mixer = ok(201, "POST", "/fleet/categories", Map.of("code", code, "name", "Concrete mixer",
                "kind", "road_vehicle", "defaultMeterType", "both", "sortOrder", 200));
        assertThat(mixer.get("code").asString()).isEqualTo("MIXER_" + sfx);
        assertThat(mixer.get("isSystem").asBoolean()).isFalse();
        assertThat(as("POST", "/fleet/categories", Map.of("code", code, "name", "Again")).status()).isEqualTo(409);
        String id = mixer.get("id").asString();
        JsonNode renamed = ok(200, "PATCH", "/fleet/categories/" + id, Map.of("name", "Transit mixer", "isActive",
                false));
        assertThat(renamed.get("name").asString()).isEqualTo("Transit mixer");
        assertThat(ok(200, "GET", "/fleet/categories", null).toString()).doesNotContain(id);
        assertThat(ok(200, "GET", "/fleet/categories?includeInactive=true", null).toString()).contains(id);
        // An inactive class cannot take new vehicles.
        Res inactive = as("POST", "/fleet/vehicles", Map.of("categoryId", id, "registrationPlate", plate()));
        assertThat(inactive.status()).isEqualTo(400);
        assertThat(msg(inactive)).isEqualTo("Vehicle class MIXER_" + sfx + " is inactive");
        ok(200, "DELETE", "/fleet/categories/" + id, null);

        tipper(Map.of());
        Res inUse = as("DELETE", "/fleet/categories/" + tipper, null);
        assertThat(inUse.status()).isEqualTo(400);
        assertThat(msg(inUse)).startsWith("Vehicle class TIPPER is used by ");
    }

    // ------------------------------------------------------------------ vehicles

    @Test
    @DisplayName("vehicles: validation, unique plate / VIN, filters, pagination, detail, patch + clear, delete")
    void vehicleCrud() {
        Res noPlate = as("POST", "/fleet/vehicles", Map.of("categoryId", tipper));
        assertThat(noPlate.status()).isEqualTo(400);
        assertThat(msg(noPlate)).isEqualTo("A registration plate is required for road vehicles and trailers");
        Res noOwner = as("POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", plate(),
                "ownership", "third_party"));
        assertThat(msg(noOwner)).isEqualTo("The owner / transporter is required for hired and third-party vehicles");
        assertThat(as("POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", plate(),
                "year", 1900)).status()).isEqualTo(400);
        assertThat(as("POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", plate(),
                "colour", "red")).status()).isEqualTo(400);

        String p = plate();
        String vin = "LZZ" + sfx + "X" + plates;
        JsonNode t = tipper(Map.of("registrationPlate", p.toLowerCase(), "vin", vin.toLowerCase(), "notes",
                "Quarry truck"));
        String id = t.get("id").asString();
        assertThat(t.get("code").asString()).startsWith("VEH-");
        assertThat(t.get("registrationPlate").asString()).isEqualTo(p);
        assertThat(t.get("vin").asString()).isEqualTo(vin);
        assertThat(t.get("meterType").asString()).isEqualTo("odometer");
        assertThat(t.get("status").asString()).isEqualTo("active");
        assertThat(t.get("category").get("code").asString()).isEqualTo("TIPPER");
        assertThat(dec(t.get("meters").get("odometer").get("value"))).isEqualByComparingTo("1000");
        assertThat(dec(t.get("payloadCapacityT"))).isEqualByComparingTo("25");

        Res dupPlate = as("POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", " " + p + " "));
        assertThat(dupPlate.status()).isEqualTo(409);
        assertThat(msg(dupPlate)).isEqualTo("Registration plate " + p + " is already registered to vehicle "
                + t.get("code").asString());
        Res dupVin = as("POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", plate(),
                "vin", vin));
        assertThat(dupVin.status()).isEqualTo(409);
        Res dupCode = as("POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", plate(),
                "code", t.get("code").asString()));
        assertThat(dupCode.status()).isEqualTo(409);

        // Plant without a plate, on engine hours; an odometer reading on it is refused.
        Res hoursOnly = as("POST", "/fleet/vehicles", Map.of("categoryId", excavator, "openingOdometer", 10));
        assertThat(msg(hoursOnly)).endsWith("does not track an odometer");
        JsonNode ex = ok(201, "POST", "/fleet/vehicles", Map.of("categoryId", excavator, "code", "EX-" + sfx,
                "name", "CAT 320", "openingHours", 3500.5, "ownership", "hired", "ownerPartnerId", partner()));
        assertThat(ex.get("meterType").asString()).isEqualTo("hours");
        assertThat(dec(ex.get("meters").get("hours").get("value"))).isEqualByComparingTo("3500.5");
        assertThat(ex.get("ownerPartner").get("name").asString()).isNotBlank();

        JsonNode bySearch = ok(200, "GET", "/fleet/vehicles?search=" + sfx + "&pageSize=1&sortBy=code", null);
        assertThat(bySearch.get("meta").get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(bySearch.get("meta").get("pageSize").asInt()).isEqualTo(1);
        assertThat(bySearch.get("data")).hasSize(1);
        JsonNode plant = ok(200, "GET", "/fleet/vehicles?kind=mobile_plant&ownership=hired&search=EX-" + sfx, null);
        assertThat(plant.get("data")).hasSize(1);
        assertThat(plant.get("data").get(0).get("category").get("code").asString()).isEqualTo("EXCAVATOR");
        JsonNode byClass = ok(200, "GET", "/fleet/vehicles?categoryId=" + tipper + "&status=active&search="
                + p.replace(" ", "%20"), null);
        assertThat(byClass.get("data")).hasSize(1);
        assertThat(byClass.get("data").get(0).get("id").asString()).isEqualTo(id);
        assertThat(as("GET", "/fleet/vehicles?status=parked", null).status()).isEqualTo(400);

        JsonNode detail = ok(200, "GET", "/fleet/vehicles/" + id, null);
        assertThat(detail.get("compliance").get("total").asLong()).isZero();
        assertThat(detail.get("pendingCorrections").asLong()).isZero();

        JsonNode patched = ok(200, "PATCH", "/fleet/vehicles/" + id, Map.of("make", "Shacman", "clear",
                List.of("notes", "vin")));
        assertThat(patched.get("make").asString()).isEqualTo("Shacman");
        assertThat(patched.get("notes").isNull()).isTrue();
        assertThat(patched.get("vin").isNull()).isTrue();
        assertThat(as("PATCH", "/fleet/vehicles/" + id, Map.of("clear", List.of("categoryId"))).status())
                .isEqualTo(400);
        Res toHours = as("PATCH", "/fleet/vehicles/" + id, Map.of("meterType", "hours"));
        assertThat(msg(toHours)).endsWith("keep a meter type that tracks it");

        // Only the opening reading: deletable. With operating history: dispose instead.
        JsonNode spare = tipper(Map.of());
        ok(200, "DELETE", "/fleet/vehicles/" + spare.get("id").asString(), null);
        assertThat(as("GET", "/fleet/vehicles/" + spare.get("id").asString(), null).status()).isEqualTo(404);
        reading(id, "odometer", 1100, null, 201);
        Res keep = as("DELETE", "/fleet/vehicles/" + id, null);
        assertThat(keep.status()).isEqualTo(400);
        assertThat(msg(keep)).endsWith("has operating history; mark it disposed instead");
    }

    String partner() {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, \"isSupplier\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, true, now())").params(id, demoOrg, "HIRE-" + sfx + plates, "Plant Hire Ltd "
                + sfx).update();
        return id;
    }

    @Test
    @DisplayName("status machine: workshop / breakdown / idle with history, reasons, disposed is terminal")
    void statusMachine() {
        String id = tipper(Map.of()).get("id").asString();
        JsonNode ws = ok(201, "POST", "/fleet/vehicles/" + id + "/status", Map.of("status", "in_workshop",
                "reason", "Brake service"));
        assertThat(ws.get("status").asString()).isEqualTo("in_workshop");
        assertThat(msg(as("POST", "/fleet/vehicles/" + id + "/status", Map.of("status", "in_workshop"))))
                .endsWith("is already in workshop");
        assertThat(msg(as("POST", "/fleet/vehicles/" + id + "/status", Map.of("status", "breakdown"))))
                .isEqualTo("A reason is required to mark a vehicle as breakdown");
        ok(201, "POST", "/fleet/vehicles/" + id + "/status", Map.of("status", "active"));
        assertThat(ok(200, "GET", "/fleet/vehicles?status=in_workshop&search=" + ws.get("code").asString(), null)
                .get("data")).isEmpty();

        JsonNode history = ok(200, "GET", "/fleet/vehicles/" + id + "/status-history", null);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("toStatus").asString()).isEqualTo("active");
        assertThat(history.get(1).get("reason").asString()).isEqualTo("Brake service");
        assertThat(history.get(2).get("source").asString()).isEqualTo("registration");
        assertThat(dec(history.get(1).get("odometer"))).isEqualByComparingTo("1000");

        JsonNode disposed = ok(201, "POST", "/fleet/vehicles/" + id + "/status", Map.of("status", "disposed",
                "reason", "Written off"));
        assertThat(disposed.get("disposedAt").isNull()).isFalse();
        assertThat(msg(as("POST", "/fleet/vehicles/" + id + "/status", Map.of("status", "active"))))
                .endsWith("is disposed; its status can no longer change");
        assertThat(msg(as("PATCH", "/fleet/vehicles/" + id, Map.of("make", "X")))).endsWith(
                "is disposed and can no longer be edited");
        assertThat(as("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "odometer",
                "value", 2000)).status()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND action = 'update'"
                + " AND \"newValues\"->>'source' IS NOT NULL").param(id).query(Long.class).single()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ meters

    @Test
    @DisplayName("meters: monotonic rule incl. back-dated readings, future time, untracked meter")
    void monotonicMeters() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String id = tipper(Map.of()).get("id").asString();
        reading(id, "odometer", 1500, now.plus(Duration.ofMinutes(1)), 201);
        reading(id, "odometer", 2000, now.plus(Duration.ofMinutes(3)), 201);
        // Between its neighbours: fine. Outside them: refused with the neighbour in the message.
        reading(id, "odometer", 1800, now.plus(Duration.ofMinutes(2)), 201);
        Res low = as("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "odometer",
                "value", 1700, "readingAt", now.plus(Duration.ofMinutes(4)).toString()));
        assertThat(low.status()).isEqualTo(400);
        assertThat(msg(low)).startsWith("Odometer reading 1700 km is lower than the previous reading of 2000 km");
        Res high = as("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "odometer",
                "value", 2500, "readingAt", now.plus(Duration.ofSeconds(90)).toString()));
        assertThat(msg(high)).startsWith("Odometer reading 2500 km is higher than the later reading of 1800 km");
        Res future = as("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "odometer",
                "value", 9000, "readingAt", now.plus(Duration.ofHours(2)).toString()));
        assertThat(msg(future)).isEqualTo("Reading time cannot be in the future");
        assertThat(msg(as("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "hours",
                "value", 10)))).endsWith("does not track engine hours");
        assertThat(as("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "odometer",
                "value", -1)).status()).isEqualTo(400);

        JsonNode list = ok(200, "GET", "/fleet/vehicles/" + id + "/meter-readings?meterType=odometer", null);
        assertThat(list.get("meta").get("total").asLong()).isEqualTo(4);
        assertThat(dec(list.get("data").get(0).get("value"))).isEqualByComparingTo("2000");
        assertThat(list.get("data").get(3).get("source").asString()).isEqualTo("opening");
        JsonNode v = ok(200, "GET", "/fleet/vehicles/" + id, null);
        assertThat(dec(v.get("meters").get("odometer").get("value"))).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("concurrency: two conflicting readings on one vehicle — the row lock lets exactly one through")
    void concurrentReadings() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 3; round++) {
                String id = tipper(Map.of()).get("id").asString();
                // After the opening reading (1000 km, now), within the clock-skew tolerance.
                Instant t = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
                // A: 5000 km at t+1; B: 3000 km at t+2. Either order makes the second one non-monotonic.
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> results = new ArrayList<>();
                for (Object[] r : new Object[][] {{5000, t.plusSeconds(1)}, {3000, t.plusSeconds(2)}}) {
                    results.add(pool.submit(() -> {
                        go.await();
                        return as("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType",
                                "odometer", "value", r[0], "readingAt", r[1].toString())).status();
                    }));
                }
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : results) {
                    statuses.add(f.get());
                }
                assertThat(statuses).containsExactlyInAnyOrder(201, 400);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("correction (adjust): pending behind the default workflow, no self-approval, applied after approval")
    void correctionApproved() {
        String id = tipper(Map.of()).get("id").asString();
        JsonNode typo = reading(id, "odometer", 15000, null, 201);
        Res blocked = as("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "odometer",
                "value", 1600));
        assertThat(msg(blocked)).contains("lower than the previous reading of 15000 km");

        Res sameValue = as("POST", "/fleet/vehicles/" + id + "/meter-corrections", Map.of("meterType", "odometer",
                "kind", "adjust", "readingId", typo.get("id").asString(), "correctedValue", 15000, "reason", "x"));
        assertThat(msg(sameValue)).isEqualTo("The corrected value equals the recorded value");
        Res tooLow = as("POST", "/fleet/vehicles/" + id + "/meter-corrections", Map.of("meterType", "odometer",
                "kind", "adjust", "readingId", typo.get("id").asString(), "correctedValue", 500, "reason", "x"));
        assertThat(msg(tooLow)).startsWith("Odometer reading 500 km is lower than the previous reading of 1000 km");

        JsonNode c = ok(201, "POST", "/fleet/vehicles/" + id + "/meter-corrections", Map.of("meterType", "odometer",
                "kind", "adjust", "readingId", typo.get("id").asString(), "correctedValue", 1500,
                "reason", "Typed one zero too many"));
        assertThat(c.get("status").asString()).isEqualTo("pending");
        assertThat(dec(c.get("previousValue"))).isEqualByComparingTo("15000");
        String requestId = c.get("approvalRequestId").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"ApprovalWorkflow\" WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = 'fleet_meter_correction'").param(demoOrg).query(Long.class).single())
                .isEqualTo(1);
        assertThat(ok(200, "GET", "/fleet/vehicles/" + id, null).get("pendingCorrections").asLong()).isEqualTo(1);
        Res second = as("POST", "/fleet/vehicles/" + id + "/meter-corrections", Map.of("meterType", "odometer",
                "kind", "replace", "correctedValue", 0, "reason", "x"));
        assertThat(second.status()).isEqualTo(409);
        Res stillPending = as("POST", "/fleet/meter-corrections/" + c.get("id").asString() + "/sync", null);
        assertThat(msg(stillPending)).startsWith("The meter correction is waiting for approval");

        // Separation of duties: the requester cannot approve; a fleet approver can.
        Res self = call("POST", "/approvals/" + requestId + "/decide", Map.of("status", "approved"), adminToken);
        assertThat(msg(self)).isEqualTo("You cannot approve a request you created");
        decide(requestId, "approved", approverToken, 201);
        JsonNode applied = ok(201, "POST", "/fleet/meter-corrections/" + c.get("id").asString() + "/sync", null);
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        assertThat(applied.get("decidedBy").asString()).isNotBlank();

        JsonNode valid = ok(200, "GET", "/fleet/vehicles/" + id + "/meter-readings", null);
        assertThat(valid.toString()).doesNotContain("\"15000\"");
        JsonNode all = ok(200, "GET", "/fleet/vehicles/" + id + "/meter-readings?includeSuperseded=true", null);
        assertThat(all.get("meta").get("total").asLong()).isEqualTo(3);
        JsonNode fixed = valid.get("data").get(0);
        assertThat(dec(fixed.get("value"))).isEqualByComparingTo("1500");
        assertThat(fixed.get("source").asString()).isEqualTo("correction");
        assertThat(dec(ok(200, "GET", "/fleet/vehicles/" + id, null).get("meters").get("odometer").get("value")))
                .isEqualByComparingTo("1500");
        reading(id, "odometer", 1600, null, 201);
        assertThat(jdbc.sql("SELECT count(*) FROM \"AuditLog\" WHERE \"entityId\" = ? AND entity ="
                + " 'FleetMeterCorrection'").param(c.get("id").asString()).query(Long.class).single())
                .isGreaterThanOrEqualTo(2);
        // The subscriber sees the (already applied) decision and leaves it alone.
        outbox.tick();
        assertThat(ok(200, "GET", "/fleet/meter-corrections/" + c.get("id").asString(), null).get("status")
                .asString()).isEqualTo("applied");
    }

    @Test
    @DisplayName("correction (replace): a meter replacement re-bases the meter; rejected corrections change nothing")
    void correctionReplaceAndReject() {
        String id = tipper(Map.of("openingOdometer", 250000)).get("id").asString();
        Instant before = Instant.now().minusSeconds(30);
        JsonNode c = ok(201, "POST", "/fleet/vehicles/" + id + "/meter-corrections", Map.of("meterType", "odometer",
                "kind", "replace", "correctedValue", 0, "reason", "Odometer replaced at 250000 km"));
        decide(c.get("approvalRequestId").asString(), "approved", approverToken, 201);
        // Applied by the approval subscriber from the outbox.
        JsonNode applied = awaitDecided(c.get("id").asString());
        assertThat(applied.get("status").asString()).isEqualTo("applied");
        JsonNode v = ok(200, "GET", "/fleet/vehicles/" + id, null);
        assertThat(dec(v.get("odometerOffset"))).isEqualByComparingTo("250000");
        assertThat(dec(v.get("meters").get("odometer").get("value"))).isZero();
        assertThat(dec(v.get("meters").get("odometer").get("lifetime"))).isEqualByComparingTo("250000");
        JsonNode r = reading(id, "odometer", 120, null, 201);
        assertThat(dec(r.get("lifetimeValue"))).isEqualByComparingTo("250120");
        Res old = as("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "odometer",
                "value", 250010, "readingAt", before.toString()));
        assertThat(msg(old)).startsWith("Odometer reading predates the meter replacement on ");

        JsonNode reject = ok(201, "POST", "/fleet/vehicles/" + id + "/meter-corrections", Map.of("meterType",
                "odometer", "kind", "adjust", "readingId", r.get("id").asString(), "correctedValue", 100,
                "reason", "Maybe 100"));
        decide(reject.get("approvalRequestId").asString(), "rejected", approverToken, 201);
        assertThat(awaitDecided(reject.get("id").asString()).get("status").asString()).isEqualTo("rejected");
        assertThat(dec(ok(200, "GET", "/fleet/vehicles/" + id, null).get("meters").get("odometer").get("value")))
                .isEqualByComparingTo("120");
        JsonNode list = ok(200, "GET", "/fleet/vehicles/" + id + "/meter-corrections", null);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("status").asString()).isEqualTo("rejected");
    }

    @Test
    @DisplayName("correction without an active workflow: the engine auto-approves, so it applies at once")
    void correctionWithoutWorkflow() {
        String id = tipper(Map.of()).get("id").asString();
        JsonNode r = reading(id, "odometer", 1200, null, 201);
        // Make sure the default workflow exists, then switch it off for this test.
        ok(201, "POST", "/fleet/vehicles/" + id + "/meter-corrections", Map.of("meterType", "odometer",
                "kind", "adjust", "readingId", r.get("id").asString(), "correctedValue", 1100, "reason", "first"));
        String other = tipper(Map.of()).get("id").asString();
        JsonNode r2 = reading(other, "odometer", 1300, null, 201);
        jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = false WHERE \"organizationId\" = ? AND"
                + " \"entityType\" = 'fleet_meter_correction'").param(demoOrg).update();
        try {
            JsonNode c = ok(201, "POST", "/fleet/vehicles/" + other + "/meter-corrections", Map.of("meterType",
                    "odometer", "kind", "adjust", "readingId", r2.get("id").asString(), "correctedValue", 1250,
                    "reason", "Fixed at once"));
            assertThat(c.get("status").asString()).isEqualTo("applied");
            assertThat(c.get("approvalRequestId").isNull()).isTrue();
            assertThat(dec(ok(200, "GET", "/fleet/vehicles/" + other, null).get("meters").get("odometer")
                    .get("value"))).isEqualByComparingTo("1250");
        } finally {
            jdbc.sql("UPDATE \"ApprovalWorkflow\" SET \"isActive\" = true WHERE \"organizationId\" = ? AND"
                    + " \"entityType\" = 'fleet_meter_correction'").param(demoOrg).update();
        }
    }

    // ------------------------------------------------------------------ compliance

    @Test
    @DisplayName("compliance: expiry states, attention / expired / expiring queries, renewal, patch + clear")
    void compliance() {
        LocalDate today = LocalDate.now(BusinessZone.get());
        JsonNode t = tipper(Map.of());
        String id = t.get("id").asString();
        String base = "/fleet/vehicles/" + id + "/compliance-documents";
        JsonNode insurance = ok(201, "POST", base, Map.of("docType", "insurance", "documentNumber", "POL-1",
                "issuer", "Jubilee", "issuedOn", today.minusDays(360).toString(), "expiresOn",
                today.plusDays(5).toString()));
        assertThat(insurance.get("state").asString()).isEqualTo("expiring");
        assertThat(insurance.get("reminderLeadDays").asInt()).isEqualTo(30);
        assertThat(insurance.get("daysToExpiry").asLong()).isEqualTo(5);
        JsonNode reg = ok(201, "POST", base, Map.of("docType", "registration", "expiresOn",
                today.minusDays(1).toString()));
        assertThat(reg.get("state").asString()).isEqualTo("expired");
        assertThat(ok(201, "POST", base, Map.of("docType", "road_licence", "expiresOn",
                today.plusDays(100).toString())).get("state").asString()).isEqualTo("valid");
        assertThat(ok(201, "POST", base, Map.of("docType", "inspection")).get("state").asString())
                .isEqualTo("no_expiry");
        assertThat(ok(201, "POST", base, Map.of("docType", "operator_permit", "expiresOn",
                today.plusDays(20).toString(), "reminderLeadDays", 10)).get("state").asString()).isEqualTo("valid");
        Res badDates = as("POST", base, Map.of("docType", "other", "issuedOn", today.toString(), "expiresOn",
                today.minusDays(1).toString()));
        assertThat(msg(badDates)).isEqualTo("The expiry date must be on or after the issue date");
        assertThat(as("POST", base, Map.of("docType", "passport")).status()).isEqualTo(400);

        String q = "/fleet/compliance-documents?vehicleId=" + id;
        assertThat(ok(200, "GET", q, null).get("meta").get("total").asLong()).isEqualTo(2);
        JsonNode expired = ok(200, "GET", q + "&due=expired", null);
        assertThat(expired.get("data")).hasSize(1);
        assertThat(expired.get("data").get(0).get("docType").asString()).isEqualTo("registration");
        assertThat(expired.get("data").get(0).get("vehicle").get("code").asString())
                .isEqualTo(t.get("code").asString());
        JsonNode expiring = ok(200, "GET", q + "&due=expiring", null);
        assertThat(expiring.get("data")).hasSize(1);
        assertThat(expiring.get("data").get(0).get("id").asString()).isEqualTo(insurance.get("id").asString());
        assertThat(ok(200, "GET", q + "&due=all", null).get("meta").get("total").asLong()).isEqualTo(5);
        // Thirty days on, the permit (lead 10) is expiring too and the insurance has expired.
        JsonNode later = ok(200, "GET", q + "&asOf=" + today.plusDays(15), null);
        assertThat(later.get("meta").get("total").asLong()).isEqualTo(3);
        assertThat(complianceService.dueForOrganization(demoOrg, today).stream()
                .map(d -> d.get("id")).toList()).contains(reg.get("id").asString(), insurance.get("id").asString());

        JsonNode detail = ok(200, "GET", "/fleet/vehicles/" + id, null).get("compliance");
        assertThat(detail.get("expired").asLong()).isEqualTo(1);
        assertThat(detail.get("expiring").asLong()).isEqualTo(1);
        assertThat(detail.get("total").asLong()).isEqualTo(5);

        String insId = insurance.get("id").asString();
        Res shortRenewal = as("POST", "/fleet/compliance-documents/" + insId + "/renew", Map.of("expiresOn",
                today.plusDays(2).toString()));
        assertThat(msg(shortRenewal)).startsWith("The renewal must expire after the current document");
        JsonNode renewed = ok(201, "POST", "/fleet/compliance-documents/" + insId + "/renew", Map.of(
                "documentNumber", "POL-2", "issuedOn", today.toString(), "expiresOn", today.plusDays(365).toString()));
        assertThat(renewed.get("docType").asString()).isEqualTo("insurance");
        assertThat(renewed.get("issuer").asString()).isEqualTo("Jubilee");
        assertThat(renewed.get("state").asString()).isEqualTo("valid");
        assertThat(ok(200, "GET", "/fleet/compliance-documents/" + insId, null).get("state").asString())
                .isEqualTo("renewed");
        assertThat(msg(as("POST", "/fleet/compliance-documents/" + insId + "/renew", Map.of("expiresOn",
                today.plusDays(400).toString())))).isEqualTo("Only an active document can be renewed");
        assertThat(ok(200, "GET", q, null).get("meta").get("total").asLong()).isEqualTo(1);

        String regId = reg.get("id").asString();
        JsonNode patched = ok(200, "PATCH", "/fleet/compliance-documents/" + regId, Map.of("documentNumber", "REG-9",
                "expiresOn", today.plusDays(200).toString(), "clear", List.of("issuer")));
        assertThat(patched.get("state").asString()).isEqualTo("valid");
        assertThat(patched.get("documentNumber").asString()).isEqualTo("REG-9");
        ok(200, "DELETE", "/fleet/compliance-documents/" + regId, null);
        assertThat(as("GET", "/fleet/compliance-documents/" + regId, null).status()).isEqualTo(404);
        JsonNode docs = ok(200, "GET", base, null);
        assertThat(docs).hasSize(5);
        assertThat(docs.get(docs.size() - 1).get("status").asString()).isEqualTo("renewed");
    }

    // ------------------------------------------------------------------ attachments, asset, lookups

    @Test
    @DisplayName("attachments: photo + compliance scan stored in the file store with signed URLs; delete")
    void attachments() {
        String id = tipper(Map.of()).get("id").asString();
        String doc = ok(201, "POST", "/fleet/vehicles/" + id + "/compliance-documents", Map.of("docType",
                "insurance")).get("id").asString();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 0};
        Res photo = upload(id, "front.png", "image/png", png, Map.of("kind", "photo", "title", "Front"));
        assertThat(photo.status()).as(photo.raw().body()).isEqualTo(201);
        assertThat(photo.body().get("url").asString()).startsWith("/api/v1/files/");
        assertThat(photo.body().get("filename").asString()).isEqualTo("front.png");
        Res notImage = upload(id, "notes.txt", "text/plain", "hi".getBytes(StandardCharsets.UTF_8),
                Map.of("kind", "photo"));
        assertThat(notImage.status()).isEqualTo(400);
        Res scan = upload(id, "policy.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8),
                Map.of("kind", "document", "complianceDocumentId", doc));
        assertThat(scan.status()).as(scan.raw().body()).isEqualTo(201);

        JsonNode list = ok(200, "GET", "/fleet/vehicles/" + id + "/attachments", null);
        assertThat(list).hasSize(2);
        JsonNode docs = ok(200, "GET", "/fleet/vehicles/" + id + "/compliance-documents", null);
        assertThat(docs.get(0).get("attachments")).hasSize(1);
        assertThat(jdbc.sql("SELECT \"ownerType\" FROM \"File\" WHERE id = ?").param(photo.body().get("fileId")
                .asString()).query(String.class).single()).isEqualTo("fleet_vehicle");

        ok(200, "DELETE", "/fleet/vehicles/" + id + "/attachments/" + photo.body().get("id").asString(), null);
        assertThat(ok(200, "GET", "/fleet/vehicles/" + id + "/attachments", null)).hasSize(1);
        assertThat(as("DELETE", "/fleet/vehicles/" + id + "/attachments/" + photo.body().get("id").asString(), null)
                .status()).isEqualTo(404);
    }

    @Test
    @DisplayName("linked fixed asset: read-only depreciation view, one vehicle per asset, asset picker")
    void linkedAsset() {
        String asset = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Asset\" (id, \"organizationId\", \"assetCode\", name, \"purchaseCost\","
                + " \"currentValue\", \"updatedAt\") VALUES (?, ?, ?, 'Tipper truck asset', 120000000, 100000000, now())")
                .params(asset, demoOrg, "FA-" + sfx).update();
        jdbc.sql("INSERT INTO \"AssetDepreciation\" (id, \"organizationId\", \"assetId\", period, \"assetCost\","
                + " \"usefulLife\", \"depreciationAmount\", \"accumulatedDepr\", \"bookValue\", \"isPosted\","
                + " \"updatedAt\") VALUES (?, ?, ?, '2026-09', 120000000, 60, 2000000, 20000000, 100000000, true, now())")
                .params(UUID.randomUUID().toString(), demoOrg, asset).update();
        JsonNode t = tipper(Map.of("assetId", asset));
        String id = t.get("id").asString();
        assertThat(t.get("asset").get("assetCode").asString()).isEqualTo("FA-" + sfx);
        JsonNode view = ok(200, "GET", "/fleet/vehicles/" + id + "/asset", null);
        assertThat(view.get("asset").get("name").asString()).isEqualTo("Tipper truck asset");
        assertThat(dec(view.get("bookValue"))).isEqualByComparingTo("100000000");
        assertThat(dec(view.get("accumulatedDepreciation"))).isEqualByComparingTo("20000000");
        assertThat(view.get("depreciation")).hasSize(1);
        Res twice = as("POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", plate(),
                "assetId", asset));
        assertThat(twice.status()).isEqualTo(409);
        assertThat(as("POST", "/fleet/vehicles", Map.of("categoryId", tipper, "registrationPlate", plate(),
                "assetId", UUID.randomUUID().toString())).status()).isEqualTo(400);
        JsonNode picker = ok(200, "GET", "/fleet/lookups/assets?search=FA-" + sfx, null);
        assertThat(picker.get(0).get("linkedVehicleCode").asString()).isEqualTo(t.get("code").asString());
        JsonNode unlinked = ok(200, "PATCH", "/fleet/vehicles/" + id, Map.of("clear", List.of("assetId")));
        assertThat(unlinked.get("asset").isNull()).isTrue();
        assertThat(ok(200, "GET", "/fleet/vehicles/" + id + "/asset", null).get("asset").isNull()).isTrue();
    }

    @Test
    @DisplayName("lookups and overview tiles")
    void lookupsAndOverview() {
        tipper(Map.of());
        JsonNode lookups = ok(200, "GET", "/fleet/lookups", null);
        assertThat(lookups.get("categories").size()).isGreaterThanOrEqualTo(11);
        assertThat(lookups.get("branches").isArray()).isTrue();
        assertThat(lookups.get("locations").isArray()).isTrue();
        assertThat(lookups.get("costCenters").isArray()).isTrue();
        assertThat(ok(200, "GET", "/fleet/lookups/partners?search=a", null).isArray()).isTrue();
        JsonNode overview = ok(200, "GET", "/fleet/overview", null);
        assertThat(overview.get("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(overview.get("byStatus").isArray()).isTrue();
        assertThat(overview.get("byCategory").toString()).contains("TIPPER");
        assertThat(overview.get("compliance").has("expired")).isTrue();
    }

    // ------------------------------------------------------------------ security

    @Test
    @DisplayName("tenant isolation: another organization sees none of DEMO's vehicles, readings or documents")
    void tenantIsolation() {
        JsonNode t = tipper(Map.of());
        String id = t.get("id").asString();
        String doc = ok(201, "POST", "/fleet/vehicles/" + id + "/compliance-documents", Map.of("docType",
                "insurance", "expiresOn", LocalDate.now(BusinessZone.get()).toString())).get("id").asString();
        assertThat(call("GET", "/fleet/vehicles/" + id, null, otherToken).status()).isEqualTo(404);
        assertThat(call("PATCH", "/fleet/vehicles/" + id, Map.of("make", "X"), otherToken).status()).isEqualTo(404);
        assertThat(call("POST", "/fleet/vehicles/" + id + "/status", Map.of("status", "idle"), otherToken).status())
                .isEqualTo(404);
        assertThat(call("GET", "/fleet/vehicles/" + id + "/meter-readings", null, otherToken).status())
                .isEqualTo(404);
        assertThat(call("POST", "/fleet/vehicles/" + id + "/meter-readings", Map.of("meterType", "odometer",
                "value", 99999), otherToken).status()).isEqualTo(404);
        assertThat(call("GET", "/fleet/compliance-documents/" + doc, null, otherToken).status()).isEqualTo(404);
        JsonNode otherList = call("GET", "/fleet/vehicles?search=" + sfx, null, otherToken).body();
        assertThat(otherList.get("meta").get("total").asLong()).isZero();
        JsonNode otherDue = call("GET", "/fleet/compliance-documents?due=all", null, otherToken).body();
        assertThat(otherDue.toString()).doesNotContain(doc);
        // The other organization gets its own default classes; DEMO's class ids are not valid there.
        JsonNode otherClasses = call("GET", "/fleet/categories", null, otherToken).body();
        assertThat(otherClasses.toString()).contains("TIPPER").doesNotContain(tipper);
        Map<String, Object> body = new HashMap<>(Map.of("categoryId", tipper, "registrationPlate", plate()));
        assertThat(call("POST", "/fleet/vehicles", body, otherToken).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("authorization matrix: 401 without a token and 403 without the permission on every fleet route")
    void authorizationMatrix() {
        // The 1.1 controllers only (later sub-modules count their own routes).
        assertThat(assertAuthorizationMatrix(FleetVehicleController.class, FleetCategoryController.class,
                FleetMeterController.class, FleetComplianceController.class)).isEqualTo(32);
        // A read-only fleet user can look but not change.
        Res denied = call("POST", "/fleet/vehicles/" + tipper(Map.of()).get("id").asString() + "/meter-readings",
                Map.of("meterType", "odometer", "value", 5000), approverToken);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(msg(denied)).isEqualTo("Missing required permission(s): fleet_vehicle:meter");
        assertThat(call("GET", "/fleet/vehicles", null, approverToken).status()).isEqualTo(200);
    }
}
