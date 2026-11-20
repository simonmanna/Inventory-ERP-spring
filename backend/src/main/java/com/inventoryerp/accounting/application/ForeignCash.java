package com.inventoryerp.accounting.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Foreign-currency cash, bank and mobile-money accounts (R-8, ADR-S076; plan {@code docs/accounting/FX-TREASURY-PLAN.md}).
 *
 * <p>An account's denomination is {@code Account.currencyId} (null = the functional currency). Every line on a
 * designated foreign account carries the denomination: {@code debit}/{@code credit} in the foreign amount and
 * {@code baseDebit}/{@code baseCredit} in the functional amount. The account's foreign balance and carrying value are
 * the sums of those tagged lines; base-only lines (period-end revaluation and its reversal, the base side of a
 * designation) never move either.
 *
 * <p>A foreign account whose ledger already holds untagged lines from before FX treasury (amounts of unknown currency)
 * is <em>undesignated</em> until a designation entry states its foreign balance; FX treasury, payments and
 * reconciliation refuse it, and nothing converts it silently.
 */
public final class ForeignCash {

    /** Source types whose base-only lines may sit on a designated foreign account. */
    static final List<String> BASE_ONLY_SOURCES = List.of("fx_revaluation", "fx_designation");

    private ForeignCash() {}

    /** A foreign-denominated cash-equivalent account. */
    public record Denomination(String accountId, String code, String name, String currency, Instant designatedAt,
            boolean designated) {}

    /** Foreign balance and its carrying value (functional currency) of one account. */
    public record Balance(BigDecimal foreign, BigDecimal base) {

        /** Functional value per foreign unit; null when the foreign balance is not positive. */
        public BigDecimal carryingRate() {
            return foreign.signum() <= 0 ? null : base.divide(foreign, 10, RoundingMode.HALF_UP);
        }
    }

    /** The organisation's functional currency. */
    public static String functional(JdbcClient jdbc, String organizationId) {
        return jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(organizationId)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.badRequest("Set the company's functional currency before posting"));
    }

    /**
     * The account's foreign denomination, or {@code null} for a functional-currency account (or one that is not a cash,
     * bank or mobile-money account).
     */
    public static Denomination of(JdbcClient jdbc, String organizationId, String accountId) {
        String functional = functional(jdbc, organizationId);
        record Row(String id, String code, String name, String currency, Instant designatedAt) {}
        Row row = jdbc.sql("SELECT a.id, a.code, a.name, upper(a.\"currencyId\") AS ccy, a.\"currencyDesignatedAt\""
                        + " FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.id = ? AND"
                        + " a.\"organizationId\" = ? AND c.\"isCashEquivalent\" AND a.\"currencyId\" IS NOT NULL")
                .params(accountId, organizationId)
                .query((rs, n) -> new Row(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("ccy"), rs.getTimestamp("currencyDesignatedAt") == null ? null
                                : rs.getTimestamp("currencyDesignatedAt").toInstant()))
                .optional().orElse(null);
        if (row == null || row.currency().isBlank() || row.currency().equalsIgnoreCase(functional)) {
            return null;
        }
        boolean designated = row.designatedAt() != null || !hasUntaggedHistory(jdbc, organizationId, row.id(),
                row.currency());
        return new Denomination(row.id(), row.code(), row.name(), row.currency(), row.designatedAt(), designated);
    }

    /**
     * Whether the account holds posted lines not in its denomination, other than revaluations, designations and their
     * reversals (the amounts of unknown currency a designation must state).
     */
    static boolean hasUntaggedHistory(JdbcClient jdbc, String organizationId, String accountId, String currency) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                        + " l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ? AND"
                        + " e.status::text IN ('posted', 'reversed') AND (l.\"currencyId\" IS NULL OR"
                        + " upper(l.\"currencyId\") <> ?) AND " + notBaseOnlySource("e") + ")")
                .params(organizationId, accountId, currency).query(Boolean.class).single();
    }

    /** SQL: entry {@code e} is neither a revaluation / designation nor the reversal of one. */
    static String notBaseOnlySource(String e) {
        return "coalesce(" + e + ".\"sourceType\", '') NOT IN ('fx_revaluation', 'fx_designation') AND NOT EXISTS"
                + " (SELECT 1 FROM \"JournalEntry\" o WHERE o.id = " + e + ".\"reversalOfId\" AND o.\"sourceType\" IN"
                + " ('fx_revaluation', 'fx_designation'))";
    }

    /** Refuse an undesignated account (balances of unknown currency). */
    public static void requireDesignated(Denomination d) {
        if (d != null && !d.designated()) {
            throw ApiException.badRequest("Account " + d.code() + " has balances of unknown currency; designate its "
                    + d.currency() + " balance first");
        }
    }

    /** Posted foreign balance and carrying value of the account's lines in {@code currency}. */
    public static Balance balance(JdbcClient jdbc, String organizationId, String accountId, String currency) {
        return jdbc.sql("SELECT coalesce(sum(l.debit - l.credit), 0) AS f, coalesce(sum(l.\"baseDebit\" -"
                        + " l.\"baseCredit\"), 0) AS b FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                        + " l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ? AND"
                        + " e.status::text IN ('posted', 'reversed') AND upper(l.\"currencyId\") = ?")
                .params(organizationId, accountId, currency)
                .query((rs, n) -> new Balance(rs.getBigDecimal("f"), rs.getBigDecimal("b"))).single();
    }

    /**
     * Functional value relieved when {@code amount} (foreign) leaves an account holding {@code bal} (plan §3
     * R-carrying): at the carrying rate; all the remaining carrying value when the outflow empties the balance (so no
     * base residual stays on a zero foreign balance); any part beyond a positive balance at {@code spotRate}.
     */
    public static BigDecimal relieve(Balance bal, BigDecimal amount, BigDecimal spotRate) {
        if (bal.foreign().signum() <= 0) {
            return r6(amount.multiply(spotRate));
        }
        if (amount.compareTo(bal.foreign()) >= 0) {
            return r6(bal.base().add(amount.subtract(bal.foreign()).multiply(spotRate)));
        }
        return amount.multiply(bal.base()).divide(bal.foreign(), 6, RoundingMode.HALF_UP);
    }

    /** Funds check in the account's denomination. */
    public static void assertFunds(Balance bal, BigDecimal amount, String label, String currency) {
        if (bal.foreign().compareTo(amount) < 0) {
            throw ApiException.badRequest(label + " has insufficient funds (available " + currency + " "
                    + bal.foreign().setScale(2, RoundingMode.HALF_UP).toPlainString() + ")");
        }
    }

    /** A foreign amount may not carry more decimals than its currency (plan §3 R-precision). */
    public static void assertDecimals(JdbcClient jdbc, String currency, BigDecimal amount) {
        Integer places = jdbc.sql("SELECT \"decimalPlaces\" FROM \"Currency\" WHERE upper(code) = ?").param(currency)
                .query(Integer.class).optional().orElse(2);
        if (amount.stripTrailingZeros().scale() > places) {
            throw ApiException.badRequest(currency + " amounts take at most " + places + " decimals");
        }
    }

    /** Refuse an account that is foreign-denominated (flows that stay functional-only, plan P-12). */
    public static void assertFunctional(JdbcClient jdbc, String organizationId, String accountId, String action) {
        Denomination d = of(jdbc, organizationId, accountId);
        if (d != null) {
            throw ApiException.badRequest(action + " cannot use account " + d.code() + ", which is held in "
                    + d.currency() + "; transfer the funds to a " + functional(jdbc, organizationId)
                    + " account through treasury first");
        }
    }

    /**
     * Account denomination lock (plan P-2): an account's currency cannot change once it has posted lines, or every
     * amount on it would change meaning.
     */
    public static void assertCurrencyChangeable(JdbcClient jdbc, String organizationId, String accountId,
            String newCurrency) {
        record Row(String code, String currency) {}
        Row row = jdbc.sql("SELECT code, \"currencyId\" FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?")
                .params(accountId, organizationId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2))).optional().orElse(null);
        if (row == null) {
            return;
        }
        String functional = functional(jdbc, organizationId);
        String from = norm(row.currency(), functional);
        String to = norm(newCurrency, functional);
        if (from.equals(to)) {
            return;
        }
        boolean posted = jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id ="
                        + " l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"accountId\" = ? AND"
                        + " e.status::text IN ('posted', 'reversed'))")
                .params(organizationId, accountId).query(Boolean.class).single();
        if (posted) {
            throw ApiException.badRequest("The currency of account " + row.code() + " cannot change once it has"
                    + " postings");
        }
    }

    private static String norm(String currency, String functional) {
        return currency == null || currency.isBlank() ? functional.toUpperCase() : currency.trim().toUpperCase();
    }

    /**
     * Engine invariant (plan §2 row 3): every line on a designated foreign account is in its denomination. Base-only
     * lines are allowed only for revaluations and designations. Undesignated (legacy) accounts keep today's behaviour.
     *
     * @param lineCurrencies per line: account id and the line's effective currency (null = functional, base-only)
     */
    static void assertDenominated(JdbcClient jdbc, String organizationId, List<String[]> lineCurrencies,
            String sourceType) {
        List<String> ids = new ArrayList<>();
        lineCurrencies.forEach(l -> ids.add(l[0]));
        String functional = functional(jdbc, organizationId);
        Map<String, String[]> foreign = new LinkedHashMap<>();
        jdbc.sql("SELECT a.id, a.code, upper(a.\"currencyId\") AS ccy FROM \"Account\" a JOIN \"AccountCategory\" c ON"
                        + " c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ? AND a.id = ANY(?::text[]) AND"
                        + " c.\"isCashEquivalent\" AND a.\"currencyId\" IS NOT NULL AND upper(a.\"currencyId\") <> upper(?)")
                .params(organizationId, ids.toArray(String[]::new), functional)
                .query((rs, n) -> foreign.put(rs.getString("id"), new String[] {rs.getString("code"),
                        rs.getString("ccy")}))
                .list();
        if (foreign.isEmpty()) {
            return;
        }
        boolean baseOnlyAllowed = sourceType != null && BASE_ONLY_SOURCES.contains(sourceType);
        Map<String, Boolean> designated = new LinkedHashMap<>();
        for (String[] l : lineCurrencies) {
            String[] acc = foreign.get(l[0]);
            if (acc == null) {
                continue;
            }
            String lineCurrency = l[1] == null ? null : l[1].toUpperCase();
            if (Objects.equals(lineCurrency, acc[1]) || (lineCurrency == null && baseOnlyAllowed)) {
                continue;
            }
            boolean isDesignated = designated.computeIfAbsent(l[0], id -> jdbc.sql("SELECT \"currencyDesignatedAt\""
                            + " IS NOT NULL FROM \"Account\" WHERE id = ?").param(id).query(Boolean.class).single()
                    || !hasUntaggedHistory(jdbc, organizationId, id, acc[1]));
            if (isDesignated) {
                throw ApiException.badRequest("Account " + acc[0] + " is held in " + acc[1] + "; post it in " + acc[1]);
            }
        }
    }

    /**
     * FX-2 C, transaction-level validation of foreign-tagged lines ({accountId, currency, partnerId}):
     * <ul>
     *   <li>a functional-currency cash, bank or mobile-money account never carries a foreign-currency line (the FX-1
     *       expense defect class: revaluation would revalue a functional account);</li>
     *   <li>a foreign line on a receivable or payable account names its partner, so it can be revalued and settled per
     *       partner.</li>
     * </ul>
     * Revaluation and designation entries are exempt.
     */
    static void assertForeignLines(JdbcClient jdbc, String organizationId, List<String[]> lines, String sourceType) {
        if (sourceType != null && BASE_ONLY_SOURCES.contains(sourceType)) {
            return;
        }
        String functional = functional(jdbc, organizationId);
        List<String> ids = new ArrayList<>();
        for (String[] l : lines) {
            if (l[1] != null && !l[1].equalsIgnoreCase(functional)) {
                ids.add(l[0]);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<String, String[]> kinds = new LinkedHashMap<>();
        jdbc.sql("SELECT a.id, a.code, CASE WHEN c.\"isCashEquivalent\" AND (a.\"currencyId\" IS NULL OR"
                        + " upper(a.\"currencyId\") = upper(?)) THEN 'functional_cash' WHEN c.key IN ('receivable',"
                        + " 'payable') THEN c.key END AS kind FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id ="
                        + " a.\"categoryId\" WHERE a.\"organizationId\" = ? AND a.id = ANY(?::text[])")
                .params(functional, organizationId, ids.toArray(String[]::new))
                .query((rs, n) -> kinds.put(rs.getString("id"), new String[] {rs.getString("code"),
                        rs.getString("kind")}))
                .list();
        for (String[] l : lines) {
            String[] k = kinds.get(l[0]);
            if (k == null || k[1] == null || l[1] == null || l[1].equalsIgnoreCase(functional)) {
                continue;
            }
            if ("functional_cash".equals(k[1])) {
                throw ApiException.badRequest("Account " + k[0] + " is held in " + functional + "; a " + l[1].toUpperCase()
                        + " line cannot post to it");
            }
            if (l.length > 2 && (l[2] == null || l[2].isBlank())) {
                throw ApiException.badRequest("receivable".equals(k[1])
                        ? "Foreign-currency receivable lines need a partner"
                        : "Foreign-currency payable lines need a partner");
            }
        }
    }

    private static BigDecimal r6(BigDecimal v) {
        return v.setScale(6, RoundingMode.HALF_UP);
    }
}
