package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cost-centre management reports (ADR-S007 §4): P&amp;L per centre with hierarchy roll-up, and the centre × account
 * matrix. Amounts are base currency, posted entries only, signed as the P&amp;L reads them (income positive, expense
 * positive, {@code net} = income − expense). Lines without a centre are reported as {@code unassigned}, so the
 * report always ties to the company P&amp;L for the same period.
 */
@Service
public class CostCenterReportService {

    private static final Set<String> INCOME = Set.of("revenue", "other_income", "contra_revenue");
    private static final Set<String> PNL = Set.of("revenue", "contra_revenue", "cogs", "operating_expense",
            "other_income", "other_expense");

    private final JdbcClient jdbc;

    public CostCenterReportService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Bal(String costCenterId, String accountId, String code, String name, String section,
            BigDecimal net) {}

    private List<Bal> balances(LocalDate from, LocalDate to) {
        List<Bal> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"costCenterId\", l.\"accountId\", a.code, a.name,"
                + " c.\"reportSection\"::text AS section, SUM(l.\"baseDebit\" - l.\"baseCredit\") AS net"
                + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + " JOIN \"Account\" a ON a.id = l.\"accountId\" LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " WHERE l.\"organizationId\" = ? AND e.status IN ('posted', 'reversed') AND e.\"postingDate\" >= ?"
                + " AND e.\"postingDate\" < ? GROUP BY 1, 2, 3, 4, 5")
                .params(TenantContext.organizationId(), from, to.plusDays(1)))) {
            String section = (String) r.get("section");
            if (!PNL.contains(String.valueOf(section))) {
                continue;
            }
            out.add(new Bal((String) r.get("costCenterId"), (String) r.get("accountId"), (String) r.get("code"),
                    (String) r.get("name"), section, SqlRows.dec(r, "net")));
        }
        return out;
    }

    /** P&amp;L amount: income accounts flip the debit-positive net. */
    private static BigDecimal amount(Bal b) {
        return INCOME.contains(b.section()) ? b.net().negate() : b.net();
    }

    private List<Map<String, Object>> centers() {
        return SqlRows.list(jdbc.sql("SELECT id, code, name, type, category, \"parentId\", \"isActive\" FROM \"CostCenter\""
                + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY code")
                .param(TenantContext.organizationId()));
    }

    /**
     * Per centre: income, expense, net and per-account detail; {@code rollup*} adds every descendant. With
     * {@code costCenterId} only that centre (and, rolled up, its subtree) is returned.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> profitAndLoss(LocalDate from, LocalDate to, String costCenterId) {
        List<Bal> bals = balances(from, to);
        List<Map<String, Object>> centers = centers();
        Map<String, String> parent = new HashMap<>();
        centers.forEach(c -> parent.put((String) c.get("id"), (String) c.get("parentId")));

        Map<String, BigDecimal[]> own = new HashMap<>();
        Map<String, Map<String, Map<String, Object>>> accountsBy = new HashMap<>();
        BigDecimal[] company = {BigDecimal.ZERO, BigDecimal.ZERO};
        for (Bal b : bals) {
            BigDecimal v = amount(b);
            boolean income = INCOME.contains(b.section());
            String key = b.costCenterId() == null ? "" : b.costCenterId();
            BigDecimal[] t = own.computeIfAbsent(key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            t[income ? 0 : 1] = t[income ? 0 : 1].add(v);
            company[income ? 0 : 1] = company[income ? 0 : 1].add(v);
            accountsBy.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(b.accountId(), Obj.of("accountId",
                    b.accountId(), "code", b.code(), "name", b.name(), "section", b.section(), "amount",
                    JsNumbers.dec(v)));
        }
        Map<String, BigDecimal[]> rolled = new HashMap<>();
        for (Map.Entry<String, BigDecimal[]> e : own.entrySet()) {
            String cursor = e.getKey().isEmpty() ? null : e.getKey();
            for (int depth = 0; cursor != null && depth < 64; depth++) {
                BigDecimal[] t = rolled.computeIfAbsent(cursor, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                t[0] = t[0].add(e.getValue()[0]);
                t[1] = t[1].add(e.getValue()[1]);
                cursor = parent.get(cursor);
            }
        }
        Set<String> subtree = costCenterId == null ? null : subtree(costCenterId, centers);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : centers) {
            String id = (String) c.get("id");
            if (subtree != null && !subtree.contains(id)) {
                continue;
            }
            BigDecimal[] o = own.getOrDefault(id, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] r = rolled.getOrDefault(id, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            rows.add(Obj.of("costCenterId", id, "code", c.get("code"), "name", c.get("name"), "type", c.get("type"),
                    "category", c.get("category"), "parentId", c.get("parentId"),
                    "income", JsNumbers.dec(o[0]), "expense", JsNumbers.dec(o[1]), "net", JsNumbers.dec(o[0].subtract(o[1])),
                    "rollupIncome", JsNumbers.dec(r[0]), "rollupExpense", JsNumbers.dec(r[1]),
                    "rollupNet", JsNumbers.dec(r[0].subtract(r[1])),
                    "accounts", new ArrayList<>(accountsBy.getOrDefault(id, Map.of()).values())));
        }
        BigDecimal[] un = own.getOrDefault("", new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("costCenters", rows);
        if (costCenterId == null) {
            out.put("unassigned", Obj.of("income", JsNumbers.dec(un[0]), "expense", JsNumbers.dec(un[1]), "net",
                    JsNumbers.dec(un[0].subtract(un[1])), "accounts",
                    new ArrayList<>(accountsBy.getOrDefault("", Map.of()).values())));
            out.put("company", Obj.of("income", JsNumbers.dec(company[0]), "expense", JsNumbers.dec(company[1]), "net",
                    JsNumbers.dec(company[0].subtract(company[1]))));
        }
        return out;
    }

    /** Account rows × centre columns (+ unassigned and total), P&amp;L-signed amounts. */
    @Transactional(readOnly = true)
    public Map<String, Object> matrix(LocalDate from, LocalDate to) {
        List<Bal> bals = balances(from, to);
        List<Map<String, Object>> centers = centers();
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        Map<String, BigDecimal> columnTotals = new LinkedHashMap<>();
        bals.sort((a, b) -> String.valueOf(a.code()).compareTo(String.valueOf(b.code())));
        for (Bal b : bals) {
            Map<String, Object> row = rows.computeIfAbsent(b.accountId(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("accountId", b.accountId());
                m.put("code", b.code());
                m.put("name", b.name());
                m.put("section", b.section());
                m.put("byCostCenter", new LinkedHashMap<String, String>());
                m.put("total", "0");
                return m;
            });
            String key = b.costCenterId() == null ? "unassigned" : b.costCenterId();
            BigDecimal v = amount(b);
            @SuppressWarnings("unchecked")
            Map<String, String> by = (Map<String, String>) row.get("byCostCenter");
            by.merge(key, JsNumbers.dec(v), (x, y) -> JsNumbers.dec(new BigDecimal(x).add(new BigDecimal(y))));
            row.put("total", JsNumbers.dec(new BigDecimal((String) row.get("total")).add(v)));
            columnTotals.merge(key, v, BigDecimal::add);
        }
        Map<String, String> totals = new LinkedHashMap<>();
        columnTotals.forEach((k, v) -> totals.put(k, JsNumbers.dec(v)));
        return Obj.of("from", from.toString(), "to", to.toString(), "costCenters", centers,
                "accounts", new ArrayList<>(rows.values()), "columnTotals", totals);
    }

    private static Set<String> subtree(String rootId, List<Map<String, Object>> centers) {
        Set<String> out = new java.util.LinkedHashSet<>();
        out.add(rootId);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map<String, Object> c : centers) {
                if (c.get("parentId") != null && out.contains(c.get("parentId")) && out.add((String) c.get("id"))) {
                    grew = true;
                }
            }
        }
        return out;
    }
}
