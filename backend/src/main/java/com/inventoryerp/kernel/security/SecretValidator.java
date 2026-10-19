package com.inventoryerp.kernel.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuse-to-start guard for the {@code prod} profile (port of {@code kernel/config/env.ts}): JWT secrets
 * must be set, at least 32 characters, not a known placeholder and distinct per token type.
 */
@Component
@Profile("prod")
public class SecretValidator {

    private static final Logger log = LoggerFactory.getLogger(SecretValidator.class);

    static final Set<String> PLACEHOLDER_SECRETS = Set.of(
            "dev-access-secret-change-me", "dev-refresh-secret-change-me", "", "changeme", "secret",
            "change-me-in-prod-32-chars-minimum-secret",
            "dev-only-access-secret-change-me-32bytes!!", "dev-only-refresh-secret-change-me-32bytes!",
            "dev-only-pos-secret-change-me-32bytes!!!!!");
    static final List<Pattern> WEAK_SECRET_PATTERNS = List.of(
            Pattern.compile("^change[-_ ]?me", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^please[-_ ]?change", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^your[-_ ]?secret", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^dev-only-", Pattern.CASE_INSENSITIVE));
    static final int MIN_SECRET_LENGTH = 32;

    public SecretValidator(JwtProperties props) {
        List<String> errors = validate(props);
        if (!errors.isEmpty()) {
            errors.forEach(log::error);
            throw new IllegalStateException("Refusing to start: " + String.join("; ", errors));
        }
    }

    static List<String> validate(JwtProperties props) {
        List<String> errors = new ArrayList<>();
        check("JWT_ACCESS_SECRET", props.accessSecret(), errors);
        check("JWT_REFRESH_SECRET", props.refreshSecret(), errors);
        String access = props.accessSecret();
        if (access != null && access.equals(props.refreshSecret())) {
            errors.add("JWT_ACCESS_SECRET and JWT_REFRESH_SECRET must be different");
        }
        String pos = props.posSecret();
        if (pos != null && !pos.isEmpty()) {
            if (pos.length() < MIN_SECRET_LENGTH) {
                errors.add("JWT_POS_SECRET must be at least " + MIN_SECRET_LENGTH + " characters long");
            }
            if (PLACEHOLDER_SECRETS.contains(pos) || WEAK_SECRET_PATTERNS.stream().anyMatch(p -> p.matcher(pos).find())) {
                errors.add("JWT_POS_SECRET is set to a known placeholder; refuse to start");
            }
            if (pos.equals(access)) {
                errors.add("JWT_POS_SECRET must differ from JWT_ACCESS_SECRET");
            }
        } else {
            log.warn("JWT_POS_SECRET is not set — POS cashier tokens are signed with JWT_ACCESS_SECRET. Set a distinct secret.");
        }
        return errors;
    }

    private static void check(String key, String value, List<String> errors) {
        if (value == null || value.isEmpty()) {
            errors.add(key + " is not set");
            return;
        }
        if (PLACEHOLDER_SECRETS.contains(value)) {
            errors.add(key + " is set to a known development placeholder; refuse to start");
        }
        if (WEAK_SECRET_PATTERNS.stream().anyMatch(p -> p.matcher(value).find())) {
            errors.add(key + " looks like an unedited placeholder; refuse to start");
        }
        if (value.length() < MIN_SECRET_LENGTH) {
            errors.add(key + " must be at least " + MIN_SECRET_LENGTH + " characters long");
        }
    }
}
