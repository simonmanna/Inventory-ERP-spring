package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.util.List;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reservation crons (port of {@code kernel/workers/reservation-worker.ts}): no-show sweep every minute, RESERVED-flag
 * refresh every 5 minutes, per active organization. Disabled with {@code app.jobs.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class PosReservationWorker {

    private static final Logger log = LoggerFactory.getLogger("ReservationWorker");

    private final JdbcClient jdbc;
    private final PosReservationsService reservations;

    public PosReservationWorker(JdbcClient jdbc, PosReservationsService reservations) {
        this.jdbc = jdbc;
        this.reservations = reservations;
    }

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "reservation-no-show", lockAtMostFor = "PT5M")
    public void sweepNoShows() {
        int total = forEachOrg("No-show sweep", reservations::sweepNoShows);
        if (total > 0) {
            log.info("Reservations: {} marked no-show", total);
        }
    }

    @Scheduled(cron = "0 */5 * * * *")
    @SchedulerLock(name = "reservation-refresh", lockAtMostFor = "PT4M")
    public void refreshReserved() {
        int total = forEachOrg("Reservation refresh", reservations::refreshReservedFlags);
        if (total > 0) {
            log.info("Reservations: {} tables flipped to RESERVED", total);
        }
    }

    private int forEachOrg(String what, Supplier<Integer> body) {
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" WHERE status = 'active'").query(String.class).list();
        int total = 0;
        for (String orgId : orgs) {
            try {
                total += TenantContext.run(TenantStore.organization(orgId), body);
            } catch (RuntimeException err) {
                log.warn("{} for org {} failed: {}", what, orgId, err.toString());
            }
        }
        return total;
    }
}
