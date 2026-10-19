package com.inventoryerp.kernel.modules;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gates a controller or route behind per-tenant module enablement (an active
 * {@code OrganizationModule} row), Nest {@code @RequiresModule}. Use the module's manifest name, e.g.
 * {@code manufacturing}, {@code rental}, {@code repair}, {@code hr}, {@code fixed-asset}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresModule {
    String value();
}
