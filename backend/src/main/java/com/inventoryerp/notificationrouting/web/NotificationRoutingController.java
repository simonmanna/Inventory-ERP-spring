package com.inventoryerp.notificationrouting.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.notificationrouting.application.NotificationRouteService;
import com.inventoryerp.notificationrouting.application.NotificationRouteService.RouteInput;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /notification-routing} (ADR-S065): who is told when each catalogued event happens. */
@RestController
@RequestMapping("/notification-routing")
public class NotificationRoutingController {

    private final NotificationRouteService routes;

    public NotificationRoutingController(NotificationRouteService routes) {
        this.routes = routes;
    }

    /** Every routable event of the enabled modules, with its parties and what is in force. */
    @GetMapping("/events")
    @RequirePermissions(Permissions.NOTIFICATION_ROUTING__READ)
    public List<Map<String, Object>> events() {
        return routes.list();
    }

    @PutMapping("/events/{event}")
    @RequirePermissions(Permissions.NOTIFICATION_ROUTING__MANAGE)
    public Map<String, Object> save(@PathVariable String event, @Valid @RequestBody RouteInput dto) {
        return routes.save(event, dto);
    }

    /** Back to the event's defaults. */
    @DeleteMapping("/events/{event}")
    @RequirePermissions(Permissions.NOTIFICATION_ROUTING__MANAGE)
    public Map<String, Object> reset(@PathVariable String event) {
        return routes.reset(event);
    }

    /** The latest routed events and how many people each reached. */
    @GetMapping("/activity")
    @RequirePermissions(Permissions.NOTIFICATION_ROUTING__READ)
    public List<Map<String, Object>> activity(@RequestParam(required = false) Integer limit) {
        return routes.activity(limit);
    }
}
