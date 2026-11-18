package com.inventoryerp.biexport.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedded Apache Superset dashboards ({@code app.bi.superset.*}, ADR-S078). Off unless {@code url} is set.
 *
 * @param url          base URL the API uses to reach Superset, e.g. {@code http://superset:8088}. Empty = disabled
 * @param publicUrl    base URL the browser loads the dashboard from (defaults to {@code url})
 * @param username     Superset service account that may create guest tokens (never logged)
 * @param password     its password (never logged)
 * @param dashboards   ERP module key ({@code sales}, {@code procurement}, ...) to the Superset embedded dashboard id
 * @param httpTimeout  how long to wait on Superset
 */
@ConfigurationProperties("app.bi.superset")
public record SupersetProperties(
        String url,
        String publicUrl,
        String username,
        String password,
        Map<String, String> dashboards,
        Duration httpTimeout) {

    public SupersetProperties {
        url = url == null ? "" : url.trim().replaceAll("/+$", "");
        publicUrl = publicUrl == null || publicUrl.isBlank() ? url : publicUrl.trim().replaceAll("/+$", "");
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        Map<String, String> clean = new LinkedHashMap<>();
        if (dashboards != null) {
            dashboards.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    clean.put(k, v.trim());
                }
            });
        }
        dashboards = Map.copyOf(clean);
        httpTimeout = httpTimeout == null ? Duration.ofSeconds(10) : httpTimeout;
    }

    /** Is Superset configured at all? */
    public boolean enabled() {
        return !url.isEmpty() && !username.isEmpty();
    }

    /** Never print the password. */
    @Override
    public String toString() {
        return "SupersetProperties[url=" + url + ", publicUrl=" + publicUrl + ", dashboards=" + dashboards.keySet()
                + "]";
    }
}
