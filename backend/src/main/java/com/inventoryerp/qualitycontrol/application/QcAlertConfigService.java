package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.RootCauseInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.RootCausePatch;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.StageInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.StagePatch;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.TagInput;
import com.inventoryerp.qualitycontrol.application.QcAlertCommands.TagPatch;
import com.inventoryerp.qualitycontrol.domain.QcAlertStage;
import com.inventoryerp.qualitycontrol.domain.QcRootCause;
import com.inventoryerp.qualitycontrol.domain.QcTag;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Settings of quality alerts (ADR-S087 §8): kanban stages, tags and root causes. */
@Service
public class QcAlertConfigService {

    /** Default stages created on first use: New, Confirmed, Action Proposed, Solved (closed). */
    static final List<Object[]> DEFAULT_STAGES = List.of(new Object[] {"New", 10, false},
            new Object[] {"Confirmed", 20, false}, new Object[] {"Action Proposed", 30, false},
            new Object[] {"Solved", 40, true});

    private final QcDb db;
    private final AuditService audit;

    public QcAlertConfigService(QcDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ stages

    /** Creates the default stages for an organization that has none (idempotent under concurrency). */
    @Transactional
    public void ensureStages() {
        long n = db.jdbc().sql("SELECT count(*) FROM \"QcAlertStage\" WHERE \"organizationId\" = ?").param(QcDb.org())
                .query(Long.class).single();
        if (n > 0) {
            return;
        }
        for (Object[] s : DEFAULT_STAGES) {
            db.jdbc().sql("INSERT INTO \"QcAlertStage\" (id, \"organizationId\", name, sequence, \"isClosed\")"
                    + " VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING").params(UUID.randomUUID().toString(), QcDb.org(),
                    s[0], s[1], s[2]).update();
        }
    }

    @Transactional
    public List<Map<String, Object>> stages(Boolean active) {
        ensureStages();
        String filter = active == null ? "" : " AND \"isActive\" = " + active;
        List<Map<String, Object>> rows = db.list(QcAlertStage.class, filter + " ORDER BY sequence, lower(name)");
        Map<String, Long> counts = new LinkedHashMap<>();
        db.jdbc().sql("SELECT \"stageId\", count(*) AS n FROM \"QcAlert\" WHERE \"organizationId\" = ? GROUP BY"
                + " \"stageId\"").param(QcDb.org()).query((rs, i) -> counts.put(rs.getString(1), rs.getLong(2))).list();
        rows.forEach(r -> r.put("alertCount", counts.getOrDefault(QcUtil.str(r, "id"), 0L)));
        return rows;
    }

    /** The first active open stage: where new alerts start. */
    @Transactional
    public Map<String, Object> firstStage() {
        ensureStages();
        List<Map<String, Object>> rows = db.list(QcAlertStage.class, " AND \"isActive\" AND NOT \"isClosed\""
                + " ORDER BY sequence, lower(name) LIMIT 1");
        if (rows.isEmpty()) {
            throw ApiException.badRequest("No open alert stage is active. Add or reactivate one in the alert stages.");
        }
        return rows.getFirst();
    }

    @Transactional
    public Map<String, Object> createStage(StageInput in) {
        ensureStages();
        String name = in.name().trim();
        unique("QcAlertStage", name, null, "An alert stage named " + name + " already exists.");
        Map<String, Object> s = db.insert(QcAlertStage.class, Obj.of("name", name, "sequence", in.sequence() == null
                ? nextSequence() : in.sequence(), "isClosed", Boolean.TRUE.equals(in.isClosed()), "folded",
                Boolean.TRUE.equals(in.folded()), "isActive", true));
        audit.recordInTx(AuditInput.of("QcAlertStage", QcUtil.str(s, "id"), "create", null, Obj.of("name", name)));
        return s;
    }

    @Transactional
    public Map<String, Object> updateStage(String id, StagePatch in) {
        Map<String, Object> before = db.require(QcAlertStage.class, id, true, "Alert stage not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.name() != null) {
            unique("QcAlertStage", in.name().trim(), id, "An alert stage named " + in.name().trim() + " already exists.");
            data.put("name", in.name().trim());
        }
        if (in.sequence() != null) {
            data.put("sequence", in.sequence());
        }
        if (in.isClosed() != null) {
            data.put("isClosed", in.isClosed());
        }
        if (in.folded() != null) {
            data.put("folded", in.folded());
        }
        if (Boolean.FALSE.equals(in.isActive()) && QcUtil.bool(before, "isActive")) {
            long alerts = db.jdbc().sql("SELECT count(*) FROM \"QcAlert\" WHERE \"organizationId\" = ? AND \"stageId\" = ?")
                    .params(QcDb.org(), id).query(Long.class).single();
            if (alerts > 0) {
                throw ApiException.badRequest("Move the " + alerts + " alert(s) out of stage " + before.get("name")
                        + " before deactivating it.");
            }
        }
        if (in.isActive() != null) {
            data.put("isActive", in.isActive());
        }
        Map<String, Object> after = data.isEmpty() ? before : db.update(QcAlertStage.class, id, data);
        audit.recordInTx(AuditInput.of("QcAlertStage", id, "update", before, data));
        return after;
    }

    private int nextSequence() {
        return db.jdbc().sql("SELECT COALESCE(max(sequence), 0) + 10 FROM \"QcAlertStage\" WHERE \"organizationId\" = ?")
                .param(QcDb.org()).query(Integer.class).single();
    }

    // ------------------------------------------------------------------ tags

    @Transactional(readOnly = true)
    public List<Map<String, Object>> tags() {
        return db.list(QcTag.class, " ORDER BY lower(name)");
    }

    @Transactional
    public Map<String, Object> createTag(TagInput in) {
        String name = in.name().trim();
        unique("QcTag", name, null, "A tag named " + name + " already exists.");
        Map<String, Object> t = db.insert(QcTag.class, Obj.of("name", name, "color", QcUtil.trim(in.color())));
        audit.recordInTx(AuditInput.of("QcTag", QcUtil.str(t, "id"), "create", null, Obj.of("name", name)));
        return t;
    }

    @Transactional
    public Map<String, Object> updateTag(String id, TagPatch in) {
        Map<String, Object> before = db.require(QcTag.class, id, true, "Tag not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.name() != null) {
            unique("QcTag", in.name().trim(), id, "A tag named " + in.name().trim() + " already exists.");
            data.put("name", in.name().trim());
        }
        if (in.color() != null) {
            data.put("color", QcUtil.trim(in.color()));
        }
        Map<String, Object> after = data.isEmpty() ? before : db.update(QcTag.class, id, data);
        audit.recordInTx(AuditInput.of("QcTag", id, "update", before, data));
        return after;
    }

    // ------------------------------------------------------------------ root causes

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rootCauses(Boolean active) {
        String filter = active == null ? "" : " AND \"isActive\" = " + active;
        return db.list(QcRootCause.class, filter + " ORDER BY \"isActive\" DESC, lower(name)");
    }

    @Transactional
    public Map<String, Object> createRootCause(RootCauseInput in) {
        String name = in.name().trim();
        unique("QcRootCause", name, null, "A root cause named " + name + " already exists.");
        Map<String, Object> r = db.insert(QcRootCause.class, Obj.of("name", name, "description",
                QcUtil.trim(in.description()), "isActive", true));
        audit.recordInTx(AuditInput.of("QcRootCause", QcUtil.str(r, "id"), "create", null, Obj.of("name", name)));
        return r;
    }

    @Transactional
    public Map<String, Object> updateRootCause(String id, RootCausePatch in) {
        Map<String, Object> before = db.require(QcRootCause.class, id, true, "Root cause not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.name() != null) {
            unique("QcRootCause", in.name().trim(), id, "A root cause named " + in.name().trim() + " already exists.");
            data.put("name", in.name().trim());
        }
        if (in.description() != null) {
            data.put("description", QcUtil.trim(in.description()));
        }
        if (in.isActive() != null) {
            data.put("isActive", in.isActive());
        }
        Map<String, Object> after = data.isEmpty() ? before : db.update(QcRootCause.class, id, data);
        audit.recordInTx(AuditInput.of("QcRootCause", id, "update", before, data));
        return after;
    }

    private void unique(String table, String name, String exceptId, String message) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ? AND lower(name) ="
                + " lower(?) AND id IS DISTINCT FROM ?").params(QcDb.org(), name, exceptId).query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict(message);
        }
    }
}
