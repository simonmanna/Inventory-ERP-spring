package com.inventoryerp.kernel.seed;

/**
 * An opt-in demo that lives in an organization of its own (seed profile). {@link SeedRunner} creates the
 * organization with the same base as the construction demo (Administrator, the construction roles and users, taxes,
 * fiscal periods for this and last year, branches {@code QRY} and {@code KLA}, units, chart of accounts and the
 * {@code MAIN} warehouse), then calls {@link #seed} inside the organization's tenant context with no open
 * transaction. Unlike {@code DEMO} it also runs on a database that already holds organizations, and is skipped when
 * its organization code exists.
 */
public interface DemoOrganization {

    /** Whether the demo was switched on (its environment flag). */
    boolean enabled();

    /** Sign-in organization code, unique. */
    String code();

    /** Organization display name. */
    String name();

    /** E-mail domain of the demo users, e.g. {@code granite.test}: the admin is {@code admin@<domain>}. */
    String emailDomain();

    /** Seed the demo's documents into the new organization. */
    void seed(String organizationId);
}
