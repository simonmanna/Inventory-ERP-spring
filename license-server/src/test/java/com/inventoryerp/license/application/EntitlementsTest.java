package com.inventoryerp.license.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.license.domain.License;
import com.inventoryerp.license.domain.Plan;
import org.junit.jupiter.api.Test;

/**
 * "Null inherits, set overrides" is the rule the whole catalogue depends on: it decides whether
 * raising a plan's cap lifts the licences already sold on it.
 */
class EntitlementsTest {

    private Plan plan() {
        Plan plan = new Plan();
        plan.setCode("pro-monthly");
        plan.setPeriod("MONTH");
        plan.setPeriodCount(1);
        plan.setMaxDevices(5);
        plan.setMaxUsers(25);
        plan.setMaxOrganizations(1);
        plan.setInstanceLimit(1);
        plan.setGraceDays(7);
        plan.setFeatures("[\"crm\",\"accounting\"]");
        return plan;
    }

    private License license() {
        License license = new License();
        license.setGraceDays(7);
        return license;
    }

    @Test
    void inheritsEverythingFromThePlanByDefault() {
        Entitlements resolved = Entitlements.resolve(license(), plan());

        assertThat(resolved.features()).containsExactly("crm", "accounting");
        assertThat(resolved.maxDevices()).isEqualTo(5);
        assertThat(resolved.maxUsers()).isEqualTo(25);
        assertThat(resolved.maxOrganizations()).isEqualTo(1);
        assertThat(resolved.instanceLimit()).isEqualTo(1);
        assertThat(resolved.graceDays()).isEqualTo(7);
    }

    @Test
    void aSetCapOverridesThePlan() {
        License license = license();
        license.setMaxDevices(20);

        Entitlements resolved = Entitlements.resolve(license, plan());

        assertThat(resolved.maxDevices()).isEqualTo(20);
        assertThat(resolved.maxUsers()).isEqualTo(25); // untouched, still inherited
    }

    @Test
    void featureOverridesReplaceRatherThanMerge() {
        License license = license();
        license.setFeatureOverrides("[\"hr\",\"rental\"]");

        assertThat(Entitlements.resolve(license, plan()).features())
                .containsExactly("hr", "rental")
                .doesNotContain("crm");
    }

    @Test
    void aZeroCapIsACapAndNotAnAbsentOne() {
        License license = license();
        license.setMaxDevices(0);

        assertThat(Entitlements.resolve(license, plan()).maxDevices()).isZero();
    }

    @Test
    void aNullPlanCapMeansUnlimitedAndIsOmittedFromTheClaim() {
        Plan plan = plan();
        plan.setMaxUsers(null);

        Entitlements resolved = Entitlements.resolve(license(), plan);

        assertThat(resolved.maxUsers()).isNull();
        assertThat(resolved.limitsClaim()).containsKeys("devices", "organizations").doesNotContainKey("users");
    }

    @Test
    void featureSetsAreReadOnly() {
        Entitlements resolved = Entitlements.resolve(license(), plan());
        assertThat(resolved.features()).isUnmodifiable();
    }

    @Test
    void reportsFeatureKeysNoReleaseUnderstands() {
        License license = license();
        license.setFeatureOverrides("[\"crm\",\"telepathy\"]");

        assertThat(Entitlements.resolve(license, plan()).unknownFeatures()).containsExactly("telepathy");
    }

    @Test
    void everyKnownFeatureIsAcceptedAsKnown() {
        License license = license();
        license.setFeatureOverrides(com.inventoryerp.license.shared.Json.writeStringCollection(
                Entitlements.KNOWN_FEATURES));

        assertThat(Entitlements.resolve(license, plan()).unknownFeatures()).isEmpty();
    }

    @Test
    void toleratesAbsentOrRubbishFeatureJson() {
        Plan plan = plan();
        plan.setFeatures(null);
        assertThat(Entitlements.resolve(license(), plan).features()).isEmpty();

        plan.setFeatures("[]");
        assertThat(Entitlements.resolve(license(), plan).features()).isEmpty();
    }
}
