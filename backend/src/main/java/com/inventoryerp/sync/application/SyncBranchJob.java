package com.inventoryerp.sync.application;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ADR-S083 O7: the branch node's relay loop (only with {@code erp.sync.mode=branch}; independent of
 * {@code app.jobs.enabled}, which a branch runs with {@code false}): outcomes and devices from HQ, pending ops to HQ,
 * the master-data mirror every {@code erp.sync.branch.pull-interval}.
 */
@Component
@ConditionalOnProperty(name = "erp.sync.mode", havingValue = "branch")
public class SyncBranchJob {

    private final SyncBranchRelay relay;

    public SyncBranchJob(SyncBranchRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${erp.sync.branch.interval:PT10S}",
            initialDelayString = "${erp.sync.branch.initial-delay:PT5S}")
    @SchedulerLock(name = "sync-branch-relay", lockAtMostFor = "PT10M")
    public void tick() {
        relay.cycle();
    }
}
