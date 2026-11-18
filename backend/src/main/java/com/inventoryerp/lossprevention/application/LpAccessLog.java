package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The evidence access trail (ADR-S077 §8): every view, download, export, request, snapshot and live view, and every
 * upload, integrity failure and deletion. Append-only; a row is written in the caller's transaction.
 */
@Component
public class LpAccessLog {

    public static final String VIEW = "view";
    public static final String DOWNLOAD = "download";
    public static final String EXPORT = "export";
    public static final String REQUEST = "request";
    public static final String SNAPSHOT = "snapshot";
    public static final String LIVE_START = "live_view_start";
    public static final String LIVE_STOP = "live_view_stop";
    public static final String UPLOADED = "uploaded";
    public static final String INTEGRITY_FAILED = "integrity_failed";
    public static final String MISSING = "missing";
    public static final String DELETED = "deleted";

    private final JdbcClient jdbc;
    private final LpScope scope;

    public LpAccessLog(JdbcClient jdbc, LpScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /**
     * Records one access by the current user ({@code null} when the system acted: an upload, retention, a clip a case
     * asked for).
     */
    @Transactional
    public void record(String evidenceId, String cameraId, String caseId, String action, Map<String, Object> detail) {
        jdbc.sql("INSERT INTO \"LpEvidenceAccess\" (id, \"organizationId\", \"evidenceId\", \"cameraId\", \"caseId\","
                + " \"userId\", action, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)")
                .params(java.util.Arrays.asList(UUID.randomUUID().toString(), LpScope.org(), evidenceId, cameraId,
                        caseId, TenantContext.userId(), action, scope.json(detail == null ? Map.of() : detail)))
                .update();
    }
}
