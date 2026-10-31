package com.inventoryerp.kernel.licensing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.inventoryerp.kernel.tenancy.InstallWide;

/**
 * This installation's licence: what it is, and how it changes.
 *
 * <p>Everything else in the kernel asks {@link #current()}, which is a 60-second cached snapshot —
 * so the guard that runs on every request is a map lookup, not a signature check. The cache is
 * dropped whenever the licence changes, and the window is short enough that an expiry is acted on
 * within a minute of the moment it happens.
 *
 * <p>A token is never trusted because it is in the database. It is re-verified against the pinned
 * keys every time it is loaded, so editing the row by hand gets an operator nowhere.
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(60);
    private static final String SNAPSHOT_KEY = "state";

    /** What this install is running, for the authority's limits and the licence page. */
    public record Usage(int devices, int users, int organizations) {}

    private final LicenseInstallationRepository installations;
    private final LicenseFingerprint fingerprint;
    private final LicenseKeySet keySet;
    private final LicenseServerClient client;
    private final LicenseEntitlements entitlements;
    private final LicenseProperties properties;
    private final JdbcClient jdbc;
    private final String appVersion;
    private final InstallWide installWide;

    private final Cache<String, LicenseState> snapshot =
            Caffeine.newBuilder().expireAfterWrite(SNAPSHOT_TTL).build();

    public LicenseService(LicenseInstallationRepository installations, LicenseFingerprint fingerprint,
            LicenseKeySet keySet, LicenseServerClient client, LicenseEntitlements entitlements,
            LicenseProperties properties, JdbcClient jdbc, InstallWide installWide,
            @org.springframework.beans.factory.annotation.Value("${app.version:0.1.0}") String appVersion) {
        this.installations = installations;
        this.fingerprint = fingerprint;
        this.keySet = keySet;
        this.client = client;
        this.entitlements = entitlements;
        this.properties = properties;
        this.jdbc = jdbc;
        this.appVersion = appVersion;
        this.installWide = installWide;
    }

    // ---- reads ----

    /** The licence as it stands. Cheap: cached for a minute and recomputed from the clock. */
    public LicenseState current() {
        return snapshot.get(SNAPSHOT_KEY, key -> evaluate());
    }

    public void invalidate() {
        snapshot.invalidateAll();
    }

    public String fingerprint() {
        return fingerprint.get();
    }

    /** Is enforcement switched on at all? */
    public boolean enforcing() {
        return properties.enforce();
    }

    /**
     * Counts the authority caps. Deliberately counted across all organizations: the licence binds
     * the installation, so ten tenants of five users each are fifty users on one licence.
     */
    public Usage usage() {
        // Its own transaction with no tenant: inside a request the tenant row-level security policy would count the
        // caller's organization only, and the caps would let every tenant fill the licence again.
        return installWide.run(() -> new Usage(
                count("SELECT count(*) FROM \"PosDevice\" WHERE \"revokedAt\" IS NULL"),
                count("SELECT count(*) FROM \"User\" WHERE \"isActive\" = true AND \"deletedAt\" IS NULL"),
                count("SELECT count(*) FROM \"Organization\" WHERE status = 'active' AND \"deletedAt\" IS NULL")));
    }

    // ---- changes ----

    /**
     * Bind this installation to a licence key, online.
     *
     * <p>The authority's refusals ("key not recognised", "already activated on another
     * installation") come back as they are: they are the words the spec promises the customer.
     */
    @Transactional
    public LicenseState activate(String licenseKey) {
        if (!properties.hasServer()) {
            throw ApiException.badRequest(
                    "No licence server is configured. Ask your supplier for an offline licence file.");
        }
        String key = licenseKey == null ? "" : licenseKey.trim();
        if (key.isEmpty()) {
            throw ApiException.badRequest("Enter a licence key.");
        }
        LicenseServerClient.Issued issued;
        try {
            issued = client.activate(key, fingerprint.get(), instanceName(), hostname(), appVersion);
        } catch (LicenseServerClient.Unreachable e) {
            log.warn("Activation could not reach the licence server: {}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, LicenseMessages.UNREACHABLE);
        }
        LicenseState state = store(issued.token(), key, true);
        log.info("Licence activated: plan {}, expires {}", state.plan(), state.expiresAt());
        return state;
    }

    /**
     * Apply a licence file issued for this installation. This is the air-gapped path: no network is
     * involved, and the signature plus the fingerprint in the token are the whole of the trust.
     */
    @Transactional
    public LicenseState applyOfflineToken(String token) {
        if (token == null || token.isBlank()) {
            throw ApiException.badRequest("Paste the licence file's contents, or upload the .lic file.");
        }
        LicenseState state = store(token.trim(), null, false);
        log.info("Offline licence applied: plan {}, expires {}", state.plan(), state.expiresAt());
        return state;
    }

    /**
     * Check in with the authority: report counts, take a fresh token back. This is how a renewal, a
     * suspension or a plan change reaches a customer with nobody touching the install.
     *
     * @param force when true, a failure is reported to the caller; the scheduled job passes false,
     *              because an outage is not something to wake anyone about
     */
    @Transactional
    public LicenseState refresh(boolean force) {
        LicenseInstallation row = row();
        if (row.getToken() == null) {
            if (force) {
                throw ApiException.badRequest("This installation has no licence to refresh. Activate one first.");
            }
            return current();
        }
        if (!properties.hasServer()) {
            if (force) {
                throw ApiException.badRequest("No licence server is configured.");
            }
            return current();
        }

        Usage usage = usage();
        try {
            LicenseServerClient.Issued issued = client.heartbeat(row.getToken(), usage.devices(),
                    usage.users(), usage.organizations(), appVersion, current().mode());
            return store(issued.token(), row.getLicenseKey(), true);
        } catch (ApiException e) {
            // A decision by the authority: a revoked or suspended licence answers 403 here. Record
            // it against the licence so the UI can explain itself, and let the dates do the rest.
            noteCheckFailure(e.getMessage());
            if (force) {
                throw e;
            }
            log.warn("Licence check-in refused: {}", e.getMessage());
            return current();
        } catch (LicenseServerClient.Unreachable e) {
            noteCheckFailure("unreachable: " + e.getMessage());
            if (force) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, LicenseMessages.UNREACHABLE);
            }
            log.debug("Licence check-in could not reach the server: {}", e.getMessage());
            return current();
        }
    }

    /**
     * Release this installation's slot — it is being retired, or the licence is moving to new
     * hardware. The local licence is cleared whether or not the authority could be told, because
     * the operator's intent is not conditional on the network; the slot is then freed from the admin
     * side if the call did not get through.
     */
    @Transactional
    public LicenseState deactivate() {
        LicenseInstallation row = row();
        String key = row.getLicenseKey();
        if (key != null && properties.hasServer()) {
            try {
                client.deactivate(key, row.getFingerprint());
            } catch (RuntimeException e) {
                log.warn("Could not tell the licence server about this deactivation ({}). "
                        + "Free the slot from the licence server's admin screen.", e.getMessage());
            }
        }
        row.setToken(null);
        row.setTokenKid(null);
        row.setClaims(null);
        row.setLicenseKey(null);
        row.setStatus(null);
        row.setStartsAt(null);
        row.setExpiresAt(null);
        row.setGraceUntil(null);
        row.setRefreshAfter(null);
        installations.save(row);
        invalidate();

        LicenseState state = current();
        audit(LicenseMode.valueOf(row.getMode()), state.mode(), "deactivated", Map.of());
        persistMode(state);
        entitlements.reconcile(state);
        return state;
    }

    // ---- internals ----

    /**
     * Verify a token and make it this installation's licence.
     *
     * <p>Order matters: the signature is checked before anything in the token is believed, and the
     * claims are checked for being about this installation before they are stored. A token that
     * fails either is not saved at all, so a bad file cannot displace a good licence.
     */
    private LicenseState store(String token, String licenseKey, boolean fromServer) {
        LicenseJws.Parsed parsed;
        try {
            parsed = LicenseJws.parse(token);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("That does not look like a licence file.");
        }
        LicenseInstallation row = row();
        java.security.PublicKey key = trustedKeyFor(parsed.kid(), row);
        if (key == null) {
            throw ApiException.badRequest(
                    "This licence was signed by a key this installation does not trust. "
                            + "Update the application, or check the file came from your supplier.");
        }
        if (!LicenseJws.verify(key, parsed)) {
            log.warn("Rejected a licence token whose signature does not verify (kid {})", parsed.kid());
            throw ApiException.badRequest("This licence file is not valid.");
        }

        LicenseClaims claims;
        try {
            claims = LicenseClaims.parse(parsed.payloadJson());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("This licence file is not valid.");
        }
        if (!fingerprint.get().equals(claims.fingerprint())) {
            throw ApiException.badRequest(LicenseMessages.WRONG_INSTANCE);
        }

        LicenseMode before = LicenseMode.valueOf(row.getMode());
        row.setToken(token);
        row.setTokenKid(parsed.kid());
        // The payload is already JSON; store it as it stands so the column is queryable.
        row.setClaims(parsed.payloadJson());
        row.setLicenseKey(licenseKey != null ? licenseKey : claims.licenseKey());
        row.setStatus(claims.status());
        row.setStartsAt(claims.startsAt());
        row.setExpiresAt(claims.expiresAt());
        row.setGraceUntil(claims.graceUntil());
        row.setRefreshAfter(claims.refreshAfter());
        row.setServerTimeHighWater(highWater(row.getServerTimeHighWater(), claims.serverTime()));
        if (fromServer) {
            row.setLastCheckAt(Instant.now());
            row.setLastCheckOk(true);
            row.setLastCheckError(null);
        }
        installations.save(row);
        invalidate();

        LicenseState state = current();
        if (state.mode() != before) {
            audit(before, state.mode(), fromServer ? "check-in" : "offline file",
                    Map.of("plan", String.valueOf(state.plan())));
        }
        persistMode(state);
        entitlements.reconcile(state);
        return state;
    }

    /** Evaluate the licence from the stored row and the clock. */
    @Transactional(readOnly = true)
    LicenseState evaluate() {
        LicenseInstallation row = row();
        LicenseClaims claims = null;
        if (row.getToken() != null) {
            try {
                LicenseJws.Parsed parsed = LicenseJws.parse(row.getToken());
                java.security.PublicKey key = trustedKeyFor(parsed.kid(), row);
                // Re-verified on every load: a token is never trusted for being in the database.
                if (key != null && LicenseJws.verify(key, parsed)) {
                    claims = LicenseClaims.parse(parsed.payloadJson());
                } else {
                    log.warn("The stored licence no longer verifies (kid {}); treating this install as "
                            + "unlicensed until a valid licence is applied.", parsed.kid());
                }
            } catch (RuntimeException e) {
                log.warn("The stored licence is unreadable ({}); treating this install as unlicensed.",
                        e.getMessage());
            }
        }
        return LicenseStateMachine.evaluate(claims, Instant.now(), row.getCreatedAt(),
                row.getServerTimeHighWater(), properties.trialDays(), row.getFingerprint(),
                row.getLastCheckAt(), row.getLastCheckError());
    }

    /**
     * The key for a token's id, learning it from the authority if this release has never seen it.
     * A fetched set is written back to the licence row, so a rotation followed by an outage does not
     * leave the install unable to verify what it already holds.
     */
    private java.security.PublicKey trustedKeyFor(String kid, LicenseInstallation row) {
        Optional<java.security.PublicKey> known = keySet.get(kid);
        if (known.isPresent()) {
            return known.get();
        }
        if (row.getTrustedKeys() != null && keySet.addAll(row.getTrustedKeys()) > 0) {
            Optional<java.security.PublicKey> cached = keySet.get(kid);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        Optional<String> fetched = client.jwks();
        if (fetched.isPresent() && keySet.addAll(fetched.get()) > 0) {
            row.setTrustedKeys(keySet.asJwks());
            installations.save(row);
            return keySet.get(kid).orElse(null);
        }
        return null;
    }

    /** The one licence row, establishing this installation's identity if this is the first boot. */
    @Transactional(propagation = Propagation.REQUIRED)
    LicenseInstallation row() {
        fingerprint.get(); // creates the row on first boot
        return installations.findById(LicenseInstallation.ID)
                .orElseThrow(() -> new IllegalStateException("The licence installation row is missing"));
    }

    private void persistMode(LicenseState state) {
        jdbc.sql("UPDATE \"LicenseInstallation\" SET mode = ?, \"updatedAt\" = now() WHERE id = ?")
                .params(state.mode().name(), LicenseInstallation.ID)
                .update();
    }

    private void noteCheckFailure(String message) {
        jdbc.sql("""
                        UPDATE "LicenseInstallation"
                        SET "lastCheckAt" = now(), "lastCheckOk" = false, "lastCheckError" = ?,
                            "updatedAt" = now()
                        WHERE id = ?""")
                .params(abbreviate(message), LicenseInstallation.ID)
                .update();
        invalidate();
    }

    /**
     * Local, append-only record of every mode change — written outside the caller's transaction so
     * the explanation of a lockout survives even if what caused it rolled back.
     */
    void audit(LicenseMode from, LicenseMode to, String reason, Map<String, Object> detail) {
        Map<String, Object> body = new LinkedHashMap<>(detail);
        jdbc.sql("""
                        INSERT INTO "LicenseAuditLocal" ("fromMode", "toMode", reason, detail)
                        VALUES (?, ?, ?, ?::jsonb)""")
                .params(from == null ? null : from.name(), to.name(), abbreviate(reason),
                        body.isEmpty() ? null : EntityJson.toJson(body))
                .update();
    }

    private int count(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).optional().orElse(0L);
        return value == null ? 0 : value.intValue();
    }

    private static Instant highWater(Instant current, Instant reported) {
        if (reported == null) {
            return current;
        }
        return current == null || reported.isAfter(current) ? reported : current;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private String instanceName() {
        String configured = properties.instanceName();
        return configured == null || configured.isBlank() ? hostname() : configured;
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "unknown-host";
        }
    }
}
