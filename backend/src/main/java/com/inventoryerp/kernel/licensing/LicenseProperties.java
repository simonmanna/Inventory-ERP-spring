package com.inventoryerp.kernel.licensing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Licensing configuration ({@code app.license.*}).
 *
 * <p>Defaults are chosen so a fresh install is usable and honest: enforcement on, a 14-day trial,
 * and no licence server configured — an install that was handed an offline {@code .lic} file never
 * needs one.
 *
 * @param serverUrl    base URL of the licence authority, e.g. {@code https://licences.example.com}.
 *                     Empty means this install can only be licensed by an offline file.
 * @param trialDays    how long an install with no licence may run before going read-only
 * @param enforce      master switch. {@code false} keeps the licence visible and warns, but never
 *                     refuses a write — for development and for a support session where billing
 *                     must not be what is blocking the diagnosis
 * @param heartbeat    whether to check in on a schedule (needs {@code serverUrl})
 * @param httpTimeout  how long to wait on the licence server before giving up and staying offline
 * @param instanceName what this install calls itself in the authority's list of activations
 */
@ConfigurationProperties("app.license")
public record LicenseProperties(
        String serverUrl,
        Integer trialDays,
        Boolean enforce,
        Boolean heartbeat,
        Duration httpTimeout,
        String instanceName) {

    public LicenseProperties {
        serverUrl = serverUrl == null ? "" : serverUrl.trim().replaceAll("/+$", "");
        trialDays = trialDays == null ? 14 : Math.max(0, trialDays);
        enforce = enforce == null || enforce;
        heartbeat = heartbeat == null || heartbeat;
        httpTimeout = httpTimeout == null ? Duration.ofSeconds(10) : httpTimeout;
    }

    /** Is there an authority to talk to at all? */
    public boolean hasServer() {
        return !serverUrl.isEmpty();
    }

    public String endpoint(String path) {
        return serverUrl + path;
    }
}
