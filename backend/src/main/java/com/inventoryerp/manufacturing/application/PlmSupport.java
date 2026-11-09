package com.inventoryerp.manufacturing.application;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.domain.PlmChangeActivity;
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

    private final MfgDb db;
    private final EventBus events;

    public PlmSupport(MfgDb db, EventBus events) {
        this.db = db;
        this.events = events;
    }

    MfgDb db() {
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
                + MfgDb.scope(Product.class, null) + " LIMIT 1").params(productId, MfgDb.org()));
    }

    /** {@code id → {id, code, name}} for the given products (missing ones are left out). */
    Map<String, Map<String, Object>> products(Collection<String> ids) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        List<String> wanted = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT id, code, name FROM \"Product\""
                + " WHERE \"organizationId\" = ? AND id = ANY(?::text[])").params(MfgDb.org(), wanted.toArray(String[]::new)))) {
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
                + " AND id = ANY(?::text[])").params(MfgDb.org(), wanted.toArray(String[]::new)))) {
            out.put((String) r.get("id"), (String) r.get("name"));
        }
        return out;
    }

    /** A user of this organization, else 400 naming the field. */
    void requireUser(String userId, String field) {
        if (userId == null) {
            return;
        }
        Long n = db.jdbc().sql("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ?")
                .params(userId, MfgDb.org()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest(field + " is not a user of this organization.");
        }
    }

    static String label(Map<String, Object> product) {
        return product == null ? "?" : product.get("code") + " " + product.get("name");
    }

    /** Append to the ECO activity log. */
    void activity(String changeId, String kind, String message) {
        db.insert(PlmChangeActivity.class, Obj.of("changeId", changeId, "kind", kind, "message", message, "userId",
                TenantContext.userId()));
    }

    /** Outbox event with the organization id first. */
    void event(String name, Map<String, Object> payload) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("organizationId", MfgDb.org());
        p.putAll(payload);
        events.publish(name, p);
    }

    /** The organization's time zone (UTC when unset or invalid): effective dates are org calendar days. */
    java.time.ZoneId orgZone() {
        String tz = db.jdbc().sql("SELECT timezone FROM \"Organization\" WHERE id = ?").param(MfgDb.org())
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
