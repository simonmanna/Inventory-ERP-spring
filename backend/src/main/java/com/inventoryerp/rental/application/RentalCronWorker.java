package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalDeposit;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rental housekeeping every minute (port of {@code rental-cron.worker.ts}): expire held reservations past their
 * hold window and auto-forfeit the remaining deposit of agreements left {@code returned} (unsettled) for 14 days.
 * Each org runs in its own tenant scope; one failing org or row never stops the rest.
 */
@Component
@ConditionalOnExpression("${app.jobs.enabled:true} and ${app.modules.rental:false}")
public class RentalCronWorker {

    private static final Logger log = LoggerFactory.getLogger(RentalCronWorker.class);

    private final PosDb db;
    private final RentalReservationService reservations;
    private final RentalDepositService deposit;

    public RentalCronWorker(RentalRows rows, RentalReservationService reservations, RentalDepositService deposit) {
        this.db = rows.db();
        this.reservations = reservations;
        this.deposit = deposit;
    }

    @Scheduled(cron = "0 * * * * *")
    @SchedulerLock(name = "rental-housekeeping", lockAtMostFor = "PT5M")
    public void housekeeping() {
        List<String> orgs = db.jdbc().sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL")
                .query(String.class).list();
        for (String orgId : orgs) {
            try {
                runForOrg(orgId);
            } catch (RuntimeException err) {
                log.error("rental housekeeping failed for org {}: {}", orgId, err.toString());
            }
        }
    }

    /** One org's pass (package-visible for tests). */
    void runForOrg(String orgId) {
        TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
            List<String> expired = db.jdbc().sql("SELECT id FROM \"RentalReservation\" WHERE \"organizationId\" = ? AND"
                    + " status = 'held' AND \"expiresAt\" < ?").params(orgId, SqlRows.ts(Instant.now()))
                    .query(String.class).list();
            for (String id : expired) {
                try {
                    reservations.cancel(id);
                } catch (RuntimeException err) {
                    log.warn("reservation {} expiry failed: {}", id, err.toString());
                }
            }
            if (!expired.isEmpty()) {
                log.info("expired {} held reservation(s) in org {}", expired.size(), orgId);
            }

            List<String> abandoned = db.jdbc().sql("SELECT id FROM \"RentalAgreement\" WHERE \"organizationId\" = ? AND"
                    + " status = 'returned' AND \"returnedAt\" < ?")
                    .params(orgId, SqlRows.ts(Instant.now().minus(Duration.ofDays(14)))).query(String.class).list();
            for (String agreementId : abandoned) {
                try {
                    Map<String, Object> dep = db.first(RentalDeposit.class, "\"organizationId\" = ? AND"
                            + " \"agreementId\" = ?", orgId, agreementId);
                    if (dep == null) {
                        continue;
                    }
                    double available = RentalDepositService.available(dep);
                    if (available > 0) {
                        Map<String, Object> dto = new LinkedHashMap<>();
                        dto.put("amount", available);
                        dto.put("note", "Auto-forfeit after 14 days");
                        deposit.forfeit(agreementId, dto);
                    }
                } catch (RuntimeException err) {
                    log.warn("deposit forfeit for {} failed: {}", agreementId, err.toString());
                }
            }
            if (!abandoned.isEmpty()) {
                log.info("forfeited deposits on {} abandoned return(s) in org {}", abandoned.size(), orgId);
            }
        });
    }
}
