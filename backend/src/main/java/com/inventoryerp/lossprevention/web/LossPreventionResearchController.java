package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.lossprevention.application.LpCommands.ActivityQuery;
import com.inventoryerp.lossprevention.application.LpCommands.ActivityReviewInput;
import com.inventoryerp.lossprevention.application.LpResearchService;
import com.inventoryerp.shared.web.PageResult;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /loss-prevention/research} (LP7): activities recognized in shadow mode and people's reviews of them. No rule
 * reads an activity; these routes measure recognizers. Licence feature {@code lpVision}.
 */
@RestController
@RequestMapping("/loss-prevention/research")
@RequiresModule("loss-prevention")
@RequiresFeature(LicenseFeatures.LP_VISION)
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionResearchController {

    private final LpResearchService research;

    public LossPreventionResearchController(LpResearchService research) {
        this.research = research;
    }

    @GetMapping("/activities")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String zoneId, @RequestParam(required = false) String activity,
            @RequestParam(required = false) String recognizer, @RequestParam(required = false) String verdict,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        return research.list(new ActivityQuery(from, to, branchId, zoneId, activity, recognizer, verdict, page,
                pageSize));
    }

    @GetMapping("/activities/summary")
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public Map<String, Object> summary(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branchId) {
        return research.summary(new ActivityQuery(from, to, branchId, null, null, null, null, null, null));
    }

    @PostMapping("/activities/{eventId}/review")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__INVESTIGATE)
    public Map<String, Object> review(@PathVariable String eventId, @RequestBody ActivityReviewInput body) {
        return research.review(eventId, body);
    }
}
