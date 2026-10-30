package com.inventoryerp.procurement.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily purchasing housekeeping (ADR-PROCUREMENT-001): activates approved policy versions whose effective date has
 * arrived, expires purchase agreements past their end date and announces supplier certificates that expire within 30 days ({@code supplier.certificate_expiring}, once
 * per certificate per day) so communication rules can e-mail the buyer.
 */
@Component
public class ProcurementPolicyJob {

    private static final Logger log = LoggerFactory.getLogger(ProcurementPolicyJob.class);

    private final JdbcClient jdbc;
    private final ProcurementPolicyService policies;
    private final EventBus events;
    private final PurchaseAgreementService agreements;
    private final boolean enabled;

    public ProcurementPolicyJob(JdbcClient jdbc, ProcurementPolicyService policies, EventBus events,
            PurchaseAgreementService agreements, @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.agreements = agreements;
        this.enabled = enabled;
        this.jdbc = jdbc;
        this.policies = policies;
        this.events = events;
    }

    @Scheduled(cron = "${app.procurement.daily-cron:0 15 4 * * *}")
    @SchedulerLock(name = "procurement-daily", lockAtMostFor = "PT30M")
    public void run() {
        if (!enabled) {
            return;
        }
        List<String> orgs = jdbc.sql("SELECT id FROM \"Organization\" WHERE \"deletedAt\" IS NULL").query(String.class)
                .list();
        for (String orgId : orgs) {
            try {
                runForOrg(orgId);
            } catch (RuntimeException err) {
                log.error("Procurement daily job failed for org {}", orgId, err);
            }
        }
    }

    /** One organisation (tests call this directly). */
    public Map<String, Object> runForOrg(String orgId) {
        return TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
            int activated = policies.activateDue();
            int expired = agreements.expireDue();
            List<Map<String, Object>> expiring = SqlRows.list(jdbc.sql("SELECT id, \"partnerId\", type, \"validTo\" FROM"
                    + " \"SupplierCertificate\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"validTo\""
                    + " BETWEEN CURRENT_DATE AND CURRENT_DATE + 30").param(orgId));
            for (Map<String, Object> c : expiring) {
                events.publish("supplier.certificate_expiring", Obj.of("organizationId", orgId, "partnerId",
                        c.get("partnerId"), "certificateId", c.get("id"), "type", c.get("type"), "validTo",
                        String.valueOf(c.get("validTo"))));
            }
            return Obj.of("policiesActivated", activated, "agreementsExpired", expired, "certificatesExpiring",
                    expiring.size());
        });
    }
}
