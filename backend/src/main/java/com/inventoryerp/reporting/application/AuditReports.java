package com.inventoryerp.reporting.application;

import static com.inventoryerp.reporting.application.Tables.date;
import static com.inventoryerp.reporting.application.Tables.dec;
import static com.inventoryerp.reporting.application.Tables.money;
import static com.inventoryerp.reporting.application.Tables.number;
import static com.inventoryerp.reporting.application.Tables.text;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.reporting.spi.FilterKey;
import com.inventoryerp.reporting.spi.OptionSpec;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportBody.Drill;
import com.inventoryerp.reporting.spi.ReportCategory;
import com.inventoryerp.reporting.spi.ReportContext;
import com.inventoryerp.reporting.spi.ReportDefinition;
import com.inventoryerp.reporting.spi.ReportProvider;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Audit, compliance and internal control (FR-7): audit trail, pending approvals and approval history, unposted and
 * backdated entries, reversals, manual vs automatic journals, entries in closed or locked periods (post-closing
 * adjustments), segregation-of-duties conflicts, duplicate supplier bills and payments, rule-based anomaly flags and
 * the period close status.
 */
@Component
public class AuditReports implements ReportProvider {

    static final String AUDIT = Permissions.AUDIT_LOG__READ;
    static final String ACC = Permissions.REPORT__ACCOUNTING;
    static final List<FilterKey> PERIOD = List.of(FilterKey.PERIOD);
    static final String MANUAL = "(e.\"sourceType\" IS NULL OR e.\"sourceType\" IN ('manual', 'journal_entry',"
            + " 'test', 'adjustment', 'opening_balance'))";

    /** Permission pairs one person should not hold together. */
    static final String[][] SOD = {
        {"journal_entry:create", "journal_entry:post", "Prepare and post journals"},
        {"payment:create", "payment:approve", "Create and approve payments"},
        {"expense:create", "expense:approve", "Raise and approve expenses"},
        {"partner:update", "payment:create", "Change supplier details and pay suppliers"},
        {"purchase_order:create", "purchase_order:approve", "Raise and approve purchase orders"},
        {"bank_account:update", "payment:create", "Change bank details and make payments"},
        {"role:update", "journal_entry:post", "Grant access and post journals"},
        {"inventory:adjust", "inventory:approve", "Adjust and approve stock"}};

    private final JdbcClient jdbc;

    public AuditReports(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReportDefinition> definitions() {
        ReportCategory a = ReportCategory.AUDIT;
        return List.of(
                ReportDefinition.of("audit.trail", a, "Audit Trail Report", "Who changed what and when, with the"
                        + " fields changed.", "OPERATIONAL", AUDIT, PERIOD, OptionSpec.text("entity", "Entity"
                                + " (e.g. JournalEntry)", null), OptionSpec.select("action", "Action", "", "",
                                "create", "update", "delete", "post", "approve", "reject", "cancel", "login")),
                ReportDefinition.of("audit.pending-approvals", a, "Pending Approvals", "Requests waiting for a"
                        + " decision, oldest first.", "OPERATIONAL", AUDIT, List.of(FilterKey.AS_OF)),
                ReportDefinition.of("audit.approval-history", a, "Financial Approval History", "Every approval"
                        + " decision with approver, step and comment.", "OPERATIONAL", AUDIT, PERIOD),
                ReportDefinition.of("audit.unposted", a, "Unposted Transactions", "Draft journal entries and"
                        + " documents not yet posted.", "OPERATIONAL", ACC, PERIOD),
                ReportDefinition.of("audit.backdated", a, "Backdated Transactions", "Entries posted to a date earlier"
                        + " than the day they were entered.", "OPERATIONAL", ACC, PERIOD, OptionSpec.number(
                                "minDays", "Backdated by at least (days)", "3")),
                ReportDefinition.of("audit.reversals", a, "Reversed Journal Report", "Reversed entries and their"
                        + " reversals.", "FINANCIAL", ACC, PERIOD),
                ReportDefinition.of("audit.manual-journals", a, "Manual Journal Analysis", "Manual against"
                        + " automatic entries by journal, with the manual entries listed.", "FINANCIAL", ACC, PERIOD,
                        OptionSpec.number("minAmount", "List manual entries from amount", "0")),
                ReportDefinition.of("audit.closed-period-postings", a, "Locked and Closed Period Exceptions",
                        "Entries dated in a closed or locked period that were entered or posted after it closed.",
                        "FINANCIAL", ACC, PERIOD),
                ReportDefinition.of("adv.post-closing", ReportCategory.ADVANCED, "Post-Closing Adjustments",
                        "Adjustments posted into periods after they were closed.", "FINANCIAL", ACC, PERIOD),
                ReportDefinition.of("audit.segregation", a, "Segregation of Duties", "Users holding conflicting"
                        + " permissions through their roles.", "OPERATIONAL", Permissions.ROLE__READ, List.of()),
                ReportDefinition.of("audit.duplicate-bills", a, "Duplicate Invoice Report", "Supplier bills with the"
                        + " same reference, or the same amount within a few days.", "OPERATIONAL", ACC, PERIOD,
                        OptionSpec.number("days", "Same amount within (days)", "3")),
                ReportDefinition.of("audit.duplicate-payments", a, "Duplicate Payment Report", "Payments to the same"
                        + " partner for the same amount within a few days.", "OPERATIONAL", ACC, PERIOD,
                        OptionSpec.number("days", "Within (days)", "3")),
                ReportDefinition.of("audit.anomalies", a, "Suspicious Transaction Report", "Journal entries flagged"
                        + " by rules: unusually large, round amounts, weekend or night posting, manual cash or"
                        + " revenue entries.", "MANAGEMENT", ACC, PERIOD, OptionSpec.number("roundTo",
                                "Round amount multiple", "1000000")),
                ReportDefinition.of("audit.period-status", a, "Period Closing Status", "Fiscal periods with status,"
                        + " who closed them and draft entries still open in each.", "OPERATIONAL", ACC, PERIOD));
    }

    @Override
    public ReportBody run(ReportContext ctx) {
        return switch (ctx.definition().id()) {
            case "audit.trail" -> trail(ctx);
            case "audit.pending-approvals" -> pending(ctx);
            case "audit.approval-history" -> approvals(ctx);
            case "audit.unposted" -> unposted(ctx);
            case "audit.backdated" -> backdated(ctx);
            case "audit.reversals" -> reversals(ctx);
            case "audit.manual-journals" -> manual(ctx);
            case "audit.closed-period-postings", "adv.post-closing" -> closedPeriods(ctx);
            case "audit.segregation" -> segregation(ctx);
            case "audit.duplicate-bills" -> duplicateBills(ctx);
            case "audit.duplicate-payments" -> duplicatePayments(ctx);
            case "audit.anomalies" -> anomalies(ctx);
            case "audit.period-status" -> periodStatus(ctx);
            default -> throw ApiException.notFound("Report " + ctx.definition().id() + " not found");
        };
    }

    private Timestamp from(ReportContext ctx) {
        return SqlRows.ts(ctx.start(ctx.current().from()));
    }

    private Timestamp to(ReportContext ctx) {
        return SqlRows.ts(ctx.endExclusive(ctx.current().to()));
    }

    private static Map<String, Drill> entry(Object id) {
        return Map.of("_row", Drill.route("/journal-entries/" + id));
    }

    ReportBody trail(ReportContext ctx) {
        List<Object> p = new ArrayList<>(List.of(ctx.organizationId(), from(ctx), to(ctx)));
        String f = "";
        String entity = ctx.request().option("entity");
        if (entity != null && !entity.isBlank()) {
            f += " AND a.entity = ?";
            p.add(entity.trim());
        }
        String action = ctx.request().option("action");
        if (action != null && !action.isBlank()) {
            f += " AND a.action::text = ?";
            p.add(action);
        }
        p.add(GlReports.MAX_LINES);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.\"createdAt\", a.entity, a.\"entityId\","
                + " a.action::text AS action, coalesce(nullif(concat_ws(' ', u.\"firstName\", u.\"lastName\"), ''),"
                + " a.\"actorId\") AS actor, a.\"ipAddress\", a.\"oldValues\"::text AS old, a.\"newValues\"::text AS new"
                + " FROM \"AuditLog\" a LEFT JOIN \"User\" u ON u.id = a.\"actorId\" WHERE a.\"organizationId\" = ? AND"
                + " a.\"createdAt\" >= ? AND a.\"createdAt\" < ?" + f + " ORDER BY a.\"createdAt\" DESC LIMIT ?")
                .params(p));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("at", "When"), text("actor", "User"), text("action",
                "Action"), text("entity", "Entity"), text("entityId", "Record"), text("fields", "Fields changed"),
                text("ipAddress", "IP"));
        for (Map<String, Object> r : rows) {
            java.time.Instant at = Tables.instant(r.get("createdAt"));
            r.put("at", at == null ? null : at.atZone(ctx.zone()).toLocalDateTime().withNano(0).toString().replace('T',
                    ' '));
            r.put("fields", changed((String) r.get("old"), (String) r.get("new")));
            t.line(r);
        }
        return t.build(false);
    }

    /** Top-level keys whose value differs between two JSON objects (cheap textual comparison). */
    static String changed(String oldJson, String newJson) {
        Map<String, String> o = flat(oldJson);
        Map<String, String> n = flat(newJson);
        Set<String> keys = new LinkedHashSet<>(n.keySet());
        keys.addAll(o.keySet());
        keys.removeIf(k -> String.valueOf(o.get(k)).equals(String.valueOf(n.get(k))) || "updatedAt".equals(k));
        String s = String.join(", ", keys);
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static Map<String, String> flat(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank() || "null".equals(json)) {
            return out;
        }
        try {
            tools.jackson.databind.JsonNode node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                    json);
            if (node.isObject()) {
                for (Map.Entry<String, tools.jackson.databind.JsonNode> e : node.properties()) {
                    out.put(e.getKey(), e.getValue().toString());
                }
            }
        } catch (RuntimeException e) {
            out.put("(raw)", json);
        }
        return out;
    }

    ReportBody pending(ReportContext ctx) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT r.id, r.\"entityType\", r.\"entityId\","
                + " r.\"createdAt\", r.\"currentStep\", r.\"requiredCount\", coalesce(nullif(concat_ws(' ', u.\"firstName\","
                + " u.\"lastName\"), ''), r.\"createdById\") AS requester FROM \"ApprovalRequest\" r LEFT JOIN \"User\" u"
                + " ON u.id = r.\"createdById\" WHERE r.\"organizationId\" = ? AND r.status::text = 'pending' AND"
                + " r.\"createdAt\" < ? ORDER BY r.\"createdAt\"").params(ctx.organizationId(), SqlRows.ts(
                ctx.endExclusive(ctx.asOf()))));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("createdAt", "Requested"), text("entityType",
                "Type"), text("entityId", "Record"), text("requester", "Requested by"), number("currentStep", "Step"),
                number("age", "Waiting (days)"));
        for (Map<String, Object> r : rows) {
            r.put("age", java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(Tables.day(r.get(
                    "createdAt"), ctx.zone())), ctx.asOf()));
            t.line(r, Map.of("_row", Drill.route("/approvals")));
        }
        return t.build(false);
    }

    ReportBody approvals(ReportContext ctx) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT d.id, d.\"decidedAt\", r.\"entityType\","
                + " r.\"entityId\", d.\"stepOrder\", d.status::text AS decision, d.comment, coalesce(nullif(concat_ws(' ',"
                + " u.\"firstName\", u.\"lastName\"), ''), d.\"approverId\") AS approver, coalesce(nullif(concat_ws(' ',"
                + " q.\"firstName\", q.\"lastName\"), ''), r.\"createdById\") AS requester FROM \"ApprovalDecision\" d JOIN"
                + " \"ApprovalRequest\" r ON r.id = d.\"requestId\" LEFT JOIN \"User\" u ON u.id = d.\"approverId\" LEFT"
                + " JOIN \"User\" q ON q.id = r.\"createdById\" WHERE d.\"organizationId\" = ? AND d.\"decidedAt\" >= ? AND"
                + " d.\"decidedAt\" < ? ORDER BY d.\"decidedAt\"").params(ctx.organizationId(), from(ctx), to(ctx)));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("decidedAt", "Decided"), text("entityType", "Type"),
                text("entityId", "Record"), text("requester", "Requested by"), text("approver", "Approver"),
                number("stepOrder", "Step"), text("decision", "Decision"), text("comment", "Comment"));
        int selfApproved = 0;
        for (Map<String, Object> r : rows) {
            if (r.get("approver") != null && r.get("approver").equals(r.get("requester"))) {
                selfApproved++;
            }
            t.line(r);
        }
        t.check("NO_SELF_APPROVAL", "No request was approved by its own requester", selfApproved == 0, selfApproved
                + " self-approval(s)");
        return t.build(false);
    }

    ReportBody unposted(ReportContext ctx) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT e.id, 'Journal entry' AS kind, e.\"entryNumber\""
                + " AS number, e.\"postingDate\" AS date, e.description, (SELECT sum(l.\"baseDebit\") FROM \"JournalLine\" l"
                + " WHERE l.\"journalEntryId\" = e.id) AS amount, e.\"createdAt\", coalesce(nullif(concat_ws(' ',"
                + " u.\"firstName\", u.\"lastName\"), ''), e.\"createdBy\") AS \"createdBy\" FROM \"JournalEntry\" e LEFT"
                + " JOIN \"User\" u ON u.id = e.\"createdBy\" WHERE e.\"organizationId\" = ? AND e.status::text = 'draft'"
                + " AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ? UNION ALL SELECT d.id, d.\"documentType\"::text,"
                + " d.\"documentNumber\", d.\"issueDate\", d.reference, d.\"totalAmount\", d.\"createdAt\","
                + " coalesce(nullif(concat_ws(' ', u.\"firstName\", u.\"lastName\"), ''), d.\"createdBy\") FROM \"Document\""
                + " d LEFT JOIN \"User\" u ON u.id = d.\"createdBy\" WHERE d.\"organizationId\" = ? AND d.status::text IN"
                + " ('draft', 'submitted', 'approved') AND d.\"documentType\"::text <> 'proforma_invoice' AND"
                + " d.\"issueDate\" >= ? AND d.\"issueDate\" < ? ORDER BY 4").params(ctx.organizationId(), from(ctx),
                to(ctx), ctx.organizationId(), from(ctx), to(ctx)));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("kind", "Type"), text("number", "Number"), date("date",
                "Date"), text("description", "Description"), money("amount", "Amount"), date("createdAt", "Created"),
                text("createdBy", "Created by"));
        rows.forEach(r -> t.line(r, "Journal entry".equals(r.get("kind")) ? entry(r.get("id")) : OpenItems.route(
                (String) r.get("kind"), (String) r.get("id")) == null ? Map.of() : Map.of("_row", Drill.route(
                OpenItems.route((String) r.get("kind"), (String) r.get("id"))))));
        return t.build();
    }

    ReportBody backdated(ReportContext ctx) {
        int min = Math.max(1, ctx.request().intOption("minDays"));
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM (SELECT e.id, e.\"entryNumber\","
                + " e.\"postingDate\", e.\"createdAt\", e.\"postedAt\", e.description, e.\"sourceType\","
                + " (e.\"createdAt\"::date - e.\"postingDate\"::date) AS lag, (SELECT sum(l.\"baseDebit\") FROM"
                + " \"JournalLine\" l WHERE l.\"journalEntryId\" = e.id) AS amount, coalesce(nullif(concat_ws(' ',"
                + " u.\"firstName\", u.\"lastName\"), ''), e.\"createdBy\") AS \"createdBy\" FROM \"JournalEntry\" e LEFT"
                + " JOIN \"User\" u ON u.id = e.\"createdBy\" WHERE e.\"organizationId\" = ? AND e.status::text IN"
                + " ('posted', 'reversed') AND e.\"createdAt\" >= ? AND e.\"createdAt\" < ?) x WHERE lag >= ? ORDER BY"
                + " lag DESC").params(ctx.organizationId(), from(ctx), to(ctx), min));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("entryNumber", "Entry"), date("postingDate",
                "Posting date"), date("createdAt", "Entered"), number("lag", "Days backdated"), text("description",
                "Description"), text("sourceType", "Source"), money("amount", "Amount"), text("createdBy",
                "Entered by"));
        rows.forEach(r -> t.line(r, entry(r.get("id"))));
        return t.build();
    }

    ReportBody reversals(ReportContext ctx) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT e.id, e.\"entryNumber\", e.\"postingDate\","
                + " e.description, e.status::text AS status, o.\"entryNumber\" AS \"reversalOf\", r.\"entryNumber\" AS"
                + " \"reversedBy\", (SELECT sum(l.\"baseDebit\") FROM \"JournalLine\" l WHERE l.\"journalEntryId\" = e.id)"
                + " AS amount, coalesce(nullif(concat_ws(' ', u.\"firstName\", u.\"lastName\"), ''), e.\"createdBy\") AS"
                + " \"createdBy\" FROM \"JournalEntry\" e LEFT JOIN \"JournalEntry\" o ON o.id = e.\"reversalOfId\" LEFT"
                + " JOIN \"JournalEntry\" r ON r.id = e.\"reversedEntryId\" LEFT JOIN \"User\" u ON u.id = e.\"createdBy\""
                + " WHERE e.\"organizationId\" = ? AND (e.\"reversalOfId\" IS NOT NULL OR e.\"reversedEntryId\" IS NOT NULL"
                + " OR e.status::text = 'reversed') AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ? ORDER BY"
                + " e.\"postingDate\"").params(ctx.organizationId(), from(ctx), to(ctx)));
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("postingDate", "Date"), text("entryNumber",
                "Entry"), text("status", "Status"), text("reversalOf", "Reverses"), text("reversedBy", "Reversed by"),
                text("description", "Description"), money("amount", "Amount"), text("createdBy", "By"));
        rows.forEach(r -> t.line(r, entry(r.get("id"))));
        return t.build();
    }

    ReportBody manual(ReportContext ctx) {
        List<Map<String, Object>> summary = SqlRows.list(jdbc.sql("SELECT coalesce(j.name, 'No journal') AS journal,"
                + " CASE WHEN " + MANUAL + " THEN 'manual' ELSE 'automatic' END AS origin, count(DISTINCT e.id) AS"
                + " entries, sum(l.\"baseDebit\") AS amount FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON"
                + " l.\"journalEntryId\" = e.id LEFT JOIN \"Journal\" j ON j.id = e.\"journalId\" WHERE"
                + " e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND e.\"postingDate\" >= ? AND"
                + " e.\"postingDate\" < ? GROUP BY 1, 2 ORDER BY 1, 2").params(ctx.organizationId(), from(ctx),
                to(ctx)));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("journal", "Journal / entry"), text("origin",
                "Origin"), number("entries", "Entries"), money("amount", "Amount"), date("postingDate", "Date"),
                text("createdBy", "Prepared by"));
        BigDecimal manualAmt = BigDecimal.ZERO;
        BigDecimal all = BigDecimal.ZERO;
        for (Map<String, Object> r : summary) {
            r.put("_id", r.get("journal") + ":" + r.get("origin"));
            t.add("subtotal", 0, false, r, Map.of(), true);
            all = all.add(dec(r.get("amount")));
            if ("manual".equals(r.get("origin"))) {
                manualAmt = manualAmt.add(dec(r.get("amount")));
            }
        }
        BigDecimal min = ctx.request().decimalOption("minAmount");
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM (SELECT e.id, e.\"entryNumber\" AS journal,"
                + " 'manual entry' AS origin, e.\"postingDate\", (SELECT sum(l.\"baseDebit\") FROM \"JournalLine\" l WHERE"
                + " l.\"journalEntryId\" = e.id) AS amount, coalesce(nullif(concat_ws(' ', u.\"firstName\","
                + " u.\"lastName\"), ''), e.\"createdBy\") AS \"createdBy\" FROM \"JournalEntry\" e LEFT JOIN \"User\" u ON"
                + " u.id = e.\"createdBy\" WHERE e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed') AND"
                + " e.\"postingDate\" >= ? AND e.\"postingDate\" < ? AND " + MANUAL + ") x WHERE amount >= ? ORDER BY"
                + " amount DESC LIMIT 500").params(ctx.organizationId(), from(ctx), to(ctx), min))) {
            t.add("line", 1, false, r, entry(r.get("id")), false);
        }
        t.meta("manualShare", Tables.pctOf(manualAmt, all));
        return t.build(false);
    }

    ReportBody closedPeriods(ReportContext ctx) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT e.id, e.\"entryNumber\", e.\"postingDate\","
                + " e.\"createdAt\", e.\"postedAt\", e.description, fp.name AS period, fp.status::text AS \"periodStatus\","
                + " fp.\"closedAt\", (SELECT sum(l.\"baseDebit\") FROM \"JournalLine\" l WHERE l.\"journalEntryId\" = e.id)"
                + " AS amount, coalesce(nullif(concat_ws(' ', u.\"firstName\", u.\"lastName\"), ''), e.\"createdBy\") AS"
                + " \"createdBy\" FROM \"JournalEntry\" e JOIN \"FiscalPeriod\" fp ON fp.\"organizationId\" ="
                + " e.\"organizationId\" AND fp.\"deletedAt\" IS NULL AND e.\"postingDate\" >= fp.\"startDate\" AND"
                + " e.\"postingDate\" < fp.\"endDate\" + interval '1 day' AND fp.status::text IN ('closed', 'locked') AND"
                + " fp.\"closedAt\" IS NOT NULL AND coalesce(e.\"postedAt\", e.\"createdAt\") > fp.\"closedAt\" LEFT JOIN"
                + " \"User\" u ON u.id = e.\"createdBy\" WHERE e.\"organizationId\" = ? AND e.status::text IN ('posted',"
                + " 'reversed') AND coalesce(e.\"postedAt\", e.\"createdAt\") >= ? AND coalesce(e.\"postedAt\","
                + " e.\"createdAt\") < ? ORDER BY e.\"postingDate\"").params(ctx.organizationId(), from(ctx), to(ctx)));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("period", "Period"), text("periodStatus", "Status"),
                date("closedAt", "Closed on"), text("entryNumber", "Entry"), date("postingDate", "Dated"),
                date("postedAt", "Posted on"), text("description", "Description"), money("amount", "Amount"),
                text("createdBy", "By"));
        rows.forEach(r -> t.line(r, entry(r.get("id"))));
        t.check("NONE", "No entry changed a closed period", rows.isEmpty(), rows.size() + " entry(ies) dated in a"
                + " closed period after closing");
        return t.build();
    }

    ReportBody segregation(ReportContext ctx) {
        List<Map<String, Object>> users = SqlRows.list(jdbc.sql("SELECT u.id, nullif(concat_ws(' ', u.\"firstName\","
                + " u.\"lastName\"), '') AS name, u.email, array_agg(DISTINCT r.name) AS roles, array_agg(DISTINCT p) AS"
                + " perms FROM \"User\" u JOIN \"_UserRoles\" ur ON ur.\"B\" = u.id JOIN \"Role\" r ON r.id = ur.\"A\""
                + " AND r.\"deletedAt\" IS NULL CROSS JOIN LATERAL unnest(r.permissions) p WHERE u.\"organizationId\" = ?"
                + " AND u.\"isActive\" AND u.\"deletedAt\" IS NULL GROUP BY u.id").param(ctx.organizationId()));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("user", "User"), text("email", "E-mail"),
                text("roles", "Roles"), text("conflict", "Conflict"), text("permissions", "Permissions"));
        for (Map<String, Object> u : users) {
            Set<String> perms = new LinkedHashSet<>(array(u.get("perms")));
            for (String[] c : SOD) {
                if (perms.contains(c[0]) && perms.contains(c[1])) {
                    t.line(Obj.of("_id", u.get("id") + ":" + c[0], "user", u.get("name"), "email", u.get("email"),
                            "roles", String.join(", ", array(u.get("roles"))), "conflict", c[2], "permissions", c[0]
                                    + " + " + c[1]));
                }
            }
        }
        t.meta("note", "Administrators hold every permission and always appear; compensate with approvals and review.");
        return t.build(false);
    }

    private static List<String> array(Object v) {
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        if (v instanceof String[] a) {
            return List.of(a);
        }
        if (v instanceof java.sql.Array a) {
            try {
                return List.of((String[]) a.getArray());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return List.of();
    }

    ReportBody duplicateBills(ReportContext ctx) {
        int days = Math.max(0, ctx.request().intOption("days"));
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.\"documentNumber\", a.\"issueDate\","
                + " a.reference, a.\"totalAmount\", pt.name AS supplier, b.\"documentNumber\" AS \"duplicateOf\","
                + " b.\"issueDate\" AS \"otherDate\", CASE WHEN a.reference IS NOT NULL AND a.reference = b.reference"
                + " THEN 'same supplier reference' ELSE 'same amount, close dates' END AS reason FROM \"Document\" a JOIN"
                + " \"Document\" b ON b.\"organizationId\" = a.\"organizationId\" AND b.\"documentType\" = a.\"documentType\""
                + " AND b.\"partnerId\" = a.\"partnerId\" AND b.id < a.id AND b.status::text NOT IN ('cancelled', 'draft')"
                + " AND ((a.reference IS NOT NULL AND a.reference <> '' AND a.reference = b.reference) OR"
                + " (a.\"totalAmount\" = b.\"totalAmount\" AND abs(extract(epoch FROM a.\"issueDate\" - b.\"issueDate\")) <="
                + " ? * 86400)) LEFT JOIN \"Partner\" pt ON pt.id = a.\"partnerId\" WHERE a.\"organizationId\" = ? AND"
                + " a.\"documentType\"::text = 'vendor_bill' AND a.status::text NOT IN ('cancelled', 'draft') AND"
                + " a.\"issueDate\" >= ? AND a.\"issueDate\" < ? ORDER BY a.\"issueDate\"").params(days,
                ctx.organizationId(), from(ctx), to(ctx)));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("supplier", "Supplier"), text("documentNumber",
                "Bill"), date("issueDate", "Date"), text("reference", "Supplier ref"), money("totalAmount",
                "Amount"), text("duplicateOf", "Possible duplicate of"), date("otherDate", "Its date"), text("reason",
                "Reason"));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route("/vendor-bills/" + r.get("id")))));
        t.check("NONE", "No possible duplicate bills", rows.isEmpty(), rows.size() + " possible duplicate(s)");
        return t.build(false);
    }

    ReportBody duplicatePayments(ReportContext ctx) {
        int days = Math.max(0, ctx.request().intOption("days"));
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.\"paymentNumber\", a.\"paymentDate\","
                + " a.direction::text AS direction, a.amount, pt.name AS partner, b.\"paymentNumber\" AS \"duplicateOf\","
                + " b.\"paymentDate\" AS \"otherDate\" FROM \"Payment\" a JOIN \"Payment\" b ON b.\"organizationId\" ="
                + " a.\"organizationId\" AND b.direction = a.direction AND b.\"partnerId\" = a.\"partnerId\" AND b.amount ="
                + " a.amount AND b.id < a.id AND b.status::text <> 'cancelled' AND b.\"voidedAt\" IS NULL AND"
                + " abs(extract(epoch FROM a.\"paymentDate\" - b.\"paymentDate\")) <= ? * 86400 LEFT JOIN \"Partner\" pt ON"
                + " pt.id = a.\"partnerId\" WHERE a.\"organizationId\" = ? AND a.\"partnerId\" IS NOT NULL AND"
                + " a.status::text <> 'cancelled' AND a.\"voidedAt\" IS NULL AND a.\"refundOfId\" IS NULL AND"
                + " a.\"paymentDate\" >= ? AND a.\"paymentDate\" < ? ORDER BY a.\"paymentDate\"").params(days,
                ctx.organizationId(), from(ctx), to(ctx)));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("partner", "Partner"), text("direction",
                "Direction"), text("paymentNumber", "Payment"), date("paymentDate", "Date"), money("amount",
                "Amount"), text("duplicateOf", "Possible duplicate of"), date("otherDate", "Its date"));
        rows.forEach(r -> t.line(r, Map.of("_row", Drill.route("/payments/" + r.get("id")))));
        t.check("NONE", "No possible duplicate payments", rows.isEmpty(), rows.size() + " possible duplicate(s)");
        return t.build(false);
    }

    ReportBody anomalies(ReportContext ctx) {
        BigDecimal round = ctx.request().decimalOption("roundTo");
        List<Map<String, Object>> entries = SqlRows.list(jdbc.sql("SELECT e.id, e.\"entryNumber\", e.\"postingDate\","
                + " e.\"createdAt\", e.description, e.\"sourceType\", " + MANUAL + " AS manual, sum(l.\"baseDebit\") AS"
                + " amount, bool_or(c.key IN ('cash', 'bank', 'mobile_money', 'petty_cash', 'revenue')) AS sensitive,"
                + " coalesce(nullif(concat_ws(' ', u.\"firstName\", u.\"lastName\"), ''), e.\"createdBy\") AS \"createdBy\""
                + " FROM \"JournalEntry\" e JOIN \"JournalLine\" l ON l.\"journalEntryId\" = e.id JOIN \"Account\" a ON"
                + " a.id = l.\"accountId\" LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" LEFT JOIN \"User\" u"
                + " ON u.id = e.\"createdBy\" WHERE e.\"organizationId\" = ? AND e.status::text IN ('posted', 'reversed')"
                + " AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ? GROUP BY e.id, u.\"firstName\", u.\"lastName\"")
                .params(ctx.organizationId(), from(ctx), to(ctx)));
        double sum = 0;
        double sq = 0;
        for (Map<String, Object> e : entries) {
            double v = dec(e.get("amount")).doubleValue();
            sum += v;
            sq += v * v;
        }
        int n = entries.size();
        double mean = n == 0 ? 0 : sum / n;
        double sd = n < 2 ? 0 : Math.sqrt(Math.max(0, sq / n - mean * mean));
        BigDecimal limit = BigDecimal.valueOf(mean + 3 * sd).setScale(2, RoundingMode.HALF_UP);
        Tables.Builder t = new Tables.Builder(ctx.zone(), date("postingDate", "Date"), text("entryNumber",
                "Entry"), text("description", "Description"), money("amount", "Amount"), text("flags", "Flags"),
                text("createdBy", "By"));
        for (Map<String, Object> e : entries) {
            List<String> flags = new ArrayList<>();
            BigDecimal amt = dec(e.get("amount"));
            if (n >= 20 && sd > 0 && amt.compareTo(limit) > 0) {
                flags.add("unusually large (> mean + 3σ)");
            }
            if (round.signum() > 0 && amt.compareTo(round) >= 0 && amt.remainder(round).signum() == 0) {
                flags.add("round amount");
            }
            java.time.Instant created = Tables.instant(e.get("createdAt"));
            if (created != null) {
                LocalDateTime local = created.atZone(ctx.zone()).toLocalDateTime();
                if (local.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    flags.add("entered on a Sunday");
                }
                if (local.getHour() < 6 || local.getHour() >= 22) {
                    flags.add("entered at night");
                }
            }
            if (Boolean.TRUE.equals(e.get("manual")) && Boolean.TRUE.equals(e.get("sensitive"))) {
                flags.add("manual entry to cash or revenue");
            }
            if (!flags.isEmpty()) {
                e.put("flags", String.join("; ", flags));
                t.line(e, entry(e.get("id")));
            }
        }
        t.meta("largeThreshold", limit);
        return t.build();
    }

    ReportBody periodStatus(ReportContext ctx) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT fp.id, fp.name, fp.\"startDate\","
                + " fp.\"endDate\", fp.status::text AS status, fp.\"closedAt\", coalesce(nullif(concat_ws(' ',"
                + " u.\"firstName\", u.\"lastName\"), ''), fp.\"closedBy\") AS \"closedBy\", (SELECT count(*) FROM"
                + " \"JournalEntry\" e WHERE e.\"organizationId\" = fp.\"organizationId\" AND e.status::text = 'draft' AND"
                + " e.\"postingDate\" >= fp.\"startDate\" AND e.\"postingDate\" < fp.\"endDate\" + interval '1 day') AS"
                + " drafts FROM \"FiscalPeriod\" fp LEFT JOIN \"User\" u ON u.id = fp.\"closedBy\" WHERE"
                + " fp.\"organizationId\" = ? AND fp.\"deletedAt\" IS NULL AND fp.\"endDate\" >= ? AND fp.\"startDate\" < ?"
                + " ORDER BY fp.\"startDate\"").params(ctx.organizationId(), from(ctx), to(ctx)));
        Tables.Builder t = new Tables.Builder(ctx.zone(), text("name", "Period"), date("startDate", "From"),
                date("endDate", "To"), text("status", "Status"), date("closedAt", "Closed on"), text("closedBy",
                        "Closed by"), number("drafts", "Draft entries"));
        long open = 0;
        for (Iterator<Map<String, Object>> it = rows.iterator(); it.hasNext();) {
            Map<String, Object> r = it.next();
            if (!"open".equals(r.get("status")) && ((Number) r.get("drafts")).longValue() > 0) {
                open++;
            }
            t.line(r, Map.of("_row", Drill.route("/fiscal-periods")));
        }
        t.check("NO_DRAFTS_IN_CLOSED", "No draft entries in closed periods", open == 0, open + " closed period(s)"
                + " with drafts");
        return t.build(false);
    }
}
