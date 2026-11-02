package com.inventoryerp.reporting.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configurable financial statement layouts (owner catalogue §6): the accountant maps account categories, report
 * sections or account codes to statement lines and defines subtotals as formulas, without code. Two system layouts
 * (IFRS for SMEs profit or loss and statement of financial position) are created per organization on first use and
 * stay editable; {@link #reset(String)} restores them.
 */
@Service
public class StatementLayoutService {

    public static final String DEFAULT_PL = "IFRS_SME_PL";
    public static final String DEFAULT_BS = "IFRS_SME_BS";
    public static final String DEFAULT_CF = "IFRS_SME_CF";
    static final java.util.List<String> CASH_CATEGORIES = java.util.List.of("cash", "bank", "mobile_money",
            "petty_cash");

    static final Set<String> PL_SECTIONS = Set.of("revenue", "contra_revenue", "other_income", "cogs",
            "operating_expense", "other_expense");
    static final Set<String> BS_SECTIONS = Set.of("current_assets", "non_current_assets", "current_liabilities",
            "long_term_liabilities", "equity");

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    /** Accounts a line takes: any listed category, section or code, minus the excluded codes. */
    public record Selector(List<String> categories, List<String> sections, List<String> accountCodes,
            List<String> excludeAccountCodes) {

        public Selector {
            categories = categories == null ? List.of() : List.copyOf(categories);
            sections = sections == null ? List.of() : List.copyOf(sections);
            accountCodes = accountCodes == null ? List.of() : List.copyOf(accountCodes);
            excludeAccountCodes = excludeAccountCodes == null ? List.of() : List.copyOf(excludeAccountCodes);
        }

        public boolean matches(LedgerAggregator.AccountInfo a) {
            if (excludeAccountCodes.contains(a.code())) {
                return false;
            }
            return categories.contains(a.category()) || sections.contains(a.section())
                    || accountCodes.contains(a.code());
        }
    }

    /**
     * @param lineType {@code HEADER}, {@code ACCOUNTS} or {@code FORMULA}
     * @param sign {@code DEBIT} (debit − credit) or {@code CREDIT} (credit − debit)
     */
    public record Line(String code, String label, String lineType, Selector selector, String formula, String sign,
            int indent, boolean bold, boolean kpi, boolean percent) {}

    public record Layout(String id, String code, String name, String kind, boolean isSystem, List<Line> lines) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AuditService audit;

    public StatementLayoutService(JdbcClient jdbc, JsonMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** All layouts of the organization (system layouts created on first use). */
    @Transactional
    public List<Map<String, Object>> list() {
        ensureDefaults();
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.sql("SELECT id, code, name, kind, \"isSystem\", \"updatedAt\" FROM \"FinancialStatementLayout\""
                        + " WHERE \"organizationId\" = ? ORDER BY kind DESC, \"isSystem\" DESC, code")
                .param(org())
                .query((rs, n) -> out.add(Obj.of("id", rs.getString("id"), "code", rs.getString("code"), "name",
                        rs.getString("name"), "kind", rs.getString("kind"), "isSystem", rs.getBoolean("isSystem"),
                        "updatedAt", rs.getTimestamp("updatedAt").toInstant())))
                .list();
        return out;
    }

    /** One layout with its lines; 404 when unknown. */
    @Transactional
    public Layout get(String code) {
        ensureDefaults();
        Map<String, Object> head = jdbc.sql("SELECT id, code, name, kind, \"isSystem\" FROM"
                        + " \"FinancialStatementLayout\" WHERE \"organizationId\" = ? AND code = ?")
                .params(org(), code)
                .query((rs, n) -> Obj.of("id", rs.getString("id"), "code", rs.getString("code"), "name",
                        rs.getString("name"), "kind", rs.getString("kind"), "isSystem", rs.getBoolean("isSystem")))
                .optional().orElseThrow(() -> ApiException.notFound("Statement layout " + code + " not found"));
        List<Line> lines = jdbc.sql("""
                        SELECT code, label, "lineType", selector::text AS selector, formula, sign, indent, bold,
                               "isKpi", percent
                        FROM "FinancialStatementLine" WHERE "layoutId" = ? ORDER BY "sortOrder\"""")
                .param(head.get("id"))
                .query((rs, n) -> new Line(rs.getString("code"), rs.getString("label"), rs.getString("lineType"),
                        rs.getString("selector") == null ? null : json.readValue(rs.getString("selector"),
                                Selector.class), rs.getString("formula"), rs.getString("sign"), rs.getInt("indent"),
                        rs.getBoolean("bold"), rs.getBoolean("isKpi"), rs.getBoolean("percent")))
                .list();
        return new Layout((String) head.get("id"), code, (String) head.get("name"), (String) head.get("kind"),
                (Boolean) head.get("isSystem"), lines);
    }

    /** Create a layout, optionally copying the lines of another one. */
    @Transactional
    public Layout create(String code, String name, String kind, String copyFrom) {
        ensureDefaults();
        if (code == null || !CODE.matcher(code).matches()) {
            throw ApiException.badRequest("code must be upper-case letters, digits or _ (max 40), starting with a"
                    + " letter");
        }
        if (exists(code)) {
            throw ApiException.conflict("Statement layout " + code + " already exists");
        }
        List<Line> lines = List.of();
        if (copyFrom != null && !copyFrom.isBlank()) {
            Layout src = get(copyFrom);
            kind = src.kind();
            lines = src.lines();
        }
        if (!"PL".equals(kind) && !"BS".equals(kind) && !"CF".equals(kind)) {
            throw ApiException.badRequest("kind must be one of: BS, CF, PL");
        }
        insert(new Layout(null, code, name, kind, false, lines));
        audit.recordInTx(AuditInput.of("FinancialStatementLayout", code, "create", null, Obj.of("name", name, "kind",
                kind, "copyFrom", copyFrom)));
        return get(code);
    }

    /** Replace the name and lines of a layout (system layouts too: they belong to the accountant). */
    @Transactional
    public Layout update(String code, String name, List<Line> lines) {
        Layout current = get(code);
        validate(lines);
        jdbc.sql("UPDATE \"FinancialStatementLayout\" SET name = ?, \"updatedAt\" = now() WHERE id = ?")
                .params(name == null || name.isBlank() ? current.name() : name.trim(), current.id()).update();
        jdbc.sql("DELETE FROM \"FinancialStatementLine\" WHERE \"layoutId\" = ?").param(current.id()).update();
        insertLines(current.id(), lines);
        audit.recordInTx(AuditInput.of("FinancialStatementLayout", code, "update", Obj.of("lines",
                current.lines().size()), Obj.of("lines", lines.size())));
        return get(code);
    }

    /** Restore a system layout to the shipped default. */
    @Transactional
    public Layout reset(String code) {
        Layout def = defaults().stream().filter(l -> l.code().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Only system layouts can be reset"));
        return update(code, def.name(), def.lines());
    }

    @Transactional
    public void delete(String code) {
        Layout l = get(code);
        if (l.isSystem()) {
            throw ApiException.badRequest("A system layout cannot be deleted; reset it instead");
        }
        jdbc.sql("DELETE FROM \"FinancialStatementLayout\" WHERE id = ?").param(l.id()).update();
        audit.recordInTx(AuditInput.of("FinancialStatementLayout", code, "delete", Obj.of("name", l.name()), null));
    }

    /** Line codes unique and well-formed, known line types and signs, formulas valid and acyclic. */
    static void validate(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw ApiException.badRequest("lines should not be empty");
        }
        Map<String, Line> byCode = new LinkedHashMap<>();
        for (Line l : lines) {
            if (l.code() == null || !CODE.matcher(l.code()).matches()) {
                throw ApiException.badRequest("Line code " + l.code() + " must be upper-case letters, digits or _");
            }
            if (byCode.put(l.code(), l) != null) {
                throw ApiException.badRequest("Line code " + l.code() + " is used twice");
            }
            if (l.label() == null || l.label().isBlank()) {
                throw ApiException.badRequest("Line " + l.code() + " needs a label");
            }
            if (!Set.of("HEADER", "ACCOUNTS", "FORMULA").contains(l.lineType())) {
                throw ApiException.badRequest("lineType must be one of: HEADER, ACCOUNTS, FORMULA");
            }
            if (l.sign() != null && !Set.of("DEBIT", "CREDIT").contains(l.sign())) {
                throw ApiException.badRequest("sign must be one of: DEBIT, CREDIT");
            }
            if ("FORMULA".equals(l.lineType()) && (l.formula() == null || l.formula().isBlank())) {
                throw ApiException.badRequest("Line " + l.code() + " needs a formula");
            }
        }
        Map<String, Set<String>> refs = new HashMap<>();
        for (Line l : lines) {
            if ("FORMULA".equals(l.lineType())) {
                FormulaEvaluator.validate(l.formula(), byCode);
                refs.put(l.code(), FormulaEvaluator.references(l.formula()));
            }
        }
        for (String code : refs.keySet()) {
            cycle(code, refs, new HashSet<>());
        }
    }

    private static void cycle(String code, Map<String, Set<String>> refs, Set<String> path) {
        if (!path.add(code)) {
            throw ApiException.badRequest("Formula of line " + code + " refers to itself");
        }
        for (String r : refs.getOrDefault(code, Set.of())) {
            cycle(r, refs, path);
        }
        path.remove(code);
    }

    private boolean exists(String code) {
        return jdbc.sql("SELECT count(*) FROM \"FinancialStatementLayout\" WHERE \"organizationId\" = ? AND code = ?")
                .params(org(), code).query(Long.class).single() > 0;
    }

    private void ensureDefaults() {
        for (Layout d : defaults()) {
            if (!exists(d.code())) {
                insert(d);
            }
        }
    }

    private void insert(Layout l) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"FinancialStatementLayout\" (id, \"organizationId\", code, name, kind, \"isSystem\","
                + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", code) DO NOTHING")
                .params(id, org(), l.code(), l.name() == null || l.name().isBlank() ? l.code() : l.name().trim(),
                        l.kind(), l.isSystem(), TenantContext.userId())
                .update();
        insertLines(id, l.lines());
    }

    private void insertLines(String layoutId, List<Line> lines) {
        int order = 0;
        for (Line l : lines) {
            jdbc.sql("""
                    INSERT INTO "FinancialStatementLine" (id, "organizationId", "layoutId", "sortOrder", code, label,
                      "lineType", selector, formula, sign, indent, bold, "isKpi", percent)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""")
                    .params(UUID.randomUUID().toString(), org(), layoutId, order++, l.code(), l.label().trim(),
                            l.lineType(), l.selector() == null ? null : json.writeValueAsString(l.selector()),
                            l.formula(), l.sign() == null ? "CREDIT" : l.sign(), Math.max(0, Math.min(l.indent(), 6)),
                            l.bold(), l.kpi(), l.percent())
                    .update();
        }
    }

    private static Line accounts(String code, String label, String sign, int indent, List<String> categories,
            List<String> sections, List<String> codes, List<String> exclude) {
        return new Line(code, label, "ACCOUNTS", new Selector(categories, sections, codes, exclude), null, sign, indent,
                false, false, false);
    }

    private static Line formula(String code, String label, String formula, int indent, boolean bold) {
        return new Line(code, label, "FORMULA", null, formula, "CREDIT", indent, bold, false, false);
    }

    private static Line kpi(String code, String label, String formula, boolean percent) {
        return new Line(code, label, "FORMULA", null, formula, "CREDIT", 0, false, true, percent);
    }

    private static Line header(String code, String label, int indent) {
        return new Line(code, label, "HEADER", null, null, "CREDIT", indent, true, false, false);
    }

    /** Shipped layouts, from the seeded chart of accounts (categories, 5600 depreciation, 7200 FX loss). */
    static List<Layout> defaults() {
        List<Line> pl = List.of(
                accounts("REV", "Revenue", "CREDIT", 0, List.of("revenue"), null, null, null),
                accounts("DISC", "Sales returns and discounts", "CREDIT", 1, List.of("contra_revenue"), null, null,
                        null),
                formula("NET_REV", "Net revenue", "REV + DISC", 0, true),
                accounts("COS", "Cost of sales", "CREDIT", 0, List.of("cost_of_goods_sold"), null, null, null),
                formula("GP", "Gross profit", "NET_REV + COS", 0, true),
                accounts("OI", "Other operating income", "CREDIT", 0, List.of("other_income"), null, null, null),
                accounts("OPEX", "Operating expenses", "CREDIT", 0, List.of("operating_expense"), null, null,
                        List.of("5600", "7200")),
                accounts("DEP", "Depreciation and amortisation", "CREDIT", 0, null, null, List.of("5600"), null),
                formula("OP", "Operating profit (EBIT)", "GP + OI + OPEX + DEP", 0, true),
                accounts("FIN", "Finance costs and exchange losses", "CREDIT", 0, null, null, List.of("7200"), null),
                accounts("OE", "Other expenses", "CREDIT", 0, List.of("other_expense"), null, null, null),
                formula("PBT", "Profit before tax", "OP + FIN + OE", 0, true),
                accounts("TAX", "Income tax expense", "CREDIT", 0, null, null, null, null),
                formula("NP", "Net profit for the period", "PBT + TAX", 0, true),
                kpi("EBITDA", "EBITDA (management measure)", "OP - DEP", false),
                kpi("GPM", "Gross profit margin %", "GP / NET_REV * 100", true),
                kpi("OPM", "Operating margin %", "OP / NET_REV * 100", true),
                kpi("NPM", "Net profit margin %", "NP / NET_REV * 100", true));
        List<String> plSections = List.of("revenue", "contra_revenue", "other_income", "cogs", "operating_expense",
                "other_expense");
        List<Line> bs = List.of(
                header("H_ASSETS", "Assets", 0),
                header("H_CA", "Current assets", 1),
                accounts("CASH", "Cash and cash equivalents", "DEBIT", 2, List.of("cash", "bank", "mobile_money",
                        "petty_cash"), null, null, null),
                accounts("AR", "Trade and other receivables", "DEBIT", 2, List.of("receivable"), null, null, null),
                accounts("INV", "Inventories", "DEBIT", 2, List.of("inventory", "work_in_progress"), null, null, null),
                accounts("PREP", "Prepayments", "DEBIT", 2, List.of("prepaid_expense"), null, null, null),
                accounts("OCA", "Other current assets", "DEBIT", 2, List.of("current_asset"), null, null, null),
                formula("TCA", "Total current assets", "CASH + AR + INV + PREP + OCA", 1, true),
                header("H_NCA", "Non-current assets", 1),
                accounts("PPE", "Property, plant and equipment", "DEBIT", 2, List.of("non_current_asset",
                        "construction_in_progress"), null, null, null),
                accounts("ACCDEP", "Accumulated depreciation and amortisation", "DEBIT", 2, List.of("contra_asset",
                        "accumulated_depreciation", "accumulated_amortization"), null, null, null),
                formula("TNCA", "Total non-current assets", "PPE + ACCDEP", 1, true),
                formula("TA", "Total assets", "TCA + TNCA", 0, true),
                header("H_EL", "Equity and liabilities", 0),
                header("H_CL", "Current liabilities", 1),
                accounts("AP", "Trade payables", "CREDIT", 2, List.of("payable"), null, null, null),
                accounts("TAXL", "Tax payable", "CREDIT", 2, List.of("tax"), null, null, null),
                accounts("DEFREV", "Deferred revenue", "CREDIT", 2, List.of("deferred_revenue"), null, null, null),
                accounts("OCL", "Other current liabilities", "CREDIT", 2, List.of("current_liability"), null, null,
                        null),
                formula("TCL", "Total current liabilities", "AP + TAXL + DEFREV + OCL", 1, true),
                header("H_NCL", "Non-current liabilities", 1),
                accounts("NCL", "Borrowings and other non-current liabilities", "CREDIT", 2, List.of(
                        "non_current_liability", "contra_liability"), null, null, null),
                formula("TNCL", "Total non-current liabilities", "NCL", 1, true),
                formula("TL", "Total liabilities", "TCL + TNCL", 0, true),
                header("H_EQ", "Equity", 1),
                accounts("EQ", "Share capital and reserves", "CREDIT", 2, List.of("equity"), null, null, null),
                accounts("OCI", "Other comprehensive income reserve", "CREDIT", 2, List.of(
                        "other_comprehensive_income"), null, null, null),
                accounts("CYE", "Profit not yet closed to retained earnings", "CREDIT", 2, null, plSections, null,
                        null),
                formula("TE", "Total equity", "EQ + OCI + CYE", 1, true),
                formula("TLE", "Total equity and liabilities", "TL + TE", 0, true));
        List<Line> cf = List.of(
                header("H_OP", "Cash flows from operating activities", 0),
                accounts("NP", "Profit for the period", "CREDIT", 1, null, plSections, null, null),
                accounts("DEP", "Depreciation and amortisation", "CREDIT", 1, List.of("contra_asset",
                        "accumulated_depreciation", "accumulated_amortization"), null, null, null),
                header("H_WC", "Changes in working capital", 1),
                accounts("D_AR", "(Increase) / decrease in trade and other receivables", "CREDIT", 2, List.of(
                        "receivable"), null, null, null),
                accounts("D_INV", "(Increase) / decrease in inventories", "CREDIT", 2, List.of("inventory",
                        "work_in_progress"), null, null, null),
                accounts("D_PREP", "(Increase) / decrease in prepayments and other current assets", "CREDIT", 2,
                        List.of("prepaid_expense", "current_asset"), null, null, null),
                accounts("D_AP", "Increase / (decrease) in trade payables", "CREDIT", 2, List.of("payable"), null,
                        null, null),
                accounts("D_TAX", "Increase / (decrease) in tax payable", "CREDIT", 2, List.of("tax"), null, null,
                        null),
                accounts("D_OCL", "Increase / (decrease) in other current liabilities", "CREDIT", 2, List.of(
                        "deferred_revenue", "current_liability"), null, null, null),
                formula("OPER", "Net cash from operating activities", "NP + DEP + D_AR + D_INV + D_PREP + D_AP"
                        + " + D_TAX + D_OCL", 0, true),
                header("H_INV", "Cash flows from investing activities", 0),
                accounts("CAPEX", "Purchase of property, plant and equipment (net of disposals)", "CREDIT", 1,
                        List.of("non_current_asset", "construction_in_progress"), null, null, null),
                formula("INVEST", "Net cash from investing activities", "CAPEX", 0, true),
                header("H_FIN", "Cash flows from financing activities", 0),
                accounts("BORROW", "Borrowings and other non-current liabilities", "CREDIT", 1, List.of(
                        "non_current_liability", "contra_liability"), null, null, null),
                accounts("EQUITY", "Capital contributions, dividends and reserves", "CREDIT", 1, List.of("equity",
                        "other_comprehensive_income"), null, null, null),
                formula("FINANCE", "Net cash from financing activities", "BORROW + EQUITY", 0, true),
                formula("NET", "Net increase / (decrease) in cash and cash equivalents", "OPER + INVEST + FINANCE",
                        0, true));
        return List.of(new Layout(null, DEFAULT_PL, "Statement of Profit or Loss (IFRS for SMEs)", "PL", true, pl),
                new Layout(null, DEFAULT_BS, "Statement of Financial Position (IFRS for SMEs)", "BS", true, bs),
                new Layout(null, DEFAULT_CF, "Statement of Cash Flows, indirect method (IFRS for SMEs)", "CF", true,
                        cf));
    }
}
