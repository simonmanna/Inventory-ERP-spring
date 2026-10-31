package com.inventoryerp.alerts.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.alerts.spi.AlertTypes.Definition;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * One scan of one organization. Every enabled type asks its sources what is true now, claims what has not alerted in
 * its period and delivers one digest per type. Each source and each type fails alone: a bad query in one module costs
 * that module's items for this scan, nothing else. Metrics: {@code alerts.raised} and {@code alerts.failed} (tag
 * {@code type}).
 */
@Service
public class AlertEngine {

    private static final Logger log = LoggerFactory.getLogger(AlertEngine.class);

    private final List<AlertSource> sources;
    private final AlertRuleService rules;
    private final AlertDeliveryService delivery;
    private final ObjectProvider<MeterRegistry> meters;

    public AlertEngine(List<AlertSource> sources, AlertRuleService rules, AlertDeliveryService delivery,
            ObjectProvider<MeterRegistry> meters) {
        this.sources = sources;
        this.rules = rules;
        this.delivery = delivery;
        this.meters = meters;
    }

    /** The job's entry: one organization's pass of one cadence in its tenant scope. */
    public Map<String, Object> runForOrg(String orgId, String cadence) {
        return TenantContext.run(TenantContext.TenantStore.organization(orgId), () -> {
            return run(cadence, null);
        });
    }

    /**
     * Scan the caller's organization: the types of {@code cadence}, or only {@code alertType}, or every type when both
     * are null. A disabled type is skipped either way. Returns how many alerts each type raised.
     */
    public Map<String, Object> run(String cadence, String alertType) {
        if (alertType != null && AlertTypes.definition(alertType) == null) {
            throw ApiException.notFound("Alert type not found");
        }
        String org = TenantContext.organizationId();
        LocalDate today = LocalDate.now(BusinessZone.get());
        Map<String, AlertRuleService.Rule> effective = rules.effectiveRules();
        Map<String, Object> raised = new LinkedHashMap<>();
        for (Definition def : AlertTypes.ALL) {
            AlertRuleService.Rule rule = effective.get(def.type());
            boolean wanted = alertType != null ? alertType.equals(def.type())
                    : cadence == null || cadence.equals(def.cadence());
            if (!wanted || !rule.enabled()) {
                continue;
            }
            try {
                raised.put(def.type(), runType(org, def, rule, today));
            } catch (RuntimeException e) {
                log.error("alert type {} failed for org {}: {}", def.type(), org, e.toString());
                count("alerts.failed", def.type(), 1);
                raised.put(def.type(), "failed");
            }
        }
        return Obj.of("asOf", today.toString(), "raised", raised);
    }

    private int runType(String org, Definition def, AlertRuleService.Rule rule, LocalDate today) {
        AlertScan scan = new AlertScan(org, today, rule.threshold());
        List<AlertItem> items = new ArrayList<>();
        for (AlertSource source : sources) {
            if (!def.type().equals(source.alertType())) {
                continue;
            }
            try {
                items.addAll(source.collect(scan));
            } catch (RuntimeException e) {
                log.error("alert source {} failed for org {}: {}", source.getClass().getName(), org, e.toString());
                count("alerts.failed", def.type(), 1);
            }
        }
        if (items.isEmpty()) {
            return 0;
        }
        AlertDeliveryService.Batch batch = delivery.claim(org, def, rule, today, items);
        if (batch.claimed() > 0) {
            delivery.deliver(org, def, rule, batch);
            count("alerts.raised", def.type(), batch.claimed());
        }
        return batch.claimed();
    }

    private void count(String name, String type, int amount) {
        MeterRegistry r = meters.getIfAvailable();
        if (r != null) {
            r.counter(name, "type", type).increment(amount);
        }
    }
}
