package com.inventoryerp.license.web;

import com.inventoryerp.license.application.AdminAuthService;
import com.inventoryerp.license.application.AdminAudits;
import com.inventoryerp.license.application.AdminTokens;
import com.inventoryerp.license.domain.AdminRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin sessions and accounts. {@code /admin/auth/login} is throttled; the rest needs a session. */
@RestController
@RequestMapping("/admin")
public class AdminAuthController {

    private final AdminAuthService auth;
    private final AdminAudits audits;

    public AdminAuthController(AdminAuthService auth, AdminAudits audits) {
        this.auth = auth;
        this.audits = audits;
    }

    @PostMapping("/auth/login")
    @RateLimited
    public AdminDtos.SessionResponse login(@Valid @RequestBody AdminDtos.LoginRequest body,
            HttpServletRequest request) {
        var session = auth.login(body.email(), body.password(), body.totpCode(),
                ClientRateLimitInterceptor.clientIp(request));
        return session(session);
    }

    @PostMapping("/auth/refresh")
    @RateLimited
    public AdminDtos.SessionResponse refresh(@Valid @RequestBody AdminDtos.RefreshRequest body) {
        return session(auth.refresh(body.refreshToken()));
    }

    @PostMapping("/auth/logout")
    @RequireAdmin
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(AdminTokens.Principal actor) {
        auth.logout(actor.adminId());
    }

    @GetMapping("/auth/me")
    @RequireAdmin
    public AdminDtos.AdminResponse me(AdminTokens.Principal actor) {
        return AdminDtos.AdminResponse.of(auth.require(actor.adminId()));
    }

    @PostMapping("/auth/password")
    @RequireAdmin
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody AdminDtos.ChangePasswordRequest body,
            AdminTokens.Principal actor, HttpServletRequest request) {
        auth.changePassword(actor.adminId(), body.currentPassword(), body.newPassword());
        audits.record(actor, "admin.password.change", actor.adminId(),
                ClientRateLimitInterceptor.clientIp(request), Map.of());
    }

    /**
     * Begin TOTP enrolment. The secret is shown once; MFA only comes into force once a code from the
     * authenticator has been confirmed, so a half-finished enrolment cannot lock an admin out.
     */
    @PostMapping("/auth/mfa")
    @RequireAdmin
    public AdminDtos.MfaSecretResponse beginMfa(AdminTokens.Principal actor) {
        String secret = auth.beginMfaEnrolment(actor.adminId());
        String uri = "otpauth://totp/" + URLEncoder.encode("Inventory ERP Licences:" + actor.email(),
                StandardCharsets.UTF_8) + "?secret=" + secret + "&issuer="
                + URLEncoder.encode("Inventory ERP Licences", StandardCharsets.UTF_8);
        return new AdminDtos.MfaSecretResponse(secret, uri);
    }

    @PostMapping("/auth/mfa/confirm")
    @RequireAdmin
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmMfa(@Valid @RequestBody AdminDtos.MfaConfirmRequest body, AdminTokens.Principal actor,
            HttpServletRequest request) {
        auth.confirmMfaEnrolment(actor.adminId(), body.code());
        audits.record(actor, "admin.mfa.enrol", actor.adminId(),
                ClientRateLimitInterceptor.clientIp(request), Map.of());
    }

    @GetMapping("/admins")
    @RequireAdmin(AdminRole.OWNER)
    public List<AdminDtos.AdminResponse> admins() {
        return auth.list().stream().map(AdminDtos.AdminResponse::of).toList();
    }

    @PostMapping("/admins")
    @RequireAdmin(AdminRole.OWNER)
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.AdminResponse createAdmin(@Valid @RequestBody AdminDtos.CreateAdminRequest body,
            AdminTokens.Principal actor, HttpServletRequest request) {
        AdminRole role = body.role() == null || body.role().isBlank()
                ? AdminRole.SUPPORT : AdminRole.of(body.role());
        var created = auth.create(body.email(), body.password(), body.displayName(), role);
        audits.record(actor, "admin.create", created.getId(), ClientRateLimitInterceptor.clientIp(request),
                Map.of("email", created.getEmail(), "role", role.value()));
        return AdminDtos.AdminResponse.of(created);
    }

    private static AdminDtos.SessionResponse session(AdminAuthService.Session session) {
        return new AdminDtos.SessionResponse(session.accessToken(), session.refreshToken(),
                session.expiresInSeconds(), session.email(), session.role().value(), session.mfaEnrolled());
    }
}
