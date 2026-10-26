package com.inventoryerp.license.application;

import com.inventoryerp.license.crypto.Jws;
import com.inventoryerp.license.domain.License;
import com.inventoryerp.license.domain.LicenseActivation;
import com.inventoryerp.license.domain.LicensePeriod;
import com.inventoryerp.license.domain.Plan;
import com.inventoryerp.license.domain.SigningKey;
import com.inventoryerp.license.shared.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Mints the signed licence an install runs on. The claim layout is fixed by
 * docs/spec/licensing.md §2 and read by {@code LicenseStateMachine} on the client.
 *
 * <p>Two things here are easy to get wrong and are deliberate:
 *
 * <ul>
 *   <li><b>{@code exp} is the end of grace, not the end of the term.</b> Verification has to keep
 *       succeeding while a lapsed shop is still trading on grace; a signature that stops verifying
 *       looks exactly like tampering and would lock the shop out instead of warning it. The client
 *       therefore derives its mode from {@code lic.expiresAt} and {@code lic.graceUntil}.
 *   <li><b>{@code lic.serverTime} is the client's clock anchor.</b> The install keeps the highest
 *       value it has ever seen; a clock rolled back behind that is treated as tampering.
 * </ul>
 */
@Service
public class TokenIssuer {

    private final SigningKeyService signingKeys;
    private final LicenseProperties properties;

    public TokenIssuer(SigningKeyService signingKeys, LicenseProperties properties) {
        this.signingKeys = signingKeys;
        this.properties = properties;
    }

    /** A freshly signed token, and the moment the install should come back for another. */
    public record Minted(String token, String kid, Instant refreshAfter, Instant expiresAt) {}

    public Minted mint(License license, Plan plan, LicenseActivation activation, Customer customer,
            boolean offline, Instant now) {
        Entitlements entitlements = Entitlements.resolve(license, plan);
        LicensePeriod period = plan.periodType();
        Instant graceUntil = LicensePeriods.graceUntil(license.getExpiresAt(), entitlements.graceDays());
        Instant tokenExpiry = LicensePeriods.tokenExpiry(license.getExpiresAt(), entitlements.graceDays(), now);
        // An offline token has no way to be refreshed: the install cannot reach us by definition.
        Instant refreshAfter = offline ? null : now.plus(properties.getActivation().getRefreshAfter());

        Map<String, Object> lic = new LinkedHashMap<>();
        lic.put("customer", customer.name());
        lic.put("customerId", customer.id());
        lic.put("plan", plan.getCode());
        lic.put("planName", plan.getName());
        lic.put("period", period.name());
        lic.put("periodCount", plan.getPeriodCount());
        lic.put("status", license.getStatus());
        lic.put("trial", Boolean.TRUE.equals(plan.getIsTrial()));
        lic.put("startsAt", iso(license.getStartsAt()));
        lic.put("expiresAt", iso(license.getExpiresAt()));
        lic.put("graceUntil", iso(graceUntil));
        lic.put("graceDays", entitlements.graceDays());
        lic.put("heartbeatSeconds", period.heartbeatInterval().toSeconds());
        if (refreshAfter != null) {
            lic.put("refreshAfter", iso(refreshAfter));
        }
        lic.put("limits", entitlements.limitsClaim());
        lic.put("features", new ArrayList<>(entitlements.features()));
        lic.put("offline", offline);
        lic.put("serverTime", iso(now));

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.getIssuer());
        claims.put("sub", license.getKey());
        claims.put("aud", activation.getFingerprint());
        claims.put("jti", activation.getId());
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.getEpochSecond());
        claims.put("exp", tokenExpiry.getEpochSecond());
        claims.put("lic", lic);

        SigningKey key = signingKeys.current();
        String token = Jws.sign(signingKeys.privateKeyOf(key), key.getKid(), Json.write(claims));
        return new Minted(token, key.getKid(), refreshAfter, tokenExpiry);
    }

    /** Only the customer's name and id reach a token; nothing else about them needs to. */
    public record Customer(String id, String name) {}

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
