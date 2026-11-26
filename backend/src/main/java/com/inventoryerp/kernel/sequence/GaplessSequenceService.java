package com.inventoryerp.kernel.sequence;

import com.inventoryerp.kernel.tenancy.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gap-free document numbers (sales readiness INV-11): the counter row of {@code (organization, key)} is locked and
 * advanced inside the caller's transaction, so a number is used only when the caller commits, and concurrent callers
 * of one key queue on the row lock. On first use the counter starts after the highest number already carrying
 * {@code prefix} in {@code "Document"}, so numbering continues from what the PostgreSQL sequence issued before.
 */
@Service
public class GaplessSequenceService {

    private final JdbcClient jdbc;

    public GaplessSequenceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The next number {@code prefix + zero-padded counter}; must run inside the transaction that uses it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String key, String prefix, int padding) {
        String org = TenantContext.organizationId();
        Long last = jdbc.sql("UPDATE \"GaplessCounter\" SET last = last + 1, \"updatedAt\" = now() WHERE \"organizationId\""
                + " = ? AND key = ? RETURNING last").params(org, key).query(Long.class).optional().orElse(null);
        if (last == null) {
            // First use of the key: serialise the seeding, then insert the counter after the highest issued number.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("gapless:" + org + ":" + key).query().listOfRows();
            long seed = jdbc.sql("SELECT COALESCE(max(substring(\"documentNumber\" FROM ?)::bigint), 0) FROM \"Document\""
                    + " WHERE \"organizationId\" = ? AND \"documentNumber\" LIKE ? AND substring(\"documentNumber\" FROM ?)"
                    + " ~ '^[0-9]+$'").params(prefix.length() + 1, org, like(prefix) + "%", prefix.length() + 1)
                    .query(Long.class).single();
            last = jdbc.sql("INSERT INTO \"GaplessCounter\" (\"organizationId\", key, last) VALUES (?, ?, ?) ON CONFLICT"
                    + " (\"organizationId\", key) DO UPDATE SET last = \"GaplessCounter\".last + 1, \"updatedAt\" = now()"
                    + " RETURNING last").params(org, key, seed + 1).query(Long.class).single();
        }
        String digits = Long.toString(last);
        return prefix + "0".repeat(Math.max(0, padding - digits.length())) + digits;
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
