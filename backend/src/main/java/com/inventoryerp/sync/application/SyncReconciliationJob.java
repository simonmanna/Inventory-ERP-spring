package com.inventoryerp.sync.application;

import java.util.concurrent.atomic.AtomicBoolean;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-S083 C2: the post-restore reconciliation runs a few minutes after every start (a restore means a restart),
 * then every {@code erp.sync.reconciliation.interval} (default daily). Not on a branch node (HQ is the system of
 * record), and not with {@code app.jobs.enabled=false}.
 */
@Component
@ConditionalOnExpression("'${app.jobs.enabled:true}' == 'true' and '${erp.sync.mode:hq}' != 'branch'")
public class SyncReconciliationJob {

    private final SyncReconciliationService reconciliation;
    private final AtomicBoolean first = new AtomicBoolean(true);

    public SyncReconciliationJob(SyncReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(initialDelayString = "${erp.sync.reconciliation.initial-delay:PT3M}",
            fixedDelayString = "${erp.sync.reconciliation.interval:P1D}")
    @SchedulerLock(name = "sync-reconciliation", lockAtMostFor = "PT1H")
    public void tick() {
        reconciliation.runAll(first.getAndSet(false) ? "startup" : "scheduled");
    }
}
