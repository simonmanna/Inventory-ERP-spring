package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.lossprevention.application.LpCommands.InsightsQuery;
import com.inventoryerp.lossprevention.application.LpCommands.InsightsRecomputeInput;
import com.inventoryerp.lossprevention.application.LpInsightsReportService;
import com.inventoryerp.lossprevention.application.LpInsightsService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /loss-prevention/insights} (LP6a and LP6b, ADR-S077 §10): operations metrics, from the summaries the insights
 * job computes out of the event ledger; the camera ones (footfall, queues, occupancy) from zone counts. Licence
 * feature {@code lpInsights}.
 */
@RestController
@RequestMapping("/loss-prevention/insights")
@RequiresModule("loss-prevention")
@RequiresFeature(LicenseFeatures.LP_INSIGHTS)
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionInsightsController {

    private final LpInsightsReportService reports;
    private final LpInsightsService insights;

    public LossPreventionInsightsController(LpInsightsReportService reports, LpInsightsService insights) {
        this.reports = reports;
        this.insights = insights;
    }

    @GetMapping("/summary")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> summary(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.summary(new InsightsQuery(from, to, branchId, null));
    }

    @GetMapping("/hourly")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> hourly(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.hourly(new InsightsQuery(from, to, branchId, null));
    }

    @GetMapping("/kitchen")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> kitchen(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.kitchenStations(new InsightsQuery(from, to, branchId, null));
    }

    /** Per role for {@code lp:view}; {@code groupBy=person} also needs {@code lp:investigate}. */
    @GetMapping("/staff")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> staff(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String groupBy) {
        return reports.staff(new InsightsQuery(from, to, branchId, groupBy));
    }

    @GetMapping("/tables")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> tables(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.tables(new InsightsQuery(from, to, branchId, null));
    }

    @GetMapping("/pour")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> pour(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.pourYield(new InsightsQuery(from, to, branchId, null));
    }

    /** People in and out at the entrances, and sales per person in (LP6b). */
    @GetMapping("/footfall")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> footfall(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.footfall(new InsightsQuery(from, to, branchId, null));
    }

    /** Waits and queue length per queue zone (LP6b). */
    @GetMapping("/queues")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> queues(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.queues(new InsightsQuery(from, to, branchId, null));
    }

    /** Table occupancy, seatings and the wait to the first order per dining zone (LP6b). */
    @GetMapping("/occupancy")
    @RequirePermissions(Permissions.LOSS_PREVENTION__VIEW)
    public Map<String, Object> occupancy(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return reports.occupancy(new InsightsQuery(from, to, branchId, null));
    }

    /** Marks the days of a period dirty, so the job computes them again. Answers 200: nothing is created. */
    @PostMapping("/recompute")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> recompute(@Valid @RequestBody InsightsRecomputeInput body) {
        return insights.recompute(body);
    }
}
