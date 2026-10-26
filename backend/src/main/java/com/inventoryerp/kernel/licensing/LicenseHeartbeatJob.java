package com.inventoryerp.kernel.licensing;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Checks in with the licence authority on a schedule, and once shortly after boot.
 *
 * <p>Three decisions worth stating:
 *
 * <ul>
 *   <li><b>The interval comes from the licence, not from configuration.</b> A daily plan checks in
 *       hourly so a day's purchase is noticed; a yearly one once a day. The token carries
 *       {@code heartbeatSeconds} and the job honours it.
 *   <li><b>Jittered by up to 10%.</b> Thousands of installs booted by the same overnight update
 *       would otherwise arrive together.
 *   <li><b>Failure is not an error.</b> An install is expected to be offline sometimes; that is the
 *       whole reason licences verify locally. A failed check-in is recorded on the licence row and
 *       the install carries on with what it holds.
 * </ul>
 *
 * <p>Takes a ShedLock so a multi-instance deployment checks in once, not once per node.
 */
@Component
public class LicenseHeartbeatJob {

    private static final Logger log = LoggerFactory.getLogger(LicenseHeartbeatJob.class);

    /** How long after boot to make the first attempt — late enough not to slow the start-up. */
    private static final Duration AFTER_BOOT = Duration.ofSeconds(45);

    private final LicenseService licenses;
    private final LicenseEntitlements entitlements;
    private final LicenseProperties properties;

    private volatile Instant nextAttempt = Instant.EPOCH;

    public LicenseHeartbeatJob(LicenseService licenses, LicenseEntitlements entitlements,
            LicenseProperties properties) {
        this.licenses = licenses;
        this.entitlements = entitlements;
        this.properties = properties;
    }

    /**
     * Pick the licence up at boot: apply what it grants, and schedule the first check-in a little
     * way out rather than on the start-up path.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        LicenseState state = licenses.current();
        log.info("Licence mode {} ({}); {}", state.mode(), state.reason(),
                state.expiresAt() == null ? "no expiry" : "expires " + state.expiresAt());
        try {
            entitlements.reconcile(state);
        } catch (RuntimeException e) {
            log.warn("Could not apply licence entitlements at start-up: {}", e.getMessage());
        }
        nextAttempt = Instant.now().plus(AFTER_BOOT);
    }

    /**
     * Runs every five minutes but only acts when the licence's own interval has elapsed — which is
     * how one fixed cron serves plans that want hourly and plans that want daily.
     */
    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "license-heartbeat", lockAtMostFor = "PT10M")
    public void tick() {
        if (!properties.heartbeat() || !properties.hasServer()) {
            return;
        }
        Instant now = Instant.now();
        if (now.isBefore(nextAttempt)) {
            return;
        }
        LicenseState before = licenses.current();
        if (!before.licensed()) {
            // Nothing to check in about: a trial install has never spoken to the authority.
            nextAttempt = now.plus(Duration.ofHours(1));
            return;
        }

        LicenseState after = licenses.refresh(false);
        nextAttempt = now.plus(jittered(interval(after)));

        if (after.mode() != before.mode()) {
            log.info("Licence mode changed at check-in: {} -> {} ({})", before.mode(), after.mode(),
                    after.reason());
        }
        try {
            entitlements.reconcile(after);
        } catch (RuntimeException e) {
            log.warn("Could not apply licence entitlements after check-in: {}", e.getMessage());
        }
    }

    private static Duration interval(LicenseState state) {
        if (state.claims() == null) {
            return Duration.ofHours(6);
        }
        long seconds = state.claims().heartbeatSeconds();
        // Floor of five minutes: a bad claim must not turn an install into a hammer.
        return Duration.ofSeconds(Math.max(300, seconds));
    }

    /** ±10%, so a fleet updated together does not arrive together. */
    private static Duration jittered(Duration base) {
        long spread = Math.max(1, base.toSeconds() / 10);
        return base.plusSeconds(ThreadLocalRandom.current().nextLong(-spread, spread + 1));
    }
}
