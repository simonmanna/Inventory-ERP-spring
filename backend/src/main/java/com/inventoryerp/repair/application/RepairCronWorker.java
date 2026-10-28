package com.inventoryerp.repair.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.repair.domain.RepairOrder;
import com.inventoryerp.repair.domain.RepairSchedule;
import com.inventoryerp.repair.domain.RepairStatusHistory;
import com.inventoryerp.repair.domain.RepairWarranty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RMMS housekeeping every 10 minutes (port of {@code repair-cron.worker.ts}, cron {@code repair-housekeeping}), per
 * organization: due preventive schedules generate a {@code preventive} repair order and roll forward; active
 * warranties past {@code coverageEnd} expire. Disabled with {@code app.jobs.enabled=false}.
 */
@Component
@ConditionalOnExpression("${app.modules.repair:false} and ${app.jobs.enabled:true}")
public class RepairCronWorker {

    private static final Logger log = LoggerFactory.getLogger("RepairCronWorker");

    private final RepairDb db;
    private final SequenceService seq;

    public RepairCronWorker(RepairDb db, SequenceService seq) {
        this.db = db;
        this.seq = seq;
    }

    @Scheduled(cron = "0 */10 * * * *")
    @SchedulerLock(name = "repair-housekeeping", lockAtMostFor = "PT9M")
    public void housekeeping() {
        List<String> orgs = db.jdbc().sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL")
                .query(String.class).list();
        for (String orgId : orgs) {
            try {
                TenantContext.run(TenantStore.organization(orgId), () -> runForOrg(orgId));
            } catch (RuntimeException err) {
                log.error("repair housekeeping failed for org {}: {}", orgId, err.toString());
            }
        }
    }

    /** One organization's run (inside its tenant context). */
    void runForOrg(String orgId) {
        Instant now = Instant.now();
        List<Map<String, Object>> due = db.where(RepairSchedule.class).eq("status", "active")
                .and("\"nextDueAt\" <= ?", PosDb.param(now)).list(null);
        for (Map<String, Object> schedule : due) {
            String repairNumber = seq.next("repair_order", "RPR-", 6);
            Object title = schedule.get("title");
            Map<String, Object> order = db.insert(RepairOrder.class, Obj.of("organizationId", orgId,
                    "repairNumber", repairNumber, "status", "received", "orderType", "preventive",
                    "itemType", RepairDb.nz(schedule.get("assetRef"), title),
                    "problemDescription", RepairDb.nz(schedule.get("taskTemplate"), "Preventive maintenance: " + title),
                    "notes", "Auto-generated from schedule " + schedule.get("id")));
            db.insert(RepairStatusHistory.class, Obj.of("organizationId", orgId, "repairOrderId", order.get("id"),
                    "toStatus", "received", "action", "preventive",
                    "note", "Auto-generated preventive order from schedule " + schedule.get("id")));
            db.update(RepairSchedule.class, (String) schedule.get("id"), Obj.of("lastRunAt", now,
                    "nextDueAt", RepairContractService.addDays(now, ((Number) schedule.get("intervalDays")).intValue())));
            db.notify(orgId, "Preventive order " + repairNumber, RepairDb.s(title),
                    Obj.of("repairOrderId", order.get("id"), "repairNumber", repairNumber,
                            "scheduleId", schedule.get("id")));
        }

        Map<String, Object> expired = db.where(RepairWarranty.class).eq("status", "active")
                .and("\"coverageEnd\" <= ?", PosDb.param(now)).updateMany(Obj.of("status", "expired"));
        long count = ((Number) expired.get("count")).longValue();
        if (count > 0) {
            log.info("org {}: expired {} warranties", orgId, count);
        }
    }
}
