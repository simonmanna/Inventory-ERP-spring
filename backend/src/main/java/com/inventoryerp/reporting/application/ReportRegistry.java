package com.inventoryerp.reporting.application;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportProvider;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every report of the library: engine reports from the {@link ReportProvider} beans and links to reports that keep
 * their own screens ({@code reporting/catalogue.json}). Ids are unique and every permission is a known one; a bad
 * definition fails startup.
 */
@Component
public class ReportRegistry {

    private final Map<String, ReportDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, ReportProvider> providers = new LinkedHashMap<>();

    public ReportRegistry(List<ReportProvider> beans, JsonMapper json) {
        for (ReportProvider p : beans) {
            for (ReportDefinition d : p.definitions()) {
                add(d);
                providers.put(d.id(), p);
            }
        }
        for (ReportDefinition d : links(json)) {
            add(d);
        }
    }

    private void add(ReportDefinition d) {
        if (definitions.containsKey(d.id())) {
            throw new IllegalStateException("Duplicate report id " + d.id());
        }
        if (!Permissions.ALL.contains(d.permission())) {
            throw new IllegalStateException("Report " + d.id() + " has unknown permission " + d.permission());
        }
        definitions.put(d.id(), d);
    }

    static List<ReportDefinition> links(JsonMapper json) {
        try (InputStream in = ReportRegistry.class.getResourceAsStream("/reporting/catalogue.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing reporting/catalogue.json");
            }
            List<ReportDefinition> out = new ArrayList<>();
            for (JsonNode n : json.readTree(in)) {
                out.add(new ReportDefinition(text(n, "id"), ReportCategory.valueOf(text(n, "category")),
                        text(n, "title"), text(n, "description"), text(n, "level"), text(n, "permission"), List.of(),
                        false, text(n, "webRoute"), text(n, "apiRoute")));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    public Collection<ReportDefinition> all() {
        return definitions.values();
    }

    public Optional<ReportDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    ReportProvider provider(String id) {
        return providers.get(id);
    }
}
