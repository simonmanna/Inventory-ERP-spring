package com.inventoryerp.biexport.application;

import com.inventoryerp.shared.web.EntityJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The Superset settings in force (ADR-S078): {@link SupersetProperties} plus, when configured, the dashboard ids and
 * the service account that the compose stack writes to files at run time (Superset starts after the API, and its
 * dashboards are provisioned after that). The files are re-read when they change, so dashboards appear without an API
 * restart. A file that cannot be read counts as empty: dashboards stay hidden, nothing else breaks.
 */
@Component
public class SupersetSettings {

    private static final Logger log = LoggerFactory.getLogger(SupersetSettings.class);
    private static final Pattern MODULE = Pattern.compile("[a-z_]{1,32}");
    private static final Pattern DASHBOARD_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private record Snapshot(long dashboardsStamp, long secretsStamp, SupersetProperties effective) {}

    private final SupersetProperties base;
    private volatile Snapshot snapshot;

    public SupersetSettings(SupersetProperties base) {
        this.base = base;
    }

    /** The settings now: the configured ones, with the files' values where nothing was set directly. */
    public SupersetProperties current() {
        if (base.dashboardsFile().isEmpty() && base.secretsFile().isEmpty()) {
            return base;
        }
        long dashboardsStamp = stamp(base.dashboardsFile());
        long secretsStamp = stamp(base.secretsFile());
        Snapshot s = snapshot;
        if (s != null && s.dashboardsStamp() == dashboardsStamp && s.secretsStamp() == secretsStamp) {
            return s.effective();
        }
        Map<String, String> secrets = readEnvFile(base.secretsFile());
        SupersetProperties effective = base.withFileValues(secrets.getOrDefault("SUPERSET_EMBED_USERNAME", ""),
                secrets.getOrDefault("SUPERSET_EMBED_PASSWORD", ""), readDashboards(base.dashboardsFile()));
        snapshot = new Snapshot(dashboardsStamp, secretsStamp, effective);
        return effective;
    }

    /** Modification time, or -1 when the file is absent or not configured. */
    private static long stamp(String file) {
        if (file.isEmpty()) {
            return -1;
        }
        try {
            return Files.getLastModifiedTime(Path.of(file)).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    static Map<String, String> readDashboards(String file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file.isEmpty() || !Files.isReadable(Path.of(file))) {
            return out;
        }
        try {
            JsonNode node = EntityJson.tree(Files.readString(Path.of(file), StandardCharsets.UTF_8)).path("dashboards");
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String id = e.getValue().asString("");
                if (MODULE.matcher(e.getKey()).matches() && DASHBOARD_ID.matcher(id).matches()) {
                    out.put(e.getKey(), id);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Superset dashboards file {} unreadable: {}", file, e.getClass().getSimpleName());
        }
        return out;
    }

    /** {@code KEY=value} lines; blank lines, comments and surrounding quotes are ignored. Values are never logged. */
    static Map<String, String> readEnvFile(String file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (file.isEmpty() || !Files.isReadable(Path.of(file))) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
                String l = line.strip();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq <= 0) {
                    continue;
                }
                String value = l.substring(eq + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(l.substring(0, eq).strip(), value);
            }
        } catch (IOException e) {
            log.warn("Superset secrets file {} unreadable: {}", file, e.getClass().getSimpleName());
        }
        return out;
    }
}
