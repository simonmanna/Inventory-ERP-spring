package com.inventoryerp.kernel.licensing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a route behind a licensed feature — a 402 when the licence does not include it.
 *
 * <p>Different from {@link com.inventoryerp.kernel.modules.RequiresModule}, and the two compose:
 * that one asks whether the <em>operator</em> switched a module on, this one asks whether the
 * <em>licence</em> allows it at all. The licence is the ceiling, so an unlicensed feature is
 * refused however the install is configured.
 *
 * <p>Most vertical modules need nothing here: the licence reconciler already deactivates their
 * {@code OrganizationModule} rows, so the module guard refuses them. Use this for the add-ons that
 * are not modules — {@code webhooks}, {@code apiAccess}, {@code advancedReports},
 * {@code backupOffsite}, {@code multiOrg} — which have no module row to switch off.
 *
 * @see LicenseFeatures for the keys
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {

    /** A key from {@link LicenseFeatures}. */
    String value();
}
