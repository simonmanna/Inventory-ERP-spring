package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Home dashboard KPIs and recent activity (port of {@code reports-dashboard.controller.ts}, which queries Prisma
 * inline). Figures are JavaScript numbers on the wire, computed in double arithmetic exactly as Nest does.
 */
@Service
public class ReportsDashboardService {

    private static final String OPEN_SALES_INVOICE = " FROM \"Document\" WHERE \"organizationId\" = :org"
            + " AND \"documentType\"::text = 'sales_invoice' AND status::text = 'posted' AND \"amountResidual\" > 0";

    private final JdbcClient jdbc;

    public ReportsDashboardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Number js(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15 ? (Number) (long) v : (Number) v;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> kpi() {
        String org = TenantContext.organizationId();
        Instant now = Instant.now();
        // `new Date(y, m, 1)` — first of the month in the server's local zone.
        Instant startOfMonth = LocalDate.now(ZoneId.systemDefault()).withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        Timestamp nowTs = FiscalPeriodControl.ts(now);

        long openInvoices = jdbc.sql("SELECT count(*)" + OPEN_SALES_INVOICE).param("org", org).query(Long.class).single();
        long overdueInvoices = jdbc.sql("SELECT count(*)" + OPEN_SALES_INVOICE + " AND \"dueDate\" < :now")
                .param("org", org).param("now", nowTs).query(Long.class).single();
        // Cash position = posted lines on cash-equivalent accounts, as flagged by their category.
        String cash = jdbc.sql("SELECT COALESCE(SUM(\"baseDebit\" - \"baseCredit\"), 0)::text AS total FROM \"JournalLine\" jl"
                + " JOIN \"Account\" a ON a.id = jl.\"accountId\" JOIN \"AccountCategory\" ac ON ac.id = a.\"categoryId\""
                + " JOIN \"JournalEntry\" je ON je.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = ?"
                + " AND je.status IN ('posted', 'reversed') AND ac.\"isCashEquivalent\" = true").param(org).query(String.class).single();
        double revenue = Double.parseDouble(sectionSince(org, List.of("revenue", "other_income"), startOfMonth));
        double cogs = Double.parseDouble(sectionSince(org, List.of("cogs"), startOfMonth));
        double exp = Double.parseDouble(sectionSince(org, List.of("operating_expense", "other_expense"), startOfMonth));
        Instant d30 = now.minus(Duration.ofDays(30));
        Instant d60 = now.minus(Duration.ofDays(60));
        Instant d90 = now.minus(Duration.ofDays(90));
        return body("openInvoices", openInvoices, "overdueInvoices", overdueInvoices,
                "cashPosition", js(Double.parseDouble(cash)), "revenueMonth", js(revenue),
                "netIncomeMonth", js(revenue - cogs - exp),
                "arAging", body("current", arBucket(org, " AND (\"dueDate\" IS NULL OR \"dueDate\" >= :a)", now, null),
                        "b30", arBucket(org, " AND \"dueDate\" >= :a AND \"dueDate\" < :b", d30, now),
                        "b60", arBucket(org, " AND \"dueDate\" >= :a AND \"dueDate\" < :b", d60, d30),
                        "b90", arBucket(org, " AND \"dueDate\" >= :a AND \"dueDate\" < :b", d90, d60),
                        "over90", arBucket(org, " AND \"dueDate\" < :a", d90, null)));
    }

    /** Posted movement on every account in the given report sections, signed by each account's normal balance. */
    private String sectionSince(String org, List<String> sections, Instant since) {
        return jdbc.sql("SELECT COALESCE(SUM(CASE WHEN ac.\"normalBalance\" = 'credit' THEN jl.\"baseCredit\" - jl.\"baseDebit\""
                        + " ELSE jl.\"baseDebit\" - jl.\"baseCredit\" END), 0)::text AS total FROM \"JournalLine\" jl"
                        + " JOIN \"Account\" a ON a.id = jl.\"accountId\" JOIN \"AccountCategory\" ac ON ac.id = a.\"categoryId\""
                        + " JOIN \"JournalEntry\" je ON je.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = :org"
                        + " AND je.status IN ('posted', 'reversed') AND je.\"postingDate\" >= :since"
                        + " AND ac.\"reportSection\"::text IN (:sections)")
                .param("org", org).param("since", FiscalPeriodControl.ts(since)).param("sections", sections)
                .query(String.class).single();
    }

    private Number arBucket(String org, String where, Instant a, Instant b) {
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT COALESCE(SUM(\"amountResidual\"), 0)::text" + OPEN_SALES_INVOICE + where)
                .param("org", org).param("a", FiscalPeriodControl.ts(a));
        if (b != null) {
            spec = spec.param("b", FiscalPeriodControl.ts(b));
        }
        return js(Double.parseDouble(spec.query(String.class).single()));
    }

    /** Recent invoices, payments and journal entries, newest first. */
    @Transactional(readOnly = true)
    public Map<String, Object> activity(int limit) {
        String org = TenantContext.organizationId();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> d : SqlRows.list(jdbc.sql("SELECT d.id, d.\"documentType\"::text AS type, d.\"documentNumber\","
                + " d.\"totalAmount\", d.\"createdAt\", p.name AS partner FROM \"Document\" d LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\""
                + " WHERE d.\"organizationId\" = :org AND d.\"documentType\"::text IN ('sales_invoice', 'vendor_bill', 'credit_note')"
                + " ORDER BY d.\"createdAt\" DESC LIMIT :lim").param("org", org).param("lim", limit))) {
            String type = (String) d.get("type");
            items.add(body("id", d.get("id"), "type", type,
                    "description", d.get("documentNumber") + " · " + (d.get("partner") != null ? d.get("partner") : ""),
                    "amount", js(SqlRows.dec(d, "totalAmount").doubleValue()), "at", JsonConfig.toJsIso((Instant) d.get("createdAt")),
                    "href", "sales_invoice".equals(type) ? "/invoices/" + d.get("id")
                            : "credit_note".equals(type) ? "/credit-notes/" + d.get("id") : "/expenses/" + d.get("id")));
        }
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT pm.id, pm.\"paymentNumber\", pm.amount, pm.\"createdAt\","
                + " pt.name AS partner FROM \"Payment\" pm LEFT JOIN \"Partner\" pt ON pt.id = pm.\"partnerId\""
                + " WHERE pm.\"organizationId\" = :org ORDER BY pm.\"createdAt\" DESC LIMIT :lim").param("org", org).param("lim", limit))) {
            items.add(body("id", p.get("id"), "type", "payment",
                    "description", p.get("paymentNumber") + " · " + (p.get("partner") != null ? p.get("partner") : ""),
                    "amount", js(SqlRows.dec(p, "amount").doubleValue()), "at", JsonConfig.toJsIso((Instant) p.get("createdAt")),
                    "href", "/payments/" + p.get("id")));
        }
        for (Map<String, Object> j : SqlRows.list(jdbc.sql("SELECT id, \"entryNumber\", description, \"createdAt\" FROM \"JournalEntry\""
                + " WHERE \"organizationId\" = :org ORDER BY \"createdAt\" DESC LIMIT :lim").param("org", org).param("lim", limit))) {
            String description = (String) j.get("description");
            items.add(body("id", j.get("id"), "type", "journal_entry",
                    "description", j.get("entryNumber") + (description != null && !description.isEmpty() ? " · " + description : ""),
                    "at", JsonConfig.toJsIso((Instant) j.get("createdAt")), "href", "/journal-entries/" + j.get("id")));
        }
        items.sort(Comparator.comparing((Map<String, Object> i) -> (String) i.get("at")).reversed());
        return body("data", items.subList(0, Math.min(limit, items.size())));
    }
}
