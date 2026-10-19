package com.inventoryerp.accounting.application;

import static com.inventoryerp.accounting.application.MoneyActivityTaxonomy.toFixed2;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Money &amp; Accounts overview (port of {@code money-overview.service.ts}): where the money is (posted GL
 * balances by account type), registers, today's flow, needs-attention signals and recent activity.
 */
@Service
public class MoneyOverviewService {

    private static final Map<String, String[]> GROUPS = Map.of(
            "drawer", new String[] {"drawers", "Register drawers"},
            "bank", new String[] {"bank", "Bank"},
            "mobile_money", new String[] {"mobile_money", "Mobile money"},
            "petty_cash", new String[] {"petty_cash", "Petty cash"},
            "cash", new String[] {"cash", "Cash & safe"});

    private final JdbcClient jdbc;
    private final CashFlowService cashFlow;
    private final CashSessionService sessions;
    private final MoneyActivityService activity;
    private final double registerOpenHours;
    private final double settlementReminderDays;

    public MoneyOverviewService(JdbcClient jdbc, CashFlowService cashFlow, CashSessionService sessions,
            MoneyActivityService activity, @Value("${MONEY_REGISTER_OPEN_HOURS:}") String openHours,
            @Value("${MONEY_SETTLEMENT_REMINDER_DAYS:}") String reminderDays) {
        this.jdbc = jdbc;
        this.cashFlow = cashFlow;
        this.sessions = sessions;
        this.activity = activity;
        this.registerOpenHours = positive(openHours, 16);
        this.settlementReminderDays = positive(reminderDays, 5);
    }

    private static double positive(String raw, double fallback) {
        double n = com.inventoryerp.kernel.common.JsNumbers.number(raw);
        return !Double.isNaN(n) && !Double.isInfinite(n) && n > 0 ? n : fallback;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> attention(String kind, String severity, String message, String amount, String href,
            String actionLabel) {
        Map<String, Object> m = body("kind", kind, "severity", severity, "message", message);
        if (amount != null) {
            m.put("amount", amount);
        }
        m.put("href", href);
        m.put("actionLabel", actionLabel);
        return m;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> overview() {
        String orgId = TenantContext.organizationId();
        Map<String, Object> org = SqlRows.one(jdbc.sql("SELECT \"currencyCode\", timezone FROM \"Organization\" WHERE id = ?").param(orgId));
        Object baseCurrency = org == null ? null : org.get("currencyCode");
        String timezone = org == null || org.get("timezone") == null || ((String) org.get("timezone")).isEmpty() ? "UTC"
                : (String) org.get("timezone");
        ZoneId zone = OrgDates.orgTimezone(jdbc, orgId);
        List<Map<String, Object>> accounts = cashFlow.getCashAccounts();
        List<Map<String, Object>> registers = SqlRows.list(jdbc.sql("SELECT id, code, name, \"defaultAccountId\" FROM \"CashRegister\""
                + " WHERE \"organizationId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY name ASC").param(orgId));
        List<Map<String, Object>> openSessions = SqlRows.list(jdbc.sql("SELECT s.id, s.\"cashRegisterId\", s.\"userId\", s.\"openedAt\","
                + " s.\"drawerAccountId\", r.name AS rname FROM \"CashSession\" s LEFT JOIN \"CashRegister\" r ON r.id = s.\"cashRegisterId\""
                + " WHERE s.\"organizationId\" = ? AND s.status = 'open' ORDER BY s.\"openedAt\" ASC").param(orgId));
        List<Map<String, Object>> methods = SqlRows.list(jdbc.sql("SELECT m.id, m.label, m.kind, m.\"accountId\", a.name AS aname,"
                + " a.\"isActive\" AS aactive, a.\"deletedAt\" AS adeleted, a.id AS aid FROM \"PosPaymentMethod\" m"
                + " LEFT JOIN \"Account\" a ON a.id = m.\"accountId\" WHERE m.\"organizationId\" = ? AND m.\"deletedAt\" IS NULL"
                + " AND m.\"isActive\" = true").param(orgId));
        List<Map<String, Object>> pendingVariances = SqlRows.list(jdbc.sql("SELECT s.id, s.\"closingDifference\", s.\"closedAt\","
                + " r.name AS rname FROM \"CashSession\" s LEFT JOIN \"CashRegister\" r ON r.id = s.\"cashRegisterId\""
                + " WHERE s.\"organizationId\" = ? AND s.status = 'closed' AND s.\"varianceStatus\" = 'pending_review'"
                + " ORDER BY s.\"closedAt\" DESC NULLS LAST LIMIT 20").param(orgId));

        Set<Object> drawerIds = new HashSet<>();
        registers.forEach(r -> {
            if (r.get("defaultAccountId") != null) {
                drawerIds.add(r.get("defaultAccountId"));
            }
        });
        openSessions.forEach(s -> {
            if (s.get("drawerAccountId") != null) {
                drawerIds.add(s.get("drawerAccountId"));
            }
        });
        Map<Object, Double> balanceOf = new HashMap<>();
        accounts.forEach(a -> balanceOf.put(a.get("id"), Double.parseDouble((String) a.get("balance"))));
        Map<String, Map<String, Object>> byType = new LinkedHashMap<>();
        double total = 0;
        List<Map<String, Object>> foreign = new ArrayList<>();
        for (Map<String, Object> a : accounts) {
            Object currencyId = a.get("currencyId");
            boolean inBase = currencyId == null || baseCurrency == null || currencyId.equals(baseCurrency);
            if (!inBase) {
                foreign.add(body("id", a.get("id"), "name", a.get("name"), "currencyId", currencyId));
                continue;
            }
            String[] g = drawerIds.contains(a.get("id")) ? GROUPS.get("drawer")
                    : GROUPS.getOrDefault((String) a.get("accountType"),
                            new String[] {a.get("accountType") == null ? "other" : (String) a.get("accountType"), "Other"});
            Map<String, Object> cur = byType.computeIfAbsent(g[0], k -> body("key", g[0], "label", g[1], "balance", 0.0,
                    "accountCount", 0));
            double bal = Double.parseDouble((String) a.get("balance"));
            cur.put("balance", (Double) cur.get("balance") + bal);
            cur.put("accountCount", (Integer) cur.get("accountCount") + 1);
            total += bal;
        }

        Map<Object, String> userName = new HashMap<>();
        for (Map<String, Object> s : openSessions) {
            Object uid = s.get("userId");
            if (uid != null && !userName.containsKey(uid)) {
                jdbc.sql("SELECT \"firstName\" || coalesce(' ' || nullif(\"lastName\", ''), '') FROM \"User\" WHERE id = ?")
                        .param(uid).query(String.class).optional().ifPresent(n -> userName.put(uid, n));
            }
        }
        Instant now = Instant.now();
        List<Map<String, Object>> openRegisters = new ArrayList<>();
        for (Map<String, Object> s : openSessions) {
            long hours = Math.floorDiv(Duration.between((Instant) s.get("openedAt"), now).toMillis(), 3_600_000L);
            openRegisters.add(body("registerId", s.get("cashRegisterId"), "registerName", s.get("rname") == null ? "—" : s.get("rname"),
                    "sessionId", s.get("id"), "cashierName", userName.get(s.get("userId")), "openedAt", s.get("openedAt"),
                    "hoursOpen", hours, "expectedCash", sessions.expectedCash((String) s.get("id")).setScale(2, RoundingMode.HALF_UP)
                            .toPlainString()));
        }
        Set<Object> openRegisterIds = new HashSet<>();
        openSessions.forEach(s -> openRegisterIds.add(s.get("cashRegisterId")));
        List<Map<String, Object>> closedDrawers = new ArrayList<>();
        for (Map<String, Object> r : registers) {
            double bal = balanceOf.getOrDefault(r.get("defaultAccountId"), 0.0);
            if (!openRegisterIds.contains(r.get("id")) && bal > 0) {
                closedDrawers.add(body("registerId", r.get("id"), "registerName", r.get("name"), "accountId", r.get("defaultAccountId"),
                        "amount", toFixed2(bal)));
            }
        }
        double cashAwaitingBanking = closedDrawers.stream().mapToDouble(d -> Double.parseDouble((String) d.get("amount"))).sum();

        String todayStr = OrgDates.orgToday(zone, now);
        Instant[] range = OrgDates.zonedDayRange(todayStr, zone);
        Map<String, Object> today = todayTotals(orgId, accounts.stream().map(a -> (String) a.get("id")).toList(), range);

        List<Map<String, Object>> attention = new ArrayList<>();
        for (Map<String, Object> v : pendingVariances) {
            double diff = v.get("closingDifference") == null ? 0 : SqlRows.dec(v, "closingDifference").doubleValue();
            attention.add(attention("variance_unresolved", "warning", (v.get("rname") == null ? "A register" : v.get("rname"))
                    + " closed " + (diff < 0 ? "short" : "over") + " and the variance has not been reviewed",
                    toFixed2(Math.abs(diff)), "/pos/cash-registers?tab=history", "Review variance"));
        }
        for (Map<String, Object> d : closedDrawers) {
            attention.add(attention("awaiting_banking", "warning", d.get("registerName")
                    + " has cash from closed shifts that has not been banked", (String) d.get("amount"),
                    "/pos/cash-registers?tab=history", "Bank cash"));
        }
        for (Map<String, Object> r : openRegisters) {
            long hours = (Long) r.get("hoursOpen");
            if (hours >= registerOpenHours) {
                attention.add(attention("register_open_too_long", "warning", r.get("registerName") + " has been open for " + hours
                        + " hours" + (r.get("cashierName") != null ? " (" + r.get("cashierName") + ")" : ""), null,
                        "/pos/cash-registers", "View register"));
            }
        }
        for (Map<String, Object> m : methods) {
            if ("cash".equals(m.get("kind")) || "store_credit".equals(m.get("kind"))) {
                continue;
            }
            if (m.get("accountId") == null || m.get("aid") == null || !Boolean.TRUE.equals(m.get("aactive")) || m.get("adeleted") != null) {
                attention.add(attention("payment_method_unmapped", "warning", m.get("label")
                        + " is not connected to an active receiving account", null, "/settings/payment-methods", "Connect account"));
            }
        }
        if (accounts.stream().noneMatch(a -> "bank".equals(a.get("accountType")))) {
            attention.add(attention("config_incomplete", "warning",
                    "No bank account exists yet, so cash cannot be banked and providers cannot be settled", null,
                    "/accounts/cash-accounts/accounts?new=bank", "Add bank account"));
        }
        if (registers.isEmpty()) {
            attention.add(attention("config_incomplete", "warning", "No cash register is set up, so the POS cannot take cash",
                    null, "/settings/registers", "Add register"));
        }
        attention.addAll(settlementReminders(orgId, methods, balanceOf));

        Map<String, Object> recent = activity.list(new MoneyActivityService.Filters(null, null, null, null, null, null, null, 1, 10));
        List<Map<String, Object>> types = new ArrayList<>(byType.values());
        types.sort(Comparator.comparingDouble((Map<String, Object> g) -> -(Double) g.get("balance")));
        types.forEach(g -> g.put("balance", toFixed2((Double) g.get("balance"))));
        return body("baseCurrency", baseCurrency, "timezone", timezone, "todayDate", todayStr,
                "totalAvailableBookBalance", toFixed2(total), "byType", types, "foreignCurrencyAccounts", foreign,
                "openRegisters", openRegisters, "cashAwaitingBanking", toFixed2(cashAwaitingBanking), "closedDrawers", closedDrawers,
                "today", today, "attention", attention, "recent", ((Map<String, Object>) recent).get("data"));
    }

    private Map<String, Object> todayTotals(String orgId, List<String> accountIds, Instant[] range) {
        if (accountIds.isEmpty()) {
            return body("externalIn", "0.00", "externalOut", "0.00", "internalMoved", "0.00", "posReceipts", "0.00",
                    "activityCount", 0);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT x.pos, COALESCE(SUM(GREATEST(x.d - x.c, 0)), 0) AS external_in,"
                        + " COALESCE(SUM(GREATEST(x.c - x.d, 0)), 0) AS external_out, COALESCE(SUM(LEAST(x.d, x.c)), 0) AS internal_moved,"
                        + " COUNT(*) AS n FROM (SELECT je.id, COALESCE(" + MoneyActivityTaxonomy.EFFECTIVE_SOURCE_TYPE + " IN ("
                        + MoneyActivityTaxonomy.sqlList(MoneyActivityTaxonomy.POS_SALE_SOURCE_TYPES) + "), false) AS pos,"
                        + " SUM(jl.\"baseDebit\") AS d, SUM(jl.\"baseCredit\") AS c FROM \"JournalLine\" jl"
                        + " JOIN \"JournalEntry\" je ON je.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = :org"
                        + " AND jl.\"accountId\" IN (:ids) AND je.status::text IN ('posted', 'reversed')"
                        + " AND je.\"postingDate\" >= :start AND je.\"postingDate\" < :end GROUP BY je.id) x GROUP BY x.pos")
                .param("org", orgId).param("ids", accountIds).param("start", FiscalPeriodControl.ts(range[0]))
                .param("end", FiscalPeriodControl.ts(range[1])));
        double in = 0;
        double out = 0;
        double internal = 0;
        double pos = 0;
        long n = 0;
        for (Map<String, Object> r : rows) {
            double ei = SqlRows.dec(r, "external_in").doubleValue();
            in += ei;
            out += SqlRows.dec(r, "external_out").doubleValue();
            internal += SqlRows.dec(r, "internal_moved").doubleValue();
            n += ((Number) r.get("n")).longValue();
            if (Boolean.TRUE.equals(r.get("pos"))) {
                pos += ei;
            }
        }
        return body("externalIn", toFixed2(in), "externalOut", toFixed2(out), "internalMoved", toFixed2(internal),
                "posReceipts", toFixed2(pos), "activityCount", n);
    }

    private List<Map<String, Object>> settlementReminders(String orgId, List<Map<String, Object>> methods,
            Map<Object, Double> balanceOf) {
        Set<String> accountIds = new LinkedHashSet<>();
        for (Map<String, Object> m : methods) {
            if (!"cash".equals(m.get("kind")) && m.get("accountId") != null) {
                accountIds.add((String) m.get("accountId"));
            }
        }
        if (accountIds.isEmpty()) {
            return List.of();
        }
        Map<String, Instant> lastOf = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"sourceAccountId\", max(\"settledAt\") AS last FROM \"TenderSettlement\""
                + " WHERE \"organizationId\" = :org AND \"sourceAccountId\" IN (:ids) GROUP BY \"sourceAccountId\"")
                .param("org", orgId).param("ids", accountIds))) {
            lastOf.put((String) l.get("sourceAccountId"), (Instant) l.get("last"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Instant now = Instant.now();
        Instant cutoff = now.minusMillis((long) (settlementReminderDays * 864e5));
        for (String id : accountIds) {
            Double balance = balanceOf.get(id);
            if (balance == null || balance <= 0) {
                continue;
            }
            Instant lastAt = lastOf.get(id);
            if (lastAt != null && !lastAt.isBefore(cutoff)) {
                continue;
            }
            String name = methods.stream().filter(m -> id.equals(m.get("accountId")) && m.get("aname") != null)
                    .map(m -> (String) m.get("aname")).findFirst().orElse("A provider account");
            out.add(attention("provider_no_recent_settlement", "info", lastAt != null
                    ? name + " has a balance and no recorded settlement for "
                            + Math.floorDiv(Duration.between(lastAt, now).toMillis(), 86_400_000L) + " days"
                    : name + " has a balance and no settlement has been recorded yet",
                    toFixed2(balance), "/accounts/cash-accounts/settlements?source=" + id, "Open settlements"));
        }
        return out;
    }
}
