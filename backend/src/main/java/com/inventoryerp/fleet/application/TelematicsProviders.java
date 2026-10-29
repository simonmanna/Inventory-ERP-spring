package com.inventoryerp.fleet.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The registered {@link TelematicsProvider} beans by type and the configured default (fleet 1.8):
 * {@code app.fleet.telematics.provider} (env {@code FLEET_TELEMATICS_PROVIDER}, default {@code mock}). A real vendor's
 * base URL and API key come from {@code app.fleet.telematics.base-url} / {@code api-key} (env
 * {@code FLEET_TELEMATICS_URL} / {@code FLEET_TELEMATICS_API_KEY}); only whether they are set is ever exposed.
 */
@Component
public class TelematicsProviders {

    private final Map<String, TelematicsProvider> byType = new LinkedHashMap<>();
    private final String configured;
    private final boolean baseUrlConfigured;
    private final boolean apiKeyConfigured;
    private final int retentionDays;

    public TelematicsProviders(List<TelematicsProvider> providers,
            @Value("${app.fleet.telematics.provider:${FLEET_TELEMATICS_PROVIDER:mock}}") String configured,
            @Value("${app.fleet.telematics.base-url:${FLEET_TELEMATICS_URL:}}") String baseUrl,
            @Value("${app.fleet.telematics.api-key:${FLEET_TELEMATICS_API_KEY:}}") String apiKey,
            @Value("${app.fleet.telematics.retention-days:${FLEET_TELEMATICS_RETENTION_DAYS:365}}") int retentionDays) {
        providers.forEach(p -> byType.put(p.type(), p));
        this.configured = configured == null || configured.isBlank() ? MockTelematicsProvider.TYPE : configured.trim();
        this.baseUrlConfigured = baseUrl != null && !baseUrl.isBlank();
        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        this.retentionDays = Math.max(7, retentionDays);
    }

    public Optional<TelematicsProvider> get(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** The provider new devices use. */
    public String configured() {
        return configured;
    }

    public List<String> available() {
        return List.copyOf(byType.keySet());
    }

    public boolean baseUrlConfigured() {
        return baseUrlConfigured;
    }

    public boolean apiKeyConfigured() {
        return apiKeyConfigured;
    }

    /** Positions older than this many days are purged by the sync job (minimum 7). */
    public int retentionDays() {
        return retentionDays;
    }
}
