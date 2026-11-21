package com.inventoryerp.sync.web;

import com.inventoryerp.kernel.security.AuthenticationFilter;
import com.inventoryerp.kernel.licensing.LicenseService;
import com.inventoryerp.kernel.licensing.LicenseSyncView;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.ArrayMinSize;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.sync.application.SyncDeadLetterService;
import com.inventoryerp.sync.application.SyncDevicesService;
import com.inventoryerp.sync.application.SyncPullService;
import com.inventoryerp.sync.application.SyncProtocol;
import com.inventoryerp.sync.application.SyncPushService;
import com.inventoryerp.sync.application.SyncSnapshotService;
import com.inventoryerp.kernel.idempotency.IdempotencyService;
import java.time.Instant;
import com.inventoryerp.sync.application.SyncPushService.Device;
import com.inventoryerp.sync.application.SyncPushService.Op;
import com.inventoryerp.kernel.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * {@code /sync} (port of {@code sync.controller.ts}). Device management and dead-letter review use the user JWT;
 * {@code /sync/pull} and {@code /sync/push} are {@code @Public} and authenticated by {@code X-Device-Token}, resolved
 * by the authentication filter ({@code DeviceTokenGuard} → 401 when no device is attached).
 */
@RestController
@RequestMapping("/sync")
public class SyncController {

    static final String STR = "must be a string";
    static final String ISO_DATE =
            "^\\d{4}-\\d{2}-\\d{2}([Tt ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([Zz]|[+-]\\d{2}(:?\\d{2})?)?)?$";

    /** {@code RegisterDeviceDto}. */
    public record RegisterDeviceRequest(@NotNull(message = STR) String name,
            @OneOf({"android", "web"}) String platform, String branchId) {}

    /** {@code ResolveDeadLetterDto}. */
    public record ResolveDeadLetterRequest(
            @NotNull(message = "must be one of the following values: resolved, discarded")
            @OneOf({"resolved", "discarded"}) String status,
            @NotNull(message = STR) String note) {}

    /** {@code SyncOpDto}. */
    public record SyncOpRequest(
            @NotNull(message = STR) String opId,
            @NotNull(message = "must be an integer number") @Min(value = 1, message = "must not be less than 1")
                    Integer deviceSeq,
            @NotNull(message = "must be one of the following values: " + OP_TYPES_LIST) @OneOf({"cash_session.open",
                "cash_session.close", "cash_session.movement", "sale.checkout", "tab.settle", "sale.refund", "sale.void",
                "reservation.create", "reservation.seat", "reservation.cancel", "reservation.noShow", "customer.upsert",
                "customer.delete", "setting.set", "menuCategory.upsert", "menuCategory.delete", "menuItem.upsert",
                "menuItem.delete", "modifierGroup.upsert", "modifierGroup.delete", "accompanimentGroup.upsert",
                "accompanimentGroup.delete", "tax.upsert", "tax.delete", "product.upsert", "product.delete",
                "productCategory.upsert", "productCategory.delete", "cashRegister.upsert", "cashRegister.delete",
                "posTable.upsert", "posTable.delete", "staff.pinChange", "message.send", "message.markRead",
                "stock.in", "stock.out", "stock.count", "expense.create", "kitchen.void", "cash_drawer.open"}) String type,
            @NotNull(message = STR) String actorUserId,
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = ISO_DATE, message = "must be a valid ISO 8601 date string") String occurredAt,
            @NotNull(message = "must be an object") Map<String, Object> payload,
            // ADR-S083 (optional, additive): hops the op already travelled, and the dead-lettered op it corrects.
            List<String> originChain, String supersedesOpId) {}

    /** ADR-S083 S5 dead-letter transition with a mandatory note. */
    public record DeadLetterNoteRequest(@NotNull(message = STR) String note) {}

    /** ADR-S083 C4 device policy: lowers the org offline window for one device ({@code null} clears). */
    public record DevicePolicyRequest(@Min(value = 1, message = "must not be less than 1") Integer offlineWindowHours) {}

    /** ADR-S083 C5 snapshot open. */
    public record SnapshotRequest(String scopes) {}

    /** ADR-S083 C5 snapshot completion: per scope, the row count held and the combined page checksum. */
    public record SnapshotCompleteRequest(@NotNull(message = "must be an object") Map<String, Map<String, Object>> scopes) {}

    static final String OP_TYPES_LIST = "cash_session.open, cash_session.close, cash_session.movement, sale.checkout,"
            + " tab.settle, sale.refund, sale.void, reservation.create, reservation.seat, reservation.cancel,"
            + " reservation.noShow, customer.upsert, customer.delete, setting.set, menuCategory.upsert,"
            + " menuCategory.delete, menuItem.upsert, menuItem.delete, modifierGroup.upsert, modifierGroup.delete,"
            + " accompanimentGroup.upsert, accompanimentGroup.delete, tax.upsert, tax.delete, product.upsert,"
            + " product.delete, productCategory.upsert, productCategory.delete, cashRegister.upsert, cashRegister.delete,"
            + " posTable.upsert, posTable.delete, staff.pinChange, message.send, message.markRead, stock.in, stock.out,"
            + " stock.count, expense.create, kitchen.void, cash_drawer.open"; // kitchen.void: Phase I6, cash_drawer.open:
    // LP2b (both additive)

    /** {@code SyncPushDto}. */
    public record SyncPushRequest(
            @NotNull(message = STR) String deviceId,
            @NotNull(message = "must be an array") @ArrayMinSize(1) @Valid
                    List<SyncOpRequest> ops) {}

    private final SyncDevicesService devices;
    private final SyncPullService pullSvc;
    private final SyncPushService pushSvc;
    private final SyncDeadLetterService deadLetters;
    private final Validator validator;
    private final LicenseService licenses;
    private SyncSnapshotService snapshots;
    private IdempotencyService idempotency;

    @org.springframework.beans.factory.annotation.Autowired
    void setO0Collaborators(SyncSnapshotService snapshots, IdempotencyService idempotency) {
        this.snapshots = snapshots;
        this.idempotency = idempotency;
    }

    public SyncController(SyncDevicesService devices, SyncPullService pullSvc, SyncPushService pushSvc,
            SyncDeadLetterService deadLetters, Validator validator, LicenseService licenses) {
        this.validator = validator;
        this.devices = devices;
        this.pullSvc = pullSvc;
        this.pushSvc = pushSvc;
        this.deadLetters = deadLetters;
        this.licenses = licenses;
    }

    @PostMapping("/devices/register")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ORGANIZATION__UPDATE)
    public Map<String, Object> register(@Valid @RequestBody RegisterDeviceRequest dto) {
        return devices.register(dto.name(), dto.platform(), dto.branchId());
    }

    @GetMapping("/devices")
    @RequirePermissions(Permissions.ORGANIZATION__READ)
    public List<Map<String, Object>> list() {
        return devices.list();
    }

    @PostMapping("/devices/{id}/revoke")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ORGANIZATION__UPDATE)
    public Map<String, Object> revoke(@PathVariable String id) {
        return devices.revoke(id);
    }

    @PostMapping("/devices/{id}/policy")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ORGANIZATION__UPDATE)
    public Map<String, Object> devicePolicy(@PathVariable String id, @Valid @RequestBody DevicePolicyRequest dto) {
        return devices.setPolicy(id, dto.offlineWindowHours());
    }

    @PostMapping("/dead-letters/{id}/investigate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> investigateDeadLetter(@PathVariable String id, @Valid @RequestBody DeadLetterNoteRequest dto) {
        return deadLetters.investigate(id, dto.note());
    }

    @PostMapping("/dead-letters/{id}/retry")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> retryDeadLetter(@PathVariable String id, @Valid @RequestBody DeadLetterNoteRequest dto) {
        return deadLetters.retry(id, dto.note());
    }

    @GetMapping("/dead-letters")
    @RequirePermissions(Permissions.POS__REPORTS)
    public List<Map<String, Object>> listDeadLetters(@RequestParam(required = false) String status) {
        return deadLetters.list(status == null ? "open" : status);
    }

    @GetMapping("/dead-letters/counts")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> deadLetterCounts() {
        return deadLetters.counts();
    }

    @PostMapping("/dead-letters/{id}/resolve")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> resolveDeadLetter(@PathVariable String id,
            @Valid @RequestBody ResolveDeadLetterRequest dto) {
        return deadLetters.resolve(id, dto.status(), dto.note());
    }

    /**
     * Additive to the Nest contract: the response carries a {@code license} member so an Android
     * terminal learns its enforcement mode on the call it already makes, rather than needing a
     * second round trip on a connection that may be about to disappear. Existing members are
     * untouched and in their original order.
     */
    @GetMapping("/pull")
    @Public
    public Map<String, Object> pull(HttpServletRequest request, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String scopes) {
        String version = SyncProtocol.of(request.getHeader(SyncProtocol.HEADER));
        Map<?, ?> attached = activeDevice(request, version);
        SyncProtocol.requirePullSupported(version);
        boolean v2 = SyncProtocol.atLeast2(version);
        Map<String, Object> body = new java.util.LinkedHashMap<>(pullSvc.pull(cursor, scopes, v2));
        body.put("license", LicenseSyncView.of(licenses.current()));
        // ADR-S083 (additive): protocol, identity, offline policy, token lifecycle (rotation on protocol 2).
        body.put("protocolVersion", SyncProtocol.CURRENT);
        body.putAll(devices.afterPull(attached, v2));
        return body;
    }

    /** ADR-S083 C5: open a snapshot pinned to now. */
    @PostMapping("/snapshot")
    @Public
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> openSnapshot(HttpServletRequest request, @RequestBody(required = false) SnapshotRequest dto) {
        Device device = requireDevice(request);
        return snapshots.create(device, dto == null ? null : dto.scopes());
    }

    @GetMapping("/snapshot/{id}/page")
    @Public
    public Map<String, Object> snapshotPage(HttpServletRequest request, @PathVariable String id,
            @RequestParam(required = false) String scope, @RequestParam(required = false) String after) {
        Device device = requireDevice(request);
        return snapshots.page(device, id, scope, after);
    }

    @PostMapping("/snapshot/{id}/complete")
    @Public
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> completeSnapshot(HttpServletRequest request, @PathVariable String id,
            @RequestBody SnapshotCompleteRequest dto) {
        Device device = requireDevice(request);
        if (dto == null || dto.scopes() == null) {
            throw ApiException.badRequest("scopes must be an object");
        }
        return snapshots.complete(device, id, dto.scopes());
    }

    @PostMapping("/push")
    @Public
    @ResponseStatus(HttpStatus.CREATED)
    public Object push(HttpServletRequest request, @RequestBody SyncPushRequest dto) {
        // Nest runs DeviceTokenGuard before the ValidationPipe: 401 wins over a 400.
        // ADR-S083 S2: push is the one route a revoked/expired device may still use, for ops before its cut-off.
        Device device = pushDevice(request);
        validate(dto);
        List<Op> ops = new ArrayList<>();
        for (SyncOpRequest o : dto.ops()) {
            ops.add(new Op(o.opId(), o.deviceSeq(), o.type(), o.actorUserId(), o.occurredAt(), o.payload(),
                    o.originChain(), o.supersedesOpId()));
        }
        String key = IdempotencyService.readKey(request.getHeader("Idempotency-Key"));
        if (key == null) {
            return pushSvc.push(device, ops);
        }
        // ADR-S083 S1: a resent batch (same Idempotency-Key, same body) returns the stored per-op results.
        Map<String, Object> hashed = new java.util.LinkedHashMap<>();
        hashed.put("deviceId", dto.deviceId());
        hashed.put("ops", dto.ops());
        return idempotency.executeWithKey("sync-batch:" + device.id() + ":" + key, idempotency.hashPayload(hashed),
                "SYNC_BATCH", "/sync/push", null, () -> new IdempotencyService.Result(false, 201, pushSvc.push(device, ops)))
                .body();
    }

    private void validate(SyncPushRequest dto) {
        List<String> errors = new ArrayList<>();
        for (ConstraintViolation<SyncPushRequest> v : validator.validate(dto)) {
            errors.add(v.getPropertyPath().toString().replaceAll("\\[(\\d+)]", ".$1") + " " + v.getMessage());
        }
        if (!errors.isEmpty()) {
            errors.sort(null);
            throw ApiException.withBody(HttpStatus.BAD_REQUEST, GlobalExceptionHandler.nestBody(HttpStatus.BAD_REQUEST,
                    errors));
        }
    }

    /** {@code DeviceTokenGuard}: the filter attached the enrolled device, or 401; heartbeat on success. */
    private Device requireDevice(HttpServletRequest request) {
        Map<?, ?> d = activeDevice(request, SyncProtocol.of(request.getHeader(SyncProtocol.HEADER)));
        return new Device((String) d.get("id"), (String) d.get("organizationId"), (String) d.get("branchId"));
    }

    /** A device that is neither revoked nor past its token expiry (ADR-S083 C4), or 401. */
    private Map<?, ?> activeDevice(HttpServletRequest request, String version) {
        Map<?, ?> d = attached(request);
        if (d.get("revokedAt") != null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid X-Device-Token is required");
        }
        if (d.get("tokenExpiresAt") instanceof Instant exp && exp.isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "This device's token expired — register the device again");
        }
        devices.touch((String) d.get("id"), version);
        return d;
    }

    /** Push: a lapsed device is accepted with a cut-off; its later ops are dead-lettered (ADR-S083 S2). */
    private Device pushDevice(HttpServletRequest request) {
        Map<?, ?> d = attached(request);
        Instant cutoff = null;
        String code = null;
        if (d.get("revokedAt") instanceof Instant revoked) {
            cutoff = revoked;
            code = "DEVICE_REVOKED";
        }
        if (d.get("tokenExpiresAt") instanceof Instant exp && exp.isBefore(Instant.now())
                && (cutoff == null || exp.isBefore(cutoff))) {
            cutoff = exp;
            code = "DEVICE_TOKEN_EXPIRED";
        }
        devices.touch((String) d.get("id"), SyncProtocol.of(request.getHeader(SyncProtocol.HEADER)));
        return new Device((String) d.get("id"), (String) d.get("organizationId"), (String) d.get("branchId"), cutoff,
                code);
    }

    private static Map<?, ?> attached(HttpServletRequest request) {
        if (!(request.getAttribute(AuthenticationFilter.DEVICE_ATTRIBUTE) instanceof Map<?, ?> d)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid X-Device-Token is required");
        }
        return d;
    }
}
