package com.inventoryerp.fleet.web.weighbridge;

import com.inventoryerp.fleet.application.WeighbridgeCommands.DeviceInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.DevicePatch;
import com.inventoryerp.fleet.application.WeighbridgeCommands.ProductSettingInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.SimulatorWeight;
import com.inventoryerp.fleet.application.WeighbridgeCommands.StationInput;
import com.inventoryerp.fleet.application.WeighbridgeCommands.StationPatch;
import com.inventoryerp.fleet.application.WeighbridgeStationService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Weighbridge configuration (fleet 1.4): stations, indicator devices (adapter type + connection / frame config, test
 * read, live read, simulator weight), per-product weighing settings and the lookups of the ticket screens.
 */
@RestController
@RequestMapping("/fleet/weighbridge")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class WeighbridgeSettingsController {

    private final WeighbridgeStationService stations;

    public WeighbridgeSettingsController(WeighbridgeStationService stations) {
        this.stations = stations;
    }

    @GetMapping("/stations")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public List<Map<String, Object>> stations(@RequestParam(required = false) Boolean includeInactive) {
        return stations.stations(Boolean.TRUE.equals(includeInactive));
    }

    @GetMapping("/stations/{id}")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public Map<String, Object> station(@PathVariable String id) {
        return stations.station(id);
    }

    @PostMapping("/stations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__CONFIGURE)
    public Map<String, Object> createStation(@Valid @RequestBody StationInput dto) {
        return stations.createStation(dto);
    }

    @PatchMapping("/stations/{id}")
    @RequirePermissions(Permissions.WEIGHBRIDGE__CONFIGURE)
    public Map<String, Object> updateStation(@PathVariable String id, @Valid @RequestBody StationPatch dto) {
        return stations.updateStation(id, dto);
    }

    @GetMapping("/devices")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public List<Map<String, Object>> devices(@RequestParam(required = false) String stationId,
            @RequestParam(required = false) Boolean includeInactive) {
        return stations.devices(stationId, Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__CONFIGURE)
    public Map<String, Object> createDevice(@Valid @RequestBody DeviceInput dto) {
        return stations.createDevice(dto);
    }

    @PatchMapping("/devices/{id}")
    @RequirePermissions(Permissions.WEIGHBRIDGE__CONFIGURE)
    public Map<String, Object> updateDevice(@PathVariable String id, @Valid @RequestBody DevicePatch dto) {
        return stations.updateDevice(id, dto);
    }

    /** Settings screen: reachability + one stable read; answers 201 with {@code ok: false} on a device failure. */
    @PostMapping("/devices/{id}/test-read")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__CONFIGURE)
    public Map<String, Object> testRead(@PathVariable String id) {
        return stations.testRead(id);
    }

    /** Operator screen: the current stable weight (503 / 409 on a device failure); nothing is stored. */
    @PostMapping("/devices/{id}/read")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__WEIGH)
    public Map<String, Object> read(@PathVariable String id) {
        return stations.liveRead(id);
    }

    @PostMapping("/devices/{id}/simulator-weight")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.WEIGHBRIDGE__CONFIGURE)
    public Map<String, Object> simulatorWeight(@PathVariable String id, @Valid @RequestBody SimulatorWeight dto) {
        return stations.setSimulatorWeight(id, dto);
    }

    @GetMapping("/product-settings")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public List<Map<String, Object>> productSettings() {
        return stations.productSettings();
    }

    @PutMapping("/product-settings/{productId}")
    @RequirePermissions(Permissions.WEIGHBRIDGE__CONFIGURE)
    public Map<String, Object> putProductSetting(@PathVariable String productId,
            @Valid @RequestBody ProductSettingInput dto) {
        return stations.putProductSetting(productId, dto);
    }

    @GetMapping("/lookups")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public Map<String, Object> lookups() {
        return stations.lookups();
    }

    @GetMapping("/lookups/products")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public List<Map<String, Object>> products(@RequestParam(required = false) String search) {
        return stations.lookupProducts(search);
    }

    @GetMapping("/lookups/partners")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public List<Map<String, Object>> partners(@RequestParam(required = false) String search,
            @RequestParam(required = false) String role) {
        return stations.lookupPartners(search, role);
    }

    @GetMapping("/lookups/purchase-orders")
    @RequirePermissions(Permissions.WEIGHBRIDGE__READ)
    public List<Map<String, Object>> purchaseOrders(@RequestParam(required = false) String search,
            @RequestParam(required = false) String partnerId) {
        return stations.lookupPurchaseOrders(search, partnerId);
    }
}
