package com.inventoryerp.fleet.web.projects;

import com.inventoryerp.fleet.application.FleetProjectTagService;
import com.inventoryerp.fleet.application.FleetProjectTagService.TagInput;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Project tag of trips and work orders (ADR-S075 §3); {@code projectId: null} clears it. */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
public class FleetProjectTagController {

    public record TagRequest(String projectId, String wbsId) {}

    private final FleetProjectTagService tags;

    public FleetProjectTagController(FleetProjectTagService tags) {
        this.tags = tags;
    }

    @PutMapping("/trips/{id}/project")
    @RequirePermissions(Permissions.FLEET_TRIP__UPDATE)
    public Map<String, Object> tagTrip(@PathVariable String id, @RequestBody TagRequest r) {
        return tags.tagTrip(id, new TagInput(r.projectId(), r.wbsId()));
    }

    @PutMapping("/work-orders/{id}/project")
    @RequirePermissions(Permissions.FLEET_MAINTENANCE__UPDATE)
    public Map<String, Object> tagWorkOrder(@PathVariable String id, @RequestBody TagRequest r) {
        return tags.tagWorkOrder(id, new TagInput(r.projectId(), r.wbsId()));
    }
}
