package com.inventoryerp.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventoryerp.kernel.featureflags.FeatureFlagsService;
import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.modules.ModuleAccess;
import com.inventoryerp.kernel.modules.ModuleRegistry;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.plm.application.PlmModule;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** PLM's application switch (ADR-S086): add-on semantics of the module guard and the licence mapping. */
class PlmModuleAccessTest {

    private static final String ORG = "org-1";

    private static ModuleRegistry registry() {
        ModuleRegistry r = ModuleRegistry.empty();
        r.register(new ModuleRegistry.ErpModule("manufacturing", "3.0.0", List.of()));
        r.register(ModuleRegistry.ErpModule.addOn(PlmModule.NAME, "2.0.0", List.of("manufacturing")));
        return r;
    }

    /**
     * @param rows          the organization has module rows at all
     * @param manufacturing manufacturing row active
     * @param envFlag       app.modules.manufacturing
     * @param plmOff        a plm row with isActive = false
     */
    private static ModuleAccess access(boolean rows, boolean manufacturing, boolean envFlag, boolean plmOff) {
        FeatureFlagsService flags = mock(FeatureFlagsService.class);
        when(flags.hasAnyModuleRow()).thenReturn(rows);
        when(flags.isModuleEnabled("manufacturing")).thenReturn(manufacturing);
        when(flags.isModuleEnabled("plm")).thenReturn(false);
        when(flags.isModuleSwitchedOff("plm")).thenReturn(plmOff);
        MockEnvironment env = new MockEnvironment().withProperty("app.modules.manufacturing", String.valueOf(envFlag));
        return new ModuleAccess(flags, env, registry());
    }

    private static <T> T inOrg(Supplier<T> body) {
        return TenantContext.run(TenantStore.user(ORG, "user-1"), body::get);
    }

    @Test
    @DisplayName("on wherever manufacturing is on (no rows, an active row, or the deployment flag)")
    void onByDefault() {
        assertThat(inOrg(() -> access(false, false, false, false).allowed("plm"))).isTrue();
        assertThat(inOrg(() -> access(true, true, false, false).allowed("plm"))).isTrue();
        assertThat(inOrg(() -> access(true, false, true, false).allowed("plm"))).isTrue();
    }

    @Test
    @DisplayName("off when the organization switched it off, or when manufacturing is off")
    void off() {
        assertThat(inOrg(() -> access(true, true, true, true).allowed("plm"))).isFalse();
        assertThat(inOrg(() -> access(true, false, false, false).allowed("plm"))).isFalse();
    }

    @Test
    @DisplayName("enabling plm needs manufacturing; modules without dependencies have none to miss")
    void missingDependencies() {
        assertThat(inOrg(() -> access(true, false, false, false).missingDependencies("plm")))
                .containsExactly("manufacturing");
        assertThat(inOrg(() -> access(true, true, false, false).missingDependencies("plm"))).isEmpty();
        assertThat(inOrg(() -> access(true, false, false, false).missingDependencies("manufacturing"))).isEmpty();
        assertThat(inOrg(() -> access(true, false, false, false).missingDependencies("quarry"))).isEmpty();
    }

    @Test
    @DisplayName("non-add-on modules keep the Nest guard rules exactly")
    void regularModulesUnchanged() {
        assertThat(inOrg(() -> access(false, false, false, false).allowed("manufacturing"))).isTrue();
        assertThat(inOrg(() -> access(true, false, false, false).allowed("manufacturing"))).isFalse();
        assertThat(inOrg(() -> access(true, false, true, false).allowed("manufacturing"))).isTrue();
        assertThat(inOrg(() -> access(true, true, false, false).allowed("manufacturing"))).isTrue();
    }

    @Test
    @DisplayName("licence: plm is sold as part of manufacturing")
    void licence() {
        assertThat(LicenseFeatures.featureForModule("plm")).isEqualTo(LicenseFeatures.MANUFACTURING);
        assertThat(LicenseFeatures.featureForModule("manufacturing")).isEqualTo(LicenseFeatures.MANUFACTURING);
        assertThat(LicenseFeatures.ALL).doesNotContain("plm");
    }
}
