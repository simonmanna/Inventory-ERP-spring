package com.inventoryerp.kernel.seed;

/**
 * A module's part of the demo seed ({@code prisma/seed.ts}). Contributors run inside the seed transaction,
 * after the kernel part, in ascending {@link #order()}.
 */
public interface SeedContributor {

    /** Lower runs first (core 100, accounting 200, ...). */
    int order();

    /** Seed this module's rows for the DEMO organization. */
    void seed(String organizationId);
}
