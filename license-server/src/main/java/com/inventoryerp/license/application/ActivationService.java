package com.inventoryerp.license.application;

import com.inventoryerp.license.crypto.Jws;
import com.inventoryerp.license.crypto.LicenseKeyCodec;
import com.inventoryerp.license.domain.ActivationStatus;
import com.inventoryerp.license.domain.LicenseActivation;
import com.inventoryerp.license.domain.LicenseActivationRepository;
import com.inventoryerp.license.domain.LicenseEventType;
import com.inventoryerp.license.domain.LicenseHeartbeat;
import com.inventoryerp.license.domain.LicenseHeartbeatRepository;
import com.inventoryerp.license.domain.LicenseStatus;
import com.inventoryerp.license.shared.ApiException;
import com.inventoryerp.license.shared.Json;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an ERP install talks to: activate once, then check in on a schedule.
 *
 * <p>Authentication here is the licence key itself (on activate) or a token this server signed (on
 * heartbeat). There is no account: the install is the caller. That is why both routes are rate
 * limited per IP and why neither ever says more than it must — an unknown key and a malformed one
 * are answered identically.
 */
@Service
public class ActivationService {

    private static final Logger log = LoggerFactory.getLogger(ActivationService.class);

    /** What the install gets back. */
    public record Issued(String token, String kid, Instant refreshAfter, Instant expiresAt, String status) {}

    /** What an install reports about itself. Counts only — never business data. */
    public record Report(Integer deviceCount, Integer userCount, Integer orgCount, String appVersion, String mode) {}

    private final LicenseService licenses;
    private final LicenseActivationRepository activations;
    private final LicenseHeartbeatRepository heartbeats;
    private final SigningKeyService signingKeys;
    private final TokenIssuer tokens;
    private final LicenseEvents events;

    public ActivationService(LicenseService licenses, LicenseActivationRepository activations,
            LicenseHeartbeatRepository heartbeats, SigningKeyService signingKeys, TokenIssuer tokens,
            LicenseEvents events) {
        this.licenses = licenses;
        this.activations = activations;
        this.heartbeats = heartbeats;
        this.signingKeys = signingKeys;
        this.tokens = tokens;
        this.events = events;
    }

    /**
     * Bind an install to a licence and hand back its first token.
     *
     * <p>Re-activating a fingerprint the licence already knows is idempotent and does not consume a
     * second slot: that is the path a customer takes after restoring a backup, and punishing it
     * would turn a routine recovery into a support call.
     */
    @Transactional
    public Issued activate(String licenseKey, String fingerprint, String instanceName, String hostname,
            String appVersion, String ip) {
        LicenseService.Loaded loaded = licenses.loadByKey(licenseKey);
        LicenseStatus status = loaded.license().statusType();
        String refusal = LicenseMessages.forStatus(status);
        if (refusal != null) {
            events.record(loaded.license().getId(), LicenseEventType.ACTIVATION_REJECTED, "install",
                    Map.of("reason", status.value(), "ip", nullToEmpty(ip)));
            throw ApiException.forbidden(refusal);
        }
        String clean = LicenseService.requireFingerprint(fingerprint);

        Optional<LicenseActivation> existing =
                activations.findByLicenseIdAndFingerprint(loaded.license().getId(), clean);
        LicenseActivation activation = existing.orElseGet(LicenseActivation::new);
        boolean returning = existing.isPresent();
        if (!returning) {
            licenses.assertSlotAvailable(loaded, clean);
            activation.setLicenseId(loaded.license().getId());
            activation.setFingerprint(clean);
        }
        boolean wasDeactivated = returning && !activation.isActive();
        activation.setInstanceName(instanceName);
        activation.setHostname(hostname);
        activation.setAppVersion(appVersion);
        activation.setStatus(ActivationStatus.ACTIVE.value());
        activation.setDeactivatedAt(null);
        activation.setLastSeenAt(Instant.now());
        activations.saveAndFlush(activation);

        events.record(loaded.license().getId(), activation.getId(),
                wasDeactivated || returning ? LicenseEventType.REACTIVATED : LicenseEventType.ACTIVATED,
                "install",
                Map.of("fingerprint", clean, "hostname", nullToEmpty(hostname),
                        "appVersion", nullToEmpty(appVersion), "ip", nullToEmpty(ip)));
        log.info("Licence {} activated on {} ({})", LicenseKeyCodec.mask(loaded.license().getKey()),
                shortFingerprint(clean), returning ? "returning install" : "new install");

        return issue(loaded, activation);
    }

    /**
     * Routine check-in. The install sends the token it holds; it gets a fresh one back, which is how
     * a renewal, a suspension or a plan change reaches a customer without anyone touching the install.
     *
     * <p>A suspended or revoked licence still gets a token, signed with {@code lic.status} set to
     * {@code suspended} / {@code revoked}: that is what turns the install read-only at once. A bare
     * 403 would not — the install keeps the token it holds on any refusal, so it would trade on until
     * that token's grace ran out. Resuming is the same path in reverse: the next check-in carries
     * {@code active} again.
     */
    @Transactional
    public Issued heartbeat(String token, Report report, String ip) {
        Verified verified = verify(token);
        LicenseService.Loaded loaded = licenses.load(verified.activation().getLicenseId());

        LicenseActivation activation = verified.activation();
        if (loaded.license().statusType() == LicenseStatus.REVOKED) {
            // Revoking released every slot, but the install must still learn of it — from a token it
            // can verify, not from a refusal it would shrug off.
            return issue(loaded, activation);
        }
        if (!activation.isActive()) {
            // The slot was released from the admin side while this install was away.
            throw ApiException.forbidden(LicenseMessages.ALREADY_ACTIVATED);
        }

        Instant now = Instant.now();
        Duration gap = Duration.between(activation.getLastSeenAt(), now);
        activation.setLastSeenAt(now);
        if (report.appVersion() != null) {
            activation.setAppVersion(report.appVersion());
        }
        activations.save(activation);

        LicenseHeartbeat beat = new LicenseHeartbeat();
        beat.setActivationId(activation.getId());
        beat.setAt(now);
        beat.setDeviceCount(report.deviceCount());
        beat.setUserCount(report.userCount());
        beat.setOrgCount(report.orgCount());
        beat.setAppVersion(report.appVersion());
        beat.setMode(report.mode());
        beat.setIp(ip);
        heartbeats.save(beat);

        recordNotables(loaded, activation, report, gap);
        return issue(loaded, activation);
    }

    /** Release the slot — the install is being retired or moved to new hardware. */
    @Transactional
    public void deactivate(String licenseKey, String fingerprint, String actor) {
        LicenseService.Loaded loaded = licenses.loadByKey(licenseKey);
        String clean = LicenseService.requireFingerprint(fingerprint);
        LicenseActivation activation = activations
                .findByLicenseIdAndFingerprint(loaded.license().getId(), clean)
                .orElseThrow(() -> ApiException.notFound("That installation is not activated on this licence."));
        if (activation.isActive()) {
            activation.setStatus(ActivationStatus.DEACTIVATED.value());
            activation.setDeactivatedAt(Instant.now());
            activations.save(activation);
            events.record(loaded.license().getId(), activation.getId(), LicenseEventType.DEACTIVATED, actor,
                    Map.of("fingerprint", clean));
        }
    }

    /** An activation whose token verified against one of our keys. */
    record Verified(LicenseActivation activation, Map<String, Object> claims) {}

    /**
     * Verify a token the install presents. Signature first, then that the activation it names still
     * exists: an attacker with a stale token must not be able to resurrect a deleted activation.
     */
    Verified verify(String token) {
        Jws.Parsed parsed;
        try {
            parsed = Jws.parse(token);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("That licence token is not readable.");
        }
        var key = signingKeys.publicKeyFor(parsed.kid())
                .orElseThrow(() -> ApiException.unauthorized("That licence token was not issued by this server."));
        if (!Jws.verify(key, parsed)) {
            throw ApiException.unauthorized("That licence token does not verify.");
        }
        Map<String, Object> claims = Json.readObject(parsed.payloadJson());
        String activationId = String.valueOf(claims.get("jti"));
        LicenseActivation activation = activations.findById(activationId)
                .orElseThrow(() -> ApiException.unauthorized("That installation is no longer activated."));
        if (!String.valueOf(claims.get("aud")).equals(activation.getFingerprint())) {
            throw ApiException.unauthorized(LicenseMessages.WRONG_INSTANCE);
        }
        return new Verified(activation, claims);
    }

    private Issued issue(LicenseService.Loaded loaded, LicenseActivation activation) {
        TokenIssuer.Minted minted = tokens.mint(loaded.license(), loaded.plan(), activation,
                new TokenIssuer.Customer(loaded.customer().getId(), loaded.customer().getName()),
                false, Instant.now());
        return new Issued(minted.token(), minted.kid(), minted.refreshAfter(), minted.expiresAt(),
                loaded.license().getStatus());
    }

    /**
     * Two things in a check-in are worth keeping beyond the rolling heartbeat table: an install that
     * went quiet for far longer than its plan's interval, and one reporting more devices, users or
     * organizations than it is entitled to. Both are written once per occurrence, not once per
     * heartbeat, so the audit stays readable.
     */
    private void recordNotables(LicenseService.Loaded loaded, LicenseActivation activation, Report report,
            Duration gap) {
        Duration expected = loaded.plan().periodType().heartbeatInterval();
        if (gap.compareTo(expected.multipliedBy(4)) > 0) {
            events.record(loaded.license().getId(), activation.getId(), LicenseEventType.HEARTBEAT_GAP, "install",
                    Map.of("gapHours", gap.toHours(), "expectedHours", expected.toHours()));
        }
        if ("READ_ONLY".equals(report.mode()) || "REVOKED".equals(report.mode())) {
            events.record(loaded.license().getId(), activation.getId(), LicenseEventType.READ_ONLY_ENTERED,
                    "install", Map.of("mode", report.mode()));
        } else if ("GRACE".equals(report.mode())) {
            events.record(loaded.license().getId(), activation.getId(), LicenseEventType.GRACE_ENTERED,
                    "install", Map.of("mode", report.mode()));
        }

        Entitlements entitlements = loaded.entitlements();
        Map<String, Object> over = new java.util.LinkedHashMap<>();
        addIfOver(over, "devices", report.deviceCount(), entitlements.maxDevices());
        addIfOver(over, "users", report.userCount(), entitlements.maxUsers());
        addIfOver(over, "organizations", report.orgCount(), entitlements.maxOrganizations());
        if (!over.isEmpty()) {
            events.record(loaded.license().getId(), activation.getId(), LicenseEventType.LIMIT_EXCEEDED, "install",
                    over);
        }
    }

    private static void addIfOver(Map<String, Object> into, String name, Integer reported, Integer limit) {
        if (reported != null && limit != null && reported > limit) {
            into.put(name, Map.of("reported", reported, "limit", limit));
        }
    }

    private static String shortFingerprint(String fingerprint) {
        return fingerprint.substring(0, 12) + "…";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
