package com.inventoryerp.kernel.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.kernel.security.Permissions;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Port of {@code role-policy.spec.ts}: cash-management role policy (release gate). */
@DisplayName("cash-management role policy")
class RolePolicyTest {

    static final List<String> MONEY_CONTROLS = List.of(
            "expense:create", "expense:post", "expense:cancel", "expense:approve",
            "treasury:transfer", "cash_session:force_close", "cash_session:correct",
            "cash_session:approve_variance", "cash_session:reconcile", "payment:void");

    @Test
    @DisplayName("every manager permission exists in the catalog")
    void managerInCatalog() {
        var all = new HashSet<>(Permissions.ALL);
        assertThat(Permissions.MANAGER.stream().filter(p -> !all.contains(p)).toList()).isEmpty();
    }

    @Test
    @DisplayName("waiters hold no money controls")
    void waiters() {
        assertThat(SeedRunner.WAITER_PERMS.stream()
                .filter(p -> MONEY_CONTROLS.contains(p) || p.startsWith("cash_session:")).toList()).isEmpty();
    }

    @Test
    @DisplayName("cashiers can sell and run their own shift, but approve nothing and move no treasury money")
    void cashiers() {
        assertThat(SeedRunner.CASHIER_PERMS).contains("pos:checkout", "cash_session:open", "cash_session:close");
        assertThat(SeedRunner.CASHIER_PERMS.stream().filter(MONEY_CONTROLS::contains).toList()).isEmpty();
    }

    @Test
    @DisplayName("managers hold every money control")
    void managers() {
        assertThat(Permissions.MANAGER).containsAll(MONEY_CONTROLS);
    }
}
