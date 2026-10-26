package com.inventoryerp.license.application;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under {@code app.*} in application.yml, in one typed place. */
@ConfigurationProperties(prefix = "app")
public class LicenseProperties {

    /** Prepended to every controller path, so routes read without it. Mirrors the ERP's prefix. */
    private String apiPrefix = "/api/v1";

    /** {@code iss} claim of every minted token. */
    private String issuer = "inventoryerp-license";

    /** Wraps signing private keys and admin TOTP secrets at rest. Required in the prod profile. */
    private String signingPassphrase = "";

    private Admin admin = new Admin();
    private Activation activation = new Activation();
    private Retention retention = new Retention();
    private Reminders reminders = new Reminders();

    public static class Admin {
        private String bootstrapEmail = "";
        private String bootstrapPassword = "";
        private Duration accessTokenTtl = Duration.ofMinutes(30);
        private Duration refreshTokenTtl = Duration.ofDays(14);
        private String jwtSecret = "";
        private int maxFailedLogins = 5;
        private Duration lockout = Duration.ofMinutes(15);

        public String getBootstrapEmail() {
            return bootstrapEmail;
        }

        public void setBootstrapEmail(String bootstrapEmail) {
            this.bootstrapEmail = bootstrapEmail;
        }

        public String getBootstrapPassword() {
            return bootstrapPassword;
        }

        public void setBootstrapPassword(String bootstrapPassword) {
            this.bootstrapPassword = bootstrapPassword;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public int getMaxFailedLogins() {
            return maxFailedLogins;
        }

        public void setMaxFailedLogins(int maxFailedLogins) {
            this.maxFailedLogins = maxFailedLogins;
        }

        public Duration getLockout() {
            return lockout;
        }

        public void setLockout(Duration lockout) {
            this.lockout = lockout;
        }
    }

    public static class Activation {
        private int rateLimitPerMinute = 10;
        /** How long a heartbeat's token is good for before the install should check in again. */
        private Duration refreshAfter = Duration.ofHours(6);

        public int getRateLimitPerMinute() {
            return rateLimitPerMinute;
        }

        public void setRateLimitPerMinute(int rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
        }

        public Duration getRefreshAfter() {
            return refreshAfter;
        }

        public void setRefreshAfter(Duration refreshAfter) {
            this.refreshAfter = refreshAfter;
        }
    }

    public static class Retention {
        private int heartbeatDays = 90;

        public int getHeartbeatDays() {
            return heartbeatDays;
        }

        public void setHeartbeatDays(int heartbeatDays) {
            this.heartbeatDays = heartbeatDays;
        }
    }

    public static class Reminders {
        private boolean enabled = true;
        private List<Integer> daysBefore = List.of(30, 14, 7, 3, 1);
        private String from = "billing@example.com";
        private int silentMultiplier = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<Integer> getDaysBefore() {
            return daysBefore;
        }

        public void setDaysBefore(List<Integer> daysBefore) {
            this.daysBefore = daysBefore;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public int getSilentMultiplier() {
            return silentMultiplier;
        }

        public void setSilentMultiplier(int silentMultiplier) {
            this.silentMultiplier = silentMultiplier;
        }
    }

    public String getApiPrefix() {
        return apiPrefix;
    }

    public void setApiPrefix(String apiPrefix) {
        this.apiPrefix = apiPrefix;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getSigningPassphrase() {
        return signingPassphrase;
    }

    public void setSigningPassphrase(String signingPassphrase) {
        this.signingPassphrase = signingPassphrase;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Activation getActivation() {
        return activation;
    }

    public void setActivation(Activation activation) {
        this.activation = activation;
    }

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention;
    }

    public Reminders getReminders() {
        return reminders;
    }

    public void setReminders(Reminders reminders) {
        this.reminders = reminders;
    }
}
