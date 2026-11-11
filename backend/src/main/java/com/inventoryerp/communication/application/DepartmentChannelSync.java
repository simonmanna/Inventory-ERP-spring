package com.inventoryerp.communication.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Department channels (ADR-S064): one public channel per active {@code HrDepartment}; members are the department's
 * employees that have a user account, plus its manager. Members added by the sync are removed when they leave the
 * department; people who joined by hand stay, and people who left by hand are not added back. HR tables are read
 * only.
 */
@Service
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class DepartmentChannelSync {

    static final String DESCRIPTION = "Department channel. Members are kept in sync with HR.";

    private final JdbcClient jdbc;
    private final ConversationService conversations;
    private final DiscussService discuss;
    private final DiscussEvents events;

    public DepartmentChannelSync(JdbcClient jdbc, ConversationService conversations, DiscussService discuss,
            DiscussEvents events) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.discuss = discuss;
        this.events = events;
    }

    /** Create missing department channels and sync every department channel's members (current org). */
    @Transactional
    public Map<String, Object> sync() {
        String orgId = TenantContext.organizationId();
        String actor = TenantContext.userId();
        discuss.lock("departments");
        int created = 0;
        int updated = 0;
        int added = 0;
        int removed = 0;
        for (Map<String, Object> d : SqlRows.list(jdbc.sql("""
                        SELECT d.id, d.name, d.code, d."managerId" FROM "HrDepartment" d
                        WHERE d."organizationId" = ? AND d."deletedAt" IS NULL AND d."isActive" = true
                        ORDER BY d.name""")
                .param(orgId))) {
            String deptId = (String) d.get("id");
            Set<String> wanted = new HashSet<>(jdbc.sql("""
                            SELECT DISTINCT e."userId" FROM "HrEmployee" e JOIN "User" u ON u.id = e."userId"
                            WHERE e."organizationId" = ? AND e."departmentId" = ? AND e."deletedAt" IS NULL
                              AND e."isActive" = true AND u."deletedAt" IS NULL AND u."isActive" = true
                            UNION
                            SELECT e."userId" FROM "HrEmployee" e JOIN "User" u ON u.id = e."userId"
                            WHERE e.id = ? AND e."organizationId" = ? AND u."deletedAt" IS NULL AND u."isActive" = true""")
                    .params(orgId, deptId, d.get("managerId") == null ? "" : d.get("managerId"), orgId)
                    .query(String.class).list());
            wanted.remove(null);

            String channelId = jdbc.sql("SELECT id FROM \"Conversation\" WHERE \"organizationId\" = ?"
                            + " AND \"departmentId\" = ? AND \"deletedAt\" IS NULL")
                    .params(orgId, deptId).query(String.class).optional().orElse(null);
            if (channelId == null) {
                channelId = create(orgId, deptId, (String) d.get("name"), (String) d.get("code"), actor, wanted);
                created++;
            } else {
                updated++;
            }
            List<String> changed = new ArrayList<>();
            for (String u : wanted) {
                Map<String, Object> row = discuss.participantRow(channelId, u);
                if (row == null) {
                    discuss.insertParticipant(channelId, u, "member", "department");
                } else if (row.get("leftAt") != null && "department".equals(row.get("addedVia"))) {
                    discuss.addParticipant(channelId, u, "member", "department");
                } else {
                    continue;
                }
                changed.add(u);
                added++;
            }
            for (String u : jdbc.sql("""
                            SELECT "userId" FROM "ConversationParticipant"
                            WHERE "conversationId" = ? AND "organizationId" = ? AND "participantType" = 'user'
                              AND "leftAt" IS NULL AND "addedVia" = 'department'""")
                    .params(channelId, orgId).query(String.class).list()) {
                if (!wanted.contains(u)) {
                    discuss.removeParticipant(channelId, u);
                    changed.add(u);
                    removed++;
                }
            }
            if (!changed.isEmpty()) {
                events.conversationUpdated(channelId, changed);
            }
        }
        return Obj.of("created", created, "updated", updated, "membersAdded", added, "membersRemoved", removed);
    }

    private String create(String orgId, String deptId, String deptName, String code, String actor,
            Set<String> wanted) {
        String name = deptName == null || deptName.isBlank() ? code : deptName.trim();
        if (taken(orgId, name)) {
            name = name + (code == null || code.isBlank() ? " department" : " (" + code + ")");
        }
        int n = 2;
        String base = name;
        while (taken(orgId, name)) {
            name = base + " " + n++;
        }
        String id = (String) conversations.create(new ConversationService.CreateInput("channel", name, null, null, null,
                "org", null, null)).get("id");
        jdbc.sql("UPDATE \"Conversation\" SET \"departmentId\" = ?, description = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(deptId, DESCRIPTION, id, orgId).update();
        if (actor != null && !wanted.contains(actor)) {
            // The admin running the sync owns nothing they are not part of: drop the creator row.
            jdbc.sql("DELETE FROM \"ConversationParticipant\" WHERE \"conversationId\" = ? AND \"userId\" = ?"
                            + " AND \"organizationId\" = ?")
                    .params(id, actor, orgId).update();
        } else if (actor != null) {
            jdbc.sql("UPDATE \"ConversationParticipant\" SET \"addedVia\" = 'department' WHERE \"conversationId\" = ?"
                            + " AND \"userId\" = ? AND \"organizationId\" = ?")
                    .params(id, actor, orgId).update();
        }
        return id;
    }

    private boolean taken(String orgId, String name) {
        return jdbc.sql("SELECT count(*) FROM \"Conversation\" WHERE \"organizationId\" = ? AND kind = 'channel'"
                        + " AND \"contextType\" IS NULL AND \"deletedAt\" IS NULL AND lower(name) = lower(?)")
                .params(orgId, name).query(Long.class).single() > 0;
    }
}
