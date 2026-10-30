package com.inventoryerp.kernel.licensing;

import java.util.Set;

/**
 * The feature keys a licence can grant.
 *
 * <p>They are the same strings the install already uses for gating, on purpose: the sidebar keys in
 * {@code web/src/lib/features.ts} and the module names behind
 * {@link com.inventoryerp.kernel.modules.RequiresModule}. A licence therefore drives the gating that
 * already exists rather than introducing a second, parallel vocabulary.
 *
 * <p>This list must stay in step with {@code Entitlements.KNOWN_FEATURES} on the licence server;
 * that side only warns about a key no release understands, so the two drifting apart is a silent
 * way to sell something that cannot be switched on.
 */
public final class LicenseFeatures {

    // Sidebar sections / vertical modules.
    public static final String CRM = "crm";
    public static final String RENTAL = "rental";
    public static final String MANUFACTURING = "manufacturing";
    public static final String REPAIR = "repair";
    public static final String COMMUNICATION = "communication";
    public static final String ASSETS = "assets";
    public static final String TASK_BOARD = "taskBoard";
    public static final String ACCOUNTING = "accounting";
    public static final String EXPENSE = "expense";
    public static final String HR = "hr";
    public static final String BEVERAGE = "beverage";
    /** Fleet & logistics (vehicles, dispatch, weighbridge — ADR-S009). */
    public static final String FLEET = "fleet";
    /** Sales agreements and rebate programmes (ADR-S011 S9). */
    public static final String SALES_AGREEMENTS = "salesAgreements";

    // Add-ons that are not sidebar sections.
    public static final String MULTI_ORG = "multiOrg";
    public static final String ANDROID_POS = "androidPos";
    public static final String API_ACCESS = "apiAccess";
    public static final String WEBHOOKS = "webhooks";
    public static final String ADVANCED_REPORTS = "advancedReports";
    public static final String BACKUP_OFFSITE = "backupOffsite";

    /** Everything, which is what a trial grants. */
    public static final Set<String> ALL = Set.of(
            CRM, RENTAL, MANUFACTURING, REPAIR, COMMUNICATION, ASSETS, TASK_BOARD, ACCOUNTING, EXPENSE, HR,
            BEVERAGE, FLEET, SALES_AGREEMENTS, MULTI_ORG, ANDROID_POS, API_ACCESS, WEBHOOKS, ADVANCED_REPORTS, BACKUP_OFFSITE);

    /**
     * Feature key for each {@code @RequiresModule} manifest name, where the two spellings differ.
     * Used by the entitlement reconciler so {@code OrganizationModule} rows carry the names the
     * module guard looks for.
     */
    public static final java.util.Map<String, String> MODULE_NAME_BY_FEATURE = java.util.Map.of(
            ASSETS, "fixed-asset",
            TASK_BOARD, "task");

    private LicenseFeatures() {}

    /** The module name a feature gates, which is the feature key itself unless it is remapped. */
    public static String moduleName(String feature) {
        return MODULE_NAME_BY_FEATURE.getOrDefault(feature, feature);
    }

    /**
     * The feature a {@code @RequiresModule} name is sold as, or {@code null} for a module the
     * licence does not price separately (orders, for example).
     */
    public static String featureForModule(String module) {
        for (String feature : ALL) {
            if (moduleName(feature).equals(module)) {
                return feature;
            }
        }
        return null;
    }
}
