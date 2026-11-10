package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.EMAIL;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.security.Throttle;
import com.inventoryerp.pos.application.PosOverridesService;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/override} (port of {@code pos-overrides.controller.ts}). */
@RestController
@RequestMapping("/pos/override")
public class PosOverridesController {

    public record SetPinRequest(@NotNull(message = STR) String pin) {}

    public record VerifyPinRequest(@NotNull(message = STR) String pin) {}

    public record VerifyOverrideRequest(
            @Email(message = EMAIL) String email,
            String pin,
            String password,
            @NotNull(message = "must be one of the following values: discount, void, manual_refund")
            @OneOf({"discount", "void", "manual_refund"}) String overrideKind) {}

    private final PosOverridesService svc;

    public PosOverridesController(PosOverridesService svc) {
        this.svc = svc;
    }

    @PostMapping("/pin")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> setPin(AuthUser user, @Valid @RequestBody SetPinRequest dto) {
        if (user == null || user.sub() == null) {
            throw ApiException.badRequest("No user in context");
        }
        return svc.setPin(user.sub(), dto.pin());
    }

    @PostMapping("/verify")
    @ResponseStatus(HttpStatus.CREATED)
    @Throttle(limit = 10, ttlMs = 60_000)
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> verify(@Valid @RequestBody VerifyOverrideRequest dto) {
        return svc.verify(dto.email(), dto.pin(), dto.password(), dto.overrideKind());
    }

    @PostMapping("/verify-pin")
    @Throttle(limit = 30, ttlMs = 60_000)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> verifyPin(AuthUser user, @Valid @RequestBody VerifyPinRequest dto) {
        if (user == null || user.sub() == null) {
            throw ApiException.badRequest("No user in context");
        }
        return svc.verifyCurrentUserPin(user.sub(), dto.pin());
    }
}
