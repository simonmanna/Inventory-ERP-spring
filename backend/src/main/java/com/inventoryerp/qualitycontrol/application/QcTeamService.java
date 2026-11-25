package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.qualitycontrol.application.QcCommands.TeamInput;
import com.inventoryerp.qualitycontrol.application.QcCommands.TeamPatch;
import com.inventoryerp.qualitycontrol.domain.QcTeam;
import com.inventoryerp.qualitycontrol.domain.QcTeamMember;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Quality teams (ADR-S087): control points, checks and alerts are assigned to a team and its members. */
@Service
public class QcTeamService {

    private final QcDb db;
    private final AuditService audit;

    public QcTeamService(QcDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> teams = db.list(QcTeam.class, " ORDER BY \"isActive\" DESC, lower(name)");
        Map<String, List<Map<String, Object>>> members = members();
        for (Map<String, Object> t : teams) {
            t.put("members", members.getOrDefault(QcUtil.str(t, "id"), List.of()));
        }
        return teams;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> t = db.require(QcTeam.class, id, false, "Quality team not found");
        t.put("members", members().getOrDefault(id, List.of()));
        return t;
    }

    @Transactional
    public Map<String, Object> create(TeamInput in) {
        String name = in.name().trim();
        requireUniqueName(name, null);
        Map<String, Object> t = db.insert(QcTeam.class, Obj.of("name", name, "description", QcUtil.trim(in.description()),
                "isActive", true));
        String id = QcUtil.str(t, "id");
        replaceMembers(id, in.memberUserIds());
        audit.recordInTx(AuditInput.of("QcTeam", id, "create", null, Obj.of("name", name)));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, TeamPatch in) {
        Map<String, Object> before = db.require(QcTeam.class, id, true, "Quality team not found");
        Map<String, Object> data = new LinkedHashMap<>();
        if (in.name() != null) {
            requireUniqueName(in.name().trim(), id);
            data.put("name", in.name().trim());
        }
        if (in.description() != null) {
            data.put("description", QcUtil.trim(in.description()));
        }
        if (in.isActive() != null) {
            data.put("isActive", in.isActive());
        }
        if (!data.isEmpty()) {
            db.update(QcTeam.class, id, data);
        }
        if (in.memberUserIds() != null) {
            replaceMembers(id, in.memberUserIds());
        }
        audit.recordInTx(AuditInput.of("QcTeam", id, "update", Obj.of("name", before.get("name"), "isActive",
                before.get("isActive")), in));
        return get(id);
    }

    /** Member user ids of a team (empty when the team is unknown). */
    @Transactional(readOnly = true)
    public List<String> memberIds(String teamId) {
        if (teamId == null) {
            return List.of();
        }
        return db.jdbc().sql("SELECT \"userId\" FROM \"QcTeamMember\" WHERE \"teamId\" = ? AND \"organizationId\" = ?")
                .params(teamId, QcDb.org()).query(String.class).list();
    }

    private void requireUniqueName(String name, String exceptId) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"QcTeam\" WHERE \"organizationId\" = ? AND lower(name) = lower(?)"
                + (exceptId != null ? " AND id <> ?" : "")).params(exceptId != null ? List.of(QcDb.org(), name, exceptId)
                : List.of(QcDb.org(), name)).query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("Quality team " + name + " already exists.");
        }
    }

    private void replaceMembers(String teamId, List<String> userIds) {
        if (userIds == null) {
            return;
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(userIds);
        for (String u : ids) {
            if (!db.exists("User", u)) {
                throw ApiException.badRequest("User " + u + " not found");
            }
        }
        db.jdbc().sql("DELETE FROM \"QcTeamMember\" WHERE \"teamId\" = ? AND \"organizationId\" = ?")
                .params(teamId, QcDb.org()).update();
        for (String u : ids) {
            db.insert(QcTeamMember.class, Obj.of("teamId", teamId, "userId", u));
        }
    }

    private Map<String, List<Map<String, Object>>> members() {
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT m.\"teamId\", m.\"userId\", u.email,"
                + " TRIM(CONCAT(u.\"firstName\", ' ', u.\"lastName\")) AS name FROM \"QcTeamMember\" m"
                + " JOIN \"User\" u ON u.id = m.\"userId\" WHERE m.\"organizationId\" = ? ORDER BY name")
                .params(QcDb.org()))) {
            out.computeIfAbsent(QcUtil.str(r, "teamId"), k -> new ArrayList<>()).add(r);
        }
        return out;
    }
}
