package com.inventoryerp.helpdesk.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Helpdesk configuration: teams (members, assignment rule), their stages, ticket types and letter templates. A
 * default "Support" team with standard stages and two letter templates is created on first use per organization.
 */
@Service
public class HelpdeskConfigService {

    static final Set<String> ASSIGNMENTS = Set.of("manual", "round_robin", "balanced");
    static final List<Object[]> DEFAULT_STAGES = List.of(
            new Object[] {"New", 10, false, false},
            new Object[] {"In progress", 20, false, false},
            new Object[] {"Waiting on customer", 30, false, false},
            new Object[] {"Resolved", 40, true, false},
            new Object[] {"Cancelled", 50, true, true});

    private final JdbcClient jdbc;

    public HelpdeskConfigService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Default team + stages + templates when the org has no team yet; returns the default team id. */
    @Transactional
    public String ensureDefaults() {
        String existing = jdbc.sql("SELECT id FROM \"HdTeam\" WHERE \"organizationId\" = ? AND \"isActive\" ORDER BY"
                + " (code = 'support') DESC, \"createdAt\" LIMIT 1").param(org()).query(String.class).optional()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HdTeam\" (id, \"organizationId\", code, name, description) VALUES (?, ?, 'support',"
                + " 'Support', 'Default helpdesk team') ON CONFLICT (\"organizationId\", code) DO NOTHING")
                .params(id, org()).update();
        String teamId = jdbc.sql("SELECT id FROM \"HdTeam\" WHERE \"organizationId\" = ? AND code = 'support'")
                .param(org()).query(String.class).single();
        if (teamId.equals(id)) {
            createDefaultStages(teamId);
            seedTemplates();
        }
        return teamId;
    }

    private void createDefaultStages(String teamId) {
        for (Object[] s : DEFAULT_STAGES) {
            jdbc.sql("INSERT INTO \"HdStage\" (id, \"organizationId\", \"teamId\", name, sequence, \"isClosed\", folded)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), teamId, s[0], s[1], s[2], s[3]).update();
        }
    }

    private void seedTemplates() {
        jdbc.sql("""
                INSERT INTO "HdLetterTemplate" (id, "organizationId", code, name, subject, body, "closingLine")
                VALUES (?, ?, 'acknowledgement', 'Acknowledgement of receipt', 'Acknowledgement: {{subject}}',
                  ?, 'Yours sincerely,') ON CONFLICT DO NOTHING""")
                .params(UUID.randomUUID().toString(), org(), """
                        Dear {{contact.name}},

                        We acknowledge receipt of your letter {{reply.externalRef}} dated {{reply.letterDate}}, \
                        registered under our reference {{reply.letterNumber}}.

                        Your matter is being handled and we will revert to you shortly.""")
                .update();
        jdbc.sql("""
                INSERT INTO "HdLetterTemplate" (id, "organizationId", code, name, subject, body, "closingLine")
                VALUES (?, ?, 'general', 'General letter', '{{subject}}', ?, 'Yours faithfully,') ON CONFLICT DO NOTHING""")
                .params(UUID.randomUUID().toString(), org(), "Dear {{contact.name}},\n\n{{body}}").update();
    }

    // ---- teams ----

    Map<String, Object> team(String id) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"HdTeam\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Team not found");
        }
        t.put("memberIds", jdbc.sql("SELECT \"userId\" FROM \"HdTeamMember\" WHERE \"teamId\" = ? ORDER BY \"userId\"")
                .param(id).query(String.class).list());
        t.put("stages", stages(id));
        return t;
    }

    @Transactional
    public List<Map<String, Object>> teams() {
        ensureDefaults();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : jdbc.sql("SELECT id FROM \"HdTeam\" WHERE \"organizationId\" = ? ORDER BY \"isActive\" DESC,"
                + " name").param(org()).query(String.class).list()) {
            Map<String, Object> t = team(id);
            t.put("openCount", jdbc.sql("""
                    SELECT count(*) FROM "HdTicket" k JOIN "HdStage" s ON s.id = k."stageId"
                    WHERE k."teamId" = ? AND NOT s."isClosed" AND k."deletedAt" IS NULL""").param(id)
                    .query(Long.class).single());
            out.add(t);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> getTeam(String id) {
        return team(id);
    }

    public record TeamInput(String code, String name, String description, String email, String assignment,
            String leaderId, List<String> memberIds, Boolean isActive) {}

    private void validateUsers(List<String> ids) {
        for (String u : ids) {
            if (jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ?").params(u, org())
                    .query(Long.class).single() == 0) {
                throw ApiException.badRequest("User not found: " + u);
            }
        }
    }

    @Transactional
    public Map<String, Object> createTeam(TeamInput in) {
        ensureDefaults();
        String code = (in.code() == null || in.code().isBlank() ? in.name() : in.code()).trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        if (code.isEmpty()) {
            throw ApiException.badRequest("code should not be empty");
        }
        String assignment = in.assignment() == null ? "manual" : in.assignment();
        if (!ASSIGNMENTS.contains(assignment)) {
            throw ApiException.badRequest("assignment must be one of: balanced, manual, round_robin");
        }
        List<String> members = in.memberIds() == null ? List.of() : in.memberIds();
        validateUsers(members);
        if (in.leaderId() != null) {
            validateUsers(List.of(in.leaderId()));
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("INSERT INTO \"HdTeam\" (id, \"organizationId\", code, name, description, email, assignment,"
                    + " \"leaderId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(id, org(), code, in.name().trim(), in.description(), in.email(), assignment, in.leaderId())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A team with code \"" + code + "\" already exists");
        }
        setMembers(id, members);
        createDefaultStages(id);
        return team(id);
    }

    private void setMembers(String teamId, List<String> members) {
        jdbc.sql("DELETE FROM \"HdTeamMember\" WHERE \"teamId\" = ?").param(teamId).update();
        for (String u : new java.util.LinkedHashSet<>(members)) {
            jdbc.sql("INSERT INTO \"HdTeamMember\" (\"teamId\", \"userId\") VALUES (?, ?)").params(teamId, u).update();
        }
    }

    @Transactional
    public Map<String, Object> updateTeam(String id, TeamInput in) {
        team(id);
        if (in.assignment() != null && !ASSIGNMENTS.contains(in.assignment())) {
            throw ApiException.badRequest("assignment must be one of: balanced, manual, round_robin");
        }
        if (in.leaderId() != null) {
            validateUsers(List.of(in.leaderId()));
        }
        jdbc.sql("""
                UPDATE "HdTeam" SET name = coalesce(?, name), description = coalesce(?, description),
                  email = coalesce(?, email), assignment = coalesce(?, assignment), "leaderId" = coalesce(?, "leaderId"),
                  "isActive" = coalesce(?, "isActive"), "updatedAt" = now() WHERE id = ?""")
                .params(in.name() == null ? null : in.name().trim(), in.description(), in.email(), in.assignment(),
                        in.leaderId(), in.isActive(), id)
                .update();
        if (in.memberIds() != null) {
            validateUsers(in.memberIds());
            setMembers(id, in.memberIds());
        }
        return team(id);
    }

    // ---- stages ----

    List<Map<String, Object>> stages(String teamId) {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"HdStage\" WHERE \"teamId\" = ? ORDER BY sequence, name")
                .param(teamId));
    }

    Map<String, Object> stage(String id) {
        Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT * FROM \"HdStage\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (s == null) {
            throw ApiException.notFound("Stage not found");
        }
        return s;
    }

    /** First open stage of the team. */
    String firstStage(String teamId) {
        return jdbc.sql("SELECT id FROM \"HdStage\" WHERE \"teamId\" = ? AND NOT \"isClosed\" ORDER BY sequence LIMIT 1")
                .param(teamId).query(String.class).optional()
                .orElseThrow(() -> ApiException.badRequest("The team has no open stage"));
    }

    /** First closed (resolved) stage of the team. */
    String closedStage(String teamId) {
        return jdbc.sql("SELECT id FROM \"HdStage\" WHERE \"teamId\" = ? AND \"isClosed\" ORDER BY folded, sequence"
                + " LIMIT 1").param(teamId).query(String.class).optional()
                .orElseThrow(() -> ApiException.badRequest("The team has no closed stage"));
    }

    @Transactional
    public Map<String, Object> createStage(String teamId, String name, Integer sequence, Boolean isClosed,
            Boolean folded) {
        team(teamId);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"HdStage\" (id, \"organizationId\", \"teamId\", name, sequence, \"isClosed\", folded)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), teamId, name.trim(), sequence == null ? 100 : sequence, Boolean.TRUE.equals(isClosed),
                        Boolean.TRUE.equals(folded))
                .update();
        return stage(id);
    }

    @Transactional
    public Map<String, Object> updateStage(String id, String name, Integer sequence, Boolean isClosed, Boolean folded) {
        stage(id);
        jdbc.sql("UPDATE \"HdStage\" SET name = coalesce(?, name), sequence = coalesce(?, sequence), \"isClosed\" ="
                + " coalesce(?, \"isClosed\"), folded = coalesce(?, folded) WHERE id = ?")
                .params(name == null ? null : name.trim(), sequence, isClosed, folded, id).update();
        return stage(id);
    }

    @Transactional
    public void deleteStage(String id) {
        Map<String, Object> s = stage(id);
        long used = jdbc.sql("SELECT count(*) FROM \"HdTicket\" WHERE \"stageId\" = ?").param(id).query(Long.class)
                .single();
        if (used > 0) {
            throw ApiException.badRequest("Move the " + used + " ticket(s) of this stage first");
        }
        long left = jdbc.sql("SELECT count(*) FROM \"HdStage\" WHERE \"teamId\" = ? AND id <> ?")
                .params(s.get("teamId"), id).query(Long.class).single();
        if (left == 0) {
            throw ApiException.badRequest("A team needs at least one stage");
        }
        jdbc.sql("DELETE FROM \"HdStage\" WHERE id = ?").param(id).update();
    }

    // ---- ticket types ----

    @Transactional
    public List<Map<String, Object>> types() {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"HdTicketType\" WHERE \"organizationId\" = ? ORDER BY name")
                .param(org()));
    }

    @Transactional
    public Map<String, Object> createType(String name) {
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("INSERT INTO \"HdTicketType\" (id, \"organizationId\", name) VALUES (?, ?, ?)")
                    .params(id, org(), name.trim()).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Ticket type \"" + name.trim() + "\" already exists");
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"HdTicketType\" WHERE id = ?").param(id));
    }

    @Transactional
    public Map<String, Object> setTypeActive(String id, boolean active) {
        if (jdbc.sql("UPDATE \"HdTicketType\" SET \"isActive\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(active, id, org()).update() == 0) {
            throw ApiException.notFound("Ticket type not found");
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"HdTicketType\" WHERE id = ?").param(id));
    }

    // ---- letter templates ----

    Map<String, Object> template(String id) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"HdLetterTemplate\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Letter template not found");
        }
        return t;
    }

    @Transactional
    public List<Map<String, Object>> templates() {
        ensureDefaults();
        return SqlRows.list(jdbc.sql("SELECT * FROM \"HdLetterTemplate\" WHERE \"organizationId\" = ? ORDER BY name")
                .param(org()));
    }

    public record TemplateInput(String code, String name, String subject, String body, String closingLine,
            String signatureName, String signatureTitle, Boolean isActive) {}

    @Transactional
    public Map<String, Object> createTemplate(TemplateInput in) {
        String code = (in.code() == null || in.code().isBlank() ? in.name() : in.code()).trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("""
                    INSERT INTO "HdLetterTemplate" (id, "organizationId", code, name, subject, body, "closingLine",
                      "signatureName", "signatureTitle") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, org(), code, in.name().trim(), in.subject(), in.body(), in.closingLine(),
                            in.signatureName(), in.signatureTitle())
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A letter template with code \"" + code + "\" already exists");
        }
        return template(id);
    }

    @Transactional
    public Map<String, Object> updateTemplate(String id, TemplateInput in) {
        template(id);
        jdbc.sql("""
                UPDATE "HdLetterTemplate" SET name = coalesce(?, name), subject = coalesce(?, subject),
                  body = coalesce(?, body), "closingLine" = coalesce(?, "closingLine"),
                  "signatureName" = coalesce(?, "signatureName"), "signatureTitle" = coalesce(?, "signatureTitle"),
                  "isActive" = coalesce(?, "isActive"), "updatedAt" = now() WHERE id = ?""")
                .params(in.name(), in.subject(), in.body(), in.closingLine(), in.signatureName(), in.signatureTitle(),
                        in.isActive(), id)
                .update();
        return template(id);
    }
}
