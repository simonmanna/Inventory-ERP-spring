package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountingCatalogs.ReportSectionDef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The one place statement structure is decided (port of {@code reporting/account-classification.ts}):
 * section membership, sign and cash-flow class derive from the account's category.
 */
public final class AccountClassification {

    private AccountClassification() {}

    public static final Map<String, ReportSectionDef> REPORT_SECTION_BY_KEY = index();

    /** Balance-sheet sections, in statement order. */
    public static final List<ReportSectionDef> BALANCE_SHEET_SECTIONS =
            AccountingCatalogs.REPORT_SECTIONS.stream().filter(s -> s.side() != null).toList();

    /** P&amp;L sections, in statement order. */
    public static final List<ReportSectionDef> PNL_SECTIONS = AccountingCatalogs.REPORT_SECTIONS.stream()
            .filter(s -> s.side() == null && !"off_balance".equals(s.key())).toList();

    private static Map<String, ReportSectionDef> index() {
        Map<String, ReportSectionDef> out = new LinkedHashMap<>();
        AccountingCatalogs.REPORT_SECTIONS.forEach(s -> out.put(s.key(), s));
        return out;
    }

    public static boolean isProfitAndLoss(String classification) {
        return "revenue".equals(classification) || "expense".equals(classification);
    }

    public static boolean isBalanceSheet(String classification) {
        return "asset".equals(classification) || "liability".equals(classification) || "equity".equals(classification);
    }

    /** Which side of the balance sheet a section sits on, or {@code null} for P&amp;L sections. */
    public static String balanceSheetSideOf(String section) {
        if (section == null) {
            return null;
        }
        ReportSectionDef def = REPORT_SECTION_BY_KEY.get(section);
        return def == null ? null : def.side();
    }

    /** Ledger net (debit − credit) shown positive in the account's normal direction. */
    public static BigDecimal displayBalance(BigDecimal net, String normalBalance) {
        return "credit".equals(normalBalance) ? net.negate() : net;
    }

    /** Revenue/expense are always operating; otherwise the account/category cash-flow class. */
    public static String cashFlowSectionOf(String classification, String cashFlowClass) {
        if (isProfitAndLoss(classification)) {
            return "operating";
        }
        if ("investing".equals(cashFlowClass) || "financing".equals(cashFlowClass)) {
            return cashFlowClass;
        }
        return "operating";
    }

    /** Forest node with post-order subtotals. */
    public static final class RollupNode<T> {
        public final T node;
        public final List<RollupNode<T>> children = new ArrayList<>();
        public final BigDecimal ownBalance;
        public BigDecimal subtotal = BigDecimal.ZERO;
        public int level;

        RollupNode(T node, BigDecimal ownBalance) {
            this.node = node;
            this.ownBalance = ownBalance;
        }
    }

    /**
     * Build the account forest and compute {@code subtotal = ownBalance + Σ children.subtotal}; orphans whose
     * parent is not in {@code nodes} become roots. Depth is capped at 32.
     */
    public static <T> List<RollupNode<T>> rollupTree(List<T> nodes, Function<T, String> id, Function<T, String> parentId,
            Function<T, Integer> sortOrder, Function<T, String> code, Function<T, BigDecimal> balanceOf) {
        Map<String, RollupNode<T>> byId = new LinkedHashMap<>();
        for (T n : nodes) {
            byId.put(id.apply(n), new RollupNode<>(n, balanceOf.apply(n)));
        }
        List<RollupNode<T>> roots = new ArrayList<>();
        for (RollupNode<T> entry : byId.values()) {
            String p = parentId.apply(entry.node);
            RollupNode<T> parent = p == null ? null : byId.get(p);
            if (parent != null) {
                parent.children.add(entry);
            } else {
                roots.add(entry);
            }
        }
        Comparator<RollupNode<T>> sort = Comparator.<RollupNode<T>>comparingInt(
                        n -> sortOrder.apply(n.node) == null ? 0 : sortOrder.apply(n.node))
                .thenComparing(n -> code.apply(n.node), AccountResolverService::localeCompare);
        roots.sort(sort);
        for (RollupNode<T> root : roots) {
            visit(root, 0, sort);
        }
        return roots;
    }

    private static <T> BigDecimal visit(RollupNode<T> entry, int level, Comparator<RollupNode<T>> sort) {
        entry.level = level;
        entry.children.sort(sort);
        BigDecimal total = entry.ownBalance;
        if (level < 32) {
            for (RollupNode<T> child : entry.children) {
                total = total.add(visit(child, level + 1, sort));
            }
        }
        entry.subtotal = total;
        return total;
    }

    /** Depth-first flatten preserving statement order. */
    public static <T> List<RollupNode<T>> flattenRollup(List<RollupNode<T>> roots) {
        List<RollupNode<T>> out = new ArrayList<>();
        for (RollupNode<T> r : roots) {
            walk(r, out);
        }
        return out;
    }

    private static <T> void walk(RollupNode<T> n, List<RollupNode<T>> out) {
        out.add(n);
        for (RollupNode<T> c : n.children) {
            walk(c, out);
        }
    }
}
