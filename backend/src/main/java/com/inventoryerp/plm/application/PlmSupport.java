package com.inventoryerp.plm.application;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.plm.domain.PlmChangeActivity;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Shared helpers of the PLM services (ADR-S063): lookups, labels, the ECO activity log, events, revisions. */
@Component
public class PlmSupport {

    /** Lifecycle states in order. */
    static final List<String> STATES = List.of("concept", "design", "prototype", "released", "phase_out", "obsolete");

    /** Open ECO statuses: anything that has not been applied or rejected. */
    static final Set<String> OPEN_CHANGE = Set.of("draft", "approved");

    /** Integrity fix 2026-11-24 (ML-8): the actor of what the hourly effectivity job writes. */
    static final String SYSTEM_ACTOR = "system";

    private final PlmDb db;
    private final EventBus events;
    private final com.inventoryerp.kernel.settings.SettingResolverService settings;

    public PlmSupport(PlmDb db, EventBus events, com.inventoryerp.kernel.settings.SettingResolverService settings) {
        this.db = db;
        this.events = events;
        this.settings = settings;
    }

    /** The current user, or {@link #SYSTEM_ACTOR} when a job acts (no user in the tenant context). */
    static String actor() {
        String u = TenantContext.userId();
        return u == null ? SYSTEM_ACTOR : u;
    }

    /** Integrity fix 2026-11-24 (ML-4): {@code plm.allowSelfApproval} (default false = separation of duties). */
    boolean selfApprovalAllowed() {
        return settings.resolveBool("plm.allowSelfApproval",
                com.inventoryerp.kernel.settings.SettingResolverService.SettingContext.EMPTY);
    }

    /**
     * Integrity fix 2026-11-24 (ML-4): refuse (403) when the current user raised or owns the ECO, unless the
     * organization allows self-approval.
     */
    void requireNotOwnChange(Map<String, Object> eco) {
        String me = TenantContext.userId();
        if (me != null && (me.equals(eco.get("createdBy")) || me.equals(eco.get("ownerId")))
                && !selfApprovalAllowed()) {
            throw ApiException.forbidden("You cannot approve a change you raised.");
        }
    }

    /** The ECO row locked {@code FOR UPDATE} (tenancy-scoped), or null. */
    Map<String, Object> lockChange(String id) {
        return SqlRows.one(com.inventoryerp.manufacturing.domain.EngineeringChange.class, db.jdbc().sql("SELECT * FROM"
                + " \"EngineeringChange\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, PlmDb.org()));
    }

    /**
     * Integrity fix 2026-11-24 (ML-2): the content of a draft ECO changed after it was approved at some stage. Its
     * approvals are voided (rejections stay) and, when it already passed a stage that needs approval, it goes back to
     * the first such stage, so what is applied is what was approved. The caller holds the ECO row lock.
     */
    void reopenAfterEdit(Map<String, Object> eco, String what) {
        String id = (String) eco.get("id");
        int voided = db.jdbc().sql("UPDATE \"PlmChangeApproval\" SET voided = true WHERE \"organizationId\" = ? AND"
                + " \"changeId\" = ? AND decision = 'approved' AND NOT voided").params(PlmDb.org(), id).update();
        if (voided == 0) {
            return;
        }
        activity(id, "approval", "Approvals voided: " + what + " changed after approval.");
        // ADR-S086: within the change's own pipeline (each ECO type has its stages).
        Map<String, Object> back = SqlRows.one(db.jdbc().sql("SELECT s.id, s.name FROM \"PlmChangeStage\" s JOIN"
                + " \"PlmChangeStage\" c ON c.id = ? WHERE s.\"organizationId\" = ? AND s.\"deletedAt\" IS NULL AND"
                + " s.\"requiresApproval\" AND s.\"typeId\" IS NOT DISTINCT FROM c.\"typeId\" AND s.sequence < c.sequence"
                + " ORDER BY s.sequence LIMIT 1").params(eco.get("stageId"), PlmDb.org()));
        if (back == null) {
            return;
        }
        // A new stage starts with a clean kanban flag (ADR-S086, as every stage move).
        db.update(com.inventoryerp.manufacturing.domain.EngineeringChange.class, id, Obj.of("stageId", back.get("id"),
                "kanbanState", "normal", "blockedReason", null));
        activity(id, "stage", "Sent back to " + back.get("name") + " for re-approval.");
        event(com.inventoryerp.shared.events.DomainEvents.PLM_CHANGE_STAGE_CHANGED, Obj.of("changeId", id, "number",
                eco.get("number"), "fromStageId", eco.get("stageId"), "toStageId", back.get("id")));
        eco.put("stageId", back.get("id"));
    }

    PlmDb db() {
        return db;
    }

    /** {@code {id, code, name, manufacturingRole}} of a live product of the organization, else 404. */
    Map<String, Object> product(String productId) {
        Map<String, Object> p = productOrNull(productId);
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        return p;
    }

    Map<String, Object> productOrNull(String productId) {
        if (productId == null) {
            return null;
        }
        return SqlRows.one(db.jdbc().sql("SELECT id, code, name, \"manufacturingRole\" FROM \"Product\" WHERE id = ? AND "
                + PlmDb.scope(Product.class, null) + " LIMIT 1").params(productId, PlmDb.org()));
    }

    /** {@code id → {id, code, name}} for the given products (missing ones are left out). */
    Map<String, Map<String, Object>> products(Collection<String> ids) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        List<String> wanted = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"Product\""
                + " WHERE \"organizationId\" = ? AND id = ANY(?::text[])").params(PlmDb.org(), wanted.toArray(String[]::new)))) {
            out.put((String) r.get("id"), r);
        }
        return out;
    }

    /** {@code id → display name} of users of the organization. */
    Map<String, String> userNames(Collection<String> ids) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> wanted = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT id, coalesce(nullif(trim(concat_ws(' ',"
                + " \"firstName\", \"lastName\")), ''), email) AS name FROM \"User\" WHERE \"organizationId\" = ?"
                + " AND id = ANY(?::text[])").params(PlmDb.org(), wanted.toArray(String[]::new)))) {
            out.put((String) r.get("id"), (String) r.get("name"));
        }
        if (wanted.contains(SYSTEM_ACTOR)) {
            out.putIfAbsent(SYSTEM_ACTOR, "System");
        }
        return out;
    }

    /** A user of this organization, else 400 naming the field. */
    void requireUser(String userId, String field) {
        if (userId == null) {
            return;
        }
        Long n = db.jdbc().sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ?")
                .params(userId, PlmDb.org()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest(field + " is not a user of this organization.");
        }
    }

    static String label(Map<String, Object> product) {
        return product == null ? "?" : product.get("code") + " " + product.get("name");
    }

    /** Append to the ECO activity log, with the stage the ECO is in after the entry (time-in-stage reporting). */
    void activity(String changeId, String kind, String message) {
        String stageId = db.jdbc().sql("SELECT \"stageId\" FROM \"EngineeringChange\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(changeId, PlmDb.org()).query(String.class).optional().orElse(null);
        db.insert(PlmChangeActivity.class, Obj.of("changeId", changeId, "kind", kind, "message", message, "userId",
                actor(), "stageId", stageId));
    }

    /** Outbox event with the organization id first. */
    void event(String name, Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("organizationId", PlmDb.org());
        p.putAll(payload);
        events.publish(name, p);
    }

    /** The organization's time zone (UTC when unset or invalid): effective dates are org calendar days. */
    java.time.ZoneId orgZone() {
        String tz = db.jdbc().sql("SELECT timezone FROM \"Organization\" WHERE id = ?").param(PlmDb.org())
                .query(String.class).optional().orElse(null);
        try {
            return tz == null || tz.isBlank() ? java.time.ZoneOffset.UTC : java.time.ZoneId.of(tz.trim());
        } catch (java.time.DateTimeException e) {
            return java.time.ZoneOffset.UTC;
        }
    }

    /** Today on the organization's calendar. */
    LocalDate orgToday() {
        return LocalDate.now(orgZone());
    }

    /**
     * A JSON column value (a Jackson tree, as rows return {@code jsonb}) as plain Java values: objects become ordered
     * maps, arrays lists, numbers {@code BigDecimal}; anything that is not a tree is returned as it is.
     */
    static Object plain(Object v) {
        if (!(v instanceof tools.jackson.databind.JsonNode n)) {
            return v;
        }
        if (n.isObject()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, tools.jackson.databind.JsonNode> e : n.properties()) {
                m.put(e.getKey(), plain(e.getValue()));
            }
            return m;
        }
        if (n.isArray()) {
            List<Object> l = new java.util.ArrayList<>();
            n.forEach(x -> l.add(plain(x)));
            return l;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isNull() || n.isMissingNode()) {
            return null;
        }
        return n.asString();
    }

    /** {@link #plain} of a JSON object column, or an empty map. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object v) {
        Object p = plain(v);
        return p instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static LocalDate date(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid date " + v + ".");
        }
    }

    /** {@code A → B}, {@code Z → AA}, {@code 3 → 4}, {@code B.2 → B.3}; anything else gets {@code .1} appended. */
    static String nextRevision(String current) {
        if (current == null || current.isBlank()) {
            return "A";
        }
        String r = current.trim();
        if (r.matches("[A-Z]+")) {
            char[] c = r.toCharArray();
            int i = c.length - 1;
            while (i >= 0 && c[i] == 'Z') {
                c[i] = 'A';
                i--;
            }
            if (i < 0) {
                return "A" + new String(c);
            }
            c[i]++;
            return new String(c);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)(\\d+)$").matcher(r);
        if (m.matches()) {
            return m.group(1) + (Long.parseLong(m.group(2)) + 1);
        }
        return r + ".1";
    }
}
