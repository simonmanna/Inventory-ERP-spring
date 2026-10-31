package com.inventoryerp.kernel.seed;

/**
 * A module's part of the opt-in "Construction materials" demo organization ({@code SEED_CONSTRUCTION_DEMO=true} with the
 * {@code seed} profile). {@link SeedRunner} creates the organization, its roles and branches and runs the base
 * contributors, then calls the parts in ascending {@link #constructionOrder()}, each inside the organization's tenant
 * context and with no open transaction: a part opens its own. Parts find each other's data by code, so a part never
 * imports another module's demo.
 */
public interface ConstructionDemoPart {

    /** Lower runs first (core 100, hr 200, manufacturing 300, inventory 350, fleet 400). */
    int constructionOrder();

    /** Seed this module's rows into the construction demo organization; does nothing when they exist. */
    void seedConstructionDemo(String organizationId);
}
