package com.inventoryerp.fleet.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The registered {@link WeighbridgeAdapter} beans by type (fleet 1.4) and the read-timeout cap:
 * {@code app.fleet.weighbridge.max-read-timeout-ms} (env {@code FLEET_WEIGHBRIDGE_MAX_READ_TIMEOUT_MS}, default 15000)
 * bounds every device's own {@code readTimeoutMs}, so no request waits on an indicator longer than that.
 */
@Component
public class WeighbridgeAdapters {

    private final Map<String, WeighbridgeAdapter> byType = new LinkedHashMap<>();
    private final long maxReadTimeoutMs;

    public WeighbridgeAdapters(List<WeighbridgeAdapter> adapters,
            @Value("${app.fleet.weighbridge.max-read-timeout-ms:${FLEET_WEIGHBRIDGE_MAX_READ_TIMEOUT_MS:15000}}")
            long maxReadTimeoutMs) {
        adapters.forEach(a -> byType.put(a.type(), a));
        this.maxReadTimeoutMs = Math.max(500, maxReadTimeoutMs);
    }

    public Optional<WeighbridgeAdapter> get(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    public List<String> available() {
        return List.copyOf(byType.keySet());
    }

    /** The device's timeout, capped. */
    public Duration timeout(Integer deviceTimeoutMs) {
        long ms = deviceTimeoutMs == null ? 5000 : deviceTimeoutMs;
        return Duration.ofMillis(Math.min(Math.max(500, ms), maxReadTimeoutMs));
    }
}
