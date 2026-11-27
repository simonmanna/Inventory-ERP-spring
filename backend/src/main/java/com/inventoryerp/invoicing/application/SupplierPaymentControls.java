package com.inventoryerp.invoicing.application;

import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The controls every payment to a supplier passes, whichever route records it ({@code POST /supplier-payments} or
 * {@code POST /procurement/purchase-orders/:id/pay}) — purchase readiness fixes H5–H7 (owner-approved 2026-11-27):
 * <ul>
 * <li>the supplier's qualification status: a suspended or blacklisted supplier is not paid;</li>
 * <li>the supplier bank account: a named account must belong to the supplier and be verified (dual control); under
 * {@code procurement.supplierBankVerification = block} (default) a bank transfer must name one;</li>
 * <li>the {@link SupplierPaymentGuard}s (ADR-S074 subcontractor compliance);</li>
 * <li>the {@code supplier_payment} approval gate, on the payment amount in the functional currency; an approved
 * request authorises exactly one payment of at most the approved amount and is consumed when that payment posts.</li>
 * </ul>
 */
@Component
public class SupplierPaymentControls {

    /** The setting that makes a bank transfer to a supplier name a verified supplier bank account. */
    public static final String BANK_VERIFICATION = "procurement.supplierBankVerification";

    private final JdbcClient jdbc;
    private final ApprovalsService approvals;
    private final TransactionTemplate newTx;
    private List<SupplierPaymentGuard> guards = List.of();
    private SettingResolverService settings;

    public SupplierPaymentControls(JdbcClient jdbc, ApprovalsService approvals, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired(required = false)
    void setGuards(List<SupplierPaymentGuard> guards) {
        this.guards = guards;
    }

    @Autowired(required = false)
    void setSettings(SettingResolverService settings) {
        this.settings = settings;
    }

    /** H7 (a): refuse a supplier whose qualification status blocks payment (suspended or blacklisted). */
    public void assertPayable(String partnerId) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT pt.name, s.\"lifecycleStatus\" AS status, s.\"statusReason\""
                + " AS reason FROM \"Partner\" pt LEFT JOIN \"SupplierProfile\" s ON s.\"partnerId\" = pt.id AND"
                + " s.\"organizationId\" = pt.\"organizationId\" WHERE pt.id = ? AND pt.\"organizationId\" = ?")
                .params(partnerId, TenantContext.organizationId()));
        if (p == null) {
            return;
        }
        String status = (String) p.get("status");
        if ("suspended".equals(status) || "blacklisted".equals(status)) {
            throw ApiException.badRequest("Supplier " + p.get("name") + " is " + status
                    + (p.get("reason") != null ? " (" + p.get("reason") + ")" : "")
                    + "; payments to a suspended or blacklisted supplier are refused");
        }
    }

    /**
     * H7 (b, c): the supplier bank account a payment goes to. A named account must belong to the supplier and be
     * verified; a bank transfer ({@code method = bank}) must name one unless the setting is {@code off}. Returns the
     * account id (or null).
     */
    public String bankAccount(String partnerId, String method, String supplierBankAccountId) {
        String id = supplierBankAccountId == null || supplierBankAccountId.isBlank() ? null
                : supplierBankAccountId.trim();
        if (id == null) {
            if ("bank".equals(method) && verificationRequired()) {
                throw ApiException.badRequest("A bank transfer to a supplier must name one of the supplier's verified"
                        + " bank accounts (supplierBankAccountId). Register the account on the supplier and have"
                        + " someone else verify it first.");
            }
            return null;
        }
        Map<String, Object> acc = SqlRows.one(jdbc.sql("SELECT \"partnerId\", status FROM \"SupplierBankAccount\" WHERE"
                + " id = ? AND \"organizationId\" = ?").params(id, TenantContext.organizationId()));
        if (acc == null || !partnerId.equals(acc.get("partnerId"))) {
            throw ApiException.badRequest("The supplier bank account does not belong to this supplier");
        }
        if (!"verified".equals(String.valueOf(acc.get("status")))) {
            throw ApiException.badRequest("The supplier bank account is " + String.valueOf(acc.get("status"))
                    .replace('_', ' ') + "; only a verified bank account can receive a payment");
        }
        return id;
    }

    private boolean verificationRequired() {
        String mode = settings == null ? null : settings.resolveString(BANK_VERIFICATION,
                SettingResolverService.SettingContext.EMPTY);
        return !"off".equals(mode);
    }

    /** ADR-S074 guards over the vendor bills the payment settles (possibly none). */
    public void runGuards(String partnerId, List<String> billIds) {
        guards.forEach(g -> g.beforeSupplierPayment(partnerId, billIds == null ? List.of() : billIds));
    }

    /**
     * H5/H6: the {@code supplier_payment} approval gate on {@code functionalAmount}. Refused (403, with the request id)
     * while no approval covers it; returns the approved request the payment must {@link #consume}, or null when no
     * approval workflow applies.
     */
    public String requireApproval(String partnerId, BigDecimal functionalAmount, String method) {
        Map<String, Object> snapshot = Obj.of("amount", JsNumbers.js(functionalAmount), "partnerId", partnerId,
                "paymentMethod", method != null ? method : "cash");
        Map<String, Object> approval = newTx.execute(s -> approvals.checkOrRequestApproval("supplier_payment",
                partnerId, snapshot));
        if (approval == null) {
            return null;
        }
        if (Boolean.TRUE.equals(approval.get("needsApproval"))) {
            throw ApiException.forbidden("Vendor payment requires approval. Request ID: " + approval.get("requestId")
                    + ". Please have an authorized person approve it via the approvals endpoint.");
        }
        return (String) approval.get("requestId");
    }

    /** H6: use up the approval in the payment's own transaction (refused when another payment already used it). */
    public void consume(String approvalRequestId, String paymentRef) {
        if (approvalRequestId != null) {
            approvals.consumeApproval(approvalRequestId, paymentRef);
        }
    }
}
