package com.inventoryerp.license.application;

import com.inventoryerp.license.crypto.SecretBox;
import com.inventoryerp.license.domain.AdminRefreshToken;
import com.inventoryerp.license.domain.AdminRefreshTokenRepository;
import com.inventoryerp.license.domain.AdminRole;
import com.inventoryerp.license.domain.AdminUser;
import com.inventoryerp.license.domain.AdminUserRepository;
import com.inventoryerp.license.shared.ApiException;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may administer the licence authority.
 *
 * <p>Deliberate choices, because this login guards the ability to mint licences:
 *
 * <ul>
 *   <li>A wrong password and an unknown email answer identically, and both still cost a bcrypt
 *       verification, so the route cannot be used to enumerate admins or timed to distinguish them.
 *   <li>Failed attempts lock the account for {@code app.admin.lockout} after
 *       {@code app.admin.max-failed-logins}.
 *   <li>Refresh tokens rotate on use and the whole family is revoked on logout; only hashes are
 *       stored, so a database dump cannot be replayed.
 * </ul>
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    /** Issued session. */
    public record Session(String accessToken, String refreshToken, long expiresInSeconds, String email,
            AdminRole role, boolean mfaEnrolled) {}

    /** Returned when the password was right but a TOTP code is still needed. */
    public record MfaRequired(String challenge) {}

    private final AdminUserRepository admins;
    private final AdminRefreshTokenRepository refreshTokens;
    private final AdminTokens tokens;
    private final AdminSessionWrites sessionWrites;
    private final PasswordEncoder passwords;
    private final LicenseProperties properties;
    private final SecretBox box;

    public AdminAuthService(AdminUserRepository admins, AdminRefreshTokenRepository refreshTokens,
            AdminTokens tokens, AdminSessionWrites sessionWrites, PasswordEncoder passwords,
            LicenseProperties properties) {
        this.admins = admins;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.sessionWrites = sessionWrites;
        this.passwords = passwords;
        this.properties = properties;
        this.box = new SecretBox(properties.getSigningPassphrase());
    }

    @Transactional
    public Session login(String email, String password, String totpCode, String ip) {
        Instant now = Instant.now();
        AdminUser admin = admins.findByEmailIgnoreCase(email == null ? "" : email.trim()).orElse(null);

        if (admin == null) {
            // Spend the same work as a real check so the two cases cannot be told apart by timing.
            passwords.matches(password == null ? "" : password, "$2a$12$" + "0".repeat(53));
            throw ApiException.unauthorized("Email or password is incorrect.");
        }
        if (!Boolean.TRUE.equals(admin.getIsActive())) {
            throw ApiException.forbidden("This account is disabled.");
        }
        if (admin.isLocked(now)) {
            throw ApiException.forbidden("Too many failed attempts. Try again later.");
        }
        if (!passwords.matches(password == null ? "" : password, admin.getPasswordHash())) {
            registerFailure(admin, now, ip);
            throw ApiException.unauthorized("Email or password is incorrect.");
        }

        if (admin.hasMfa()) {
            if (totpCode == null || totpCode.isBlank()) {
                throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "A one-time code is required.", java.util.Map.of("mfaRequired", true));
            }
            if (!verifyTotp(admin, totpCode)) {
                registerFailure(admin, now, ip);
                throw ApiException.unauthorized("That one-time code is not valid.");
            }
        }

        admin.setFailedLoginCount(0);
        admin.setLockedUntil(null);
        admin.setLastLoginAt(now);
        admins.save(admin);
        log.info("Admin {} signed in from {}", admin.getEmail(), ip);
        return issue(admin, now);
    }

    /**
     * Exchange a refresh token for a new session. The presented token is revoked whether or not the
     * exchange succeeds, so a stolen token is single-use at worst.
     */
    @Transactional
    public Session refresh(String refreshToken) {
        Instant now = Instant.now();
        AdminRefreshToken row = refreshTokens.findByTokenHash(AdminTokens.hashRefreshToken(
                        refreshToken == null ? "" : refreshToken))
                .orElseThrow(() -> ApiException.unauthorized("That session has ended. Sign in again."));
        boolean usable = row.isUsable(now);
        // Spent on sight, in its own transaction: a token presented once must not still work if
        // what follows throws, or it is not single-use at all.
        sessionWrites.revokeToken(row.getId(), now);
        if (!usable) {
            // Replay of an already-spent token: end every session this admin holds.
            sessionWrites.revokeAllFor(row.getAdminId(), now);
            log.warn("Replayed admin refresh token for {}; all sessions revoked", row.getAdminId());
            throw ApiException.unauthorized("That session has ended. Sign in again.");
        }
        AdminUser admin = admins.findById(row.getAdminId())
                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                .orElseThrow(() -> ApiException.unauthorized("That session has ended. Sign in again."));
        return issue(admin, now);
    }

    public void logout(String adminId) {
        sessionWrites.revokeAllFor(adminId, Instant.now());
    }

    @Transactional(readOnly = true)
    public AdminUser require(String adminId) {
        return admins.findById(adminId)
                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                .orElseThrow(() -> ApiException.unauthorized("That session has ended. Sign in again."));
    }

    @Transactional(readOnly = true)
    public List<AdminUser> list() {
        return admins.findAllByOrderByEmailAsc();
    }

    @Transactional
    public AdminUser create(String email, String password, String displayName, AdminRole role) {
        String clean = email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
        if (clean.isEmpty() || !clean.contains("@")) {
            throw ApiException.badRequest("A valid email address is required.");
        }
        if (admins.findByEmailIgnoreCase(clean).isPresent()) {
            throw ApiException.conflict("An admin with that email already exists.");
        }
        requireStrongPassword(password);
        AdminUser admin = new AdminUser();
        admin.setEmail(clean);
        admin.setPasswordHash(passwords.encode(password));
        admin.setDisplayName(displayName);
        admin.setRole(role.value());
        return admins.saveAndFlush(admin);
    }

    @Transactional
    public void changePassword(String adminId, String currentPassword, String newPassword) {
        AdminUser admin = require(adminId);
        if (!passwords.matches(currentPassword == null ? "" : currentPassword, admin.getPasswordHash())) {
            throw ApiException.unauthorized("Your current password is incorrect.");
        }
        requireStrongPassword(newPassword);
        admin.setPasswordHash(passwords.encode(newPassword));
        admins.save(admin);
        // A password change ends every other session.
        sessionWrites.revokeAllFor(adminId, Instant.now());
    }

    /** Start TOTP enrolment: returns the secret to put in an authenticator app. */
    @Transactional
    public String beginMfaEnrolment(String adminId) {
        AdminUser admin = require(adminId);
        String secret = new DefaultSecretGenerator().generate();
        admin.setTotpSecretCipher(box.seal(secret));
        admin.setTotpEnrolledAt(null);
        admins.save(admin);
        return secret;
    }

    /** Confirm enrolment with a code from the app; until this succeeds MFA is not in force. */
    @Transactional
    public void confirmMfaEnrolment(String adminId, String code) {
        AdminUser admin = require(adminId);
        if (admin.getTotpSecretCipher() == null) {
            throw ApiException.badRequest("Start enrolment first.");
        }
        if (!verifyTotp(admin, code)) {
            throw ApiException.badRequest("That one-time code is not valid.");
        }
        admin.setTotpEnrolledAt(Instant.now());
        admins.save(admin);
    }

    private Session issue(AdminUser admin, Instant now) {
        String refresh = AdminTokens.newRefreshToken();
        AdminRefreshToken row = new AdminRefreshToken();
        row.setAdminId(admin.getId());
        row.setTokenHash(AdminTokens.hashRefreshToken(refresh));
        row.setExpiresAt(now.plus(properties.getAdmin().getRefreshTokenTtl()));
        refreshTokens.saveAndFlush(row);
        return new Session(tokens.accessToken(admin, now), refresh, tokens.accessTtl().toSeconds(),
                admin.getEmail(), admin.roleType(), admin.hasMfa());
    }

    /**
     * Count a failed attempt. Written in its own transaction ({@link AdminSessionWrites}) because
     * the caller is about to throw, and a counter rolled back by the very refusal it was counting
     * is a lockout that never happens.
     */
    private void registerFailure(AdminUser admin, Instant now, String ip) {
        boolean locked = sessionWrites.recordFailure(admin.getId(),
                properties.getAdmin().getMaxFailedLogins(),
                now.plus(properties.getAdmin().getLockout()));
        if (locked) {
            log.warn("Admin {} locked out after {} failed attempts (last from {})",
                    admin.getEmail(), properties.getAdmin().getMaxFailedLogins(), ip);
        }
    }

    private boolean verifyTotp(AdminUser admin, String code) {
        String secret = box.open(admin.getTotpSecretCipher());
        var verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(HashingAlgorithm.SHA1),
                new SystemTimeProvider());
        verifier.setAllowedTimePeriodDiscrepancy(1);
        return verifier.isValidCode(secret, code == null ? "" : code.trim());
    }

    private static void requireStrongPassword(String password) {
        if (password == null || password.length() < 12) {
            throw ApiException.badRequest("A password must be at least 12 characters long.");
        }
    }
}
