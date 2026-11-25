package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.qualitycontrol.application.QcCommands.FailureClassInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.FailureClassPatch;
import com.inventoryerp.qualitycontrol.domain.QcFailureClass;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Failure classification master (ADR-S087 §9). The defaults (material, dimensional, strength, moisture, packaging,
 * documentation, process deviation) are created on first use, idempotently.
 */
@Service
public class QcFailureClassService {

    static final List<String[]> DEFAULTS = List.of(
            new String[] {"MATERIAL", "Material defect"},
            new String[] {"DIMENSIONAL", "Dimensional defect"},
            new String[] {"STRENGTH", "Strength defect"},
            new String[] {"MOISTURE", "Moisture defect"},
            new String[] {"PACKAGING", "Packaging defect"},
            new String[] {"DOCUMENTATION", "Documentation defect"},
            new String[] {"PROCESS", "Process deviation"});

    private final QcDb db;
    private final AuditService audit;

    public QcFailureClassService(QcDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    @Transactional
    public List<Map<String, Object>> list(Boolean active) {
        ensureDefaults();
        return active != null ? db.list(QcFailureClass.class, " AND \"isActive\" = ? ORDER BY \"sortOrder\", code", active)
                : db.list(QcFailureClass.class, " ORDER BY \"sortOrder\", code");
    }

    @Transactional
    public Map<String, Object> create(FailureClassInput in) {
        ensureDefaults();
        String code = in.code().trim().toUpperCase();
        if (!db.list(QcFailureClass.class, " AND lower(code) = lower(?)", code).isEmpty()) {
            throw ApiException.conflict("Failure class " + code + " already exists.");
        }
        Map<String, Object> row = db.insert(QcFailureClass.class, Obj.of("code", code, "name", in.name().trim(),
                "sortOrder", QcUtil.or(in.sortOrder(), 100), "isActive", true));
        audit.recordInTx(AuditInput.of("QcFailureClass", QcUtil.str(row, "id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> update(String id, FailureClassPatch in) {
        Map<String, Object> before = db.require(QcFailureClass.class, id, true, "Failure class not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.name() != null) {
            data.put("name", in.name().trim());
        }
        if (in.sortOrder() != null) {
            data.put("sortOrder", in.sortOrder());
        }
        if (in.isActive() != null) {
            data.put("isActive", in.isActive());
        }
        Map<String, Object> row = data.isEmpty() ? before : db.update(QcFailureClass.class, id, data);
        audit.recordInTx(AuditInput.of("QcFailureClass", id, "update", before, row));
        return row;
    }

    /** An active failure class of this organization, or 400. */
    Map<String, Object> requireActive(String id) {
        Map<String, Object> row = db.byId(QcFailureClass.class, id, false);
        if (row == null || !QcUtil.bool(row, "isActive")) {
            throw ApiException.badRequest("Failure class " + id + " not found");
        }
        return row;
    }

    void ensureDefaults() {
        if (db.jdbc().sql("SELECT count(*) FROM \"QcFailureClass\" WHERE \"organizationId\" = ?").param(QcDb.org())
                .query(Long.class).single() > 0) {
            return;
        }
        int order = 10;
        for (String[] d : DEFAULTS) {
            db.jdbc().sql("INSERT INTO \"QcFailureClass\" (id, \"organizationId\", code, name, \"sortOrder\")"
                    + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", lower(code)) DO NOTHING")
                    .params(UUID.randomUUID().toString(), QcDb.org(), d[0], d[1], order).update();
            order += 10;
        }
    }
}
