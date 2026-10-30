package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.NOW;
import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.dec;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.strings;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.sales.application.SalesSettingsService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRM configuration (ADR-S010): the admin-configurable pipeline stages, sales teams, tags, lead sources, lost
 * reasons, organization settings and per-user saved filters. A fresh organization gets the Odoo-style default
 * pipeline (New → Qualified → Proposition → Negotiation → Won) plus default sources and lost reasons on first use.
 */
@Service
public class CrmConfigService {

    static final Set<String> LEGACY_STAGES = Set.of("lead", "qualified", "proposal", "negotiation", "won");
    static final Set<String> COLORS = Set.of("slate", "sky", "blue", "indigo", "violet", "pink", "rose", "orange",
            "amber", "lime", "emerald", "teal");
    static final Set<String> FILTER_SCOPES = Set.of("pipeline", "leads", "quotations", "activities", "report");

    private final CrmDb db;
    private final AuditService audit;
    private final SalesSettingsService sales;

    public CrmConfigService(CrmDb db, AuditService audit, SalesSettingsService sales) {
        this.db = db;
        this.audit = audit;
        this.sales = sales;
    }

    /** Quotation policy keys owned by the sales settings since ADR-S011 (shown and edited here unchanged). */
    static final List<String> QUOTE_KEYS = List.of("quoteMaxDiscountPercent", "quoteApprovalAmount", "creditCheck",
            "quoteValidityDays", "defaultQuoteTerms", "autoWinOnConversion");

    // ------------------------------------------------------------------ defaults

    /** Seed the default pipeline, sources, lost reasons and settings row once per organization (idempotent). */
    @Transactional
    public void ensureDefaults() {
        String org = org();
        db.update("INSERT INTO \"CrmSettings\" (\"organizationId\") VALUES (?) ON CONFLICT DO NOTHING", org);
        // Serialize the one-time seeding per organization.
        db.jdbc().sql("SELECT 1 FROM \"CrmSettings\" WHERE \"organizationId\" = ? FOR UPDATE").param(org).query()
                .listOfRows();
        if (db.count("SELECT count(*) FROM \"CrmStage\" WHERE \"organizationId\" = ?", List.of(org)) == 0) {
            Object[][] stages = {
                {"New", 10, "lead", false, "slate", 10},
                {"Qualified", 20, "qualified", false, "sky", 30},
                {"Proposition", 30, "proposal", false, "violet", 60},
                {"Negotiation", 40, "negotiation", false, "amber", 80},
                {"Won", 50, "won", true, "emerald", 100},
            };
            for (Object[] s : stages) {
                db.insert("CrmStage", Obj.of("name", s[0], "sequence", s[1], "legacyStage", null, "isWon", s[3],
                        "color", s[4], "probability", new BigDecimal(s[5].toString())));
                db.update("UPDATE \"CrmStage\" SET \"legacyStage\" = ?::\"DealStage\" WHERE \"organizationId\" = ? AND"
                        + " name = ?", s[2], org, s[0]);
            }
            // Attach legacy deals to the matching configured stage.
            db.update("UPDATE \"Deal\" d SET \"stageId\" = s.id FROM \"CrmStage\" s WHERE d.\"organizationId\" = ? AND"
                    + " s.\"organizationId\" = d.\"organizationId\" AND d.\"stageId\" IS NULL AND s.\"legacyStage\" ="
                    + " (CASE WHEN d.stage::text = 'lost' THEN 'lead' ELSE d.stage::text END)::\"DealStage\"", org);
        }
        if (db.count("SELECT count(*) FROM \"CrmSource\" WHERE \"organizationId\" = ?", List.of(org)) == 0) {
            for (String s : List.of("Website", "Referral", "Phone", "Email", "Walk-in", "Social media", "Campaign",
                    "Trade show")) {
                db.insert("CrmSource", Obj.of("name", s));
            }
        }
        if (db.count("SELECT count(*) FROM \"CrmLostReason\" WHERE \"organizationId\" = ?", List.of(org)) == 0) {
            for (String s : List.of("Too expensive", "Chose a competitor", "No budget", "Not interested",
                    "No response", "Timing not right", "Product not suitable")) {
                db.insert("CrmLostReason", Obj.of("name", s));
            }
        }
    }

    // ------------------------------------------------------------------ lookups

    /** Everything the CRM screens need in one call: stages, teams, tags, sources, lost reasons, users, settings. */
    @Transactional
    public Map<String, Object> lookups() {
        ensureDefaults();
        return Obj.of("stages", stages(), "teams", teams(), "tags", tags(), "sources", sources(true),
                "lostReasons", lostReasons(true), "users", users(), "settings", settings());
    }

    /** Active users of the organization (salespeople pickers). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> users() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> u : db.rows("SELECT id, email, \"firstName\", \"lastName\" FROM \"User\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY \"firstName\", email", org())) {
            String name = Stream.of(u.get("firstName"), u.get("lastName")).filter(v -> v != null
                    && !v.toString().isBlank()).map(Object::toString).collect(Collectors.joining(" "));
            out.add(Obj.of("id", u.get("id"), "email", u.get("email"), "name", name.isBlank() ? u.get("email") : name));
        }
        return out;
    }

    // ------------------------------------------------------------------ stages

    @Transactional(readOnly = true)
    public List<Map<String, Object>> stages() {
        return db.rows("SELECT s.*, (SELECT count(*) FROM \"Deal\" d WHERE d.\"stageId\" = s.id AND d.\"deletedAt\""
                + " IS NULL AND d.status = 'open') AS \"openCount\" FROM \"CrmStage\" s WHERE s.\"organizationId\" = ?"
                + " ORDER BY s.sequence, s.\"createdAt\"", org());
    }

    @Transactional
    public Map<String, Object> createStage(Map<String, Object> in) {
        ensureDefaults();
        String name = requireName(in);
        Integer seq = in.get("sequence") == null ? null : ((Number) in.get("sequence")).intValue();
        if (seq == null) {
            seq = (int) db.count("SELECT COALESCE(max(sequence), 0) + 10 FROM \"CrmStage\" WHERE \"organizationId\""
                    + " = ?", List.of(org()));
        }
        Map<String, Object> data = stageData(in);
        data.put("name", name);
        data.put("sequence", seq);
        data.remove("legacyStage");
        Map<String, Object> row = db.insert("CrmStage", data);
        applyLegacy(str(row.get("id")), in, Boolean.TRUE.equals(row.get("isWon")));
        audit.record(AuditInput.of("CrmStage", str(row.get("id")), "create", null, in));
        return stage(str(row.get("id")));
    }

    @Transactional
    public Map<String, Object> updateStage(String id, Map<String, Object> in) {
        Map<String, Object> before = db.require("CrmStage", id, "Stage not found");
        Map<String, Object> data = stageData(in);
        if (in.containsKey("name")) {
            data.put("name", requireName(in));
        }
        if (in.containsKey("sequence") && in.get("sequence") != null) {
            data.put("sequence", ((Number) in.get("sequence")).intValue());
        }
        data.remove("legacyStage");
        if (Boolean.FALSE.equals(in.get("isActive")) && Boolean.TRUE.equals(before.get("isActive"))) {
            long open = db.count("SELECT count(*) FROM \"Deal\" WHERE \"stageId\" = ? AND \"deletedAt\" IS NULL AND"
                    + " status = 'open'", List.of(id));
            if (open > 0) {
                throw ApiException.badRequest("Move the " + open + " open opportunities out of this stage first");
            }
        }
        db.patch("CrmStage", id, data, true);
        boolean won = in.containsKey("isWon") ? Boolean.TRUE.equals(in.get("isWon"))
                : Boolean.TRUE.equals(before.get("isWon"));
        if (in.containsKey("legacyStage") || in.containsKey("isWon")) {
            applyLegacy(id, in, won);
        }
        audit.record(AuditInput.of("CrmStage", id, "update", before, in));
        return stage(id);
    }

    /** Persist a new order: {@code ids} in kanban order get sequence 10, 20, 30… */
    @Transactional
    public List<Map<String, Object>> reorderStages(List<String> ids) {
        int seq = 10;
        for (String id : ids) {
            if (db.update("UPDATE \"CrmStage\" SET sequence = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                    + " \"organizationId\" = ?", seq, id, org()) == 0) {
                throw ApiException.notFound("Stage not found");
            }
            seq += 10;
        }
        audit.record(AuditInput.of("CrmStage", "reorder", "update", null, Obj.of("order", ids)));
        return stages();
    }

    /** Delete an empty stage; stages holding live opportunities must be emptied first. */
    @Transactional
    public Map<String, Object> deleteStage(String id) {
        db.require("CrmStage", id, "Stage not found");
        long used = db.count("SELECT count(*) FROM \"Deal\" WHERE \"stageId\" = ? AND \"deletedAt\" IS NULL",
                List.of(id));
        if (used > 0) {
            throw ApiException.badRequest("Stage still has " + used + " opportunities; move them first");
        }
        if (db.count("SELECT count(*) FROM \"CrmStage\" WHERE \"organizationId\" = ? AND \"isActive\"",
                List.of(org())) <= 1) {
            throw ApiException.badRequest("The pipeline needs at least one stage");
        }
        db.update("DELETE FROM \"CrmStage\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("CrmStage", id, "delete"));
        return Obj.of("ok", true);
    }

    Map<String, Object> stage(String id) {
        return db.row("SELECT * FROM \"CrmStage\" WHERE id = ? AND \"organizationId\" = ?", id, org());
    }

    /** First active stage of the pipeline (optionally for a team). */
    Map<String, Object> firstStage(String teamId) {
        Map<String, Object> s = db.row("SELECT * FROM \"CrmStage\" WHERE \"organizationId\" = ? AND \"isActive\" AND"
                + " NOT \"isWon\" AND (\"teamId\" IS NULL OR \"teamId\" = ?) ORDER BY sequence LIMIT 1", org(), teamId);
        if (s == null) {
            ensureDefaults();
            s = db.row("SELECT * FROM \"CrmStage\" WHERE \"organizationId\" = ? AND \"isActive\" ORDER BY sequence"
                    + " LIMIT 1", org());
        }
        return s;
    }

    /** The won stage (highest-sequence active stage flagged won), or null. */
    Map<String, Object> wonStage() {
        return db.row("SELECT * FROM \"CrmStage\" WHERE \"organizationId\" = ? AND \"isActive\" AND \"isWon\" ORDER BY"
                + " sequence DESC LIMIT 1", org());
    }

    private Map<String, Object> stageData(Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (in.containsKey("probability")) {
            BigDecimal p = dec(in.get("probability"));
            if (p.signum() < 0 || p.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw ApiException.badRequest("probability must be between 0 and 100");
            }
            d.put("probability", p);
        }
        for (String b : List.of("isWon", "fold", "isActive")) {
            if (in.containsKey(b)) {
                d.put(b, Boolean.TRUE.equals(in.get(b)));
            }
        }
        if (in.containsKey("color")) {
            d.put("color", color(in.get("color")));
        }
        if (in.containsKey("requirements")) {
            d.put("requirements", blank(str(in.get("requirements"))) ? null : str(in.get("requirements")));
        }
        if (in.containsKey("teamId")) {
            String team = str(in.get("teamId"));
            if (!blank(team) && !db.exists("CrmTeam", team)) {
                throw ApiException.notFound("Team not found");
            }
            d.put("teamId", blank(team) ? null : team);
        }
        if (in.containsKey("rottingDays")) {
            Object r = in.get("rottingDays");
            int days = r == null ? 0 : ((Number) r).intValue();
            if (r != null && days <= 0) {
                throw ApiException.badRequest("rottingDays must be positive");
            }
            d.put("rottingDays", r == null ? null : days);
        }
        if (in.containsKey("legacyStage")) {
            d.put("legacyStage", in.get("legacyStage"));
        }
        return d;
    }

    private void applyLegacy(String id, Map<String, Object> in, boolean won) {
        String legacy = won ? "won" : str(in.get("legacyStage"));
        if (blank(legacy)) {
            return;
        }
        if (!LEGACY_STAGES.contains(legacy)) {
            throw ApiException.badRequest("legacyStage must be one of: lead, qualified, proposal, negotiation, won");
        }
        db.update("UPDATE \"CrmStage\" SET \"legacyStage\" = ?::\"DealStage\" WHERE id = ? AND \"organizationId\" = ?",
                legacy, id, org());
    }

    // ------------------------------------------------------------------ teams

    @Transactional(readOnly = true)
    public List<Map<String, Object>> teams() {
        return db.rows("SELECT * FROM \"CrmTeam\" WHERE \"organizationId\" = ? ORDER BY name", org());
    }

    @Transactional
    public Map<String, Object> saveTeam(String id, Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("name")) {
            d.put("name", requireName(in));
        }
        if (in.containsKey("leaderId")) {
            d.put("leaderId", blank(str(in.get("leaderId"))) ? null : requireUser(str(in.get("leaderId"))));
        }
        if (in.containsKey("memberIds")) {
            List<String> members = strings(in.get("memberIds"));
            members.forEach(this::requireUser);
            d.put("memberIds", members);
        }
        if (in.containsKey("monthlyTarget")) {
            BigDecimal t = dec(in.get("monthlyTarget"));
            if (t.signum() < 0) {
                throw ApiException.badRequest("monthlyTarget must not be negative");
            }
            d.put("monthlyTarget", t);
        }
        if (in.containsKey("color")) {
            d.put("color", color(in.get("color")));
        }
        if (in.containsKey("isActive")) {
            d.put("isActive", Boolean.TRUE.equals(in.get("isActive")));
        }
        Map<String, Object> row = unique("CrmTeam", id, d, "A team with this name already exists");
        audit.record(AuditInput.of("CrmTeam", str(row.get("id")), id == null ? "create" : "update", null, in));
        return row;
    }

    // ------------------------------------------------------------------ tags / sources / lost reasons

    @Transactional(readOnly = true)
    public List<Map<String, Object>> tags() {
        return db.rows("SELECT * FROM \"CrmTag\" WHERE \"organizationId\" = ? ORDER BY name", org());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> sources(boolean all) {
        return db.rows("SELECT * FROM \"CrmSource\" WHERE \"organizationId\" = ?" + (all ? "" : " AND \"isActive\"")
                + " ORDER BY name", org());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> lostReasons(boolean all) {
        return db.rows("SELECT * FROM \"CrmLostReason\" WHERE \"organizationId\" = ?" + (all ? "" : " AND"
                + " \"isActive\"") + " ORDER BY name", org());
    }

    /** Create / update a simple lookup ({@code CrmTag}, {@code CrmSource}, {@code CrmLostReason}). */
    @Transactional
    public Map<String, Object> saveLookup(String table, String id, Map<String, Object> in) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (id == null || in.containsKey("name")) {
            d.put("name", requireName(in));
        }
        if (in.containsKey("color") && "CrmTag".equals(table)) {
            d.put("color", color(in.get("color")));
        }
        if (in.containsKey("isActive") && !"CrmTag".equals(table)) {
            d.put("isActive", Boolean.TRUE.equals(in.get("isActive")));
        }
        Map<String, Object> row = unique(table, id, d, "An entry with this name already exists");
        audit.record(AuditInput.of(table, str(row.get("id")), id == null ? "create" : "update", null, in));
        return row;
    }

    /** Delete a lookup; references are cleared ({@code ON DELETE SET NULL}, tag ids removed from deals). */
    @Transactional
    public Map<String, Object> deleteLookup(String table, String id) {
        db.require(table, id, "Not found");
        if ("CrmTag".equals(table)) {
            db.update("UPDATE \"Deal\" SET \"tagIds\" = array_remove(\"tagIds\", ?) WHERE \"organizationId\" = ? AND"
                    + " ? = ANY(\"tagIds\")", id, org(), id);
        }
        if ("CrmTeam".equals(table)) {
            db.update("UPDATE \"Deal\" SET \"teamId\" = NULL WHERE \"teamId\" = ? AND \"organizationId\" = ?", id, org());
        }
        db.update("DELETE FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of(table, id, "delete"));
        return Obj.of("ok", true);
    }

    private Map<String, Object> unique(String table, String id, Map<String, Object> d, String dupMessage) {
        if (d.containsKey("name") && db.count("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ? AND"
                + " lower(name) = lower(?) AND id <> ?", List.of(org(), d.get("name"), id == null ? "" : id)) > 0) {
            throw ApiException.conflict(dupMessage);
        }
        if (id == null) {
            return db.insert(table, d);
        }
        db.require(table, id, "Not found");
        boolean touch = !"CrmTag".equals(table) && !"CrmSource".equals(table) && !"CrmLostReason".equals(table);
        return db.patch(table, id, d, touch);
    }

    // ------------------------------------------------------------------ settings

    @Transactional
    public Map<String, Object> settings() {
        db.update("INSERT INTO \"CrmSettings\" (\"organizationId\") VALUES (?) ON CONFLICT DO NOTHING", org());
        Map<String, Object> out = db.row("SELECT * FROM \"CrmSettings\" WHERE \"organizationId\" = ?", org());
        Map<String, Object> quote = sales.settings();
        for (String k : QUOTE_KEYS) {
            out.put(k, quote.get(k));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> updateSettings(Map<String, Object> in) {
        Map<String, Object> before = settings();
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (String k : List.of("quoteMaxDiscountPercent", "quoteApprovalAmount")) {
            if (in.containsKey(k)) {
                BigDecimal v = dec(in.get(k));
                if (v.signum() < 0 || ("quoteMaxDiscountPercent".equals(k) && v.compareTo(BigDecimal.valueOf(100)) > 0)) {
                    throw ApiException.badRequest(k + " is out of range");
                }
                sets.add("\"" + k + "\" = ?");
                params.add(v);
            }
        }
        for (String k : List.of("quoteValidityDays", "rottingDays")) {
            if (in.containsKey(k)) {
                int v = in.get(k) == null ? 0 : ((Number) in.get(k)).intValue();
                if (v <= 0) {
                    throw ApiException.badRequest(k + " must be positive");
                }
                sets.add("\"" + k + "\" = ?");
                params.add(v);
            }
        }
        for (String k : List.of("creditCheck", "autoWinOnConversion", "useLeads")) {
            if (in.containsKey(k)) {
                sets.add("\"" + k + "\" = ?");
                params.add(Boolean.TRUE.equals(in.get(k)));
            }
        }
        if (in.containsKey("defaultQuoteTerms")) {
            sets.add("\"defaultQuoteTerms\" = ?");
            params.add(str(in.get("defaultQuoteTerms")));
        }
        if (in.containsKey("assignmentMode")) {
            String m = str(in.get("assignmentMode"));
            if (m == null || !Set.of("manual", "round_robin", "least_loaded").contains(m)) {
                throw ApiException.badRequest("assignmentMode must be one of: manual, round_robin, least_loaded");
            }
            sets.add("\"assignmentMode\" = ?");
            params.add(m);
        }
        if (in.containsKey("assignmentPool")) {
            List<String> pool = strings(in.get("assignmentPool"));
            pool.forEach(this::requireUser);
            sets.add("\"assignmentPool\" = ?");
            params.add(pool.toArray(String[]::new));
        }
        Map<String, Object> quote = new java.util.LinkedHashMap<>();
        for (String k : QUOTE_KEYS) {
            if (in.containsKey(k)) {
                quote.put(k, in.get(k));
            }
        }
        if (!quote.isEmpty()) {
            sales.update(quote);
        }
        if (!sets.isEmpty()) {
            params.add(org());
            db.jdbc().sql("UPDATE \"CrmSettings\" SET " + String.join(", ", sets) + ", \"updatedAt\" = " + NOW
                    + " WHERE \"organizationId\" = ?").params(params).update();
            audit.record(AuditInput.of("CrmSettings", org(), "update", before, in));
        }
        return settings();
    }

    // ------------------------------------------------------------------ saved filters

    @Transactional(readOnly = true)
    public List<Map<String, Object>> savedFilters(String scope) {
        return db.rows("SELECT * FROM \"CrmSavedFilter\" WHERE \"organizationId\" = ? AND scope = ? AND (\"userId\" = ?"
                + " OR shared) ORDER BY name", org(), scope == null ? "pipeline" : scope, user());
    }

    @Transactional
    public Map<String, Object> saveFilter(Map<String, Object> in) {
        String scope = str(in.get("scope"));
        if (scope == null || !FILTER_SCOPES.contains(scope)) {
            throw ApiException.badRequest("scope must be one of: pipeline, leads, quotations, activities, report");
        }
        Object filters = in.get("filters") == null ? Map.of() : in.get("filters");
        if (Boolean.TRUE.equals(in.get("isDefault"))) {
            db.update("UPDATE \"CrmSavedFilter\" SET \"isDefault\" = false WHERE \"organizationId\" = ? AND \"userId\" = ?"
                    + " AND scope = ?", org(), user(), scope);
        }
        Map<String, Object> row = db.insert("CrmSavedFilter", Obj.of("userId", user(), "scope", scope, "name",
                requireName(in), "shared", Boolean.TRUE.equals(in.get("shared")), "isDefault",
                Boolean.TRUE.equals(in.get("isDefault"))));
        db.update("UPDATE \"CrmSavedFilter\" SET filters = ?::jsonb WHERE id = ?", EntityJson.toJson(filters),
                row.get("id"));
        return db.row("SELECT * FROM \"CrmSavedFilter\" WHERE id = ?", row.get("id"));
    }

    @Transactional
    public Map<String, Object> deleteFilter(String id) {
        int n = db.update("DELETE FROM \"CrmSavedFilter\" WHERE id = ? AND \"organizationId\" = ? AND \"userId\" = ?",
                id, org(), user());
        if (n == 0) {
            throw ApiException.notFound("Saved filter not found");
        }
        return Obj.of("ok", true);
    }

    // ------------------------------------------------------------------ helpers

    String requireUser(String id) {
        if (db.count("SELECT count(*) FROM \"User\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                List.of(id, org())) == 0) {
            throw ApiException.notFound("User not found");
        }
        return id;
    }

    private static String requireName(Map<String, Object> in) {
        String name = str(in.get("name"));
        if (blank(name)) {
            throw ApiException.badRequest("name should not be empty");
        }
        if (name.length() > 120) {
            throw ApiException.badRequest("name must be shorter than or equal to 120 characters");
        }
        return name.trim();
    }

    private static String color(Object v) {
        String c = str(v);
        if (c == null || !COLORS.contains(c)) {
            throw ApiException.badRequest("color must be one of: " + String.join(", ", COLORS.stream().sorted().toList()));
        }
        return c;
    }
}
