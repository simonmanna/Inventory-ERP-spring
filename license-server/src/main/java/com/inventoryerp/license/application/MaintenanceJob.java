package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.AdminRefreshTokenRepository;
import com.inventoryerp.license.domain.LicenseHeartbeatRepository;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly housekeeping: close the books on lapsed licences and throw away telemetry we no longer
 * need.
 *
 * <p>Marking a licence {@code expired} is bookkeeping, not enforcement — the install worked that out
 * from its own token days ago. It matters because an expired licence is what the renewal lists and
 * the dashboard count on.
 */
@Component
public class MaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJob.class);

    private final LicenseService licenses;
    private final LicenseHeartbeatRepository heartbeats;
    private final AdminRefreshTokenRepository refreshTokens;
    private final LicenseProperties properties;

    public MaintenanceJob(LicenseService licenses, LicenseHeartbeatRepository heartbeats,
            AdminRefreshTokenRepository refreshTokens, LicenseProperties properties) {
        this.licenses = licenses;
        this.heartbeats = heartbeats;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
    }

    @Scheduled(cron = "0 17 3 * * *")
    @SchedulerLock(name = "license-maintenance", lockAtMostFor = "PT1H")
    @Transactional
    public void run() {
        Instant now = Instant.now();

        int expired = licenses.markLapsedExpired(now);
        if (expired > 0) {
            log.info("Marked {} licence(s) expired", expired);
        }

        int beats = heartbeats.deleteOlderThan(
                now.minus(Duration.ofDays(Math.max(1, properties.getRetention().getHeartbeatDays()))));
        if (beats > 0) {
            log.info("Removed {} heartbeat row(s) past retention", beats);
        }

        // Spent refresh tokens are kept a week past expiry so a replay still finds the row and
        // triggers the "revoke the whole family" path rather than looking like an unknown token.
        int tokens = refreshTokens.deleteExpired(now.minus(Duration.ofDays(7)));
        if (tokens > 0) {
            log.debug("Removed {} expired admin refresh token(s)", tokens);
        }
    }
}
