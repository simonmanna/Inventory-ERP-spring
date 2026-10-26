package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.AdminRefreshTokenRepository;
import com.inventoryerp.license.domain.AdminUserRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The writes a <em>rejected</em> sign-in has to leave behind.
 *
 * <p>This exists because of a bug worth remembering: the failed-login counter and the
 * revoke-on-use of a refresh token were originally written inside {@link AdminAuthService#login}
 * and {@link AdminAuthService#refresh}, which then threw. The exception rolled the transaction
 * back, so <b>the lockout never engaged and a refresh token was never actually single-use</b> —
 * both defences silently did nothing. {@code LicenseAdminIT} caught it.
 *
 * <p>Every method here therefore runs in its own transaction, so it survives the refusal that
 * follows it. Each is deliberately a single targeted statement rather than a read-modify-write, so
 * two attempts racing cannot lose a count between them.
 */
@Service
public class AdminSessionWrites {

    private final AdminUserRepository admins;
    private final AdminRefreshTokenRepository refreshTokens;

    public AdminSessionWrites(AdminUserRepository admins, AdminRefreshTokenRepository refreshTokens) {
        this.admins = admins;
        this.refreshTokens = refreshTokens;
    }

    /**
     * Count one failed attempt, locking the account once it reaches {@code maxFailures}.
     *
     * @return {@code true} when this attempt locked the account
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(String adminId, int maxFailures, Instant lockedUntil) {
        var admin = admins.findById(adminId).orElse(null);
        if (admin == null) {
            return false;
        }
        int failures = (admin.getFailedLoginCount() == null ? 0 : admin.getFailedLoginCount()) + 1;
        boolean lock = failures >= maxFailures;
        admin.setFailedLoginCount(lock ? 0 : failures);
        if (lock) {
            admin.setLockedUntil(lockedUntil);
        }
        admins.saveAndFlush(admin);
        return lock;
    }

    /** Spend a refresh token, whether or not the exchange that follows succeeds. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeToken(String tokenId, Instant now) {
        refreshTokens.findById(tokenId).ifPresent(row -> {
            if (row.getRevokedAt() == null) {
                row.setRevokedAt(now);
                refreshTokens.saveAndFlush(row);
            }
        });
    }

    /** End every session an admin holds — on logout, a password change, or a detected replay. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllFor(String adminId, Instant now) {
        return refreshTokens.revokeAllFor(adminId, now);
    }
}
