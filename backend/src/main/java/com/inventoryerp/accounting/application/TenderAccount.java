package com.inventoryerp.accounting.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One tender-account resolver for POS, invoice collections and refunds (port of {@code tender-account.ts});
 * no electronic-to-cash fallback.
 */
public final class TenderAccount {

    private TenderAccount() {}

    /** Tender kinds a POS payment method may be configured as. */
    public static final List<String> POS_TENDER_METHODS = List.of("cash", "bank", "card", "mobile_money", "store_credit");

    /** Account category keys that may back each tender method. */
    public static final Map<String, List<String>> ALLOWED_CATEGORIES_BY_METHOD = Map.of(
            "cash", List.of("cash", "petty_cash"),
            "bank", List.of("bank"),
            "cheque", List.of("bank"),
            "card", List.of("bank", "current_asset"),
            "mobile_money", List.of("mobile_money"),
            "store_credit", List.of("current_liabilities", "other_current_liabilities"));

    private static final Map<String, String> MAPPING_BY_METHOD = Map.of("cash", "default_cash", "bank", "default_bank",
            "cheque", "default_bank", "card", "card_clearing", "mobile_money", "mobile_money", "store_credit", "store_credit");

    /** Resolved tender: account, journal and (for a register shift) the session id. */
    public record Resolved(String accountId, String journalCode, String cashSessionId) {}

    public static Resolved resolve(JdbcClient jdbc, AccountDeterminationService determination, String organizationId,
            String method, String accountId, String cashSessionId) {
        if (!List.of("cash", "bank", "cheque", "card", "mobile_money", "store_credit").contains(method)) {
            throw ApiException.badRequest("Unsupported payment method");
        }
        String sessionId = null;
        if (cashSessionId != null) {
            jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(cashSessionId, organizationId).query(String.class).list();
            record Session(String id, String status, String drawer) {}
            Session session = jdbc.sql("SELECT s.id, s.status::text AS status,"
                            + " coalesce(s.\"drawerAccountId\", r.\"defaultAccountId\") AS drawer FROM \"CashSession\" s"
                            + " JOIN \"CashRegister\" r ON r.id = s.\"cashRegisterId\" WHERE s.id = ? AND s.\"organizationId\" = ?")
                    .params(cashSessionId, organizationId)
                    .query((rs, n) -> new Session(rs.getString("id"), rs.getString("status"), rs.getString("drawer")))
                    .optional().orElse(null);
            if (session == null || !"open".equals(session.status())) {
                throw ApiException.badRequest("The selected register session is closed or unavailable. Resolve this sale"
                        + " before changing its register.");
            }
            sessionId = session.id();
            if ("cash".equals(method)) {
                if (accountId != null && !accountId.equals(session.drawer())) {
                    throw ApiException.badRequest("Cash must use the selected register’s drawer account");
                }
                accountId = session.drawer();
            }
        }
        if (accountId == null) {
            try {
                accountId = determination.mapped(MAPPING_BY_METHOD.get(method));
            } catch (ApiException e) {
                throw ApiException.badRequest("Select a named " + method.replaceFirst("_", " ")
                        + " account or configure its account mapping");
            }
        }
        record Acct(String id, String name, String currencyId, String category) {}
        Acct account = jdbc.sql("SELECT a.id, a.name, a.\"currencyId\", c.key FROM \"Account\" a"
                        + " LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE a.id = ? AND a.\"organizationId\" = ? AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL")
                .params(accountId, organizationId)
                .query((rs, n) -> new Acct(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional().orElse(null);
        if (account == null) {
            throw ApiException.badRequest("Payment account is inactive or unavailable");
        }
        if (account.currencyId() != null) {
            String orgCurrency = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(organizationId)
                    .query(String.class).optional().orElse(null);
            if (orgCurrency != null && !account.currencyId().equals(orgCurrency)) {
                throw ApiException.badRequest("POS tenders must use the organization currency; convert foreign currency"
                        + " through treasury first");
            }
        }
        if ("store_credit".equals(method)) {
            if (!account.id().equals(determination.mapped("store_credit"))) {
                throw ApiException.badRequest("Store credit must use its configured liability account");
            }
        } else if (!ALLOWED_CATEGORIES_BY_METHOD.get(method).contains(account.category())) {
            throw ApiException.badRequest("Account " + account.name() + " is not a " + method.replaceFirst("_", " ") + " account");
        }
        if ("card".equals(method) && "current_asset".equals(account.category())
                && !account.id().equals(determination.mapped("card_clearing"))) {
            throw ApiException.badRequest("Card clearing must use the configured card clearing account");
        }
        String journal = "cash".equals(method) ? "CASH" : "store_credit".equals(method) ? "GEN" : "BANK";
        return new Resolved(account.id(), journal, sessionId);
    }
}
