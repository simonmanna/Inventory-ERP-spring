package com.inventoryerp.license.application;

import com.inventoryerp.license.crypto.LicenseKeyCodec;
import com.inventoryerp.license.domain.ActivationStatus;
import com.inventoryerp.license.domain.Customer;
import com.inventoryerp.license.domain.CustomerRepository;
import com.inventoryerp.license.domain.License;
import com.inventoryerp.license.domain.LicenseActivation;
import com.inventoryerp.license.domain.LicenseActivationRepository;
import com.inventoryerp.license.domain.LicenseEventType;
import com.inventoryerp.license.domain.LicensePeriod;
import com.inventoryerp.license.domain.LicenseRepository;
import com.inventoryerp.license.domain.LicenseStatus;
import com.inventoryerp.license.domain.Plan;
import com.inventoryerp.license.domain.PlanRepository;
import com.inventoryerp.license.shared.ApiException;
import com.inventoryerp.license.shared.Json;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The commercial lifecycle of a licence: sell it, issue it, renew it, stop it.
 *
 * <p>Every method that changes a licence writes a {@link LicenseEventType} row in the same
 * transaction, because this is the history a customer's lockout is explained from. Nothing here
 * mints tokens — that belongs to {@link ActivationService} (online) and
 * {@link #offlineToken} (air-gapped), both of which go through {@link TokenIssuer}.
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    /** A licence with the plan and customer it needs to be understood. */
    public record Loaded(License license, Plan plan, Customer customer) {

        public Entitlements entitlements() {
            return Entitlements.resolve(license, plan);
        }
    }

    private final LicenseRepository licenses;
    private final PlanRepository plans;
    private final CustomerRepository customers;
    private final LicenseActivationRepository activations;
    private final LicenseEvents events;
    private final TokenIssuer tokens;

    public LicenseService(LicenseRepository licenses, PlanRepository plans, CustomerRepository customers,
            LicenseActivationRepository activations, LicenseEvents events, TokenIssuer tokens) {
        this.licenses = licenses;
        this.plans = plans;
        this.customers = customers;
        this.activations = activations;
        this.events = events;
        this.tokens = tokens;
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    public Loaded load(String id) {
        License license = licenses.findById(id)
                .orElseThrow(() -> ApiException.notFound("No licence with id '" + id + "'"));
        return hydrate(license);
    }

    @Transactional(readOnly = true)
    public Loaded loadByKey(String rawKey) {
        String key = LicenseKeyCodec.normalize(rawKey);
        if (key == null || !LicenseKeyCodec.isValid(key)) {
            // A malformed key is not a lookup failure; say the same thing as an unknown one so the
            // route cannot be used to tell which keys exist.
            throw ApiException.notFound(LicenseMessages.KEY_NOT_RECOGNISED);
        }
        License license = licenses.findByKey(key)
                .orElseThrow(() -> ApiException.notFound(LicenseMessages.KEY_NOT_RECOGNISED));
        return hydrate(license);
    }

    @Transactional(readOnly = true)
    public Page<License> search(String status, String customerId, String query, int page, int pageSize) {
        return licenses.search(blankToNull(status), blankToNull(customerId), blankToNull(query),
                PageRequest.of(Math.max(0, page - 1), Math.min(200, Math.max(1, pageSize))));
    }

    @Transactional(readOnly = true)
    public List<LicenseActivation> activationsOf(String licenseId) {
        return activations.findByLicenseIdOrderByFirstSeenAtAsc(licenseId);
    }

    // ---- lifecycle ----

    /**
     * Record a sale. A licence is created {@code pending} and carries no dates until it is issued,
     * so a key can be generated and sent on before the term starts.
     */
    @Transactional
    public Loaded create(String customerId, String planCode, Integer graceDaysOverride, Integer instanceLimit,
            Integer maxDevices, Integer maxUsers, Integer maxOrganizations, Set<String> featureOverrides,
            boolean autoRenew, String notes, Instant startNow, String actor) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> ApiException.badRequest("No customer with id '" + customerId + "'"));
        Plan plan = planByCode(planCode);

        License license = new License();
        license.setKey(uniqueKey());
        license.setCustomerId(customer.getId());
        license.setPlanId(plan.getId());
        license.setStatusType(LicenseStatus.PENDING);
        license.setGraceDays(graceDaysOverride != null ? graceDaysOverride : plan.getGraceDays());
        license.setInstanceLimit(instanceLimit);
        license.setMaxDevices(maxDevices);
        license.setMaxUsers(maxUsers);
        license.setMaxOrganizations(maxOrganizations);
        license.setFeatureOverrides(featureOverrides == null || featureOverrides.isEmpty()
                ? null : Json.writeStringCollection(featureOverrides));
        license.setAutoRenew(autoRenew);
        license.setNotes(notes);
        license.setCreatedBy(actor);
        licenses.saveAndFlush(license);

        events.record(license.getId(), LicenseEventType.ISSUED, actor, Map.of(
                "stage", "created", "plan", plan.getCode(), "customer", customer.getName()));

        Loaded loaded = new Loaded(license, plan, customer);
        warnOnUnknownFeatures(loaded);
        return startNow == null ? loaded : issue(license.getId(), startNow, actor);
    }

    /** Start the term. Idempotent in effect: issuing an already-active licence is a conflict. */
    @Transactional
    public Loaded issue(String id, Instant startsAt, String actor) {
        Loaded loaded = load(id);
        License license = loaded.license();
        if (license.statusType() == LicenseStatus.REVOKED) {
            throw ApiException.conflict("A revoked licence cannot be issued again. Create a new one.");
        }
        if (license.statusType() == LicenseStatus.ACTIVE) {
            throw ApiException.conflict("This licence is already active. Use renew to extend it.");
        }
        Instant start = startsAt == null ? Instant.now() : startsAt;
        LicensePeriod period = loaded.plan().periodType();

        license.setStartsAt(start);
        license.setExpiresAt(LicensePeriods.expiryOf(start, period, loaded.plan().getPeriodCount()));
        license.setIssuedAt(Instant.now());
        license.setStatusType(LicenseStatus.ACTIVE);
        licenses.save(license);

        events.record(license.getId(), LicenseEventType.ISSUED, actor, detail(license, Map.of(
                "stage", "issued", "period", period.name(), "periodCount", loaded.plan().getPeriodCount())));
        return loaded;
    }

    /**
     * Extend by one or more periods. The new term starts at the later of now and the current expiry
     * (see {@link LicensePeriods#renewalStart}), so renewing early never costs the customer days.
     * A lapsed or suspended licence comes back to {@code active}.
     */
    @Transactional
    public Loaded renew(String id, Integer periods, String actor) {
        Loaded loaded = load(id);
        License license = loaded.license();
        if (license.statusType() == LicenseStatus.REVOKED) {
            throw ApiException.conflict("A revoked licence cannot be renewed. Create a new one.");
        }
        Plan plan = loaded.plan();
        LicensePeriod period = plan.periodType();
        if (period.isPerpetual()) {
            throw ApiException.conflict("A perpetual licence never expires and cannot be renewed.");
        }
        if (license.getStartsAt() == null) {
            return issue(id, Instant.now(), actor);
        }

        int count = (periods == null || periods < 1 ? 1 : periods) * plan.getPeriodCount();
        Instant previousExpiry = license.getExpiresAt();
        Instant from = LicensePeriods.renewalStart(Instant.now(), previousExpiry);
        license.setExpiresAt(LicensePeriods.expiryOf(from, period, count));
        license.setStatusType(LicenseStatus.ACTIVE);
        licenses.save(license);

        events.record(license.getId(), LicenseEventType.RENEWED, actor, detail(license, Map.of(
                "periods", count,
                "previousExpiresAt", String.valueOf(previousExpiry),
                "renewedFrom", String.valueOf(from))));
        return loaded;
    }

    /** Stop the licence without consuming its term (non-payment). Reversible by {@link #resume}. */
    @Transactional
    public Loaded suspend(String id, String reason, String actor) {
        Loaded loaded = load(id);
        License license = loaded.license();
        if (license.statusType() == LicenseStatus.REVOKED) {
            throw ApiException.conflict("This licence is already revoked.");
        }
        license.setStatusType(LicenseStatus.SUSPENDED);
        licenses.save(license);
        events.record(license.getId(), LicenseEventType.SUSPENDED, actor, Map.of("reason", nullToEmpty(reason)));
        return loaded;
    }

    @Transactional
    public Loaded resume(String id, String actor) {
        Loaded loaded = load(id);
        License license = loaded.license();
        if (license.statusType() != LicenseStatus.SUSPENDED) {
            throw ApiException.conflict("Only a suspended licence can be resumed.");
        }
        // Whether it comes back as active or expired depends on whether its term ran out while it
        // was stopped — suspending does not extend a term.
        boolean lapsed = license.getExpiresAt() != null && license.getExpiresAt().isBefore(Instant.now());
        license.setStatusType(lapsed ? LicenseStatus.EXPIRED : LicenseStatus.ACTIVE);
        licenses.save(license);
        events.record(license.getId(), LicenseEventType.RESUMED, actor,
                Map.of("resumedAs", license.getStatus()));
        return loaded;
    }

    /**
     * End the licence for good. Every activation is released and installs go read-only with no
     * grace at their next check-in — or at once, if they can reach the server.
     */
    @Transactional
    public Loaded revoke(String id, String reason, String actor) {
        Loaded loaded = load(id);
        License license = loaded.license();
        license.setStatusType(LicenseStatus.REVOKED);
        licenses.save(license);

        Instant now = Instant.now();
        for (LicenseActivation activation : activations.findByLicenseIdOrderByFirstSeenAtAsc(id)) {
            if (activation.isActive()) {
                activation.setStatus(ActivationStatus.DEACTIVATED.value());
                activation.setDeactivatedAt(now);
                activations.save(activation);
            }
        }
        events.record(license.getId(), LicenseEventType.REVOKED, actor, Map.of("reason", nullToEmpty(reason)));
        log.warn("Licence {} revoked by {}", LicenseKeyCodec.mask(license.getKey()), actor);
        return loaded;
    }

    /** Edit the negotiated terms. Dates and status are moved by the lifecycle methods only. */
    @Transactional
    public Loaded update(String id, String planCode, Integer graceDays, Integer instanceLimit, Integer maxDevices,
            Integer maxUsers, Integer maxOrganizations, Set<String> featureOverrides, boolean featuresGiven,
            Boolean autoRenew, String notes, String actor) {
        Loaded loaded = load(id);
        License license = loaded.license();
        Plan plan = loaded.plan();
        Map<String, Object> changed = new LinkedHashMap<>();

        if (planCode != null && !planCode.equals(plan.getCode())) {
            Plan next = planByCode(planCode);
            if (!next.periodType().equals(plan.periodType())) {
                // Changing the shape of the term mid-licence would silently re-date it. Make the
                // operator issue a new licence instead of guessing what they meant.
                throw ApiException.conflict("A licence cannot move between "
                        + plan.periodType().name() + " and " + next.periodType().name()
                        + " plans. Issue a new licence instead.");
            }
            license.setPlanId(next.getId());
            changed.put("plan", next.getCode());
            plan = next;
        }
        if (graceDays != null) {
            license.setGraceDays(graceDays);
            changed.put("graceDays", graceDays);
        }
        if (instanceLimit != null) {
            license.setInstanceLimit(instanceLimit);
            changed.put("instanceLimit", instanceLimit);
        }
        if (maxDevices != null) {
            license.setMaxDevices(maxDevices);
            changed.put("maxDevices", maxDevices);
        }
        if (maxUsers != null) {
            license.setMaxUsers(maxUsers);
            changed.put("maxUsers", maxUsers);
        }
        if (maxOrganizations != null) {
            license.setMaxOrganizations(maxOrganizations);
            changed.put("maxOrganizations", maxOrganizations);
        }
        if (featuresGiven) {
            // An empty set means "inherit the plan again", not "grant nothing".
            license.setFeatureOverrides(featureOverrides == null || featureOverrides.isEmpty()
                    ? null : Json.writeStringCollection(featureOverrides));
            changed.put("features", featureOverrides == null ? List.of() : List.copyOf(featureOverrides));
        }
        if (autoRenew != null) {
            license.setAutoRenew(autoRenew);
            changed.put("autoRenew", autoRenew);
        }
        if (notes != null) {
            license.setNotes(notes);
        }
        licenses.save(license);

        Loaded updated = new Loaded(license, plan, loaded.customer());
        if (!changed.isEmpty()) {
            events.record(license.getId(), "updated", actor, changed);
            warnOnUnknownFeatures(updated);
        }
        return updated;
    }

    /**
     * A licence file for an install that cannot reach the server. The operator reads the install's
     * fingerprint off its licence page and pastes it here; the token comes back bound to it.
     *
     * <p>It consumes an instance slot exactly as an online activation does, and it carries no
     * {@code refreshAfter}: the only way to extend an air-gapped install is another file.
     */
    @Transactional
    public OfflineToken offlineToken(String id, String fingerprint, String instanceName, String actor) {
        Loaded loaded = load(id);
        License license = loaded.license();
        if (!license.statusType().canActivate()) {
            throw ApiException.conflict(LicenseMessages.forStatus(license.statusType()));
        }
        String cleanFingerprint = requireFingerprint(fingerprint);

        LicenseActivation activation = activations
                .findByLicenseIdAndFingerprint(license.getId(), cleanFingerprint)
                .orElseGet(LicenseActivation::new);
        boolean fresh = activation.getId() == null;
        if (fresh) {
            assertSlotAvailable(loaded, cleanFingerprint);
            activation.setLicenseId(license.getId());
            activation.setFingerprint(cleanFingerprint);
        }
        activation.setInstanceName(instanceName);
        activation.setStatus(ActivationStatus.ACTIVE.value());
        activation.setDeactivatedAt(null);
        activation.setLastSeenAt(Instant.now());
        activations.saveAndFlush(activation);

        TokenIssuer.Minted minted = tokens.mint(license, loaded.plan(), activation,
                new TokenIssuer.Customer(loaded.customer().getId(), loaded.customer().getName()),
                true, Instant.now());

        events.record(license.getId(), activation.getId(), LicenseEventType.OFFLINE_TOKEN_ISSUED, actor,
                Map.of("fingerprint", cleanFingerprint, "instanceName", nullToEmpty(instanceName)));
        return new OfflineToken(minted.token(), minted.kid(), minted.expiresAt(), license.getKey(),
                loaded.customer().getName());
    }

    /** A downloadable {@code .lic} payload. */
    public record OfflineToken(String token, String kid, Instant expiresAt, String licenseKey, String customer) {}

    /**
     * Instance slots. Re-activating a fingerprint already on the licence is free; a new one is
     * refused once the limit is reached, so a key cannot quietly spread across installs.
     */
    void assertSlotAvailable(Loaded loaded, String fingerprint) {
        int limit = loaded.entitlements().instanceLimit();
        long used = activations.countByLicenseIdAndStatus(loaded.license().getId(), ActivationStatus.ACTIVE.value());
        if (used >= limit) {
            events.record(loaded.license().getId(), LicenseEventType.ACTIVATION_REJECTED, "install", Map.of(
                    "reason", "instance_limit", "limit", limit, "fingerprint", fingerprint));
            throw ApiException.conflict(LicenseMessages.ALREADY_ACTIVATED);
        }
    }

    /** Bookkeeping sweep: an active licence whose grace has run out becomes {@code expired}. */
    @Transactional
    public int markLapsedExpired(Instant now) {
        int marked = 0;
        for (License license : licenses.activeButPastExpiry(now)) {
            Instant graceUntil = license.graceUntil();
            if (graceUntil != null && graceUntil.isAfter(now)) {
                continue; // still inside grace — the install is warning, not blocked
            }
            license.setStatusType(LicenseStatus.EXPIRED);
            licenses.save(license);
            events.record(license.getId(), LicenseEventType.EXPIRED, "system",
                    Map.of("expiresAt", String.valueOf(license.getExpiresAt())));
            marked++;
        }
        return marked;
    }

    Plan planByCode(String code) {
        return plans.findByCode(code)
                .orElseThrow(() -> ApiException.badRequest("No plan with code '" + code + "'"));
    }

    Loaded hydrate(License license) {
        Plan plan = plans.findById(license.getPlanId())
                .orElseThrow(() -> new IllegalStateException(
                        "Licence " + license.getId() + " points at missing plan " + license.getPlanId()));
        Customer customer = customers.findById(license.getCustomerId())
                .orElseThrow(() -> new IllegalStateException(
                        "Licence " + license.getId() + " points at missing customer " + license.getCustomerId()));
        return new Loaded(license, plan, customer);
    }

    static String requireFingerprint(String fingerprint) {
        String clean = fingerprint == null ? "" : fingerprint.trim().toLowerCase(java.util.Locale.ROOT);
        if (!clean.matches("[0-9a-f]{64}")) {
            throw ApiException.badRequest("An instance fingerprint is 64 hexadecimal characters.");
        }
        return clean;
    }

    private String uniqueKey() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String key = LicenseKeyCodec.generate();
            if (licenses.findByKey(key).isEmpty()) {
                return key;
            }
        }
        throw new IllegalStateException("Could not generate an unused licence key in 10 attempts");
    }

    private void warnOnUnknownFeatures(Loaded loaded) {
        Set<String> unknown = loaded.entitlements().unknownFeatures();
        if (!unknown.isEmpty()) {
            log.warn("Licence {} grants feature keys no ERP release understands: {}",
                    LicenseKeyCodec.mask(loaded.license().getKey()), unknown);
        }
    }

    private static Map<String, Object> detail(License license, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>(extra);
        out.put("startsAt", String.valueOf(license.getStartsAt()));
        out.put("expiresAt", String.valueOf(license.getExpiresAt()));
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
