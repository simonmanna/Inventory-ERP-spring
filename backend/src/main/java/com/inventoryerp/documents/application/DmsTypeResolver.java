package com.inventoryerp.documents.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Fast code → {@code DocumentTypeDef.id} resolution for document writers (port of {@code dms-type-resolver.service.ts}).
 * Cached per code; a miss falls back to a single-key query.
 */
@Service
public class DmsTypeResolver {

    private final JdbcClient jdbc;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public DmsTypeResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Reload every code → id pair (boot / after seeding). */
    public void refresh() {
        cache.clear();
        jdbc.sql("SELECT id, code FROM \"DocumentTypeDef\"").query().listOfRows()
                .forEach(r -> cache.put((String) r.get("code"), (String) r.get("id")));
    }

    /** The DocumentTypeDef id for a code; 404 {@code Unknown document type code: x} when absent. */
    public String resolveIdByCode(String code) {
        String cached = cache.get(code);
        if (cached != null) {
            return cached;
        }
        String id = jdbc.sql("SELECT id FROM \"DocumentTypeDef\" WHERE code = ?").param(code).query(String.class)
                .optional().orElseThrow(() -> ApiException.notFound("Unknown document type code: " + code));
        cache.put(code, id);
        return id;
    }

    /** Snapshot of the cache. */
    public Map<String, String> cachedIds() {
        return Map.copyOf(cache);
    }
}
