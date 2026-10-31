package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.hr.application.HrContractService;
import com.inventoryerp.hr.web.HrContractController;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Contract records (HR-2): one active contract, supersession, termination, renewal, expiry, tenancy, permissions. */
class HrContractIT extends ApiTestSupport {

    @Autowired
    HrContractService contracts;

    final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    String employee() {
        return ok(201, "POST", "/hr/employees", Map.of("firstName", "Ivy", "lastName", "Lule")).get("id").asString();
    }

    JsonNode draft(String employeeId, String type, LocalDate end) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("employeeId", employeeId, "contractType", type,
                "startDate", today.toString()));
        if (end != null) {
            body.put("endDate", end.toString());
        }
        return ok(201, "POST", "/hr/contracts", body);
    }

    @Test
    @DisplayName("terms are validated: a fixed-term contract needs an end, the end cannot precede the start")
    void terms() {
        String emp = employee();
        assertThat(as("POST", "/hr/contracts", Map.of("employeeId", emp, "contractType", "fixed_term", "startDate",
                today.toString())).status()).isEqualTo(400);
        assertThat(as("POST", "/hr/contracts", Map.of("employeeId", emp, "contractType", "nope", "startDate",
                today.toString())).status()).isEqualTo(400);
        assertThat(as("POST", "/hr/contracts", Map.of("employeeId", emp, "startDate", today.toString(), "endDate",
                today.minusDays(1).toString())).status()).isEqualTo(400);
        assertThat(draft(emp, "permanent", null).get("code").asString()).startsWith("CTR-");
    }

    @Test
    @DisplayName("activating supersedes the current contract and moves HrEmployee.contractEndDate with it")
    void activation() {
        String emp = employee();
        JsonNode first = draft(emp, "fixed_term", today.plusMonths(6));
        String firstId = first.get("id").asString();
        ok(201, "POST", "/hr/contracts/" + firstId + "/activate", null);
        assertThat(ok(200, "GET", "/hr/employees/" + emp, null).get("contractEndDate").asString())
                .startsWith(today.plusMonths(6).toString());
        assertThat(as("PATCH", "/hr/contracts/" + firstId, Map.of("notes", "x")).status()).isEqualTo(400);
        assertThat(as("DELETE", "/hr/contracts/" + firstId, null).status()).isEqualTo(400);
        String second = draft(emp, "permanent", null).get("id").asString();
        ok(201, "POST", "/hr/contracts/" + second + "/activate", null);
        assertThat(ok(200, "GET", "/hr/contracts/" + firstId, null).get("status").asString()).isEqualTo("superseded");
        assertThat(ok(200, "GET", "/hr/contracts/" + second, null).get("status").asString()).isEqualTo("active");
        JsonNode employee = ok(200, "GET", "/hr/employees/" + emp, null);
        assertThat(employee.get("contractEndDate") == null || employee.get("contractEndDate").isNull()).isTrue();
        Long active = jdbc.sql("SELECT count(*) FROM \"HrEmployeeContract\" WHERE \"employeeId\" = ? AND status ="
                + " 'active'").param(emp).query(Long.class).single();
        assertThat(active).isEqualTo(1L);
    }

    @Test
    @DisplayName("terminating needs a reason; renewing drafts the next contract starting the day after")
    void terminateAndRenew() {
        String emp = employee();
        LocalDate end = today.plusMonths(3);
        String id = draft(emp, "fixed_term", end).get("id").asString();
        ok(201, "POST", "/hr/contracts/" + id + "/activate", null);
        JsonNode renewal = ok(201, "POST", "/hr/contracts/" + id + "/renew", Map.of("endDate",
                end.plusMonths(6).toString()));
        assertThat(renewal.get("status").asString()).isEqualTo("draft");
        assertThat(renewal.get("startDate").asString()).isEqualTo(end.plusDays(1).toString());
        assertThat(renewal.get("renewalOfId").asString()).isEqualTo(id);
        assertThat(as("POST", "/hr/contracts/" + id + "/terminate", Map.of()).status()).isEqualTo(400);
        JsonNode ended = ok(201, "POST", "/hr/contracts/" + id + "/terminate", Map.of("reason", "Site closed"));
        assertThat(ended.get("status").asString()).isEqualTo("terminated");
        assertThat(as("POST", "/hr/contracts/" + id + "/terminate", Map.of("reason", "again")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("the expiring list shows contracts ending soon, and the daily job expires those past their end")
    void expiry() {
        String emp = employee();
        String id = draft(emp, "fixed_term", today.plusDays(5)).get("id").asString();
        ok(201, "POST", "/hr/contracts/" + id + "/activate", null);
        assertThat(ok(200, "GET", "/hr/contracts/expiring?days=10", null).get("rows").toString()).contains(id);
        assertThat(ok(200, "GET", "/hr/contracts/expiring?days=1", null).get("rows").toString()).doesNotContain(id);
        jdbc.sql("UPDATE \"HrEmployeeContract\" SET \"startDate\" = ?, \"endDate\" = ? WHERE id = ?")
                .params(java.sql.Date.valueOf(today.minusDays(9)), java.sql.Date.valueOf(today.minusDays(1)), id).update();
        int expired = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), contracts::expireDue);
        assertThat(expired).isPositive();
        assertThat(ok(200, "GET", "/hr/contracts/" + id, null).get("status").asString()).isEqualTo("expired");
    }

    @Test
    @DisplayName("another organization cannot read a contract")
    void tenantIsolation() {
        String id = draft(employee(), "permanent", null).get("id").asString();
        assertThat(call("GET", "/hr/contracts/" + id, null, otherToken).status()).isIn(403, 404);
    }

    @Test
    @DisplayName("authorization matrix on all 9 routes")
    void authorization() {
        assertThat(assertAuthorizationMatrix(HrContractController.class)).isEqualTo(9);
    }
}
