package com.inventoryerp.fleet.web.weighbridge;

import com.inventoryerp.fleet.application.FleetWeighbridgeRuleService;
import com.inventoryerp.fleet.application.WeighbridgeCommands.DispatchRequirementInput;
import com.inventoryerp.fleet.application.WeighbridgeTicketService;
import com.inventoryerp.fleet.application.WeighbridgeTripFeed;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Weighbridge ↔ dispatch (fleet 1.4b): the trip picker and trip pre-fill of the ticket form, feeding a completed
 * ticket's weight to its trip (again), and the "weighbridge required for dispatch" rules per organization / branch.
 */
@RestController
@RequestMapping("/fleet/weighbridge")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class WeighbridgeDispatchController {

    private final WeighbridgeTripFeed tripFeed;
    private final WeighbridgeTicketService tickets;
    private final FleetWeighbridgeRuleService rules;

    public WeighbridgeDispatchController(WeighbridgeTripFeed tripFeed, WeighbridgeTicketService tickets,
            FleetWeighbridgeRuleService rules) {
        this.tripFeed = tripFeed;
        this.tickets = tickets;
        this.rules = rules;
    }

    @GetMapping("/lookups/trips")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public List<Map<String, Object>> trips(@RequestParam(required = false) String search,
            @RequestParam(required = false) String vehicleId) {
        return tripFeed.openTrips(search, vehicleId);
    }

    @GetMapping("/lookups/trips/{tripId}")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public Map<String, Object> tripPrefill(@PathVariable String tripId) {
        return tripFeed.prefill(tripId);
    }

    @PostMapping("/tickets/{id}/feed-trip")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> feedTrip(@PathVariable String id) {
        return tickets.feedTrip(id);
    }

    @GetMapping("/dispatch-requirements")
    @RequirePermissions(Permissions.FLEET_DISPATCH__READ)
    public Map<String, Object> requirements() {
        return rules.overview();
    }

    @PutMapping("/dispatch-requirements")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> putRequirement(@Valid @RequestBody DispatchRequirementInput dto) {
        return rules.put(dto);
    }

    @DeleteMapping("/dispatch-requirements/{id}")
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> deleteRequirement(@PathVariable String id) {
        return rules.delete(id);
    }
}
