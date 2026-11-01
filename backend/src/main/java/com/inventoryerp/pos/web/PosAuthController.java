package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosAuthService;
import com.inventoryerp.shared.validation.Length;
import com.inventoryerp.shared.validation.MinLength;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/auth} (port of {@code pos-auth.controller.ts}); every route answers 200 ({@code @HttpCode(200)}). */
@RestController
@RequestMapping("/pos/auth")
public class PosAuthController {

    private static final String LEN48 = "must be longer than or equal to 4 and shorter than or equal to 8 characters";

    public record PinLoginRequest(
            @NotNull(message = STR) String userId,
            @NotNull(message = STR) @Length(min = 4, max = 8, message = LEN48) String pin) {}

    public record ChangePinRequest(
            @NotNull(message = STR) @Length(min = 4, max = 8, message = LEN48) String currentPin,
            @NotNull(message = STR) @Length(min = 4, max = 8, message = LEN48) String newPin) {}

    public record ChangePasswordRequest(
            @NotNull(message = STR) @MinLength(1)
            String currentPassword,
            @NotNull(message = STR) @MinLength(8)
            String newPassword) {}

    private final PosAuthService svc;

    public PosAuthController(PosAuthService svc) {
        this.svc = svc;
    }

    @GetMapping("/staff")
    @RequirePermissions(Permissions.POS__READ)
    public List<Map<String, Object>> listStaff() {
        return svc.listStaff();
    }

    @PostMapping("/pin-login")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> pinLogin(@Valid @RequestBody PinLoginRequest dto) {
        return svc.pinLogin(dto.userId(), dto.pin());
    }

    @PostMapping("/logoff")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> logoff(AuthUser user) {
        return svc.logoff(user.sub());
    }

    @PostMapping("/change-pin")
    @RequirePermissions(Permissions.POS__READ)
    public void changePin(AuthUser user, @Valid @RequestBody ChangePinRequest dto) {
        svc.changePin(user.sub(), dto.currentPin(), dto.newPin());
    }

    @PostMapping("/change-password")
    @RequirePermissions(Permissions.POS__READ)
    public void changePassword(AuthUser user, @Valid @RequestBody ChangePasswordRequest dto) {
        svc.changePassword(user.sub(), dto.currentPassword(), dto.newPassword());
    }
}
