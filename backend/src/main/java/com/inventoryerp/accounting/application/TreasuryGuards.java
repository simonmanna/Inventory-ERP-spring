package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Shared money-movement guards (port of {@code treasury-guards.ts}): one rule set and one lock order —
 * accounts are row-locked in ascending id order before any balance is read.
 */
public final class TreasuryGuards {

    private TreasuryGuards() {}

    /** Active, postable account with its category key and cash-equivalent flag. */
    public record AccountRow(String id, String code, String name, String currencyId, boolean isPostable,
            String categoryKey, boolean isCashEquivalent) {}

    /** Row-lock the accounts (ascending id) so concurrent balance checks serialise. */
    public static void lockAccounts(JdbcClient jdbc, String organizationId, List<String> accountIds) {
        for (String id : new TreeSet<>(accountIds)) {
            List<String> rows = jdbc.sql("SELECT id FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(id, organizationId).query(String.class).list();
            if (rows.isEmpty()) {
                throw ApiException.badRequest("Account not found");
            }
        }
    }

    /** Posted ledger balance (debit − credit, base currency); reversed originals count. */
    public static BigDecimal postedBalance(JdbcClient jdbc, String organizationId, String accountId) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\"), 0) - coalesce(sum(l.\"baseCredit\"), 0) FROM \"JournalLine\" l"
                        + " JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ?"
                        + " AND l.\"accountId\" = ? AND e.status::text IN ('posted', 'reversed')")
                .params(organizationId, accountId).query(BigDecimal.class).single();
    }

    /** Negative cash, bank and wallet balances are never allowed. */
    public static void assertSufficientFunds(JdbcClient jdbc, String organizationId, String accountId, BigDecimal amount,
            String label) {
        BigDecimal balance = postedBalance(jdbc, organizationId, accountId);
        if (balance.compareTo(amount) < 0) {
            throw ApiException.badRequest(label + " has insufficient funds (available "
                    + balance.setScale(2, RoundingMode.HALF_UP).toPlainString() + ")");
        }
    }

    /** A register drawer account only moves through its own shift. */
    public static void assertNotDrawerAccount(JdbcClient jdbc, String organizationId, String accountId, String action) {
        jdbc.sql("SELECT code FROM \"CashRegister\" WHERE \"organizationId\" = ? AND \"defaultAccountId\" = ?"
                        + " AND \"deletedAt\" IS NULL LIMIT 1")
                .params(organizationId, accountId).query(String.class).optional()
                .ifPresent(code -> {
                    throw ApiException.badRequest(action + " cannot use the drawer account of register " + code
                            + ". Record it from that register's shift (pay-in, pay-out or banking).");
                });
    }

    /** Active, postable account in this organization, with its category. */
    public static AccountRow requireAccount(JdbcClient jdbc, String organizationId, String accountId, String label) {
        AccountRow account = jdbc.sql("SELECT a.id, a.code, a.name, a.\"currencyId\", a.\"isPostable\", c.key AS category_key,"
                        + " coalesce(c.\"isCashEquivalent\", false) AS cash_eq FROM \"Account\" a"
                        + " LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE a.id = ? AND a.\"organizationId\" = ? AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL")
                .params(accountId, organizationId)
                .query((rs, n) -> new AccountRow(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("currencyId"), rs.getBoolean("isPostable"), rs.getString("category_key"),
                        rs.getBoolean("cash_eq")))
                .optional().orElse(null);
        if (account == null) {
            throw ApiException.badRequest(label + " not found or inactive");
        }
        if (!account.isPostable()) {
            throw ApiException.badRequest(label + " " + account.code() + " is a header account and cannot be posted to");
        }
        return account;
    }

    /**
     * Stable identity for one business operation: derived from (organization, Idempotency-Key) when running
     * under a key, so a retried request reuses the same posting key; random otherwise.
     */
    public static String operationId() {
        BusinessOutcome.Operation op = BusinessOutcome.current();
        if (op == null) {
            return UUID.randomUUID().toString();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((op.organizationId() + "\n" + op.key()).getBytes(StandardCharsets.UTF_8));
            String h = HexFormat.of().formatHex(digest);
            return h.substring(0, 8) + "-" + h.substring(8, 12) + "-4" + h.substring(13, 16) + "-a" + h.substring(17, 20)
                    + "-" + h.substring(20, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
