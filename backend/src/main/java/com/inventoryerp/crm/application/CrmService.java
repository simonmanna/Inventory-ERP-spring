package com.inventoryerp.crm.application;

import com.inventoryerp.core.domain.Contact;
import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.crm.domain.Activity;
import com.inventoryerp.crm.domain.Deal;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Deals pipeline + activity timeline (port of {@code crm.service.ts}). Deals and activities are soft-deleted; a won
 * deal converts its partner into a customer and cannot be deleted; every stage change logs a
 * {@code deal_stage_change} activity; {@code invoice.created} links the invoice to the partner's open deal.
 */
@Service
public class CrmService {

    private static final Logger log = LoggerFactory.getLogger(CrmService.class);

    /** {@code CreateDealDto}. */
    public record CreateDealInput(String name, String partnerId, String ownerId, String stage, BigDecimal amount,
            String currencyCode, String expectedClose, String notes) {}

    /** {@code CreateActivityDto}. */
    public record CreateActivityInput(String type, String title, String body, String subjectType, String subjectId,
            String dealId, String partnerId, String dueAt, Integer duration) {}

    /** {@code UpdateActivityDto}. */
    public record UpdateActivityInput(String title, String body, String dueAt, String status, Boolean completed) {}

    /** List filters shared by deals and activities. */
    public record ListQuery(String stage, String type, String dealId, String partnerId, String ownerId, String status,
            String q, Integer page, Integer pageSize) {}

    static final List<String> OPEN_STAGES = List.of("lead", "qualified", "proposal", "negotiation");
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final EventBus events;
    private final AuditService audit;

    public CrmService(JdbcClient jdbc, EventBus events, AuditService audit) {
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String userId() {
        return TenantContext.userId();
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("invoice.created", this::handleInvoiceCreated);
    }

    /**
     * C2 — deal ↔ invoice link. Runs in the outbox worker without a tenant context, so every query carries the
     * payload's organization; idempotent on (subjectType, subjectId) so replays never duplicate the activity.
     */
    void handleInvoiceCreated(JsonNode payload) {
        String organizationId = payload.path("organizationId").asString("");
        String documentId = payload.path("documentId").asString("");
        try {
            Map<String, Object> doc = jdbc.sql("SELECT id, \"documentNumber\", \"partnerId\" FROM \"Document\""
                    + " WHERE id = ? AND \"organizationId\" = ?").params(documentId, organizationId).query().listOfRows()
                    .stream().findFirst().orElse(null);
            if (doc == null || doc.get("partnerId") == null) {
                return;
            }
            boolean exists = jdbc.sql("SELECT 1 FROM \"Activity\" WHERE \"organizationId\" = ? AND \"subjectType\" ="
                    + " 'Invoice' AND \"subjectId\" = ? AND type = 'note' LIMIT 1").params(organizationId, doc.get("id"))
                    .query().listOfRows().size() > 0;
            if (exists) {
                return;
            }
            String openDeal = jdbc.sql("SELECT id FROM \"Deal\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND"
                    + " \"deletedAt\" IS NULL AND stage::text IN ('lead', 'qualified', 'proposal', 'negotiation')"
                    + " ORDER BY \"updatedAt\" DESC LIMIT 1").params(organizationId, doc.get("partnerId"))
                    .query(String.class).optional().orElse(null);
            if (openDeal == null) {
                return;
            }
            Object number = doc.get("documentNumber");
            jdbc.sql("INSERT INTO \"Activity\" (id, \"organizationId\", type, \"subjectType\", \"subjectId\", \"dealId\","
                            + " \"partnerId\", title, body, \"updatedAt\") VALUES (?, ?, 'note', 'Invoice', ?, ?, ?, ?, ?, " + NOW
                            + ")")
                    .params(UUID.randomUUID().toString(), organizationId, doc.get("id"), openDeal, doc.get("partnerId"),
                            "Invoice " + number + " created", "Invoice " + number
                                    + " was created for this partner — see the linked document in Timeline.")
                    .update();
        } catch (RuntimeException err) {
            log.warn("crm invoice link failed for {}: {}", documentId, err.toString());
            throw err;
        }
    }

    private void publish(String event, Map<String, Object> payload) {
        Map<String, Object> p = new HashMap<>(payload);
        p.put("organizationId", org());
        events.publish(event, p);
    }

    // ------------------------------------------------------------------ deals

    /** Create a deal for an existing partner of the organization. */
    @Transactional
    public Map<String, Object> createDeal(CreateDealInput input) {
        boolean partner = jdbc.sql("SELECT 1 FROM \"Partner\" WHERE \"organizationId\" = ? AND id = ?")
                .params(org(), input.partnerId()).query().listOfRows().size() > 0;
        if (!partner) {
            throw ApiException.notFound("Partner not found");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Deal\" (id, \"organizationId\", name, \"partnerId\", \"ownerId\", stage, amount,"
                        + " \"currencyCode\", \"expectedClose\", notes, \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?::\"DealStage\","
                        + " ?, ?, ?, ?, " + NOW + ")")
                .params(id, org(), input.name(), input.partnerId(), input.ownerId() != null ? input.ownerId() : userId(),
                        input.stage() != null ? input.stage() : "lead",
                        input.amount() != null ? input.amount() : BigDecimal.ZERO,
                        input.currencyCode() != null ? input.currencyCode() : "USD",
                        input.expectedClose() != null && !input.expectedClose().isEmpty()
                                ? SqlRows.ts(EntityPatch.toInstant(input.expectedClose())) : null,
                        input.notes())
                .update();
        Map<String, Object> deal = dealRow(id);
        audit.record(AuditInput.of("Deal", id, "create", null, Obj.of("name", deal.get("name"), "stage", deal.get("stage"),
                "amount", JsNumbers.js(new BigDecimal(deal.get("amount").toString())))));
        publish("crm.deal.created", Obj.of("dealId", id, "name", deal.get("name"), "stage", deal.get("stage")));
        return deal;
    }

    /** Partial update of a live deal. */
    @Transactional
    public Map<String, Object> updateDeal(String id, Map<String, Object> input) {
        requireDeal(id);
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (String f : List.of("name", "stage", "amount", "expectedClose", "notes", "ownerId")) {
            if (!input.containsKey(f)) {
                continue;
            }
            Object v = input.get(f);
            switch (f) {
                case "stage" -> sets.add("stage = ?::\"DealStage\"");
                case "expectedClose" -> {
                    sets.add("\"expectedClose\" = ?");
                    v = v == null || v.toString().isEmpty() ? null : SqlRows.ts(EntityPatch.toInstant(v.toString()));
                }
                case "amount" -> {
                    sets.add("amount = ?");
                    v = v == null ? null : new BigDecimal(v.toString());
                }
                default -> sets.add("\"" + f + "\" = ?");
            }
            params.add(v);
        }
        sets.add("\"updatedAt\" = " + NOW);
        params.addAll(List.of(id, org()));
        jdbc.sql("UPDATE \"Deal\" SET " + String.join(", ", sets) + " WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(params).update();
        audit.record(AuditInput.of("Deal", id, "update", null, input));
        return dealRow(id);
    }

    /** Move a deal to another stage; won converts the partner into a customer. */
    @Transactional
    public Map<String, Object> changeStage(String id, String stage) {
        Map<String, Object> deal = requireDeal(id);
        if (String.valueOf(deal.get("stage")).equals(stage)) {
            return deal;
        }
        jdbc.sql("UPDATE \"Deal\" SET stage = ?::\"DealStage\", \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(stage, id, org()).update();
        Map<String, Object> updated = dealRow(id);
        if ("won".equals(stage)) {
            jdbc.sql("UPDATE \"Partner\" SET \"isCustomer\" = true, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"isCustomer\" = false").params(deal.get("partnerId"), org()).update();
        }
        jdbc.sql("INSERT INTO \"Activity\" (id, \"organizationId\", type, title, \"dealId\", \"partnerId\", \"occurredAt\","
                        + " \"createdById\", \"updatedAt\") VALUES (?, ?, 'deal_stage_change', ?, ?, ?, " + NOW + ", ?, " + NOW
                        + ")")
                .params(UUID.randomUUID().toString(), org(), "Stage: " + deal.get("stage") + " → " + stage, id,
                        deal.get("partnerId"), userId())
                .update();
        publish("crm.deal.stage_changed", Obj.of("dealId", id, "fromStage", deal.get("stage"), "toStage", stage));
        return updated;
    }

    /** Paginated deal list {@code {data, meta}}. */
    @Transactional(readOnly = true)
    public Map<String, Object> listDeals(ListQuery q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"deletedAt\" IS NULL");
        if (notBlank(q.stage())) {
            where.append(" AND stage::text = ?");
            params.add(q.stage());
        }
        if (notBlank(q.partnerId())) {
            where.append(" AND \"partnerId\" = ?");
            params.add(q.partnerId());
        }
        if (notBlank(q.ownerId())) {
            where.append(" AND \"ownerId\" = ?");
            params.add(q.ownerId());
        }
        if (notBlank(q.q())) {
            where.append(" AND name ILIKE ?");
            params.add("%" + com.inventoryerp.kernel.crud.BaseCrudService.escapeLike(q.q()) + "%");
        }
        int page = Math.max(1, q.page() == null ? 1 : q.page());
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, q.pageSize() == null ? DEFAULT_PAGE_SIZE : q.pageSize()));
        List<Map<String, Object>> items = SqlRows.list(Deal.class, jdbc.sql("SELECT * FROM \"Deal\" WHERE" + where
                + " ORDER BY \"updatedAt\" DESC OFFSET " + (long) (page - 1) * pageSize + " LIMIT " + pageSize)
                .params(params));
        long total = jdbc.sql("SELECT count(*) FROM \"Deal\" WHERE" + where).params(params).query(Long.class).single();
        items.forEach(this::withPartnerSummary);
        items.forEach(CrmService::numericAmount);
        return Obj.of("data", attachUsers(items), "meta", Obj.of("page", page, "pageSize", pageSize, "total", total,
                "totalPages", Math.max(1, (long) Math.ceil((double) total / pageSize))));
    }

    /** Deal with partner (+ contacts), last 50 activities and owner. */
    @Transactional(readOnly = true)
    public Map<String, Object> findDeal(String id) {
        Map<String, Object> deal = SqlRows.one(Deal.class, jdbc.sql("SELECT * FROM \"Deal\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (deal == null) {
            throw ApiException.notFound("Deal not found");
        }
        Map<String, Object> partner = SqlRows.one(Partner.class, jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ?")
                .param(deal.get("partnerId")));
        if (partner != null) {
            partner.put("contacts", SqlRows.list(Contact.class, jdbc.sql("SELECT * FROM \"Contact\" WHERE"
                    + " \"partnerId\" = ?").param(partner.get("id"))));
        }
        deal.put("partner", partner);
        deal.put("activities", SqlRows.list(Activity.class, jdbc.sql("SELECT * FROM \"Activity\" WHERE \"dealId\" = ?"
                + " ORDER BY \"occurredAt\" DESC LIMIT 50").param(id)));
        numericAmount(deal);
        return attachUsers(List.of(deal)).getFirst();
    }

    /** Soft delete (won deals are protected). */
    @Transactional
    public Map<String, Object> removeDeal(String id) {
        Map<String, Object> deal = requireDeal(id);
        if ("won".equals(String.valueOf(deal.get("stage")))) {
            throw ApiException.badRequest("Cannot delete a won deal; archive it instead");
        }
        Instant deletedAt = jdbc.sql("UPDATE \"Deal\" SET \"deletedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? RETURNING \"deletedAt\"").params(id, org())
                .query(java.sql.Timestamp.class).single().toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
        audit.record(AuditInput.of("Deal", id, "delete", null, Obj.of("deletedAt", deletedAt.toString())));
        publish("crm.deal.deleted", Obj.of("dealId", id));
        return Obj.of("ok", true);
    }

    /** Restore an archived deal. */
    @Transactional
    public Map<String, Object> restoreDeal(String id) {
        int n = jdbc.sql("UPDATE \"Deal\" SET \"deletedAt\" = NULL, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NOT NULL").params(id, org()).update();
        if (n == 0) {
            throw ApiException.notFound("Archived deal not found");
        }
        Map<String, Object> restored = dealRow(id);
        audit.record(AuditInput.of("Deal", id, "restore", null, Obj.of("stage", restored.get("stage"))));
        return restored;
    }

    /** Archived deals, most recently deleted first (max 200). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDeletedDeals() {
        List<Map<String, Object>> items = SqlRows.list(Deal.class, jdbc.sql("SELECT * FROM \"Deal\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NOT NULL ORDER BY \"deletedAt\" DESC LIMIT 200")
                .param(org()));
        items.forEach(this::withPartnerSummary);
        items.forEach(CrmService::numericAmount);
        return attachUsers(items);
    }

    // ------------------------------------------------------------------ activities

    /** Log an activity (deal / partner must belong to the organization). */
    @Transactional
    public Map<String, Object> createActivity(CreateActivityInput input) {
        if (input.dealId() != null && jdbc.sql("SELECT 1 FROM \"Deal\" WHERE id = ? AND \"organizationId\" = ?")
                .params(input.dealId(), org()).query().listOfRows().isEmpty()) {
            throw ApiException.notFound("Deal not found");
        }
        if (input.partnerId() != null && jdbc.sql("SELECT 1 FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(input.partnerId(), org()).query().listOfRows().isEmpty()) {
            throw ApiException.notFound("Partner not found");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Activity\" (id, \"organizationId\", type, title, body, \"subjectType\", \"subjectId\","
                        + " \"dealId\", \"partnerId\", \"dueAt\", duration, status, \"createdById\", \"updatedAt\") VALUES (?, ?,"
                        + " ?::\"ActivityType\", ?, ?, ?, ?, ?, ?, ?, ?, 'todo', ?, " + NOW + ")")
                .params(id, org(), input.type(), input.title(), input.body(), input.subjectType(), input.subjectId(),
                        input.dealId(), input.partnerId(), input.dueAt() != null && !input.dueAt().isEmpty()
                                ? SqlRows.ts(EntityPatch.toInstant(input.dueAt())) : null,
                        input.duration(), userId())
                .update();
        return activityRow(id);
    }

    /** Partial update of a live activity. */
    @Transactional
    public Map<String, Object> updateActivity(String id, UpdateActivityInput input) {
        requireActivity(id);
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (input.title() != null) {
            sets.add("title = ?");
            params.add(input.title());
        }
        if (input.body() != null) {
            sets.add("body = ?");
            params.add(input.body());
        }
        if (input.dueAt() != null) {
            sets.add("\"dueAt\" = ?");
            params.add(input.dueAt().isEmpty() ? null : SqlRows.ts(EntityPatch.toInstant(input.dueAt())));
        }
        if (input.status() != null) {
            sets.add("status = ?::\"TaskStatus\"");
            params.add(input.status());
        }
        if (input.completed() != null) {
            sets.add("completed = ?");
            params.add(input.completed());
            sets.add(input.completed() ? "\"completedAt\" = " + NOW : "\"completedAt\" = NULL");
        }
        sets.add("\"updatedAt\" = " + NOW);
        params.addAll(List.of(id, org()));
        jdbc.sql("UPDATE \"Activity\" SET " + String.join(", ", sets) + " WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(params).update();
        return activityRow(id);
    }

    /** Mark an activity done. */
    @Transactional
    public Map<String, Object> completeActivity(String id) {
        requireActivity(id);
        jdbc.sql("UPDATE \"Activity\" SET status = 'done', completed = true, \"completedAt\" = " + NOW + ", \"updatedAt\" = "
                + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()).update();
        return activityRow(id);
    }

    /** Paginated activity list {@code {data, meta}}. */
    @Transactional(readOnly = true)
    public Map<String, Object> listActivities(ListQuery q) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"deletedAt\" IS NULL");
        if (notBlank(q.type())) {
            where.append(" AND type::text = ?");
            params.add(q.type());
        }
        if (notBlank(q.dealId())) {
            where.append(" AND \"dealId\" = ?");
            params.add(q.dealId());
        }
        if (notBlank(q.partnerId())) {
            where.append(" AND \"partnerId\" = ?");
            params.add(q.partnerId());
        }
        if (notBlank(q.status())) {
            where.append(" AND status::text = ?");
            params.add(q.status());
        }
        int page = Math.max(1, q.page() == null ? 1 : q.page());
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, q.pageSize() == null ? DEFAULT_PAGE_SIZE : q.pageSize()));
        List<Map<String, Object>> items = SqlRows.list(Activity.class, jdbc.sql("SELECT * FROM \"Activity\" WHERE" + where
                + " ORDER BY \"occurredAt\" DESC OFFSET " + (long) (page - 1) * pageSize + " LIMIT " + pageSize)
                .params(params));
        long total = jdbc.sql("SELECT count(*) FROM \"Activity\" WHERE" + where).params(params).query(Long.class)
                .single();
        return Obj.of("data", attachUsers(items), "meta", Obj.of("page", page, "pageSize", pageSize, "total", total,
                "totalPages", Math.max(1, (long) Math.ceil((double) total / pageSize))));
    }

    /** Open task follow-ups (todo / in_progress, not overdue), nearest due date first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> upcomingTasks(int limit) {
        int take = Math.min(100, Math.max(1, limit));
        return attachUsers(SqlRows.list(Activity.class, jdbc.sql("SELECT * FROM \"Activity\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND type = 'task' AND status::text IN ('todo', 'in_progress') AND"
                + " (\"dueAt\" IS NULL OR \"dueAt\" >= ?) ORDER BY \"dueAt\" ASC LIMIT " + take)
                .params(org(), SqlRows.ts(Instant.now()))));
    }

    /** Soft delete. */
    @Transactional
    public Map<String, Object> removeActivity(String id) {
        requireActivity(id);
        jdbc.sql("UPDATE \"Activity\" SET \"deletedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()).update();
        audit.record(AuditInput.of("Activity", id, "delete", null, Map.of()));
        return Obj.of("ok", true);
    }

    /** Restore an archived activity. */
    @Transactional
    public Map<String, Object> restoreActivity(String id) {
        int n = jdbc.sql("UPDATE \"Activity\" SET \"deletedAt\" = NULL, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NOT NULL").params(id, org()).update();
        if (n == 0) {
            throw ApiException.notFound("Archived activity not found");
        }
        audit.record(AuditInput.of("Activity", id, "restore", null, Map.of()));
        return activityRow(id);
    }

    /** Archived activities (max 200). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDeletedActivities() {
        return SqlRows.list(Activity.class, jdbc.sql("SELECT * FROM \"Activity\" WHERE \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NOT NULL ORDER BY \"deletedAt\" DESC LIMIT 200").param(org()));
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> requireDeal(String id) {
        Map<String, Object> deal = SqlRows.one(Deal.class, jdbc.sql("SELECT * FROM \"Deal\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (deal == null) {
            throw ApiException.notFound("Deal not found");
        }
        return deal;
    }

    private void requireActivity(String id) {
        if (jdbc.sql("SELECT 1 FROM \"Activity\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, org()).query().listOfRows().isEmpty()) {
            throw ApiException.notFound("Activity not found");
        }
    }

    private Map<String, Object> dealRow(String id) {
        return SqlRows.one(Deal.class, jdbc.sql("SELECT * FROM \"Deal\" WHERE id = ?").param(id));
    }

    private Map<String, Object> activityRow(String id) {
        return SqlRows.one(Activity.class, jdbc.sql("SELECT * FROM \"Activity\" WHERE id = ?").param(id));
    }

    private void withPartnerSummary(Map<String, Object> deal) {
        deal.put("partner", SqlRows.one(jdbc.sql("SELECT name, code FROM \"Partner\" WHERE id = ?")
                .param(deal.get("partnerId"))));
    }

    private static void numericAmount(Map<String, Object> deal) {
        Object amount = deal.get("amount");
        if (amount != null) {
            deal.put("amount", JsNumbers.js(new BigDecimal(amount.toString())));
        }
    }

    /** Resolve {@code ownerId → owner} and {@code createdById → createdBy} in one extra query. */
    private List<Map<String, Object>> attachUsers(List<Map<String, Object>> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Stream.of(r.get("ownerId"), r.get("createdById")).filter(v -> v != null).forEach(v -> ids.add(v.toString()));
        }
        if (ids.isEmpty()) {
            return rows;
        }
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> u : jdbc.sql("SELECT id, email, \"firstName\", \"lastName\" FROM \"User\" WHERE id IN"
                + " (:ids) AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(ids))
                .param("org", org()).query().listOfRows()) {
            String name = Stream.of(u.get("firstName"), u.get("lastName")).filter(v -> v != null && !v.toString().isEmpty())
                    .map(Object::toString).collect(Collectors.joining(" "));
            byId.put((String) u.get("id"), Obj.of("id", u.get("id"), "email", u.get("email"), "name", name));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> clone = new LinkedHashMap<>(r);
            if (r.get("ownerId") != null && byId.containsKey(r.get("ownerId").toString())) {
                clone.put("owner", byId.get(r.get("ownerId").toString()));
            }
            if (r.get("createdById") != null && byId.containsKey(r.get("createdById").toString())) {
                clone.put("createdBy", byId.get(r.get("createdById").toString()));
            }
            out.add(clone);
        }
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }
}
