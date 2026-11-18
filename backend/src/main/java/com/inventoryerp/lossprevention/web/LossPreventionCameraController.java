package com.inventoryerp.lossprevention.web;

import com.inventoryerp.kernel.licensing.LicenseFeatures;
import com.inventoryerp.kernel.licensing.RequiresFeature;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.security.Throttle;
import com.inventoryerp.lossprevention.application.LpCameraService;
import com.inventoryerp.lossprevention.application.LpLiveService;
import com.inventoryerp.lossprevention.application.LpVisionCommands.CreateCameraInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.CreateZoneInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.UpdateCameraInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.UpdateZoneInput;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * {@code /loss-prevention/cameras}, {@code /zones} and {@code /live} (LP1, ADR-S077 §7–8): cameras and their zones,
 * stills and live view. The backend never connects to a camera; the edge agent sends what is asked for.
 */
@RestController
@RequestMapping("/loss-prevention")
@RequiresModule("loss-prevention")
@RequiresFeature(LicenseFeatures.LP_VISION)
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LossPreventionCameraController {

    private final LpCameraService cameras;
    private final LpLiveService live;

    public LossPreventionCameraController(LpCameraService cameras, LpLiveService live) {
        this.cameras = cameras;
        this.live = live;
    }

    // ------------------------------------------------------------------ cameras

    @GetMapping("/cameras")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public List<Map<String, Object>> list(@RequestParam(required = false) String branchId,
            @RequestParam(required = false) String agentId) {
        return cameras.list(branchId, agentId);
    }

    @PostMapping("/cameras")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> create(@Valid @RequestBody CreateCameraInput dto) {
        return cameras.create(dto);
    }

    @GetMapping("/cameras/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> get(@PathVariable String id) {
        return cameras.get(id);
    }

    @PutMapping("/cameras/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateCameraInput dto) {
        return cameras.update(id, dto);
    }

    @PostMapping("/cameras/{id}/snapshot")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions({Permissions.LOSS_PREVENTION__CONFIGURE, Permissions.LOSS_PREVENTION__EVIDENCE})
    public Map<String, Object> requestSnapshot(@PathVariable String id) {
        return cameras.requestSnapshot(id);
    }

    /** The latest still; {@code X-Snapshot-Taken-At} says when the backend received it. Never cached. */
    @GetMapping("/cameras/{id}/snapshot")
    @RequirePermissions({Permissions.LOSS_PREVENTION__CONFIGURE, Permissions.LOSS_PREVENTION__EVIDENCE})
    public ResponseEntity<byte[]> snapshot(@PathVariable String id) {
        LpLiveService.Snapshot s = cameras.snapshot(id);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
                .header("X-Snapshot-Taken-At", s.at().toString())
                .header("X-Content-Type-Options", "nosniff").body(s.jpeg());
    }

    // ------------------------------------------------------------------ live view

    @PostMapping("/cameras/{id}/live")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__EVIDENCE)
    public Map<String, Object> startLive(@PathVariable String id, HttpServletRequest request) {
        return live.start(id, LpWebPaths.apiBase(request));
    }

    @DeleteMapping("/live/{sessionId}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__EVIDENCE)
    public Map<String, Object> stopLive(@PathVariable String sessionId) {
        return live.stop(sessionId);
    }

    /**
     * The live picture as {@code multipart/x-mixed-replace} JPEG frames. Public: the signed token in the link is the
     * authorization, so an image element can show it. Holds the request (a virtual thread) while it streams.
     */
    @GetMapping("/live/{sessionId}/stream")
    @Public
    @Throttle(limit = 30, ttlMs = 60_000)
    public void stream(@PathVariable String sessionId, @RequestParam(required = false) String token,
            HttpServletResponse response) throws IOException {
        LpLiveService.Stream stream = live.open(sessionId, token);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(stream.contentType());
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.flushBuffer();
        stream.writeTo(response.getOutputStream());
    }

    // ------------------------------------------------------------------ zones

    @GetMapping("/zones")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public List<Map<String, Object>> zones(@RequestParam(required = false) String cameraId,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) Boolean includeRetired) {
        return cameras.listZones(cameraId, branchId, Boolean.TRUE.equals(includeRetired));
    }

    @GetMapping("/zones/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> zone(@PathVariable String id) {
        return cameras.getZone(id);
    }

    @PostMapping("/zones")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> createZone(@Valid @RequestBody CreateZoneInput dto) {
        return cameras.createZone(dto);
    }

    @PutMapping("/zones/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> updateZone(@PathVariable String id, @Valid @RequestBody UpdateZoneInput dto) {
        return cameras.updateZone(id, dto);
    }

    @DeleteMapping("/zones/{id}")
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> retireZone(@PathVariable String id) {
        return cameras.retireZone(id);
    }

    @PostMapping("/zones/{id}/calibrate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.LOSS_PREVENTION__CONFIGURE)
    public Map<String, Object> calibrate(@PathVariable String id) {
        return cameras.calibrate(id);
    }
}
