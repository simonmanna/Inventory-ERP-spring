package com.inventoryerp.kernel.licensing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What identifies this installation to the licence authority.
 *
 * <p>{@code sha256(installId + ":" + Postgres cluster system_identifier)}, lower hex. The two halves
 * answer different worries:
 *
 * <ul>
 *   <li>The <b>install id</b> is generated once and travels with the data. A container rebuild, a
 *       new hostname, a changed IP or a move to another host keeps the same licence — all routine,
 *       none of which should cost a support call.
 *   <li>The <b>cluster system identifier</b> belongs to the Postgres cluster, assigned at
 *       {@code initdb}. Restoring a dump into a <em>different</em> cluster therefore changes the
 *       fingerprint, which is what stops one paid database being cloned into ten running installs.
 * </ul>
 *
 * <p>The deliberate consequence is that a genuine disaster recovery onto a new cluster also changes
 * it. That case degrades to {@link LicenseMode#GRACE} with a prompt to re-activate — never a
 * lockout — and the authority allows re-binding the same key and records the move.
 *
 * <p>Both values are written straight onto the single {@code LicenseInstallation} row, in its own
 * transaction, so that establishing an identity never depends on the caller's transaction
 * succeeding. {@code ON CONFLICT (id)} on a constant primary key makes two instances racing on first
 * boot settle on the same identity.
 */
@Component
public class LicenseFingerprint {

    private static final Logger log = LoggerFactory.getLogger(LicenseFingerprint.class);

    private final JdbcClient jdbc;
    private volatile String cached;

    public LicenseFingerprint(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** This installation's fingerprint, establishing its identity on first use. */
    public String get() {
        String value = cached;
        if (value == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = establish();
                }
                value = cached;
            }
        }
        return value;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    String establish() {
        String existing = jdbc.sql("SELECT fingerprint FROM \"LicenseInstallation\" WHERE id = ?")
                .param(LicenseInstallation.ID)
                .query(String.class)
                .optional()
                .orElse(null);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }

        String installId = UUID.randomUUID().toString();
        String fingerprint = compute(installId, clusterIdentifier());
        jdbc.sql("""
                        INSERT INTO "LicenseInstallation" (id, "installId", fingerprint, mode)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (id) DO NOTHING""")
                .params(LicenseInstallation.ID, installId, fingerprint, LicenseMode.UNLICENSED.name())
                .update();

        // Read back rather than trusting the insert: another instance may have won the race.
        String settled = jdbc.sql("SELECT fingerprint FROM \"LicenseInstallation\" WHERE id = ?")
                .param(LicenseInstallation.ID)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Could not establish this installation's licence identity"));
        log.info("Licence installation fingerprint is {}…", settled.substring(0, 12));
        return settled;
    }

    static String compute(String installId, String clusterIdentifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((installId + ":" + clusterIdentifier).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    /**
     * The Postgres cluster's identifier. If it cannot be read — a stripped-down role, or a managed
     * service that hides {@code pg_control_system()} — the fingerprint falls back to the install id
     * alone rather than failing: a licence that cannot be computed would stop the product, which is
     * a far worse outcome than a fingerprint that survives a restore.
     */
    private String clusterIdentifier() {
        try {
            return jdbc.sql("SELECT system_identifier::text FROM pg_control_system()")
                    .query(String.class)
                    .optional()
                    .orElse("no-cluster-id");
        } catch (RuntimeException e) {
            log.warn("Cannot read the Postgres cluster identifier ({}); the licence fingerprint will be "
                    + "based on the install id alone.", e.getMessage());
            return "no-cluster-id";
        }
    }

    /** Test seam: forget the cached value. */
    void invalidate() {
        cached = null;
    }
}
