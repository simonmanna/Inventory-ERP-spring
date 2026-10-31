package com.inventoryerp.quarry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventoryerp.kernel.featureflags.FeatureFlagsService;
import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.security.AccessGuardInterceptor;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.quarry.web.QuarryReportController;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * Quarry is a gated module: licence feature {@code quarry}, manifest name {@code quarry}, env flag
 * {@code app.modules.quarry}. With the env flag off and the organization's module switched off, every quarry route is
 * refused 403 by the module guard (the controllers are not even registered when the flag is off, i.e. 404).
 */
class QuarryModuleGateTest {

    private static final String ORG = "org-1";

    private boolean allowed(boolean envFlag, boolean orgModuleOn) throws Exception {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.permissionsOf(any(), any())).thenReturn(List.of("quarry:report"));
        FeatureFlagsService flags = mock(FeatureFlagsService.class);
        when(flags.hasAnyModuleRow()).thenReturn(true);
        when(flags.isModuleEnabled("quarry")).thenReturn(orgModuleOn);
        MockEnvironment env = new MockEnvironment().withProperty("app.modules.quarry", String.valueOf(envFlag));
        AccessGuardInterceptor guard = new AccessGuardInterceptor(permissions, flags, env);
        HandlerMethod handler = new HandlerMethod(new QuarryReportController(null), QuarryReportController.class
                .getMethod("royalties", LocalDate.class, LocalDate.class, String.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/quarry/reports/royalties");
        request.setAttribute(AuthUser.ATTRIBUTE, new AuthUser("user-1", ORG, "u@demo.test"));
        return TenantContext.run(TenantStore.user(ORG, "user-1"), () -> {
            try {
                return guard.preHandle(request, new MockHttpServletResponse(), handler);
            } catch (Exception e) {
                throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("module switched off (env flag off, org module off) → 403 'Module quarry is not enabled'")
    void disabledModuleIsRefused() {
        assertThatThrownBy(() -> allowed(false, false)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.FORBIDDEN))
                .hasMessage("Module 'quarry' is not enabled for this organization");
    }

    @Test
    @DisplayName("enabled for the organization, or by the env flag → allowed")
    void enabledModuleIsAllowed() throws Exception {
        assertThat(allowed(false, true)).isTrue();
        assertThat(allowed(true, false)).isTrue();
    }

    @Test
    @DisplayName("licence: quarry is a sold feature whose module name is quarry")
    void licenceFeature() {
        assertThat(LicenseFeatures.ALL).contains(LicenseFeatures.QUARRY);
        assertThat(LicenseFeatures.featureForModule("quarry")).isEqualTo("quarry");
        assertThat(QuarryModule.NAME).isEqualTo("quarry");
    }
}
