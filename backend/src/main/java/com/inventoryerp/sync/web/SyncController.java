package com.inventoryerp.sync.web;

import com.inventoryerp.kernel.security.AuthenticationFilter;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.sync.application.SyncDeadLetterService;
import com.inventoryerp.sync.application.SyncDevicesService;
import com.inventoryerp.sync.application.SyncPullService;
import com.inventoryerp.sync.application.SyncPushService;
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
import jakarta.validation.constraints.Size;
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
                "stock.in", "stock.out", "stock.count", "expense.create"}) String type,
            @NotNull(message = STR) String actorUserId,
            @NotNull(message = "must be a valid ISO 8601 date string")
            @Pattern(regexp = ISO_DATE, message = "must be a valid ISO 8601 date string") String occurredAt,
            @NotNull(message = "must be an object") Map<String, Object> payload) {}

    static final String OP_TYPES_LIST = "cash_session.open, cash_session.close, cash_session.movement, sale.checkout,"
            + " tab.settle, sale.refund, sale.void, reservation.create, reservation.seat, reservation.cancel,"
            + " reservation.noShow, customer.upsert, customer.delete, setting.set, menuCategory.upsert,"
            + " menuCategory.delete, menuItem.upsert, menuItem.delete, modifierGroup.upsert, modifierGroup.delete,"
            + " accompanimentGroup.upsert, accompanimentGroup.delete, tax.upsert, tax.delete, product.upsert,"
            + " product.delete, productCategory.upsert, productCategory.delete, cashRegister.upsert, cashRegister.delete,"
            + " posTable.upsert, posTable.delete, staff.pinChange, message.send, message.markRead, stock.in, stock.out,"
            + " stock.count, expense.create";

    /** {@code SyncPushDto}. */
    public record SyncPushRequest(
            @NotNull(message = STR) String deviceId,
            @NotNull(message = "must be an array") @Size(min = 1, message = "must contain at least 1 elements") @Valid
                    List<SyncOpRequest> ops) {}

    private final SyncDevicesService devices;
    private final SyncPullService pullSvc;
    private final SyncPushService pushSvc;
    private final SyncDeadLetterService deadLetters;
    private final Validator validator;

    public SyncController(SyncDevicesService devices, SyncPullService pullSvc, SyncPushService pushSvc,
            SyncDeadLetterService deadLetters, Validator validator) {
        this.validator = validator;
        this.devices = devices;
        this.pullSvc = pullSvc;
        this.pushSvc = pushSvc;
        this.deadLetters = deadLetters;
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

    @GetMapping("/pull")
    @Public
    public Map<String, Object> pull(HttpServletRequest request, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String scopes) {
        requireDevice(request);
        return pullSvc.pull(cursor, scopes);
    }

    @PostMapping("/push")
    @Public
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> push(HttpServletRequest request, @RequestBody SyncPushRequest dto) {
        // Nest runs DeviceTokenGuard before the ValidationPipe: 401 wins over a 400.
        Device device = requireDevice(request);
        validate(dto);
        List<Op> ops = new ArrayList<>();
        for (SyncOpRequest o : dto.ops()) {
            ops.add(new Op(o.opId(), o.deviceSeq(), o.type(), o.actorUserId(), o.occurredAt(), o.payload()));
        }
        return pushSvc.push(device, ops);
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
        if (!(request.getAttribute(AuthenticationFilter.DEVICE_ATTRIBUTE) instanceof Map<?, ?> d)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "A valid X-Device-Token is required");
        }
        Device device = new Device((String) d.get("id"), (String) d.get("organizationId"), (String) d.get("branchId"));
        devices.touch(device.id());
        return device;
    }
}
