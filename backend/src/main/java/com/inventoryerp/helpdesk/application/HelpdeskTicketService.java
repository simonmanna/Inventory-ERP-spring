package com.inventoryerp.helpdesk.application;

import com.inventoryerp.dms.spi.DmsAttachments;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Helpdesk tickets and the correspondence register. Numbers: {@code HD-<year>-00001} for every ticket, plus
 * {@code IN-<year>-0001} / {@code OUT-<year>-0001} for incoming / outgoing letters. Assignment follows the team
 * rule (manual, round robin, least open tickets). Moving to a closed stage needs {@code helpdesk:ticket:close}.
 */
@Service
public class HelpdeskTicketService {

    public static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    public static final Set<String> CHANNELS = Set.of("email", "portal", "phone", "walk_in", "whatsapp", "letter",
            "other");
    public static final Set<String> DIRECTIONS = Set.of("inbound", "outbound");
    public static final Set<String> DISPATCH = Set.of("post", "registered_post", "courier", "hand", "email", "fax");
    public static final Set<String> CONFIDENTIALITY = Set.of("normal", "confidential", "secret");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final JdbcClient jdbc;
    private final HelpdeskConfigService config;
    private final SequenceService sequences;
    private final DmsAttachments dms;
    private final EventOutboxService outbox;
    private final AuditService audit;
    private final NotificationsService notifications;

    public HelpdeskTicketService(JdbcClient jdbc, HelpdeskConfigService config, SequenceService sequences,
            DmsAttachments dms, EventOutboxService outbox, AuditService audit, NotificationsService notifications) {
        this.jdbc = jdbc;
        this.config = config;
        this.sequences = sequences;
        this.dms = dms;
        this.outbox = outbox;
        this.audit = audit;
        this.notifications = notifications;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Ticket fields (create and patch; null = unchanged on patch). */
    public record TicketInput(String teamId, String typeId, String priority, String channel, String direction,
            String partnerId, String contactName, String contactEmail, String contactPhone, String subject,
            String description, String assigneeId, List<String> tags, String relatedResType, String relatedResId,
            String externalRef, String ourRef, LocalDate letterDate, Instant receivedAt, String confidentiality,
            LocalDate replyDueOn, Integer version) {}

    /** Ticket list filters. */
    public record TicketFilter(String teamId, String stageId, String assigneeId, boolean mine, boolean unassigned,
            String priority, String channel, String direction, String state, String q, String partnerId,
            String relatedResType, String relatedResId, boolean lettersOnly, Integer year, boolean awaitingReply,
            int page, int pageSize) {}

    // ---- reading ----

    Map<String, Object> raw(String id) {
        Map<String, Object> t = SqlRows.one(jdbc.sql("SELECT * FROM \"HdTicket\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Ticket not found");
        }
        return t;
    }

    private static final String SELECT = """
            SELECT k.*, tm.name AS "teamName", s.name AS "stageName", s."isClosed" AS "stageClosed",
              ty.name AS "typeName", p.name AS "partnerName",
              nullif(trim(coalesce(u."firstName", '') || ' ' || coalesce(u."lastName", '')), '') AS "assigneeName",
              (k."replyDueOn" IS NOT NULL AND k."repliedByTicketId" IS NULL AND NOT s."isClosed"
                AND k."replyDueOn" < current_date) AS "replyOverdue"
            FROM "HdTicket" k JOIN "HdTeam" tm ON tm.id = k."teamId" JOIN "HdStage" s ON s.id = k."stageId"
            LEFT JOIN "HdTicketType" ty ON ty.id = k."typeId" LEFT JOIN "Partner" p ON p.id = k."partnerId"
            LEFT JOIN "User" u ON u.id = k."assigneeId"
            """;

    private Map<String, Object> shaped(String id) {
        Map<String, Object> t = SqlRows.one(jdbc.sql(SELECT + " WHERE k.id = ? AND k.\"organizationId\" = ?")
                .params(id, org()));
        if (t == null) {
            throw ApiException.notFound("Ticket not found");
        }
        if (t.get("relatedResType") != null) {
            t.put("related", dms.describe((String) t.get("relatedResType"), (String) t.get("relatedResId")));
        }
        return t;
    }

    @Transactional
    public Map<String, Object> get(String id) {
        raw(id);
        Map<String, Object> t = shaped(id);
        t.put("messages", messagesOf(id));
        t.put("attachments", dms.list(HelpdeskResourceProvider.RES_TYPE, id));
        t.put("parent", t.get("parentTicketId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, number,"
                + " \"letterNumber\", subject, direction, channel FROM \"HdTicket\" WHERE id = ?")
                .param(t.get("parentTicketId"))));
        t.put("children", SqlRows.list(jdbc.sql("SELECT id, number, \"letterNumber\", subject, direction, channel,"
                + " \"createdAt\" FROM \"HdTicket\" WHERE \"parentTicketId\" = ? AND \"deletedAt\" IS NULL ORDER BY"
                + " \"createdAt\"").param(id)));
        t.put("stages", config.stages((String) t.get("teamId")));
        return t;
    }

    @Transactional
    public PageResult<Map<String, Object>> list(TicketFilter f) {
        config.ensureDefaults();
        StringBuilder where = new StringBuilder(" WHERE k.\"organizationId\" = :org AND k.\"deletedAt\" IS NULL");
        Map<String, Object> p = new HashMap<>();
        p.put("org", org());
        if (f.teamId() != null) {
            where.append(" AND k.\"teamId\" = :team");
            p.put("team", f.teamId());
        }
        if (f.stageId() != null) {
            where.append(" AND k.\"stageId\" = :stage");
            p.put("stage", f.stageId());
        }
        if (f.mine()) {
            where.append(" AND k.\"assigneeId\" = :me");
            p.put("me", TenantContext.userId());
        } else if (f.assigneeId() != null) {
            where.append(" AND k.\"assigneeId\" = :assignee");
            p.put("assignee", f.assigneeId());
        }
        if (f.unassigned()) {
            where.append(" AND k.\"assigneeId\" IS NULL");
        }
        if (f.priority() != null) {
            where.append(" AND k.priority = :prio");
            p.put("prio", f.priority());
        }
        if (f.channel() != null) {
            where.append(" AND k.channel = :ch");
            p.put("ch", f.channel());
        }
        if (f.lettersOnly()) {
            where.append(" AND k.channel = 'letter'");
        }
        if (f.direction() != null) {
            where.append(" AND k.direction = :dir");
            p.put("dir", f.direction());
        }
        if ("open".equals(f.state())) {
            where.append(" AND NOT s.\"isClosed\"");
        } else if ("closed".equals(f.state())) {
            where.append(" AND s.\"isClosed\"");
        } else if (f.state() != null) {
            throw ApiException.badRequest("state must be one of: closed, open");
        }
        if (f.partnerId() != null) {
            where.append(" AND k.\"partnerId\" = :partner");
            p.put("partner", f.partnerId());
        }
        if (f.relatedResType() != null) {
            where.append(" AND k.\"relatedResType\" = :rt");
            p.put("rt", f.relatedResType());
            if (f.relatedResId() != null) {
                where.append(" AND k.\"relatedResId\" = :rid");
                p.put("rid", f.relatedResId());
            }
        }
        if (f.year() != null) {
            where.append(" AND extract(year FROM coalesce(k.\"letterDate\"::timestamp, k.\"createdAt\")) = :yr");
            p.put("yr", f.year());
        }
        if (f.awaitingReply()) {
            where.append(" AND k.direction = 'inbound' AND k.\"replyDueOn\" IS NOT NULL AND k.\"repliedByTicketId\""
                    + " IS NULL AND NOT s.\"isClosed\"");
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (k.subject ILIKE :q OR k.number ILIKE :q OR k.\"letterNumber\" ILIKE :q OR"
                    + " k.\"externalRef\" ILIKE :q OR k.\"contactName\" ILIKE :q OR k.\"contactEmail\" ILIKE :q OR"
                    + " p.name ILIKE :q)");
            p.put("q", "%" + f.q().trim() + "%");
        }
        int page = Math.max(1, f.page());
        int size = Math.min(200, Math.max(1, f.pageSize()));
        String from = " FROM \"HdTicket\" k JOIN \"HdStage\" s ON s.id = k.\"stageId\" LEFT JOIN \"Partner\" p ON"
                + " p.id = k.\"partnerId\"";
        long total = jdbc.sql("SELECT count(*)" + from + where).params(p).query(Long.class).single();
        p.put("lim", size);
        p.put("off", (page - 1) * size);
        String order = f.lettersOnly()
                ? " ORDER BY coalesce(k.\"letterDate\", k.\"createdAt\"::date) DESC, k.\"letterNumber\" DESC NULLS LAST"
                : " ORDER BY CASE k.priority WHEN 'urgent' THEN 0 WHEN 'high' THEN 1 WHEN 'normal' THEN 2 ELSE 3 END,"
                        + " k.\"createdAt\" DESC";
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql(SELECT + where + order + " LIMIT :lim OFFSET :off")
                .params(p));
        return PageResult.of(rows, page, size, total);
    }

    private List<Map<String, Object>> messagesOf(String ticketId) {
        return SqlRows.list(jdbc.sql("""
                SELECT m.*, coalesce(m."authorName", nullif(trim(coalesce(u."firstName", '') || ' ' ||
                  coalesce(u."lastName", '')), '')) AS "author"
                FROM "HdTicketMessage" m LEFT JOIN "User" u ON u.id = m."authorId"
                WHERE m."ticketId" = ? ORDER BY m."createdAt", m.id""").param(ticketId));
    }

    @Transactional
    public List<Map<String, Object>> messages(String ticketId) {
        raw(ticketId);
        return messagesOf(ticketId);
    }

    // ---- writing ----

    private void message(String ticketId, String kind, String body, String toEmail, boolean sent) {
        jdbc.sql("INSERT INTO \"HdTicketMessage\" (id, \"organizationId\", \"ticketId\", kind, body, \"authorId\","
                + " \"toEmail\", \"emailSent\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org(), ticketId, kind, body, TenantContext.userId(), toEmail,
                        sent)
                .update();
    }

    private void event(String name, Map<String, Object> t, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", org());
        payload.put("ticketId", t.get("id"));
        payload.put("number", t.get("number"));
        if (extra != null) {
            payload.putAll(extra);
        }
        outbox.publish(name, payload);
    }

    private void validate(TicketInput in, boolean create) {
        if (create && (in.subject() == null || in.subject().isBlank())) {
            throw ApiException.badRequest("subject should not be empty");
        }
        if (in.priority() != null && !PRIORITIES.contains(in.priority())) {
            throw ApiException.badRequest("priority must be one of: high, low, normal, urgent");
        }
        if (in.channel() != null && !CHANNELS.contains(in.channel())) {
            throw ApiException.badRequest("channel must be one of: email, letter, other, phone, portal, walk_in,"
                    + " whatsapp");
        }
        if (in.direction() != null && !DIRECTIONS.contains(in.direction())) {
            throw ApiException.badRequest("direction must be one of: inbound, outbound");
        }
        if (in.confidentiality() != null && !CONFIDENTIALITY.contains(in.confidentiality())) {
            throw ApiException.badRequest("confidentiality must be one of: confidential, normal, secret");
        }
        if (in.contactEmail() != null && !in.contactEmail().isBlank() && !EMAIL.matcher(in.contactEmail()).matches()) {
            throw ApiException.badRequest("contactEmail must be an email");
        }
        if (in.partnerId() != null && jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(in.partnerId(), org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Partner not found");
        }
        if (in.assigneeId() != null && jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ?")
                .params(in.assigneeId(), org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Assignee not found");
        }
        if (in.typeId() != null && jdbc.sql("SELECT count(*) FROM \"HdTicketType\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(in.typeId(), org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Ticket type not found");
        }
        if (in.relatedResType() != null && dms.describe(in.relatedResType(), in.relatedResId()) == null) {
            throw ApiException.badRequest("Related record not found");
        }
    }

    /** Next assignee by the team rule ({@code null} = leave unassigned). */
    private String autoAssignee(Map<String, Object> team) {
        String rule = (String) team.get("assignment");
        @SuppressWarnings("unchecked")
        List<String> members = (List<String>) team.get("memberIds");
        if ("manual".equals(rule) || members == null || members.isEmpty()) {
            return null;
        }
        if ("balanced".equals(rule)) {
            return jdbc.sql("""
                    SELECT m."userId" FROM "HdTeamMember" m
                    LEFT JOIN "HdTicket" k ON k."assigneeId" = m."userId" AND k."teamId" = m."teamId"
                      AND k."deletedAt" IS NULL AND k."closedAt" IS NULL
                    WHERE m."teamId" = ? GROUP BY m."userId" ORDER BY count(k.id), m."userId" LIMIT 1""")
                    .param(team.get("id")).query(String.class).single();
        }
        String last = (String) team.get("lastAssignedUserId");
        int i = last == null ? -1 : members.indexOf(last);
        String next = members.get((i + 1) % members.size());
        jdbc.sql("UPDATE \"HdTeam\" SET \"lastAssignedUserId\" = ? WHERE id = ?").params(next, team.get("id")).update();
        return next;
    }

    @Transactional
    public Map<String, Object> create(TicketInput in) {
        return create(in, null);
    }

    private Map<String, Object> create(TicketInput in, String parentTicketId) {
        validate(in, true);
        String teamId = in.teamId() == null ? config.ensureDefaults() : in.teamId();
        Map<String, Object> team = config.team(teamId);
        if (!Boolean.TRUE.equals(team.get("isActive"))) {
            throw ApiException.badRequest("Team is inactive");
        }
        String stageId = config.firstStage(teamId);
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String number = sequences.next("helpdesk:" + year, "HD-" + year + "-", 5);
        String channel = in.channel() == null ? "portal" : in.channel();
        String direction = in.direction() == null ? "inbound" : in.direction();
        String letterNumber = null;
        if ("letter".equals(channel)) {
            letterNumber = "inbound".equals(direction)
                    ? sequences.next("letter_in:" + year, "IN-" + year + "-", 4)
                    : sequences.next("letter_out:" + year, "OUT-" + year + "-", 4);
        }
        String assignee = in.assigneeId();
        if (assignee == null) {
            assignee = autoAssignee(team);
        }
        String contactName = in.contactName();
        String contactEmail = in.contactEmail();
        if (in.partnerId() != null && (contactName == null || contactEmail == null)) {
            Map<String, Object> partner = SqlRows.one(jdbc.sql("SELECT name, email FROM \"Partner\" WHERE id = ?")
                    .param(in.partnerId()));
            if (contactName == null) {
                contactName = (String) partner.get("name");
            }
            if (contactEmail == null) {
                contactEmail = (String) partner.get("email");
            }
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO "HdTicket" (id, "organizationId", number, "teamId", "stageId", "typeId", priority, channel,
                  direction, "partnerId", "contactName", "contactEmail", "contactPhone", subject, description,
                  "assigneeId", tags, "relatedResType", "relatedResId", "parentTicketId", "letterNumber", "externalRef",
                  "ourRef", "letterDate", "receivedAt", confidentiality, "replyDueOn", "createdById")
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, org(), number, teamId, stageId, in.typeId(), in.priority() == null ? "normal" : in.priority(),
                        channel, direction, in.partnerId(), contactName, contactEmail, in.contactPhone(),
                        in.subject().trim(), in.description(), assignee,
                        in.tags() == null ? new String[0] : in.tags().toArray(String[]::new), in.relatedResType(),
                        in.relatedResType() == null ? null : in.relatedResId(), parentTicketId, letterNumber,
                        in.externalRef(), in.ourRef() == null && letterNumber != null ? letterNumber : in.ourRef(),
                        in.letterDate() == null ? (letterNumber == null ? null
                                : java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC)))
                                : java.sql.Date.valueOf(in.letterDate()),
                        "letter".equals(channel) && "inbound".equals(direction)
                                ? SqlRows.ts(in.receivedAt() == null ? Instant.now() : in.receivedAt()) : null,
                        in.confidentiality() == null ? "normal" : in.confidentiality(),
                        in.replyDueOn() == null ? null : java.sql.Date.valueOf(in.replyDueOn()), TenantContext.userId())
                .update();
        Map<String, Object> t = raw(id);
        message(id, "system", "Ticket created" + (letterNumber == null ? "" : " — letter " + letterNumber), null, false);
        audit.recordInTx(AuditInput.of("HdTicket", id, "create", null, Map.of("number", number, "subject",
                in.subject().trim())));
        event(DomainEvents.HELPDESK_TICKET_CREATED, t, Map.of("channel", channel, "direction", direction));
        if (letterNumber != null && "inbound".equals(direction)) {
            event(DomainEvents.HELPDESK_LETTER_RECEIVED, t, Map.of("letterNumber", letterNumber));
        }
        if (assignee != null) {
            notifyAssignee(t, assignee);
        }
        return get(id);
    }

    private void notifyAssignee(Map<String, Object> t, String userId) {
        if (userId.equals(TenantContext.userId())) {
            return;
        }
        notifications.send(new SendInput(org(), userId, "in_app", "helpdesk", "Ticket assigned: " + t.get("number"),
                String.valueOf(t.get("subject")), Map.of("ticketId", t.get("id"), "path", "/helpdesk/tickets/"
                        + t.get("id"))));
    }

    @Transactional
    public Map<String, Object> update(String id, TicketInput in) {
        Map<String, Object> t = raw(id);
        if (in.version() != null && ((Number) t.get("version")).intValue() != in.version()) {
            throw ApiException.conflict("Ticket was changed by someone else; reload and try again");
        }
        validate(in, false);
        if (in.subject() != null && in.subject().isBlank()) {
            throw ApiException.badRequest("subject should not be empty");
        }
        if (in.teamId() != null && !in.teamId().equals(t.get("teamId"))) {
            config.team(in.teamId());
            jdbc.sql("UPDATE \"HdTicket\" SET \"teamId\" = ?, \"stageId\" = ? WHERE id = ?")
                    .params(in.teamId(), config.firstStage(in.teamId()), id).update();
            message(id, "system", "Moved to team " + config.team(in.teamId()).get("name"), null, false);
        }
        jdbc.sql("""
                UPDATE "HdTicket" SET "typeId" = coalesce(?, "typeId"), priority = coalesce(?, priority),
                  "partnerId" = coalesce(?, "partnerId"), "contactName" = coalesce(?, "contactName"),
                  "contactEmail" = coalesce(?, "contactEmail"), "contactPhone" = coalesce(?, "contactPhone"),
                  subject = coalesce(?, subject), description = coalesce(?, description),
                  tags = coalesce(?::text[], tags), "relatedResType" = coalesce(?, "relatedResType"),
                  "relatedResId" = coalesce(?, "relatedResId"), "externalRef" = coalesce(?, "externalRef"),
                  "ourRef" = coalesce(?, "ourRef"), "letterDate" = coalesce(?, "letterDate"),
                  "receivedAt" = coalesce(?, "receivedAt"), confidentiality = coalesce(?, confidentiality),
                  "replyDueOn" = coalesce(?, "replyDueOn"), version = version + 1, "updatedAt" = now()
                WHERE id = ?""")
                .params(in.typeId(), in.priority(), in.partnerId(), in.contactName(), in.contactEmail(),
                        in.contactPhone(), in.subject() == null ? null : in.subject().trim(), in.description(),
                        in.tags() == null ? null : in.tags().toArray(String[]::new), in.relatedResType(),
                        in.relatedResId(), in.externalRef(), in.ourRef(),
                        in.letterDate() == null ? null : java.sql.Date.valueOf(in.letterDate()),
                        SqlRows.ts(in.receivedAt()), in.confidentiality(),
                        in.replyDueOn() == null ? null : java.sql.Date.valueOf(in.replyDueOn()), id)
                .update();
        if (in.assigneeId() != null && !in.assigneeId().equals(t.get("assigneeId"))) {
            assign(id, in.assigneeId());
        }
        audit.recordInTx(AuditInput.of("HdTicket", id, "update", null, Map.of("number", t.get("number"))));
        return get(id);
    }

    @Transactional
    public Map<String, Object> assign(String id, String assigneeId) {
        Map<String, Object> t = raw(id);
        if (assigneeId != null && jdbc.sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ?")
                .params(assigneeId, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Assignee not found");
        }
        if (Objects.equals(assigneeId, t.get("assigneeId"))) {
            return get(id);
        }
        jdbc.sql("UPDATE \"HdTicket\" SET \"assigneeId\" = ?, version = version + 1, \"updatedAt\" = now() WHERE id = ?")
                .params(assigneeId, id).update();
        String name = assigneeId == null ? "nobody" : jdbc.sql("SELECT trim(coalesce(\"firstName\", '') || ' ' ||"
                + " coalesce(\"lastName\", '')) FROM \"User\" WHERE id = ?").param(assigneeId).query(String.class)
                .single();
        message(id, "system", "Assigned to " + name, null, false);
        event(DomainEvents.HELPDESK_TICKET_ASSIGNED, t, assigneeId == null ? Map.of() : Map.of("assigneeId",
                assigneeId));
        if (assigneeId != null) {
            notifyAssignee(t, assigneeId);
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> moveStage(String id, String stageId, String resolution) {
        Map<String, Object> t = raw(id);
        Map<String, Object> stage = config.stage(stageId);
        if (!t.get("teamId").equals(stage.get("teamId"))) {
            throw ApiException.badRequest("Stage belongs to another team");
        }
        if (stageId.equals(t.get("stageId"))) {
            return get(id);
        }
        boolean closing = Boolean.TRUE.equals(stage.get("isClosed"));
        if (closing && !TenantContext.has(Permissions.HELPDESK__TICKET_CLOSE)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.HELPDESK__TICKET_CLOSE);
        }
        Map<String, Object> from = config.stage((String) t.get("stageId"));
        if (Boolean.TRUE.equals(from.get("isClosed")) && !closing
                && !TenantContext.has(Permissions.HELPDESK__TICKET_CLOSE)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.HELPDESK__TICKET_CLOSE);
        }
        jdbc.sql("""
                UPDATE "HdTicket" SET "stageId" = ?, "closedAt" = CASE WHEN ? THEN coalesce("closedAt", now()) ELSE NULL END,
                  resolution = coalesce(?, resolution), version = version + 1, "updatedAt" = now() WHERE id = ?""")
                .params(stageId, closing, resolution, id).update();
        message(id, "system", "Stage: " + from.get("name") + " → " + stage.get("name")
                + (resolution == null ? "" : "\nResolution: " + resolution), null, false);
        event(DomainEvents.HELPDESK_TICKET_STAGE_CHANGED, t, Map.of("stageId", stageId, "closed", closing));
        if (closing) {
            event(DomainEvents.HELPDESK_TICKET_CLOSED, t, Map.of("stageId", stageId));
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> close(String id, String resolution) {
        Map<String, Object> t = raw(id);
        return moveStage(id, config.closedStage((String) t.get("teamId")), resolution);
    }

    @Transactional
    public Map<String, Object> reopen(String id) {
        Map<String, Object> t = raw(id);
        return moveStage(id, config.firstStage((String) t.get("teamId")), null);
    }

    /** A thread message: internal {@code note}, customer {@code reply} (optionally e-mailed) or logged {@code inbound}. */
    @Transactional
    public Map<String, Object> addMessage(String id, String kind, String body, boolean sendEmail) {
        Map<String, Object> t = raw(id);
        if (!Set.of("note", "reply", "inbound").contains(kind)) {
            throw ApiException.badRequest("kind must be one of: inbound, note, reply");
        }
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("body should not be empty");
        }
        String to = null;
        boolean sent = false;
        if ("reply".equals(kind) && sendEmail) {
            to = (String) t.get("contactEmail");
            if (to == null || to.isBlank()) {
                throw ApiException.badRequest("The ticket has no contact e-mail");
            }
            String subject = "[" + t.get("number") + "] " + t.get("subject");
            sent = notifications.sendExternalEmail(to, subject, body, null);
        }
        message(id, kind, body.trim(), to, sent);
        if ("reply".equals(kind)) {
            jdbc.sql("UPDATE \"HdTicket\" SET \"firstResponseAt\" = coalesce(\"firstResponseAt\", now()),"
                    + " \"updatedAt\" = now() WHERE id = ?").param(id).update();
            event(DomainEvents.HELPDESK_TICKET_REPLIED, t, Map.of("emailSent", sent));
        } else {
            jdbc.sql("UPDATE \"HdTicket\" SET \"updatedAt\" = now() WHERE id = ?").param(id).update();
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> t = raw(id);
        jdbc.sql("UPDATE \"HdTicket\" SET \"deletedAt\" = now() WHERE id = ?").param(id).update();
        audit.recordInTx(AuditInput.of("HdTicket", id, "delete", Map.of("number", t.get("number")), null));
        return Map.of("deleted", true);
    }

    // ---- correspondence ----

    /** Register an incoming letter (scans attached into Correspondence / incoming / year). */
    @Transactional
    public Map<String, Object> registerIncoming(TicketInput in, List<Scan> scans) {
        TicketInput letter = new TicketInput(in.teamId(), in.typeId(), in.priority(), "letter", "inbound",
                in.partnerId(), in.contactName(), in.contactEmail(), in.contactPhone(), in.subject(), in.description(),
                in.assigneeId(), in.tags(), in.relatedResType(), in.relatedResId(), in.externalRef(), in.ourRef(),
                in.letterDate(), in.receivedAt(), in.confidentiality(), in.replyDueOn(), null);
        Map<String, Object> t = create(letter, null);
        String id = (String) t.get("id");
        if (scans != null) {
            int year = LocalDate.now(ZoneOffset.UTC).getYear();
            for (Scan s : scans) {
                dms.attachInFolder(HelpdeskResourceProvider.RES_TYPE, id, "correspondence", "Incoming/" + year,
                        s.filename(), s.contentType(), s.bytes(), List.of("Letter", "Incoming"));
            }
        }
        return get(id);
    }

    /** A received scan / file. */
    public record Scan(String filename, String contentType, byte[] bytes) {}

    /** Outgoing letter content. */
    public record LetterInput(String templateId, String subject, String body, String closingLine,
            String signatureName, String signatureTitle) {}

    /** Create an outgoing letter (ticket + rendered PDF in Correspondence / outgoing / year). */
    @Transactional
    public Map<String, Object> createOutgoing(TicketInput in, LetterInput letter) {
        return outgoing(in, letter, null);
    }

    private Map<String, Object> outgoing(TicketInput in, LetterInput letter, Map<String, Object> replyTo) {
        TicketInput out = new TicketInput(in.teamId(), in.typeId(), in.priority(), "letter", "outbound",
                in.partnerId(), in.contactName(), in.contactEmail(), in.contactPhone(),
                letter.subject() != null ? letter.subject() : in.subject(), in.description(), in.assigneeId(),
                in.tags(), in.relatedResType(), in.relatedResId(), in.externalRef(), in.ourRef(), in.letterDate(), null,
                in.confidentiality(), null, null);
        if (out.subject() == null || out.subject().isBlank()) {
            throw ApiException.badRequest("subject should not be empty");
        }
        Map<String, Object> t = create(out, replyTo == null ? null : (String) replyTo.get("id"));
        render((String) t.get("id"), letter, replyTo);
        return get((String) t.get("id"));
    }

    /** Reply to an incoming letter with an outgoing letter; the incoming one is marked replied. */
    @Transactional
    public Map<String, Object> replyWithLetter(String id, LetterInput letter, String assigneeId) {
        Map<String, Object> original = raw(id);
        String subject = letter.subject() != null ? letter.subject() : "Re: " + original.get("subject");
        TicketInput in = new TicketInput((String) original.get("teamId"), (String) original.get("typeId"),
                (String) original.get("priority"), "letter", "outbound", (String) original.get("partnerId"),
                (String) original.get("contactName"), (String) original.get("contactEmail"),
                (String) original.get("contactPhone"), subject, null, assigneeId, null,
                (String) original.get("relatedResType"), (String) original.get("relatedResId"),
                (String) original.get("letterNumber"), null, null, null, (String) original.get("confidentiality"),
                null, null);
        Map<String, Object> reply = outgoing(in, new LetterInput(letter.templateId(), subject, letter.body(),
                letter.closingLine(), letter.signatureName(), letter.signatureTitle()), original);
        jdbc.sql("UPDATE \"HdTicket\" SET \"repliedByTicketId\" = ?, \"firstResponseAt\" = coalesce(\"firstResponseAt\","
                + " now()), \"updatedAt\" = now() WHERE id = ?").params(reply.get("id"), id).update();
        message(id, "system", "Replied with letter " + reply.get("letterNumber"), null, false);
        return reply;
    }

    /** (Re)render the PDF of an outgoing letter as a new DMS document on the ticket. */
    @Transactional
    public Map<String, Object> render(String id, LetterInput letter, Map<String, Object> replyTo) {
        Map<String, Object> t = raw(id);
        if (!"letter".equals(t.get("channel")) || !"outbound".equals(t.get("direction"))) {
            throw ApiException.badRequest("Only outgoing letters can be rendered");
        }
        Map<String, Object> template = letter.templateId() == null ? null : config.template(letter.templateId());
        if (replyTo == null && t.get("parentTicketId") != null) {
            replyTo = SqlRows.one(jdbc.sql("SELECT * FROM \"HdTicket\" WHERE id = ?").param(t.get("parentTicketId")));
        }
        Map<String, String> values = new HashMap<>();
        String orgName = jdbc.sql("SELECT name FROM \"Organization\" WHERE id = ?").param(org()).query(String.class)
                .single();
        values.put("organization.name", orgName);
        values.put("ticket.number", (String) t.get("number"));
        values.put("letter.number", (String) t.get("letterNumber"));
        values.put("subject", (String) t.get("subject"));
        values.put("body", letter.body() == null ? "" : letter.body());
        values.put("date", LocalDate.now(ZoneOffset.UTC).toString());
        values.put("contact.name", t.get("contactName") == null ? "Sir / Madam" : (String) t.get("contactName"));
        String partnerName = t.get("partnerId") == null ? null : jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ?")
                .param(t.get("partnerId")).query(String.class).optional().orElse(null);
        values.put("partner.name", partnerName == null ? "" : partnerName);
        String user = TenantContext.userId() == null ? "" : jdbc.sql("SELECT trim(coalesce(\"firstName\", '') || ' ' ||"
                + " coalesce(\"lastName\", '')) FROM \"User\" WHERE id = ?").param(TenantContext.userId())
                .query(String.class).optional().orElse("");
        values.put("user.name", user);
        if (replyTo != null) {
            values.put("reply.letterNumber", String.valueOf(replyTo.get("letterNumber")));
            values.put("reply.externalRef", replyTo.get("externalRef") == null ? "" : (String) replyTo.get("externalRef"));
            values.put("reply.letterDate", replyTo.get("letterDate") == null ? "" : replyTo.get("letterDate").toString());
            values.put("reply.subject", String.valueOf(replyTo.get("subject")));
        }
        String bodyTemplate = template != null ? (String) template.get("body") : (letter.body() == null ? ""
                : letter.body());
        String body = LetterRenderer.merge(bodyTemplate, values);
        if (body.isBlank()) {
            throw ApiException.badRequest("The letter has no body");
        }
        String recipient = String.join("\n", java.util.stream.Stream.of(
                        (String) t.get("contactName"), partnerName == null || partnerName.equals(t.get("contactName"))
                                ? null : partnerName)
                .filter(s -> s != null && !s.isBlank()).toList());
        byte[] pdf = LetterRenderer.pdf(new LetterRenderer.Letter(orgName, (String) t.get("letterNumber"),
                (String) t.get("ourRef"), (String) t.get("externalRef"), values.get("date"), recipient,
                (String) t.get("subject"), body,
                first(letter.closingLine(), template == null ? null : (String) template.get("closingLine")),
                first(letter.signatureName(), template == null ? null : (String) template.get("signatureName"), user),
                first(letter.signatureTitle(), template == null ? null : (String) template.get("signatureTitle")),
                (String) t.get("confidentiality")));
        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        String filename = (t.get("letterNumber") == null ? t.get("number") : t.get("letterNumber")) + ".pdf";
        dms.attachInFolder(HelpdeskResourceProvider.RES_TYPE, id, "correspondence", "Outgoing/" + year, filename,
                "application/pdf", pdf, List.of("Letter", "Outgoing"));
        jdbc.sql("UPDATE \"HdTicket\" SET description = coalesce(description, ?), \"updatedAt\" = now() WHERE id = ?")
                .params(body, id).update();
        message(id, "system", "Letter rendered: " + filename, null, false);
        return get(id);
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** Record dispatch of an outgoing letter. */
    @Transactional
    public Map<String, Object> dispatch(String id, String method, String trackingNumber, Instant sentAt) {
        Map<String, Object> t = raw(id);
        if (!"letter".equals(t.get("channel")) || !"outbound".equals(t.get("direction"))) {
            throw ApiException.badRequest("Only outgoing letters can be dispatched");
        }
        if (method == null || !DISPATCH.contains(method)) {
            throw ApiException.badRequest("method must be one of: courier, email, fax, hand, post, registered_post");
        }
        Instant when = sentAt == null ? Instant.now() : sentAt;
        jdbc.sql("UPDATE \"HdTicket\" SET \"dispatchMethod\" = ?, \"trackingNumber\" = ?, \"sentAt\" = ?,"
                + " \"dispatchedById\" = ?, version = version + 1, \"updatedAt\" = now() WHERE id = ?")
                .params(method, trackingNumber, SqlRows.ts(when), TenantContext.userId(), id).update();
        message(id, "system", "Dispatched by " + method.replace('_', ' ')
                + (trackingNumber == null || trackingNumber.isBlank() ? "" : " (tracking " + trackingNumber + ")"),
                null, false);
        event(DomainEvents.HELPDESK_LETTER_DISPATCHED, t, Map.of("method", method));
        if ("email".equals(method) && t.get("contactEmail") != null) {
            List<Map<String, Object>> docs = dms.list(HelpdeskResourceProvider.RES_TYPE, id);
            notifications.sendExternalEmail((String) t.get("contactEmail"),
                    "[" + (t.get("letterNumber") == null ? t.get("number") : t.get("letterNumber")) + "] "
                            + t.get("subject"), "Please find our letter attached.", null);
            if (!docs.isEmpty()) {
                message(id, "system", "E-mailed to " + t.get("contactEmail"), (String) t.get("contactEmail"), true);
            }
        }
        return get(id);
    }

    // ---- dashboard ----

    @Transactional
    public Map<String, Object> dashboard() {
        config.ensureDefaults();
        Map<String, Object> out = new LinkedHashMap<>();
        String base = " FROM \"HdTicket\" k JOIN \"HdStage\" s ON s.id = k.\"stageId\" WHERE k.\"organizationId\" = ?"
                + " AND k.\"deletedAt\" IS NULL";
        out.put("open", jdbc.sql("SELECT count(*)" + base + " AND NOT s.\"isClosed\"").param(org()).query(Long.class)
                .single());
        out.put("unassigned", jdbc.sql("SELECT count(*)" + base + " AND NOT s.\"isClosed\" AND k.\"assigneeId\" IS NULL")
                .param(org()).query(Long.class).single());
        out.put("mine", jdbc.sql("SELECT count(*)" + base + " AND NOT s.\"isClosed\" AND k.\"assigneeId\" = ?")
                .params(org(), TenantContext.userId()).query(Long.class).single());
        out.put("urgent", jdbc.sql("SELECT count(*)" + base + " AND NOT s.\"isClosed\" AND k.priority IN ('urgent',"
                + " 'high')").param(org()).query(Long.class).single());
        out.put("closedLast30Days", jdbc.sql("SELECT count(*)" + base + " AND k.\"closedAt\" >= now() - interval"
                + " '30 days'").param(org()).query(Long.class).single());
        out.put("lettersAwaitingReply", jdbc.sql("SELECT count(*)" + base + " AND k.channel = 'letter' AND"
                + " k.direction = 'inbound' AND k.\"replyDueOn\" IS NOT NULL AND k.\"repliedByTicketId\" IS NULL AND"
                + " NOT s.\"isClosed\"").param(org()).query(Long.class).single());
        out.put("lettersOverdue", jdbc.sql("SELECT count(*)" + base + " AND k.channel = 'letter' AND"
                + " k.direction = 'inbound' AND k.\"replyDueOn\" < current_date AND k.\"repliedByTicketId\" IS NULL"
                + " AND NOT s.\"isClosed\"").param(org()).query(Long.class).single());
        out.put("avgFirstResponseHours", jdbc.sql("SELECT round(avg(extract(epoch FROM k.\"firstResponseAt\" -"
                + " k.\"createdAt\") / 3600)::numeric, 1)" + base + " AND k.\"firstResponseAt\" IS NOT NULL AND"
                + " k.\"createdAt\" >= now() - interval '90 days'").param(org()).query(java.math.BigDecimal.class)
                .optional().orElse(null));
        out.put("byChannel", SqlRows.list(jdbc.sql("SELECT k.channel, count(*) AS count" + base + " AND NOT"
                + " s.\"isClosed\" GROUP BY k.channel ORDER BY count DESC").param(org())));
        out.put("byTeam", SqlRows.list(jdbc.sql("SELECT t.id, t.name, count(k.id) FILTER (WHERE NOT s.\"isClosed\") AS"
                + " open FROM \"HdTeam\" t LEFT JOIN \"HdTicket\" k ON k.\"teamId\" = t.id AND k.\"deletedAt\" IS NULL"
                + " LEFT JOIN \"HdStage\" s ON s.id = k.\"stageId\" WHERE t.\"organizationId\" = ? GROUP BY t.id,"
                + " t.name ORDER BY t.name").param(org())));
        out.put("byPriority", SqlRows.list(jdbc.sql("SELECT k.priority, count(*) AS count" + base + " AND NOT"
                + " s.\"isClosed\" GROUP BY k.priority").param(org())));
        List<Map<String, Object>> recent = new ArrayList<>(SqlRows.list(jdbc.sql(SELECT + " WHERE"
                + " k.\"organizationId\" = ? AND k.\"deletedAt\" IS NULL ORDER BY k.\"updatedAt\" DESC LIMIT 10")
                .param(org())));
        out.put("recent", recent);
        return out;
    }
}
