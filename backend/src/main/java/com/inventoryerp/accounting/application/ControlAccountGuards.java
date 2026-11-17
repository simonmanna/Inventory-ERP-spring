package com.inventoryerp.accounting.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Control-account policy (V518), sub-ledger side (owner decision 2026-11-17, "close now").
 *
 * <p>Sub-ledger services post through {@link PostingService#post} in system context, so
 * {@link AccountResolverService#assertPostable} lets their lines reach a designated AR / AP control account. That is
 * right for the receivable / payable line a service derives itself (the partner override, else the
 * {@code accounts_receivable} / {@code accounts_payable} mapping), tagged with the partner and backed by an open item.
 * It is wrong for an account a USER picks or configures and the service merely carries through: a free invoice / bill
 * / credit-note line account, an expense allocation or accrual account, a write-off or bad-debt account, a payment's
 * tender account, or a product / expense category's income / expense account. A line there moves AR / AP without
 * moving any customer / supplier open item, so the general ledger drifts away from the sub-ledger. These guards refuse
 * such an account with a 400, when the document is created / updated and again when it is posted (drafts saved before
 * the policy).
 *
 * <p>Designation is the explicit {@code controlAccountType} ({@code ar} / {@code ap}), exactly as
 * {@link AccountResolverService#isReceivablePayableControl}; never the name or the category. The sanctioned exceptional
 * route stays the control-account adjustment journal entry.
 */
public final class ControlAccountGuards {

    private ControlAccountGuards() {}

    /** A designated AR / AP control account. */
    public record ControlAccount(String id, String code, String name, String type) {

        public boolean receivable() {
            return "ar".equals(type);
        }
    }

    /** The first designated AR / AP control account among {@code accountIds} (by code), or null. Nulls are ignored. */
    public static ControlAccount firstControl(JdbcClient jdbc, String organizationId, Collection<String> accountIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : accountIds) {
            if (id != null && !id.isBlank()) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return null;
        }
        return jdbc.sql("SELECT id, code, name, \"controlAccountType\"::text AS t FROM \"Account\""
                        + " WHERE \"organizationId\" = :org AND id IN (:ids) AND \"controlAccountType\"::text IN ('ar', 'ap')"
                        + " ORDER BY code LIMIT 1")
                .param("org", organizationId).param("ids", List.copyOf(ids))
                .query((rs, n) -> new ControlAccount(rs.getString("id"), rs.getString("code"), rs.getString("name"),
                        rs.getString("t")))
                .optional().orElse(null);
    }

    /** Refuse (400) when any of {@code accountIds} is a designated AR / AP control account. */
    public static void assertNotControl(JdbcClient jdbc, String organizationId, String context,
            Collection<String> accountIds) {
        ControlAccount hit = firstControl(jdbc, organizationId, accountIds);
        if (hit != null) {
            throw ApiException.badRequest(refusal(context, hit));
        }
    }

    /** Single-account form of {@link #assertNotControl(JdbcClient, String, String, Collection)}; null passes. */
    public static void assertNotControl(JdbcClient jdbc, String organizationId, String context, String accountId) {
        if (accountId != null) {
            assertNotControl(jdbc, organizationId, context, List.of(accountId));
        }
    }

    /**
     * The 400 message for a user-chosen / configured account on a designated control account. The manual-journal
     * message ({@code AccountResolverService.controlAccountRefusal}) points the user at "the originating document",
     * which is where they already are, so documents get their own wording.
     */
    public static String refusal(String context, ControlAccount a) {
        boolean ar = a.receivable();
        return context + ": account " + a.code() + " " + a.name() + " is a designated accounts "
                + (ar ? "receivable" : "payable") + " control account and cannot be used here. A line posted to it this"
                + " way would move the " + (ar ? "receivable" : "payable") + " without moving any "
                + (ar ? "customer" : "supplier") + " open item, so the general ledger would no longer reconcile with the "
                + (ar ? "customer" : "supplier") + " sub-ledger. Choose an income, expense or other non-control account."
                + " The " + (ar ? "receivable" : "payable") + " control account is reached only through the document's"
                + " own " + (ar ? "receivable" : "payable") + " line, "
                + (ar ? "customer payments, credit notes and invoice write-offs"
                        : "supplier payments, debit notes and bill write-offs")
                + " or, exceptionally, a control-account adjustment journal entry (journal_entry:control_adjust).";
    }

    /** Row read by {@link #assertWriteOffAccount}. */
    private record WriteOffRow(String code, String name, String type, boolean postable, boolean active,
            boolean deprecated, String classification, String categoryKey) {}

    /**
     * A write-off / bad-debt account a user names (or a setting / mapping supplies): an existing, postable, active,
     * non-deprecated account of the organization, not a designated AR / AP control account, on the profit-and-loss
     * (an expense or income account such as bad debt expense or purchase discounts received) or, when
     * {@code allowAllowance}, a contra-asset account (an allowance for doubtful debts the customer write-off is charged
     * against).
     */
    public static void assertWriteOffAccount(JdbcClient jdbc, String organizationId, String context, String accountId,
            boolean allowAllowance) {
        WriteOffRow a = accountId == null ? null : jdbc.sql("SELECT a.code, a.name, a.\"controlAccountType\"::text AS t,"
                        + " a.\"isPostable\" IS NOT FALSE AS postable, coalesce(a.\"isActive\", false) AS active,"
                        + " a.\"deprecatedAt\" IS NOT NULL AS deprecated, c.classification::text AS cls, c.key AS ckey"
                        + " FROM \"Account\" a LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE a.id = ? AND a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL")
                .params(accountId, organizationId)
                .query((rs, n) -> new WriteOffRow(rs.getString("code"), rs.getString("name"), rs.getString("t"),
                        rs.getBoolean("postable"), rs.getBoolean("active"), rs.getBoolean("deprecated"),
                        rs.getString("cls"), rs.getString("ckey")))
                .optional().orElse(null);
        if (a == null) {
            throw ApiException.badRequest(context + ": account not found in this organization");
        }
        if ("ar".equals(a.type()) || "ap".equals(a.type())) {
            throw ApiException.badRequest(refusal(context, new ControlAccount(accountId, a.code(), a.name(), a.type())));
        }
        if (!a.postable()) {
            throw ApiException.badRequest(context + ": account " + a.code() + " is a summary/group account and cannot be"
                    + " posted to");
        }
        if (!a.active() || a.deprecated()) {
            throw ApiException.badRequest(context + ": account " + a.code() + " is inactive or deprecated");
        }
        boolean profitAndLoss = "expense".equals(a.classification()) || "revenue".equals(a.classification());
        boolean allowance = allowAllowance && "contra_asset".equals(a.categoryKey());
        if (!profitAndLoss && !allowance) {
            throw ApiException.badRequest(context + ": account " + a.code() + " " + a.name() + " is not an expense or"
                    + " income account" + (allowAllowance ? " (or an allowance / contra-asset account)" : "")
                    + "; a write-off is charged to the profit and loss, e.g. bad debt expense or purchase discounts"
                    + " received");
        }
    }
}
