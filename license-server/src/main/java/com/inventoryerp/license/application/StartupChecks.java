package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.AdminRole;
import com.inventoryerp.license.domain.AdminUserRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to run a production licence authority that is not safe to run.
 *
 * <p>Two secrets have no sane default. Without {@code LICENSE_SIGNING_PASSPHRASE} the private keys
 * are sealed with an empty passphrase, which is no protection at all; without
 * {@code LICENSE_ADMIN_JWT_SECRET} every restart silently invalidates admin sessions. Both are
 * fatal under the {@code prod} profile and a loud warning everywhere else.
 *
 * <p>It also makes sure a signing key and a first admin exist, so a fresh deployment is usable
 * without anyone running SQL by hand.
 */
@Component
public class StartupChecks {

    private static final Logger log = LoggerFactory.getLogger(StartupChecks.class);

    private final LicenseProperties properties;
    private final SigningKeyService signingKeys;
    private final AdminUserRepository admins;
    private final AdminAuthService auth;
    private final boolean prod;

    public StartupChecks(LicenseProperties properties, SigningKeyService signingKeys, AdminUserRepository admins,
            AdminAuthService auth, Environment environment) {
        this.properties = properties;
        this.signingKeys = signingKeys;
        this.admins = admins;
        this.auth = auth;
        this.prod = environment.acceptsProfiles(Profiles.of("prod"));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        List<String> missing = new ArrayList<>();
        if (isBlank(properties.getSigningPassphrase())) {
            missing.add("LICENSE_SIGNING_PASSPHRASE (app.signing-passphrase) — wraps the signing private keys");
        }
        if (isBlank(properties.getAdmin().getJwtSecret())) {
            missing.add("LICENSE_ADMIN_JWT_SECRET (app.admin.jwt-secret) — signs admin sessions");
        }
        if (!missing.isEmpty()) {
            String detail = String.join("; ", missing);
            if (prod) {
                throw new IllegalStateException("Refusing to start: " + detail);
            }
            log.warn("Development configuration: {}", detail);
        }

        var key = signingKeys.current();
        log.info("Licence signing key {} is current", key.getKid());

        ensureFirstAdmin();
    }

    /**
     * A deployment with no admin cannot be administered. If bootstrap credentials are configured the
     * owner is created from them; otherwise the server says what to set, rather than inventing a
     * password it would have to print.
     */
    private void ensureFirstAdmin() {
        if (admins.count() > 0) {
            return;
        }
        String email = properties.getAdmin().getBootstrapEmail();
        String password = properties.getAdmin().getBootstrapPassword();
        if (isBlank(email) || isBlank(password)) {
            log.warn("No admin accounts exist. Set LICENSE_ADMIN_EMAIL and LICENSE_ADMIN_PASSWORD "
                    + "(at least 12 characters) and restart to create the first owner.");
            return;
        }
        try {
            auth.create(email, password, "Owner", AdminRole.OWNER);
            log.info("Created the first admin account for {}. Remove LICENSE_ADMIN_PASSWORD from the "
                    + "environment now that it is stored.", email);
        } catch (RuntimeException e) {
            log.error("Could not create the bootstrap admin: {}", e.getMessage());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
