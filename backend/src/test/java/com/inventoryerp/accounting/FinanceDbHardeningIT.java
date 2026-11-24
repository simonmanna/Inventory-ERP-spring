package com.inventoryerp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.migrate.MigrateOnly;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Finance readiness audit B4: the database backstops (V655) and the separate migration step ({@link MigrateOnly}).
 */
class FinanceDbHardeningIT extends ApiTestSupport {

    @Autowired
    Environment env;

    @Test
    @DisplayName("B4: every trigger function in public runs with a pinned search_path (pg_temp last)")
    void triggerFunctionsPinTheirSearchPath() {
        List<String> unpinned = jdbc.sql("SELECT p.oid::regprocedure::text FROM pg_proc p WHERE p.pronamespace ="
                + " 'public'::regnamespace AND p.prorettype = 'trigger'::regtype AND NOT coalesce(array_to_string("
                + "p.proconfig, ',') LIKE '%search_path=public, pg_temp%', false) ORDER BY 1").query(String.class).list();
        assertThat(unpinned).as("trigger functions created after V655 must also SET search_path = public, pg_temp")
                .isEmpty();
    }

    @Test
    @DisplayName("B4: the finance CHECK constraints and reversal foreign keys are validated; reversalOfId is unique")
    void constraintsAreValidated() {
        List<String> names = List.of("JournalLine_amounts_non_negative", "JournalLine_one_side",
                "JournalLine_exchangeRate_positive", "CurrencyRate_rate_positive", "JournalEntry_reversalOfId_fkey",
                "JournalEntry_reversedEntryId_fkey");
        List<String> valid = jdbc.sql("SELECT conname FROM pg_constraint WHERE convalidated AND conname = ANY(?::text[])"
                + " ORDER BY conname").param(names.toArray(String[]::new)).query(String.class).list();
        assertThat(valid).containsExactlyInAnyOrderElementsOf(names);
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'JournalEntry_reversalOfId_key'")
                .query(String.class).single()).contains("UNIQUE");
    }

    @Test
    @DisplayName("B4: --migrate-only runs the same migrations as the API and exits 0 when the schema is current")
    void migrateOnlyRunsAndExitsCleanly() {
        long before = jdbc.sql("SELECT count(*) FROM flyway_schema_history").query(Long.class).single();
        List<String> args = new ArrayList<>(List.of("--migrate-only"));
        for (String key : List.of("spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
                "spring.flyway.user", "spring.flyway.password", "spring.flyway.placeholders.approle",
                "spring.flyway.placeholders.apppassword")) {
            String value = env.getProperty(key);
            if (value != null) {
                args.add("--" + key + "=" + value);
            }
        }
        assertThat(MigrateOnly.requested(args.toArray(String[]::new))).isTrue();
        assertThat(MigrateOnly.run(args.toArray(String[]::new))).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history").query(Long.class).single())
                .as("nothing pending: no new history rows").isEqualTo(before);
    }
}
