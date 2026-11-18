package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.files.FileStorage;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.lossprevention.application.LpEvidenceCipher.IntegrityException;
import com.inventoryerp.lossprevention.application.LpVisionCommands.ClipInput;
import com.inventoryerp.lossprevention.application.LpVisionCommands.EvidenceQuery;
import com.inventoryerp.lossprevention.application.LpVisionCommands.EvidenceRangeInput;
import com.inventoryerp.lossprevention.application.LpVisionDirectory.AgentRef;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evidence clips (LP1, ADR-S077 §8). A clip stays on the edge box until a case or an investigator needs it; then the
 * box uploads it in chunks, the backend checks it against the SHA-256 registered at capture, encrypts it and keeps it
 * in the file storage. Every view, download, export and request is written to {@code LpEvidenceAccess}.
 *
 * <p>Nobody sees a clip linked to a case about themselves, nor one of a branch outside their data scope.
 */
@Service
public class LpEvidenceService {

    /** How long a requested upload waits for the box before it lapses. */
    static final Duration UPLOAD_TTL = Duration.ofHours(6);
    /** A partial upload untouched this long is dropped; the box starts again when asked. */
    static final Duration STALE_UPLOAD = Duration.ofDays(1);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final String RANGE_CONTENT_TYPE = "video/mp4";

    private static final String COLUMNS = "e.id, e.\"branchId\", b.name AS \"branchName\", e.\"agentId\", a.name AS"
            + " \"agentName\", e.\"cameraId\", c.name AS \"cameraName\", e.\"zoneId\", z.name AS \"zoneName\","
            + " z.\"zoneType\", e.\"clipId\", e.kind, e.origin, e.\"startedAt\", e.\"endedAt\", e.sha256, e.\"byteSize\","
            + " e.\"contentType\", e.state, e.\"observationEventIds\", e.\"requestedAt\", e.\"requestedBy\","
            + " e.\"requestReason\", e.\"receivedBytes\", e.\"uploadedAt\", e.encryption, e.\"retainUntil\","
            + " e.\"deletedAt\", e.\"deleteReason\", e.\"createdAt\", e.\"updatedAt\", coalesce((SELECT"
            + " array_agg(ec.\"caseId\" ORDER BY ec.\"linkedAt\") FROM \"LpEvidenceCase\" ec WHERE ec.\"evidenceId\" ="
            + " e.id), ARRAY[]::text[]) AS \"caseIds\"";
    private static final String FROM = " FROM \"LpEvidence\" e JOIN \"LpCamera\" c ON c.id = e.\"cameraId\" JOIN"
            + " \"LpAgent\" a ON a.id = e.\"agentId\" LEFT JOIN \"LpZone\" z ON z.id = e.\"zoneId\" LEFT JOIN \"Branch\" b"
            + " ON b.id = e.\"branchId\"";

    private final JdbcClient jdbc;
    private final LpScope scope;
    private final LpAgentCommandService commands;
    private final LpAccessLog access;
    private final LpEvidenceCipher cipher;
    private final FileStorage storage;
    private final LpSettingsService settings;
    private final Clock clock = Clock.systemUTC();

    public LpEvidenceService(JdbcClient jdbc, LpScope scope, LpAgentCommandService commands, LpAccessLog access,
            LpEvidenceCipher cipher, FileStorage storage, LpSettingsService settings) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.commands = commands;
        this.access = access;
        this.cipher = cipher;
        this.storage = storage;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ visibility

    /** {@code WHERE} for evidence the caller may see: their organization and branches, no case about themselves. */
    private String visible(List<Object> params) {
        String w = scope.where("e", params) + " AND NOT EXISTS (SELECT 1 FROM \"LpEvidenceCase\" x JOIN \"LpCase\" k ON"
                + " k.id = x.\"caseId\" WHERE x.\"evidenceId\" = e.id AND k.\"subjectUserId\" = ?)";
        params.add(TenantContext.userId());
        return w;
    }

    private Map<String, Object> load(String id) {
        List<Object> params = new ArrayList<>();
        String w = visible(params) + " AND e.id = ?";
        params.add(id);
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT " + COLUMNS + ", e.\"storageKey\"" + FROM + w)
                .params(params));
        if (row == null) {
            throw ApiException.notFound("Evidence not found");
        }
        return row;
    }

    private static Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        out.remove("storageKey");
        return out;
    }

    /** The case when the caller may see it and it is not about them; otherwise 404. */
    private void assertCase(String caseId) {
        List<Object> params = new ArrayList<>();
        String w = scope.caseWhere("c", params) + " AND c.id = ?";
        params.add(caseId);
        if (jdbc.sql("SELECT count(*) FROM \"LpCase\" c" + w).params(params).query(Long.class).single() == 0) {
            throw ApiException.notFound("Case not found");
        }
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(EvidenceQuery q) {
        int page = q.page() == null ? 1 : Math.max(1, q.page());
        int size = q.pageSize() == null ? 50 : Math.min(200, Math.max(1, q.pageSize()));
        List<Object> params = new ArrayList<>();
        StringBuilder w = new StringBuilder(visible(params));
        if (LpScope.has(q.caseId())) {
            w.append(" AND EXISTS (SELECT 1 FROM \"LpEvidenceCase\" ec WHERE ec.\"evidenceId\" = e.id AND ec.\"caseId\""
                    + " = ?)");
            params.add(q.caseId());
        }
        String[][] equals = {{"cameraId", q.cameraId()}, {"zoneId", q.zoneId()}, {"state", q.state()}};
        for (String[] f : equals) {
            if (LpScope.has(f[1])) {
                w.append(" AND e.\"").append(f[0]).append("\" = ?");
                params.add(f[1]);
            }
        }
        if (LpScope.has(q.state()) && !LpVisionCatalog.EVIDENCE_STATES.contains(q.state())) {
            throw ApiException.badRequest("state must be one of: " + String.join(", ", LpVisionCatalog.EVIDENCE_STATES));
        }
        if (LpScope.has(q.from())) {
            w.append(" AND e.\"endedAt\" >= ?");
            params.add(SqlRows.ts(bound(q.from(), "from", false)));
        }
        if (LpScope.has(q.to())) {
            w.append(" AND e.\"startedAt\" <= ?");
            params.add(SqlRows.ts(bound(q.to(), "to", true)));
        }
        long total = jdbc.sql("SELECT count(*)" + FROM + w).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        return PageResult.of(SqlRows.list(jdbc.sql("SELECT " + COLUMNS + FROM + w + " ORDER BY e.\"startedAt\" DESC,"
                + " e.id LIMIT ? OFFSET ?").params(pageParams)), page, size, total);
    }

    /** The clip's metadata, its cases, the observations it shows and its access trail. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> out = view(load(id));
        out.put("cases", SqlRows.list(jdbc.sql("SELECT k.id, k.\"caseNumber\", k.title, k.\"ruleCode\", k.state,"
                + " k.severity, ec.\"linkedAt\", ec.\"linkedBy\" FROM \"LpEvidenceCase\" ec JOIN \"LpCase\" k ON k.id ="
                + " ec.\"caseId\" WHERE ec.\"evidenceId\" = ? ORDER BY ec.\"linkedAt\"").param(id)));
        out.put("observations", SqlRows.list(jdbc.sql("SELECT id, \"eventId\", type, \"occurredAt\", \"deviceId\","
                + " confidence, \"observationStatus\", payload FROM \"LpEvent\" WHERE \"organizationId\" = ? AND id ="
                + " ANY(?::text[]) ORDER BY \"occurredAt\", seq").params(LpScope.org(),
                        textArray(out.get("observationEventIds")))));
        out.put("access", trail(id));
        return out;
    }

    private List<Map<String, Object>> trail(String id) {
        return SqlRows.list(jdbc.sql("SELECT t.id, t.action, t.\"userId\", coalesce(nullif(trim(coalesce(u.\"firstName\","
                + " '') || ' ' || coalesce(u.\"lastName\", '')), ''), u.email) AS \"userName\", t.\"caseId\", t.detail,"
                + " t.\"createdAt\" FROM \"LpEvidenceAccess\" t LEFT JOIN \"User\" u ON u.id = t.\"userId\" WHERE"
                + " t.\"organizationId\" = ? AND t.\"evidenceId\" = ? ORDER BY t.\"createdAt\", t.id")
                .params(LpScope.org(), id));
    }

    /** A PostgreSQL text array literal; each element quoted and escaped. */
    static String textArray(Object list) {
        StringBuilder sb = new StringBuilder("{");
        if (list instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                String v = String.valueOf(l.get(i)).replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append(i == 0 ? "" : ",").append('"').append(v).append('"');
            }
        }
        return sb.append('}').toString();
    }

    /** A decrypted clip. */
    public record Content(String filename, String contentType, byte[] bytes) {}

    private static String extension(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "video/webm" -> "webm";
            case "video/x-matroska" -> "mkv";
            case "image/jpeg" -> "jpg";
            default -> "mp4";
        };
    }

    /**
     * The clip, decrypted and checked against its SHA-256. A clip that fails is recorded and answers 409; the
     * failure stays recorded although the request fails.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Content content(String id, boolean download) {
        Map<String, Object> e = load(id);
        byte[] clip = readStored(e);
        access.record(id, (String) e.get("cameraId"), null, download ? LpAccessLog.DOWNLOAD : LpAccessLog.VIEW,
                Obj.of("sha256", e.get("sha256")));
        return new Content("evidence-" + id + "." + extension((String) e.get("contentType")),
                (String) e.get("contentType"), clip);
    }

    private byte[] readStored(Map<String, Object> e) {
        String state = (String) e.get("state");
        if ("deleted".equals(state)) {
            throw new ApiException(HttpStatus.GONE, "The clip was deleted under the retention rules");
        }
        if ("integrity_failed".equals(state)) {
            throw ApiException.conflict("The clip does not match its checksum; it may have been altered");
        }
        if (!"uploaded".equals(state) || e.get("storageKey") == null) {
            throw ApiException.conflict("The clip is not uploaded yet");
        }
        String id = (String) e.get("id");
        String encryption = (String) e.get("encryption");
        if (encryption != null && !encryption.endsWith(";key=" + cipher.keyId())) {
            throw ApiException.conflict("The clip was stored under another evidence key; set LP_EVIDENCE_KEY back to"
                    + " the key it was stored with to read it");
        }
        byte[] stored;
        try (InputStream in = storage.get((String) e.get("storageKey"))) {
            stored = in.readAllBytes();
        } catch (IOException | RuntimeException ex) {
            throw ApiException.conflict("The stored clip could not be read");
        }
        try {
            byte[] clip = cipher.decrypt(id, stored);
            if (!LpEvidenceCipher.sha256(clip).equals(e.get("sha256"))) {
                throw new IntegrityException("SHA-256 differs from the one registered at capture");
            }
            return clip;
        } catch (IntegrityException ex) {
            jdbc.sql("UPDATE \"LpEvidence\" SET state = 'integrity_failed', \"updatedAt\" = CURRENT_TIMESTAMP WHERE id"
                    + " = ?").param(id).update();
            access.record(id, (String) e.get("cameraId"), null, LpAccessLog.INTEGRITY_FAILED, Obj.of("at", "read",
                    "reason", ex.getMessage()));
            throw ApiException.conflict("The clip does not match its checksum; it may have been altered");
        }
    }

    /** A ZIP with the clip, {@code manifest.json} and the access trail, for handing to someone outside the system. */
    @Transactional(noRollbackFor = ApiException.class)
    public Content export(String id) {
        Map<String, Object> e = load(id);
        byte[] clip = readStored(e);
        access.record(id, (String) e.get("cameraId"), null, LpAccessLog.EXPORT, Obj.of("sha256", e.get("sha256")));
        Map<String, Object> full = get(id);
        String clipName = "clip." + extension((String) e.get("contentType"));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "inventory-erp-lp-evidence/1");
        manifest.put("exportedAt", clock.instant().toString());
        manifest.put("exportedBy", TenantContext.userId());
        manifest.put("file", clipName);
        manifest.put("sha256", e.get("sha256"));
        manifest.put("byteSize", e.get("byteSize"));
        manifest.put("evidence", full.entrySet().stream().filter(x -> !"access".equals(x.getKey()))
                .collect(LinkedHashMap::new, (m, x) -> m.put(x.getKey(), x.getValue()), Map::putAll));
        ByteArrayOutputStream zip = new ByteArrayOutputStream(clip.length + 8192);
        try (ZipOutputStream out = new ZipOutputStream(zip)) {
            put(out, clipName, clip);
            put(out, "manifest.json", scope.json(manifest).getBytes(StandardCharsets.UTF_8));
            put(out, "access-log.json", scope.json(full.get("access")).getBytes(StandardCharsets.UTF_8));
            put(out, "SHA256SUMS", (e.get("sha256") + "  " + clipName + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return new Content("evidence-" + id + ".zip", "application/zip", zip.toByteArray());
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    /**
     * A filter bound: an ISO instant, or a business date ({@code YYYY-MM-DD}) taken from the start of that day, or to
     * its end for {@code to}, in the business time zone.
     */
    private static Instant bound(String v, String field, boolean end) {
        if (v.length() == 10) {
            java.time.LocalDate d = LpEventValidator.date(v);
            if (d == null) {
                throw ApiException.badRequest(field + " must be an ISO date (YYYY-MM-DD) or an ISO-8601 instant");
            }
            java.time.ZoneId zone = com.inventoryerp.kernel.common.BusinessZone.get();
            return end ? d.plusDays(1).atStartOfDay(zone).toInstant().minusMillis(1)
                    : d.atStartOfDay(zone).toInstant();
        }
        Instant at = LpEventValidator.instant(v);
        if (at == null) {
            throw ApiException.badRequest(field + " must be an ISO date (YYYY-MM-DD) or an ISO-8601 instant");
        }
        return at;
    }

    // ------------------------------------------------------------------ requests

    private static Instant instant(String v, String field) {
        Instant at = LpEventValidator.instant(v);
        if (at == null) {
            throw ApiException.badRequest(field + " must be an ISO-8601 instant");
        }
        return at;
    }

    private void link(String evidenceId, String caseId, boolean auto) {
        int added = jdbc.sql("INSERT INTO \"LpEvidenceCase\" (\"organizationId\", \"evidenceId\", \"caseId\", \"linkedBy\")"
                + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
                .params(java.util.Arrays.asList(LpScope.org(), evidenceId, caseId, TenantContext.userId())).update();
        if (added > 0) {
            jdbc.sql("INSERT INTO \"LpCaseEvent\" (id, \"organizationId\", \"caseId\", kind, \"actorId\", data) VALUES"
                    + " (?, ?, ?, 'evidence_linked', ?, ?::jsonb)")
                    .params(java.util.Arrays.asList(UUID.randomUUID().toString(), LpScope.org(), caseId,
                            TenantContext.userId(), scope.json(Obj.of("evidenceId", evidenceId, "auto", auto))))
                    .update();
        }
    }

    private void assertAgentUsable(String agentId) {
        String status = jdbc.sql("SELECT status FROM \"LpAgent\" WHERE id = ?").param(agentId).query(String.class)
                .optional().orElse(null);
        if (!"active".equals(status)) {
            throw ApiException.conflict("The edge agent of this camera is not enrolled or was revoked");
        }
    }

    /** Asks the box to upload a registered clip, and links it to a case when one is named. */
    @Transactional
    public Map<String, Object> request(String id, String caseId, String reason) {
        Map<String, Object> e = load(id);
        if (LpScope.has(caseId)) {
            assertCase(caseId);
        }
        ask(e, caseId, reason, false);
        return get(id);
    }

    private void ask(Map<String, Object> e, String caseId, String reason, boolean auto) {
        String id = (String) e.get("id");
        String state = (String) e.get("state");
        if ("deleted".equals(state)) {
            throw new ApiException(HttpStatus.GONE, "The clip was deleted under the retention rules");
        }
        if ("missing".equals(state)) {
            throw ApiException.conflict("The edge agent no longer has this clip");
        }
        if (LpScope.has(caseId)) {
            link(id, caseId, auto);
        }
        if (!"uploaded".equals(state)) {
            assertAgentUsable((String) e.get("agentId"));
            if ("integrity_failed".equals(state)) {
                // A fresh copy from the box: what was received is dropped.
                jdbc.sql("DELETE FROM \"LpEvidenceChunk\" WHERE \"evidenceId\" = ?").param(id).update();
                jdbc.sql("UPDATE \"LpEvidence\" SET \"receivedBytes\" = 0 WHERE id = ?").param(id).update();
            }
            jdbc.sql("UPDATE \"LpEvidence\" SET state = CASE WHEN state = 'uploading' THEN state ELSE 'requested' END,"
                    + " \"requestedAt\" = CURRENT_TIMESTAMP, \"requestedBy\" = ?, \"requestReason\" = coalesce(?,"
                    + " \"requestReason\"), \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                    .params(java.util.Arrays.asList(TenantContext.userId(), reason, id)).update();
            if (!commands.uploadPending(id)) {
                commands.queue((String) e.get("agentId"), "clip_upload", Obj.of("evidenceId", id, "cameraId",
                        e.get("cameraId"), "clipId", e.get("clipId"), "from", iso(e.get("startedAt")), "to",
                        iso(e.get("endedAt"))), UPLOAD_TTL);
            }
        } else {
            retain(id);
        }
        access.record(id, (String) e.get("cameraId"), LpScope.has(caseId) ? caseId : null, LpAccessLog.REQUEST,
                Obj.of("reason", reason, "auto", auto));
    }

    private static String iso(Object v) {
        return v instanceof Instant i ? i.toString() : v == null ? null : String.valueOf(v);
    }

    /** A clip cut from the box's rolling buffer: at most ten minutes, within the buffer days. */
    @Transactional
    public Map<String, Object> range(EvidenceRangeInput dto) {
        if (LpScope.has(dto.caseId())) {
            assertCase(dto.caseId());
        }
        List<Object> params = new ArrayList<>();
        String w = scope.where("c", params) + " AND c.id = ?";
        params.add(dto.cameraId());
        Map<String, Object> cam = SqlRows.one(jdbc.sql("SELECT c.id, c.\"agentId\", c.\"branchId\" FROM \"LpCamera\" c"
                + w).params(params));
        if (cam == null) {
            throw ApiException.notFound("Camera not found");
        }
        String id = cut(cam, instant(dto.from(), "from"), instant(dto.to(), "to"), dto.caseId(), dto.reason(), false);
        return get(id);
    }

    private String cut(Map<String, Object> cam, Instant from, Instant to, String caseId, String reason, boolean auto) {
        Instant now = clock.instant();
        if (!to.isAfter(from)) {
            throw ApiException.badRequest("to must be after from");
        }
        if (Duration.between(from, to).getSeconds() > LpVisionCatalog.MAX_CLIP_SECONDS) {
            throw ApiException.badRequest("A clip from the buffer covers at most 10 minutes");
        }
        if (to.isAfter(now)) {
            throw ApiException.badRequest("to must not be in the future");
        }
        int bufferDays = settings.effective((String) cam.get("branchId")).get(LpSettingsService.EVIDENCE_BUFFER_DAYS);
        if (from.isBefore(now.minus(Duration.ofDays(bufferDays)))) {
            throw ApiException.badRequest("The edge box keeps video for " + bufferDays + " days only");
        }
        assertAgentUsable((String) cam.get("agentId"));
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"LpEvidence\" (id, \"organizationId\", \"branchId\", \"agentId\", \"cameraId\", origin,"
                + " \"startedAt\", \"endedAt\", state, \"requestedAt\", \"requestedBy\", \"requestReason\") VALUES (?, ?, ?,"
                + " ?, ?, 'range', ?, ?, 'requested', CURRENT_TIMESTAMP, ?, ?)")
                .params(java.util.Arrays.asList(id, LpScope.org(), cam.get("branchId"), cam.get("agentId"),
                        cam.get("id"), SqlRows.ts(from), SqlRows.ts(to), TenantContext.userId(), reason))
                .update();
        if (LpScope.has(caseId)) {
            link(id, caseId, auto);
        }
        commands.queue((String) cam.get("agentId"), "clip_upload", Obj.of("evidenceId", id, "cameraId",
                cam.get("id"), "clipId", null, "from", from.toString(), "to", to.toString()), UPLOAD_TTL);
        access.record(id, (String) cam.get("id"), LpScope.has(caseId) ? caseId : null, LpAccessLog.REQUEST,
                Obj.of("reason", reason, "auto", auto, "from", from.toString(), "to", to.toString()));
        return id;
    }

    // ------------------------------------------------------------------ for a new case (system)

    /**
     * Links a registered clip to a case and asks for it, as the system. A clip that is gone is skipped. Returns
     * whether the clip was linked.
     */
    @Transactional
    public boolean requestForCase(String evidenceId, String caseId, String reason) {
        Map<String, Object> e = SqlRows.one(jdbc.sql("SELECT e.*" + " FROM \"LpEvidence\" e WHERE e.\"organizationId\" ="
                + " ? AND e.id = ?").params(LpScope.org(), evidenceId));
        if (e == null || "deleted".equals(e.get("state")) || "missing".equals(e.get("state"))) {
            return false;
        }
        try {
            ask(e, caseId, reason, true);
            return true;
        } catch (ApiException ex) {
            // The agent was revoked: link the clip anyway, so the case shows what was registered.
            link(evidenceId, caseId, true);
            return true;
        }
    }

    /** A clip cut from a camera's buffer for a case, as the system; {@code null} when it cannot be cut. */
    @Transactional
    public String rangeForCase(String cameraId, Instant from, Instant to, String caseId, String reason) {
        Map<String, Object> cam = SqlRows.one(jdbc.sql("SELECT c.id, c.\"agentId\", c.\"branchId\" FROM \"LpCamera\" c"
                + " JOIN \"LpAgent\" a ON a.id = c.\"agentId\" WHERE c.\"organizationId\" = ? AND c.id = ? AND"
                + " c.enabled AND a.status = 'active'").params(LpScope.org(), cameraId));
        if (cam == null) {
            return null;
        }
        Instant now = clock.instant();
        Instant end = to.isAfter(now) ? now : to;
        if (!end.isAfter(from)) {
            return null;
        }
        try {
            return cut(cam, from, end, caseId, reason, true);
        } catch (ApiException ex) {
            return null;
        }
    }

    /** Findings about a POS event at a till: a clip of the till's cameras around the event is asked for. */
    static final java.util.Set<String> TILL_RULES = java.util.Set.of("C01", "C02", "C03", "V-C2");
    static final int MAX_CASE_CLIPS = 6;
    static final int MAX_CASE_CAMERAS = 4;
    static final int MAX_CASE_POS_EVENTS = 3;

    /**
     * Gathers the video for a case that just opened, as the system: the clips registered for the observations behind
     * its finding, and for a finding about a POS event at a till with cameras, a clip of each such camera from
     * {@value LpVisionCatalog#CASE_CLIP_BEFORE_SECONDS} s before to {@value LpVisionCatalog#CASE_CLIP_AFTER_SECONDS} s
     * after the event. Each clip is linked to the case. Returns how many were linked.
     */
    @Transactional
    public int forNewCase(String caseId) {
        String org = LpScope.org();
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT k.id, k.\"caseNumber\", k.\"ruleCode\","
                + " d.\"evidenceEventIds\", d.detail FROM \"LpCase\" k JOIN \"LpDiscrepancy\" d ON d.id ="
                + " k.\"currentDiscrepancyId\" WHERE k.id = ? AND k.\"organizationId\" = ?").params(caseId, org));
        if (c == null) {
            return 0;
        }
        String reason = "Case " + c.get("caseNumber");
        String events = textArray(c.get("evidenceEventIds"));
        List<String> clips = jdbc.sql("SELECT v.id FROM \"LpEvidence\" v WHERE"
                + " v.\"organizationId\" = ? AND v.state NOT IN ('deleted', 'missing') AND (v.\"observationEventIds\" &&"
                + " ?::text[] OR EXISTS (SELECT 1 FROM \"LpEvent\" e WHERE e.\"organizationId\" = v.\"organizationId\""
                + " AND e.id = ANY(?::text[]) AND e.payload ->> 'clipId' = v.\"clipId\" AND e.payload ->> 'cameraId' ="
                + " v.\"cameraId\")) ORDER BY v.\"startedAt\", v.id LIMIT " + MAX_CASE_CLIPS)
                .params(org, events, events).query(String.class).list();
        int linked = 0;
        for (String id : clips) {
            linked += requestForCase(id, caseId, reason) ? 1 : 0;
        }
        if (!TILL_RULES.contains((String) c.get("ruleCode"))) {
            return linked;
        }
        String detailTill = (String) LpJson.map(c.get("detail")).get("tillId");
        for (Map<String, Object> e : SqlRows.list(jdbc.sql("SELECT e.id, e.\"occurredAt\", e.\"sessionId\","
                + " e.payload ->> 'tillId' AS \"tillId\" FROM \"LpEvent\" e WHERE e.\"organizationId\" = ? AND e.id ="
                + " ANY(?::text[]) AND e.\"evidenceSource\" = 'pos' ORDER BY e.\"occurredAt\", e.seq LIMIT "
                + MAX_CASE_POS_EVENTS).params(org, events))) {
            String till = (String) e.get("tillId");
            if (till == null && e.get("sessionId") != null) {
                till = jdbc.sql("SELECT payload ->> 'tillId' FROM \"LpEvent\" WHERE \"organizationId\" = ? AND type ="
                        + " 'cash_session.opened' AND \"sessionId\" = ? ORDER BY \"occurredAt\" LIMIT 1")
                        .params(org, e.get("sessionId")).query(String.class).optional().orElse(null);
            }
            if (till == null) {
                till = detailTill;
            }
            if (till == null) {
                continue;
            }
            Instant at = (Instant) e.get("occurredAt");
            for (String camera : jdbc.sql("SELECT DISTINCT z.\"cameraId\" FROM \"LpZone\" z JOIN \"LpCamera\" cam ON"
                    + " cam.id = z.\"cameraId\" WHERE z.\"organizationId\" = ? AND z.\"linkedType\" = 'till' AND"
                    + " z.\"linkedId\" = ? AND z.\"retiredAt\" IS NULL AND cam.enabled ORDER BY z.\"cameraId\" LIMIT "
                    + MAX_CASE_CAMERAS).params(org, till).query(String.class).list()) {
                String id = rangeForCase(camera, at.minusSeconds(LpVisionCatalog.CASE_CLIP_BEFORE_SECONDS),
                        at.plusSeconds(LpVisionCatalog.CASE_CLIP_AFTER_SECONDS), caseId, reason);
                linked += id == null ? 0 : 1;
            }
        }
        return linked;
    }

    // ------------------------------------------------------------------ the box

    /** Registers clips the box kept; each is {@code registered}, {@code duplicate} or {@code rejected}. */
    @Transactional
    public Map<String, Object> register(AgentRef agent, List<ClipInput> clips) {
        String org = LpScope.org();
        Map<String, String> cameras = new HashMap<>();
        for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT id, \"branchId\" FROM \"LpCamera\" WHERE"
                + " \"organizationId\" = ? AND \"agentId\" = ?").params(org, agent.id()))) {
            cameras.put((String) c.get("id"), (String) c.get("branchId"));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int registered = 0;
        for (ClipInput clip : clips) {
            String reason = clipReason(clip, cameras.keySet());
            if (reason == null && LpScope.has(clip.zoneId()) && jdbc.sql("SELECT count(*) FROM \"LpZone\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"cameraId\" = ?").params(clip.zoneId(), org, clip.cameraId())
                    .query(Long.class).single() == 0) {
                reason = "Zone not found for this camera";
            }
            if (reason != null) {
                results.add(Obj.of("clipId", clip.clipId(), "status", "rejected", "reason", reason));
                continue;
            }
            String existing = jdbc.sql("SELECT id FROM \"LpEvidence\" WHERE \"agentId\" = ? AND \"clipId\" = ?")
                    .params(agent.id(), clip.clipId()).query(String.class).optional().orElse(null);
            if (existing != null) {
                results.add(Obj.of("clipId", clip.clipId(), "status", "duplicate", "evidenceId", existing));
                continue;
            }
            List<String> events = clip.eventIds() == null || clip.eventIds().isEmpty() ? List.of()
                    : jdbc.sql("SELECT id FROM \"LpEvent\" WHERE \"organizationId\" = ? AND \"eventId\" ="
                            + " ANY(?::text[]) AND \"branchId\" = ? AND \"evidenceSource\" IN ('camera', 'sensor')")
                            .params(org, textArray(clip.eventIds()), agent.branchId()).query(String.class).list();
            String id = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"LpEvidence\" (id, \"organizationId\", \"branchId\", \"agentId\", \"cameraId\","
                    + " \"zoneId\", \"clipId\", origin, \"startedAt\", \"endedAt\", sha256, \"byteSize\", \"contentType\","
                    + " \"observationEventIds\") VALUES (?, ?, ?, ?, ?, ?, ?, 'flagged', ?, ?, ?, ?, ?, ?::text[])")
                    .params(java.util.Arrays.asList(id, org, cameras.get(clip.cameraId()), agent.id(),
                            clip.cameraId(), LpScope.has(clip.zoneId()) ? clip.zoneId() : null, clip.clipId(),
                            SqlRows.ts(LpEventValidator.instant(clip.startedAt())),
                            SqlRows.ts(LpEventValidator.instant(clip.endedAt())), clip.sha256(), clip.byteSize(),
                            clip.contentType(), textArray(events)))
                    .update();
            registered++;
            results.add(Obj.of("clipId", clip.clipId(), "status", "registered", "evidenceId", id,
                    "linkedEvents", events.size()));
        }
        return Obj.of("registered", registered, "results", results);
    }

    /** Why a registered clip is refused, or {@code null}. */
    static String clipReason(ClipInput clip, java.util.Set<String> cameras) {
        if (!cameras.contains(clip.cameraId())) {
            return "Camera not found for this agent";
        }
        Instant from = LpEventValidator.instant(clip.startedAt());
        Instant to = LpEventValidator.instant(clip.endedAt());
        if (from == null || to == null) {
            return "startedAt and endedAt must be ISO-8601 instants";
        }
        if (to.isBefore(from)) {
            return "endedAt must not be before startedAt";
        }
        if (Duration.between(from, to).getSeconds() > LpVisionCatalog.MAX_CLIP_SECONDS) {
            return "A clip covers at most " + LpVisionCatalog.MAX_CLIP_SECONDS + " seconds";
        }
        if (!SHA256.matcher(clip.sha256()).matches()) {
            return "sha256 must be 64 lowercase hexadecimal characters";
        }
        if (clip.byteSize() < 1 || clip.byteSize() > LpVisionCatalog.MAX_CLIP_BYTES) {
            return "byteSize must be between 1 and " + LpVisionCatalog.MAX_CLIP_BYTES;
        }
        if (!LpVisionCatalog.CONTENT_TYPES.contains(clip.contentType())) {
            return "contentType must be one of: " + String.join(", ", LpVisionCatalog.CONTENT_TYPES);
        }
        return null;
    }

    private Map<String, Object> agentRow(AgentRef agent, String evidenceId, boolean lock) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"LpEvidence\" WHERE id = ? AND \"organizationId\""
                + " = ? AND \"agentId\" = ?" + (lock ? " FOR UPDATE" : "")).params(evidenceId, LpScope.org(),
                        agent.id()));
        if (row == null) {
            throw ApiException.notFound("Evidence not found");
        }
        return row;
    }

    private static Map<String, Object> status(Map<String, Object> row) {
        return Obj.of("evidenceId", row.get("id"), "receivedBytes", row.get("receivedBytes"), "totalBytes",
                row.get("byteSize"), "complete", "uploaded".equals(row.get("state")), "state", row.get("state"));
    }

    /** Where the upload stands, so the box resumes at {@code receivedBytes}. */
    @Transactional(readOnly = true)
    public Map<String, Object> uploadStatus(AgentRef agent, String evidenceId) {
        return status(agentRow(agent, evidenceId, false));
    }

    /**
     * Appends one chunk at {@code offset}. When the last byte arrives the clip is checked against its SHA-256,
     * encrypted and stored. Sending a chunk of a finished upload again changes nothing.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> uploadChunk(AgentRef agent, String evidenceId, long offset, long total, String sha256,
            String contentType, byte[] chunk) {
        Map<String, Object> e = agentRow(agent, evidenceId, true);
        String state = (String) e.get("state");
        if ("uploaded".equals(state)) {
            return status(e);
        }
        if (!"requested".equals(state) && !"uploading".equals(state)) {
            throw ApiException.conflict("The clip was not requested");
        }
        if (total < 1 || total > LpVisionCatalog.MAX_CLIP_BYTES) {
            throw ApiException.badRequest("total must be between 1 and " + LpVisionCatalog.MAX_CLIP_BYTES);
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw ApiException.badRequest("sha256 must be 64 lowercase hexadecimal characters");
        }
        if (chunk == null || chunk.length == 0 || chunk.length > LpVisionCatalog.MAX_CHUNK_BYTES) {
            throw ApiException.badRequest("A chunk holds 1 to " + LpVisionCatalog.MAX_CHUNK_BYTES + " bytes");
        }
        if (e.get("sha256") == null) {
            // A clip cut from the buffer: its checksum and size are registered with its first chunk.
            String type = LpScope.has(contentType) ? contentType : RANGE_CONTENT_TYPE;
            if (!LpVisionCatalog.CONTENT_TYPES.contains(type)) {
                throw ApiException.badRequest("contentType must be one of: "
                        + String.join(", ", LpVisionCatalog.CONTENT_TYPES));
            }
            jdbc.sql("UPDATE \"LpEvidence\" SET sha256 = ?, \"byteSize\" = ?, \"contentType\" = ? WHERE id = ?")
                    .params(sha256, total, type, evidenceId).update();
            e.put("sha256", sha256);
            e.put("byteSize", total);
        } else if (!sha256.equals(e.get("sha256")) || total != ((Number) e.get("byteSize")).longValue()) {
            failIntegrity(e, "upload", "The checksum or size sent differs from the clip registered at capture");
            throw ApiException.conflict("Checksum does not match the clip registered at capture");
        }
        long received = ((Number) e.get("receivedBytes")).longValue();
        if (offset != received) {
            throw ApiException.conflict("Expected offset " + received);
        }
        if (offset + chunk.length > total) {
            throw ApiException.badRequest("The chunk goes past total");
        }
        jdbc.sql("INSERT INTO \"LpEvidenceChunk\" (\"organizationId\", \"evidenceId\", \"offset\", bytes) VALUES (?, ?, ?,"
                + " ?)").params(LpScope.org(), evidenceId, offset, chunk).update();
        received += chunk.length;
        jdbc.sql("UPDATE \"LpEvidence\" SET \"receivedBytes\" = ?, state = 'uploading', \"updatedAt\" ="
                + " CURRENT_TIMESTAMP WHERE id = ?").params(received, evidenceId).update();
        e.put("receivedBytes", received);
        e.put("state", "uploading");
        if (received < total) {
            return status(e);
        }
        return finish(e);
    }

    private Map<String, Object> finish(Map<String, Object> e) {
        String id = (String) e.get("id");
        ByteArrayOutputStream all = new ByteArrayOutputStream((int) ((Number) e.get("byteSize")).longValue());
        jdbc.sql("SELECT bytes FROM \"LpEvidenceChunk\" WHERE \"evidenceId\" = ? ORDER BY \"offset\"").param(id)
                .query((rs, n) -> rs.getBytes(1)).list().forEach(all::writeBytes);
        byte[] clip = all.toByteArray();
        jdbc.sql("DELETE FROM \"LpEvidenceChunk\" WHERE \"evidenceId\" = ?").param(id).update();
        if (!LpEvidenceCipher.sha256(clip).equals(e.get("sha256"))) {
            jdbc.sql("UPDATE \"LpEvidence\" SET \"receivedBytes\" = 0 WHERE id = ?").param(id).update();
            failIntegrity(e, "upload", "The bytes received do not hash to the SHA-256 registered at capture");
            throw ApiException.conflict("The clip does not match its checksum; it may have been altered");
        }
        String key = "lp-evidence/" + LpScope.org() + "/" + id;
        storage.put(key, cipher.encrypt(id, clip), "application/octet-stream");
        jdbc.sql("UPDATE \"LpEvidence\" SET state = 'uploaded', \"uploadedAt\" = CURRENT_TIMESTAMP, \"storageKey\" = ?,"
                + " encryption = ?, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .params(key, LpEvidenceCipher.FORMAT + ";key=" + cipher.keyId(), id).update();
        commands.finishUploads(id);
        retain(id);
        access.record(id, (String) e.get("cameraId"), null, LpAccessLog.UPLOADED, Obj.of("sha256", e.get("sha256"),
                "byteSize", e.get("byteSize")));
        e.put("state", "uploaded");
        return status(e);
    }

    private void failIntegrity(Map<String, Object> e, String at, String reason) {
        String id = (String) e.get("id");
        jdbc.sql("DELETE FROM \"LpEvidenceChunk\" WHERE \"evidenceId\" = ?").param(id).update();
        jdbc.sql("UPDATE \"LpEvidence\" SET state = 'integrity_failed', \"receivedBytes\" = 0, \"updatedAt\" ="
                + " CURRENT_TIMESTAMP WHERE id = ?").param(id).update();
        commands.finishUploads(id);
        access.record(id, (String) e.get("cameraId"), null, LpAccessLog.INTEGRITY_FAILED, Obj.of("at", at, "reason",
                reason));
    }

    /** The box no longer has the clip (it reported so, or the buffer days passed). */
    @Transactional
    public void markMissing(String evidenceId, String reason) {
        Map<String, Object> e = SqlRows.one(jdbc.sql("SELECT id, \"cameraId\", state FROM \"LpEvidence\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(evidenceId, LpScope.org()));
        if (e == null || "uploaded".equals(e.get("state")) || "deleted".equals(e.get("state"))
                || "missing".equals(e.get("state"))) {
            return;
        }
        jdbc.sql("DELETE FROM \"LpEvidenceChunk\" WHERE \"evidenceId\" = ?").param(evidenceId).update();
        jdbc.sql("UPDATE \"LpEvidence\" SET state = 'missing', \"receivedBytes\" = 0, \"updatedAt\" = CURRENT_TIMESTAMP"
                + " WHERE id = ?").param(evidenceId).update();
        commands.finishUploads(evidenceId);
        access.record(evidenceId, (String) e.get("cameraId"), null, LpAccessLog.MISSING, Obj.of("reason", reason));
    }

    /**
     * An upload the box did not finish in time: the clip waits on the box again (what arrived is kept, so a new
     * request resumes), or is missing once the buffer days have passed.
     */
    @Transactional
    public void uploadLapsed(String evidenceId) {
        Map<String, Object> e = SqlRows.one(jdbc.sql("SELECT id, \"branchId\", state, \"endedAt\" FROM \"LpEvidence\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(evidenceId, LpScope.org()));
        if (e == null || !("requested".equals(e.get("state")) || "uploading".equals(e.get("state")))) {
            return;
        }
        int bufferDays = settings.effective((String) e.get("branchId")).get(LpSettingsService.EVIDENCE_BUFFER_DAYS);
        if (((Instant) e.get("endedAt")).isBefore(clock.instant().minus(Duration.ofDays(bufferDays)))) {
            markMissing(evidenceId, "upload_lapsed_after_buffer");
            return;
        }
        jdbc.sql("UPDATE \"LpEvidence\" SET state = 'on_agent', \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                .param(evidenceId).update();
    }

    // ------------------------------------------------------------------ retention

    /**
     * When an uploaded clip may be deleted: {@code evidenceCaseDays} after upload while a linked case is open,
     * {@code evidenceClosedCaseDays} after its last case closed, or after upload when it has no case.
     */
    private void retain(String evidenceId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT e.\"branchId\", e.\"uploadedAt\", count(k.id) AS cases,"
                + " count(k.id) FILTER (WHERE k.state <> 'closed') AS open, max(k.\"closedAt\") AS \"lastClosedAt\" FROM"
                + " \"LpEvidence\" e LEFT JOIN \"LpEvidenceCase\" ec ON ec.\"evidenceId\" = e.id LEFT JOIN \"LpCase\" k ON"
                + " k.id = ec.\"caseId\" WHERE e.id = ? GROUP BY e.id").param(evidenceId));
        if (r == null || r.get("uploadedAt") == null) {
            return;
        }
        Map<String, Integer> s = settings.effective((String) r.get("branchId"));
        Instant uploaded = (Instant) r.get("uploadedAt");
        long open = ((Number) r.get("open")).longValue();
        long cases = ((Number) r.get("cases")).longValue();
        Instant until;
        if (open > 0) {
            until = uploaded.plus(Duration.ofDays(s.get(LpSettingsService.EVIDENCE_CASE_DAYS)));
        } else {
            Instant base = cases > 0 && r.get("lastClosedAt") != null ? (Instant) r.get("lastClosedAt") : uploaded;
            until = base.plus(Duration.ofDays(s.get(LpSettingsService.EVIDENCE_CLOSED_CASE_DAYS)));
        }
        jdbc.sql("UPDATE \"LpEvidence\" SET \"retainUntil\" = ? WHERE id = ? AND \"retainUntil\" IS DISTINCT FROM ?")
                .params(SqlRows.ts(until), evidenceId, SqlRows.ts(until)).update();
    }

    /**
     * Applies retention for the current organization: recomputes each uploaded clip's date, deletes the expired ones,
     * marks clips the box can no longer have as missing and drops stale partial uploads. Returns counts.
     */
    @Transactional
    public Map<String, Object> retention() {
        String org = LpScope.org();
        Instant now = clock.instant();
        for (String id : jdbc.sql("SELECT id FROM \"LpEvidence\" WHERE \"organizationId\" = ? AND state = 'uploaded'")
                .param(org).query(String.class).list()) {
            retain(id);
        }
        int deleted = 0;
        for (Map<String, Object> e : SqlRows.list(jdbc.sql("SELECT id, \"cameraId\", \"storageKey\" FROM \"LpEvidence\""
                + " WHERE \"organizationId\" = ? AND state = 'uploaded' AND \"retainUntil\" < ? FOR UPDATE SKIP LOCKED")
                .params(org, SqlRows.ts(now)))) {
            if (e.get("storageKey") != null) {
                try {
                    storage.delete((String) e.get("storageKey"));
                } catch (RuntimeException ex) {
                    continue; // tried again on the next run
                }
            }
            jdbc.sql("UPDATE \"LpEvidence\" SET state = 'deleted', \"deletedAt\" = CURRENT_TIMESTAMP, \"deleteReason\" ="
                    + " 'retention', \"storageKey\" = NULL, \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ?")
                    .param(e.get("id")).update();
            access.record((String) e.get("id"), (String) e.get("cameraId"), null, LpAccessLog.DELETED,
                    Obj.of("reason", "retention"));
            deleted++;
        }
        int missing = 0;
        Map<String, Map<String, Integer>> byBranch = new HashMap<>();
        for (Map<String, Object> e : SqlRows.list(jdbc.sql("SELECT id, \"branchId\", \"endedAt\" FROM \"LpEvidence\""
                + " WHERE \"organizationId\" = ? AND state = 'on_agent' AND \"endedAt\" < ?")
                .params(org, SqlRows.ts(now.minus(Duration.ofDays(1)))))) {
            int days = byBranch.computeIfAbsent((String) e.get("branchId"), settings::effective)
                    .get(LpSettingsService.EVIDENCE_BUFFER_DAYS);
            if (((Instant) e.get("endedAt")).isBefore(now.minus(Duration.ofDays(days)))) {
                markMissing((String) e.get("id"), "buffer_expired");
                missing++;
            }
        }
        int stale = 0;
        for (String id : jdbc.sql("SELECT e.id FROM \"LpEvidence\" e WHERE e.\"organizationId\" = ? AND e.state IN"
                + " ('requested', 'uploading') AND e.\"updatedAt\" < ? AND NOT EXISTS (SELECT 1 FROM \"LpAgentCommand\" k"
                + " WHERE k.\"organizationId\" = e.\"organizationId\" AND k.kind = 'clip_upload' AND k.state IN"
                + " ('pending', 'delivered') AND k.payload ->> 'evidenceId' = e.id)")
                .params(org, SqlRows.ts(now.minus(STALE_UPLOAD))).query(String.class).list()) {
            uploadLapsed(id);
            stale++;
        }
        return Obj.of("deleted", deleted, "missing", missing, "lapsed", stale);
    }
}
