package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.License;
import com.inventoryerp.license.domain.Plan;
import com.inventoryerp.license.shared.Json;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * What a licence actually grants, once the plan's defaults and the licence's overrides are combined.
 *
 * <p>The rule throughout is <em>null inherits, set overrides</em>. A licence that leaves a cap null
 * tracks its plan, so raising a plan's device cap lifts every licence that never negotiated its own;
 * a licence that set one is insulated from plan edits. The same holds for the feature set, which is
 * replaced wholesale rather than merged — a bespoke deal is easier to reason about as a complete
 * list than as a diff against a plan that may later change.
 */
public record Entitlements(Set<String> features, Integer maxDevices, Integer maxUsers,
        Integer maxOrganizations, int instanceLimit, int graceDays) {

    /** Feature keys the ERP recognises. Anything else is accepted but warned about on issue. */
    public static final Set<String> KNOWN_FEATURES = Set.of(
            // sidebar sections, gated by OrganizationModule / FeatureFlag on the install
            "crm", "rental", "manufacturing", "repair", "communication", "assets", "taskBoard",
            "accounting", "expense", "hr", "beverage", "fleet",
            // add-ons that are not sidebar sections
            "multiOrg", "androidPos", "apiAccess", "webhooks", "advancedReports", "backupOffsite");

    public static Entitlements resolve(License license, Plan plan) {
        Set<String> features = license.getFeatureOverrides() != null
                ? Json.readStringSet(license.getFeatureOverrides())
                : Json.readStringSet(plan.getFeatures());
        return new Entitlements(
                Collections.unmodifiableSet(features),
                firstNonNull(license.getMaxDevices(), plan.getMaxDevices()),
                firstNonNull(license.getMaxUsers(), plan.getMaxUsers()),
                firstNonNull(license.getMaxOrganizations(), plan.getMaxOrganizations()),
                firstNonNull(license.getInstanceLimit(), plan.getInstanceLimit(), 1),
                firstNonNull(license.getGraceDays(), plan.getGraceDays(), 0));
    }

    /** The {@code lic.limits} claim. A null cap is omitted, which the client reads as unlimited. */
    public Map<String, Object> limitsClaim() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (maxDevices != null) {
            out.put("devices", maxDevices);
        }
        if (maxUsers != null) {
            out.put("users", maxUsers);
        }
        if (maxOrganizations != null) {
            out.put("organizations", maxOrganizations);
        }
        return out;
    }

    /** Feature keys that no ERP release understands — a typo in a bespoke deal, usually. */
    public Set<String> unknownFeatures() {
        return features.stream().filter(f -> !KNOWN_FEATURES.contains(f))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
