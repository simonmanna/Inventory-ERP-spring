package com.inventoryerp.kernel.auth;

import com.inventoryerp.kernel.auth.AuthDtos.ChangePasswordRequest;
import com.inventoryerp.kernel.auth.AuthDtos.ForgotPasswordRequest;
import com.inventoryerp.kernel.auth.AuthDtos.LoginRequest;
import com.inventoryerp.kernel.auth.AuthDtos.MfaCodeRequest;
import com.inventoryerp.kernel.auth.AuthDtos.MfaLoginRequest;
import com.inventoryerp.kernel.auth.AuthDtos.RefreshRequest;
import com.inventoryerp.kernel.auth.AuthDtos.ResetPasswordRequest;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.Throttle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /auth/*} (port of {@code auth.controller.ts}). */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService auth;
    private final OneTimeTokenService tokens;

    public AuthController(AuthService auth, OneTimeTokenService tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    /** 10 attempts / 5 min / IP on top of the per-account lockout. {@code @Idempotent} → answers 200. */
    @Public
    @Throttle(limit = 10, ttlMs = 5 * 60 * 1000)
    @Idempotent
    @PostMapping("/login")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> login(@Valid @RequestBody LoginRequest dto, HttpServletRequest req) {
        return auth.login(dto.organizationCode(), dto.email(), dto.password(), info(req));
    }

    @Public
    @Throttle(limit = 10, ttlMs = 5 * 60 * 1000)
    @PostMapping("/mfa-login")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> mfaLogin(@Valid @RequestBody MfaLoginRequest dto, HttpServletRequest req) {
        return auth.mfaLogin(dto.mfaToken(), dto.code(), info(req));
    }

    @Public
    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> refresh(@Valid @RequestBody RefreshRequest dto, HttpServletRequest req) {
        return auth.refresh(dto.refreshToken(), info(req));
    }

    @GetMapping("/me")
    public Map<String, Object> me(AuthUser user) {
        return auth.me(user);
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions(AuthUser user) {
        return auth.listSessions(user);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> revoke(AuthUser user, @PathVariable String id) {
        return auth.revokeSession(user, id);
    }

    @PostMapping("/mfa/enroll")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> enroll(AuthUser user) {
        return auth.enrollMfa(user);
    }

    @PostMapping("/mfa/verify")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> verifyEnroll(AuthUser user, @Valid @RequestBody MfaCodeRequest dto) {
        return auth.verifyMfaEnrollment(user, dto.code());
    }

    @PostMapping("/mfa/disable")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> disable(AuthUser user, @Valid @RequestBody MfaCodeRequest dto) {
        return auth.disableMfa(user, dto.code());
    }

    /** Always ok (enumeration protection). 5 / 15 min / IP. */
    @Public
    @Throttle(limit = 5, ttlMs = 15 * 60 * 1000)
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> forgot(@Valid @RequestBody ForgotPasswordRequest dto, HttpServletRequest req) {
        return tokens.requestReset(dto.email(), dto.organizationCode(), req.getRemoteAddr(), null);
    }

    @Public
    @PostMapping("/reset-password")
    public Map<String, Object> reset(@Valid @RequestBody ResetPasswordRequest dto) {
        return tokens.applyReset(dto.token(), dto.newPassword());
    }

    @PostMapping("/change-password")
    public Map<String, Object> change(AuthUser user, @Valid @RequestBody ChangePasswordRequest dto) {
        auth.changePassword(user, dto.currentPassword(), dto.newPassword());
        return Map.of("ok", true);
    }

    static AuthService.RequestInfo info(HttpServletRequest req) {
        return new AuthService.RequestInfo(req.getRemoteAddr(), req.getHeader("User-Agent"), req.getHeader("X-Device-Label"));
    }
}
