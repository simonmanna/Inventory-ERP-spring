package com.inventoryerp.kernel.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.kernel.reports.ReportAccessService.RoleView;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Port of {@code test/unit/report-access.service.spec.ts}. */
@SuppressWarnings("unchecked")
class ReportAccessServiceTest {

    static final RoleView CASHIER = new RoleView("role-cashier", "Cashier", false, List.of("pos:reports"));
    static final RoleView ACCOUNTANT = new RoleView("role-accountant", "Accountant", false, List.of("report:accounting"));

    Object stored;
    String writtenKey;
    List<RoleView> userRoles = List.of(CASHIER);
    ReportAccessService service;

    @BeforeEach
    void setUp() {
        service = new ReportAccessService(new ReportAccessService.Store() {
            @Override
            public Object readConfig() {
                return stored;
            }

            @Override
            public void writeConfig(Map<String, Object> config) {
                writtenKey = ReportAccessService.REPORT_VISIBILITY_SETTING_KEY;
                stored = config;
            }

            @Override
            public List<RoleView> roles() {
                return List.of(CASHIER, ACCOUNTANT);
            }

            @Override
            public RoleView role(String id) {
                return List.of(CASHIER, ACCOUNTANT).stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
            }

            @Override
            public List<RoleView> activeUserRoles(String userId) {
                return userRoles;
            }
        });
    }

    private Map<String, Object> cashierRow() {
        return ((List<Map<String, Object>>) service.listRoleVisibility().get("roles")).stream()
                .filter(r -> r.get("id").equals(CASHIER.id())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("defaults an unconfigured role to every report its permissions allow")
    void defaults() {
        var cashier = cashierRow();
        assertThat(cashier.get("configured")).isEqualTo(false);
        assertThat((List<String>) cashier.get("reports")).contains("pos.sales", "pos.z").doesNotContain("center.sales");
    }

    @Test
    @DisplayName("stores an explicit allow-list under the settings key")
    void stores() {
        service.setRoleVisibility(CASHIER.id(), List.of("pos.sales", "pos.z"));
        assertThat(writtenKey).isEqualTo("reports.visibility");
        var cashier = cashierRow();
        assertThat(cashier.get("configured")).isEqualTo(true);
        assertThat((List<String>) cashier.get("reports")).containsExactlyInAnyOrder("pos.sales", "pos.z");
    }

    @Test
    @DisplayName("never grants a report the role has no permission for")
    void neverGrants() {
        var saved = service.setRoleVisibility(CASHIER.id(), List.of("pos.sales", "center.sales"));
        assertThat(saved.get("reports")).isEqualTo(List.of("pos.sales"));
    }

    @Test
    @DisplayName("rejects unknown report ids")
    void unknownReport() {
        assertThatThrownBy(() -> service.setRoleVisibility(CASHIER.id(), List.of("nope.report")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("rejects an unknown role")
    void unknownRole() {
        assertThatThrownBy(() -> service.setRoleVisibility("role-ghost", List.of()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    @DisplayName("clears the override with null")
    void clears() {
        service.setRoleVisibility(CASHIER.id(), List.of("pos.sales"));
        var cleared = service.setRoleVisibility(CASHIER.id(), null);
        assertThat(cleared.get("configured")).isEqualTo(false);
        assertThat((List<String>) cleared.get("reports")).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("unions a user's roles")
    void unions() {
        service.setRoleVisibility(CASHIER.id(), List.of("pos.sales"));
        userRoles = List.of(CASHIER, ACCOUNTANT);
        assertThat(service.reportsForUser("u1")).contains("pos.sales", "center.sales").doesNotContain("pos.z");
    }

    @Test
    @DisplayName("gives an inactive or unknown user nothing")
    void nothing() {
        userRoles = null;
        assertThat(service.reportsForUser("u1")).isEmpty();
        assertThat(service.reportsForUser(null)).isEmpty();
    }
}
