package com.inventoryerp.kernel.licensing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Which paths stay writable when the licence is spent.
 *
 * <p>This list is a promise to customers rather than a detail: a shop holding a day of offline
 * sales, or standing at a till with an open order, must be able to finish and to sync. Each case
 * below is one of those promises, so a future tidy-up of the list cannot quietly break one.
 */
class LicenseEnforcementPathTest {

    private static boolean allowed(String path) {
        return LicenseEnforcementInterceptor.isAllowedWhileUnlicensed(path);
    }

    @Test
    void signingInIsAlwaysPossible() {
        // Somebody has to be able to get in and read what is wrong.
        assertThat(allowed("/auth/login")).isTrue();
        assertThat(allowed("/auth/refresh")).isTrue();
        assertThat(allowed("/auth/mfa/verify")).isTrue();
    }

    @Test
    void theWayOutOfReadOnlyIsNeverBlocked() {
        assertThat(allowed("/license/status")).isTrue();
        assertThat(allowed("/license/activate")).isTrue();
        assertThat(allowed("/license/offline")).isTrue();
        assertThat(allowed("/license/refresh")).isTrue();
    }

    @Test
    void operationsEndpointsAreNotBusiness() {
        assertThat(allowed("/health/ready")).isTrue();
        assertThat(allowed("/actuator/prometheus")).isTrue();
        assertThat(allowed("/metrics")).isTrue();
    }

    @Test
    void aTerminalCanAlwaysDrainItsOfflineSales() {
        // The data is already on the till. Refusing it over a billing state destroys a day's takings.
        assertThat(allowed("/sync/push")).isTrue();
        assertThat(allowed("/sync/pull")).isTrue();
        assertThat(allowed("/sync/devices/register")).isTrue();
    }

    @Test
    void aShopCanFinishWhatItHadAlreadyStarted() {
        assertThat(allowed("/pos/shifts/018f-abc/close")).isTrue();
        assertThat(allowed("/pos/sessions/018f-abc/reconcile")).isTrue();
        assertThat(allowed("/pos/orders/018f-abc/pay")).isTrue();
        assertThat(allowed("/pos/orders/018f-abc/void")).isTrue();
    }

    @Test
    void butItCannotCarryOnTradingUnlicensed() {
        // This is the line: finish the open order, do not start another.
        assertThat(allowed("/pos/orders")).isFalse();
        assertThat(allowed("/pos/sales")).isFalse();
        assertThat(allowed("/invoicing/invoices")).isFalse();
        assertThat(allowed("/inventory/items")).isFalse();
        assertThat(allowed("/staff/users")).isFalse();
    }

    @Test
    void theOpenWorkPatternsMatchOneSegmentOnly() {
        // '*' is an id, not a wildcard subtree: nothing deeper may slip through on its coat-tails.
        assertThat(allowed("/pos/orders/018f/pay/extra")).isFalse();
        assertThat(allowed("/pos/orders/pay")).isFalse();
        assertThat(allowed("/pos/shifts/close")).isFalse();
    }

    @Test
    void aPathThatMerelyStartsLikeAnAllowedOneIsNotAllowed() {
        // "/auth/" with the slash, so "/authorisations" is not mistaken for authentication.
        assertThat(allowed("/authorisations")).isFalse();
        assertThat(allowed("/syncfusion-report")).isFalse();
    }

    @Test
    void anOfflineSaleTakenBeforeTheLapseStillReachesTheBooks() {
        // The money already changed hands. Refusing the record of it would destroy a real shop's
        // takings to make a billing point.
        LicenseState readOnly = expiredState();

        assertThat(LicenseEnforcementInterceptor.isReplayOfEarlierWork(
                "/pos/checkout", "2026-06-01T10:00:00Z", readOnly)).isTrue();
        assertThat(LicenseEnforcementInterceptor.isReplayOfEarlierWork(
                "/pos/cash-operations", "2026-06-01T10:00:00Z", readOnly)).isTrue();
    }

    @Test
    void butASaleTakenAfterWritesStoppedIsNot() {
        assertThat(LicenseEnforcementInterceptor.isReplayOfEarlierWork(
                "/pos/checkout", "2026-07-20T10:00:00Z", expiredState())).isFalse();
    }

    @Test
    void theReplayAllowanceNeedsTheHeaderAndAReplayableRoute() {
        LicenseState readOnly = expiredState();

        // No header: an ordinary request, refused.
        assertThat(LicenseEnforcementInterceptor.isReplayOfEarlierWork("/pos/checkout", null, readOnly))
                .isFalse();
        assertThat(LicenseEnforcementInterceptor.isReplayOfEarlierWork("/pos/checkout", "  ", readOnly))
                .isFalse();
        // A header on a route the queue never replays buys nothing.
        assertThat(LicenseEnforcementInterceptor.isReplayOfEarlierWork(
                "/inventory/items", "2026-06-01T10:00:00Z", readOnly)).isFalse();
        // Nor does a path that merely starts like one.
        assertThat(LicenseEnforcementInterceptor.isReplayOfEarlierWork(
                "/pos/checkout-settings", "2026-06-01T10:00:00Z", readOnly)).isFalse();
        // An unparseable timestamp is not a free pass.
        assertThat(LicenseEnforcementInterceptor.isReplayOfEarlierWork(
                "/pos/checkout", "yesterday", readOnly)).isFalse();
    }

    /** Expired 15 June, seven days of grace: writes stopped on 22 June 2026. */
    private static LicenseState expiredState() {
        return new LicenseState(LicenseMode.READ_ONLY, "expired", null,
                java.time.Instant.parse("2026-06-15T00:00:00Z"),
                java.time.Instant.parse("2026-06-22T00:00:00Z"),
                -30L, false, "a".repeat(64), null, null, java.time.Instant.parse("2026-07-22T00:00:00Z"));
    }

    @Test
    void stripsTheApiPrefixSoTheListsReadAsTheSpecDoes() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pos/orders");
        request.setRequestURI("/api/v1/pos/orders");

        assertThat(LicenseEnforcementInterceptor.pathWithinApi(request)).isEqualTo("/pos/orders");
    }

    @Test
    void stripsAServletContextPathToo() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/erp/api/v1/license/activate");
        request.setRequestURI("/erp/api/v1/license/activate");
        request.setContextPath("/erp");

        assertThat(LicenseEnforcementInterceptor.pathWithinApi(request)).isEqualTo("/license/activate");
    }

    @Test
    void messagesSayWhatHappenedAndWhatToDo() {
        assertThat(LicenseMessages.forMode(LicenseMode.READ_ONLY, "expired"))
                .isEqualTo("Your licence has expired. The system is read-only until it is renewed.");
        assertThat(LicenseMessages.forMode(LicenseMode.REVOKED, "revoked"))
                .isEqualTo("This licence has been revoked. Contact your supplier.");
        assertThat(LicenseMessages.forMode(LicenseMode.READ_ONLY, "suspended"))
                .isEqualTo("This licence is suspended. Contact your supplier.");
        assertThat(LicenseMessages.forMode(LicenseMode.UNLICENSED, "trial_expired"))
                .isEqualTo("The trial period has ended. Activate a licence to continue.");
        assertThat(LicenseMessages.featureNotLicensed("crm"))
                .isEqualTo("'crm' is not included in your licence.");
        assertThat(LicenseMessages.deviceLimit(5))
                .isEqualTo("Your licence allows 5 POS device(s). Deactivate a device or upgrade your plan.");
    }

    @Test
    void everyFeatureKeyMapsToAModuleName() {
        for (String feature : LicenseFeatures.ALL) {
            assertThat(LicenseFeatures.moduleName(feature)).as("module for %s", feature).isNotBlank();
        }
        // The two that are spelled differently in the module registry than in the sidebar.
        assertThat(LicenseFeatures.moduleName(LicenseFeatures.ASSETS)).isEqualTo("fixed-asset");
        assertThat(LicenseFeatures.moduleName(LicenseFeatures.TASK_BOARD)).isEqualTo("task");
        assertThat(LicenseFeatures.moduleName(LicenseFeatures.CRM)).isEqualTo("crm");
    }
}
