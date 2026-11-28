package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PosPaymentMethodService;
import com.inventoryerp.credit.application.CreditService;
import com.inventoryerp.invoicing.application.BranchSaleRecognitionService;
import com.inventoryerp.invoicing.application.BranchSaleRecognitionService.SaleLine;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.PaymentService;
import com.inventoryerp.invoicing.application.PaymentService.AllocationInput;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-S094 C3 (§5): the branch cash sale as a Sale of type {@code cash}. The draft lives on {@code SalesOrder} (drafted
 * from the branch's {@code BranchSalesProfile} by {@link SalesOrderService#create}); this service owns its tender
 * lines ({@code SalesOrderPayment}), its timeline ({@code SalesOrderEvent}) and its confirm, which runs every step of
 * F1 in one transaction (§5.2): the counter pickup (customer collection, stock issue, proof of handover), the invoice
 * from the sale, one receipt per verified tender allocated to it, the derived state and the fiscal outbox row. Nothing
 * is held on 1215 / 2216 / customer deposits (AD-6): the sale is invoiced and settled at the counter.
 */
@Service
public class SalesCashSaleService {

    /** Tender methods of a cash sale (§5.1). */
    static final Set<String> METHODS = Set.of("cash", "mobile_money", "bank", "card");
    private static final Set<String> PAYMENT_FIELDS = Set.of("method", "provider", "amount", "amountTendered",
            "reference", "paymentModeId", "paymentDate", "payerName", "payerAccount", "notes", "attachmentIds");
    private static final Set<String> HANDOVER_FIELDS = Set.of("affirmed", "receivedByName", "receivedByPhone",
            "signature", "notes", "lines");

    private final SalesDb db;
    private final SalesOrderService orders;
    private final BranchSalesProfileService profiles;
    private final BranchSaleRecognitionService recognition;
    private final PosPaymentMethodService paymentModes;
    private final AccountDeterminationService determination;
    private final SalesDeliveryService deliveries;
    private final SalesBillingService billing;
    private final InvoiceService invoices;
    private final PaymentService payments;
    private final CashSaleFiscalService fiscal;
    private final CreditService credit;
    private final AuditService audit;
    private final PermissionService permissions;

    public SalesCashSaleService(SalesDb db, SalesOrderService orders, BranchSalesProfileService profiles,
            BranchSaleRecognitionService recognition, PosPaymentMethodService paymentModes,
            AccountDeterminationService determination, SalesDeliveryService deliveries, SalesBillingService billing,
            InvoiceService invoices, PaymentService payments, CashSaleFiscalService fiscal, CreditService credit,
            AuditService audit, PermissionService permissions) {
        this.db = db;
        this.orders = orders;
        this.profiles = profiles;
        this.recognition = recognition;
        this.paymentModes = paymentModes;
        this.determination = determination;
        this.deliveries = deliveries;
        this.billing = billing;
        this.invoices = invoices;
        this.payments = payments;
        this.fiscal = fiscal;
        this.credit = credit;
        this.audit = audit;
        this.permissions = permissions;
    }

    // ------------------------------------------------------------------ reads

    /**
     * Payment modes a cash sale can take: the organization's active cash, mobile-money, bank and card modes whose
     * receiving account can hold the sale's money.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> paymentModes() {
        return paymentModes.terminalPaymentMethods().stream().filter(m -> METHODS.contains(m.kind()))
                .filter(m -> m.accountId() == null || recognition.tenderProblems(m.label(), m.kind(), m.accountId())
                        .isEmpty())
                .map(PosPaymentMethodService.View::toMap).toList();
    }

    /** The sale's tender lines, oldest first. */
    List<Map<String, Object>> tenders(String orderId) {
        return db.rows("SELECT p.*, a.code AS \"accountCode\", a.name AS \"accountName\", pay.\"paymentNumber\" FROM"
                + " \"SalesOrderPayment\" p LEFT JOIN \"Account\" a ON a.id = p.\"accountId\" LEFT JOIN \"Payment\" pay ON"
                + " pay.id = p.\"paymentId\" WHERE p.\"orderId\" = ? AND p.\"organizationId\" = ? ORDER BY p.\"createdAt\","
                + " p.id", orderId, org());
    }

    /** The cash sale's timeline, oldest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(String orderId) {
        orders.require(orderId);
        return db.rows("SELECT e.*, COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\", u.\"lastName\")), ''), u.email)"
                + " AS \"userName\" FROM \"SalesOrderEvent\" e LEFT JOIN \"User\" u ON u.id = e.\"userId\" WHERE"
                + " e.\"orderId\" = ? AND e.\"organizationId\" = ? ORDER BY e.\"createdAt\", e.id", orderId, org());
    }

    /** Captured (not failed) tender total of a draft, for the balance due. */
    BigDecimal captured(String orderId) {
        return dec(db.row("SELECT COALESCE(sum(amount), 0) AS paid FROM \"SalesOrderPayment\" WHERE \"orderId\" = ? AND"
                + " \"organizationId\" = ? AND \"verificationStatus\" <> 'failed'", orderId, org()).get("paid"));
    }

    /** Record one line of the cash sale's timeline (inside the caller's transaction). */
    void event(String orderId, String action, String from, String to, String note, Map<String, Object> data) {
        db.update("INSERT INTO \"SalesOrderEvent\" (id, \"organizationId\", \"orderId\", action, \"fromStatus\", \"toStatus\","
                + " note, data, \"userId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)", SalesDb.newId(), org(), orderId,
                action, from, to, note, data == null ? null : EntityJson.toJson(data), user());
    }

    // ------------------------------------------------------------------ tenders (§5.1)

    /**
     * Capture a tender line on a draft. With {@code paymentModeId} the organization's payment mode decides the method,
     * provider and receiving account; otherwise the account comes from the branch profile (card: the
     * {@code card_clearing} mapping). A client-sent account is never trusted. Physical cash may carry
     * {@code amountTendered}: the change is recorded and never posted. The captured total never passes the sale total
     * (OD-9: excess non-cash payment is refused). The collector's capture verifies the line unless the branch requires
     * segregation of duties.
     */
    @Transactional
    public Map<String, Object> addPayment(String id, Map<String, Object> in) {
        unknownFields(in, PAYMENT_FIELDS);
        Map<String, Object> o = draft(id);
        Map<String, Object> profile = profiles.get(str(o.get("profileId")));
        String method;
        String provider;
        String account;
        String modeId = blank(str(in.get("paymentModeId"))) ? null : str(in.get("paymentModeId"));
        String modeLabel = null;
        boolean referenceRequired = false;
        if (modeId != null) {
            PosPaymentMethodService.View mode = paymentModes.terminalPaymentMethods().stream()
                    .filter(m -> m.id().equals(modeId)).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("Payment mode not found"));
            method = mode.kind();
            if (!METHODS.contains(method)) {
                throw ApiException.badRequest("Payment mode " + mode.label() + " cannot be used for cash sales");
            }
            modeLabel = mode.label();
            referenceRequired = mode.requiresReference();
            provider = "mobile_money".equals(method)
                    ? (blank(mode.provider()) ? mode.code() : mode.provider()).trim().toLowerCase() : null;
            account = mode.accountId() != null ? mode.accountId() : profileAccount(profile, method, provider);
        } else {
            method = str(in.get("method"));
            if (!METHODS.contains(method)) {
                throw ApiException.badRequest("method must be one of: bank, card, cash, mobile_money");
            }
            provider = "mobile_money".equals(method) && !blank(str(in.get("provider"))) ? str(in.get("provider")).trim()
                    .toLowerCase() : null;
            account = profileAccount(profile, method, provider);
        }
        List<String> problems = recognition.tenderProblems(modeLabel == null ? method.replace('_', ' ') + " account"
                : modeLabel, method, account);
        if (!problems.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", problems));
        }
        BigDecimal amount = dec(in.get("amount")).setScale(6, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("amount must be greater than 0");
        }
        BigDecimal tendered = null;
        BigDecimal change = BigDecimal.ZERO;
        if (in.get("amountTendered") != null) {
            if (!"cash".equals(method)) {
                throw ApiException.badRequest("amountTendered applies to cash only");
            }
            tendered = dec(in.get("amountTendered")).setScale(6, RoundingMode.HALF_UP);
            if (tendered.compareTo(amount) < 0) {
                throw ApiException.badRequest("amountTendered must not be less than amount");
            }
            change = tendered.subtract(amount);
        }
        BigDecimal captured = captured(id);
        BigDecimal total = dec(o.get("total"));
        if (captured.add(amount).compareTo(total) > 0) {
            throw ApiException.badRequest("Payments (" + JsNumbers.js(captured.add(amount)) + ") would exceed the sale"
                    + " total (" + JsNumbers.js(total) + "); the balance due is " + JsNumbers.js(total.subtract(captured)
                            .max(BigDecimal.ZERO)));
        }
        LocalDate paymentDate = SalesDb.date(str(in.get("paymentDate")));
        if (paymentDate != null && paymentDate.isAfter(LocalDate.now(BusinessZone.get()))) {
            throw ApiException.badRequest("paymentDate must not be in the future");
        }
        String reference = trimmed(in.get("reference"));
        if ((!"cash".equals(method) || referenceRequired) && reference == null) {
            throw ApiException.badRequest("reference should not be empty for " + (modeLabel == null
                    ? method.replace('_', ' ') : modeLabel) + " payments");
        }
        List<String> attachmentIds = SalesDb.strings(in.get("attachmentIds")).stream().filter(a -> !blank(a))
                .distinct().toList();
        if (!attachmentIds.isEmpty() && db.rows("SELECT id FROM \"DmsDocument\" WHERE \"organizationId\" = ? AND"
                + " id = ANY(?)", org(), attachmentIds).size() != attachmentIds.size()) {
            throw ApiException.badRequest("Attachment not found");
        }
        Map<String, Object> row = Obj.of("orderId", id, "method", method, "provider", provider, "paymentModeId", modeId,
                "paymentModeLabel", modeLabel, "accountId", account, "amount", amount, "amountTendered", tendered,
                "changeGiven", change, "reference", reference, "paymentDate", paymentDate, "payerName",
                trimmed(in.get("payerName")), "payerAccount", trimmed(in.get("payerAccount")), "notes",
                trimmed(in.get("notes")), "attachmentIds", attachmentIds, "collectedById", user());
        boolean autoVerify = !segregated(profile);
        if (autoVerify) {
            row.putAll(Obj.of("verificationStatus", "verified", "verifiedById", user(), "verifiedAt", Instant.now(),
                    "verificationSource", "collector"));
        }
        try {
            db.insert("SalesOrderPayment", row);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("Payment reference " + reference + " is already recorded");
        }
        event(id, autoVerify ? "payment_verified" : "payment_added", null, null, describe(method, provider, amount,
                reference), Obj.of("amount", JsNumbers.js(amount), "changeGiven", JsNumbers.js(change)));
        return orders.get(id);
    }

    /** Remove a tender line from a draft (a failed line, or one captured by mistake). */
    @Transactional
    public Map<String, Object> removePayment(String id, String paymentId) {
        draft(id);
        Map<String, Object> pay = payment(id, paymentId);
        db.update("DELETE FROM \"SalesOrderPayment\" WHERE id = ? AND \"organizationId\" = ?", paymentId, org());
        event(id, "payment_removed", null, null, describe(str(pay.get("method")), str(pay.get("provider")),
                dec(pay.get("amount")), str(pay.get("reference"))), null);
        return orders.get(id);
    }

    /**
     * Verify (or fail) a pending tender line. Cash: the confirmer ({@code sales:cash_sale:confirm}) or a verifier;
     * mobile money, bank and card: a verifier ({@code sales:cash_sale:verify_payment}). With segregation the verifier
     * is never the collector. A failed line stays as evidence, out of the paid total, until it is removed.
     */
    @Transactional
    public Map<String, Object> verifyPayment(String id, String paymentId, Map<String, Object> in) {
        unknownFields(in, Set.of("status", "note"));
        Map<String, Object> o = draft(id);
        Map<String, Object> pay = payment(id, paymentId);
        if (!"pending".equals(pay.get("verificationStatus"))) {
            throw ApiException.badRequest("Only a pending payment can be verified or failed (this one is "
                    + pay.get("verificationStatus") + ")");
        }
        String status = blank(str(in.get("status"))) ? "verified" : str(in.get("status"));
        if (!Set.of("verified", "failed").contains(status)) {
            throw ApiException.badRequest("status must be one of: failed, verified");
        }
        boolean verifier = TenantContext.has(Permissions.SALES__CASH_SALE_VERIFY_PAYMENT);
        String source;
        if ("cash".equals(pay.get("method"))) {
            if (!verifier && !TenantContext.has(Permissions.SALES__CASH_SALE_CONFIRM)) {
                throw ApiException.forbidden("Verifying cash needs sales:cash_sale:confirm or sales:cash_sale:verify_payment");
            }
            source = verifier ? "verifier" : "confirmer_cash";
        } else {
            if (!verifier) {
                throw ApiException.forbidden("Verifying " + str(pay.get("method")).replace('_', ' ')
                        + " needs sales:cash_sale:verify_payment");
            }
            source = "verifier";
        }
        if (segregated(profiles.get(str(o.get("profileId")))) && Objects.equals(str(pay.get("collectedById")), user())) {
            throw ApiException.forbidden("Segregation of duties: the collector cannot verify the payment");
        }
        db.update("UPDATE \"SalesOrderPayment\" SET \"verificationStatus\" = ?, \"verifiedById\" = ?, \"verifiedAt\" = " + NOW
                + ", \"verificationSource\" = ?, \"verificationNote\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ?", status, user(), source, trimmed(in.get("note")), paymentId, org());
        event(id, "payment_" + status, null, null, describe(str(pay.get("method")), str(pay.get("provider")),
                dec(pay.get("amount")), str(pay.get("reference"))), null);
        return orders.get(id);
    }

    private String profileAccount(Map<String, Object> profile, String method, String provider) {
        if ("card".equals(method)) {
            String card = determination.mappedOptional("card_clearing");
            if (card == null) {
                throw ApiException.badRequest("Card payments need the card_clearing account mapping or a card payment"
                        + " mode with an account");
            }
            return card;
        }
        if ("mobile_money".equals(method) && provider == null) {
            throw ApiException.badRequest("provider should not be empty for mobile money payments");
        }
        return BranchSalesProfileService.tenderAccount(profile, method, provider);
    }

    private Map<String, Object> payment(String id, String paymentId) {
        Map<String, Object> pay = db.row("SELECT * FROM \"SalesOrderPayment\" WHERE id = ? AND \"orderId\" = ? AND"
                + " \"organizationId\" = ?", paymentId, id, org());
        if (pay == null) {
            throw ApiException.notFound("Payment not found");
        }
        return pay;
    }

    /** The locked draft cash sale (tenders change only while it is a draft). */
    private Map<String, Object> draft(String id) {
        Map<String, Object> o = orders.lock(id);
        if (!"cash".equals(o.get("saleType"))) {
            throw ApiException.badRequest("Tender lines belong to cash sales");
        }
        if (!"draft".equals(o.get("status"))) {
            throw ApiException.badRequest("The payments of a confirmed cash sale cannot change");
        }
        return o;
    }

    private static boolean segregated(Map<String, Object> profile) {
        return Boolean.TRUE.equals(profile.get("requireSegregation"));
    }

    private static String describe(String method, String provider, BigDecimal amount, String reference) {
        return method.replace('_', ' ') + (provider == null ? "" : " (" + provider + ")") + " " + JsNumbers.js(amount)
                + (reference == null ? "" : " ref " + reference);
    }

    // ------------------------------------------------------------------ confirm (§5.2 T1)

    /** The handover block of a confirm (§5.3): who received the goods and the serial numbers handed over per line. */
    private record Handover(String receivedBy, String receiverPhone, String signature, String notes,
            Map<String, List<String>> serials) {}

    /**
     * Confirm a draft cash sale with its proof of handover (§5.2, F1): every tender verified and Σ verified tenders =
     * the sale total; then, in this one transaction, the sale turns confirmed (a prepaid credit decision, OD-6), the
     * goods are handed over (customer collection from the profile's location: stock issue, proof of delivery), the
     * invoice from the sale is created and posted, each verified tender becomes a receipt allocated to it, the derived
     * state is recomputed (the sale is done: delivered, invoiced, paid) and the fiscal document is queued. Any failure
     * rolls all of it back.
     */
    @Transactional
    public Map<String, Object> confirm(String id, Map<String, Object> in) {
        Map<String, Object> o = orders.confirmable(id);
        if (!"cash".equals(o.get("saleType"))) {
            throw ApiException.badRequest("Only a cash sale is confirmed with a handover");
        }
        Map<String, Object> body = in == null ? Map.of() : in;
        unknownFields(body, Set.of("handover"));
        Map<String, Object> profile = profiles.get(str(o.get("profileId")));
        if (!Boolean.TRUE.equals(profile.get("isActive"))) {
            throw ApiException.badRequest("This branch has no active branch sales profile");
        }
        profiles.requireValidForSale(profile);
        List<Map<String, Object>> lines = db.rows("SELECT * FROM \"SalesOrderLine\" WHERE \"orderId\" = ? ORDER BY"
                + " \"sortOrder\", id", id);
        Handover handover = handover(body.get("handover"), lines);
        String partnerId = str(o.get("partnerId"));
        String number = str(o.get("number"));
        BigDecimal total = dec(o.get("total"));
        // tenders: none pending; failed lines are left out; Σ verified = total (§5.1)
        List<Map<String, Object>> verified = new ArrayList<>();
        BigDecimal paid = BigDecimal.ZERO;
        for (Map<String, Object> t : db.rows("SELECT * FROM \"SalesOrderPayment\" WHERE \"orderId\" = ? AND"
                + " \"organizationId\" = ? ORDER BY \"createdAt\", id", id, org())) {
            if ("pending".equals(t.get("verificationStatus"))) {
                throw ApiException.badRequest("Every payment must be verified before the sale is confirmed ("
                        + describe(str(t.get("method")), str(t.get("provider")), dec(t.get("amount")), str(t.get(
                                "reference"))) + " is pending)");
            }
            if (!"verified".equals(t.get("verificationStatus"))) {
                continue;
            }
            if (segregated(profile) && (Objects.equals(str(t.get("verifiedById")), str(t.get("collectedById")))
                    || Objects.equals(str(t.get("verifiedById")), user()))) {
                throw ApiException.forbidden("Segregation of duties: payments must be verified by someone other than"
                        + " the collector and the confirmer");
            }
            List<String> problems = recognition.tenderProblems(str(t.get("method")).replace('_', ' ') + " account",
                    str(t.get("method")), str(t.get("accountId")));
            if (!problems.isEmpty()) {
                throw ApiException.badRequest(String.join("; ", problems));
            }
            verified.add(t);
            paid = paid.add(dec(t.get("amount")));
        }
        if (paid.compareTo(total) < 0) {
            throw ApiException.badRequest("The sale is not fully paid: " + JsNumbers.js(paid) + " of " + JsNumbers.js(
                    total));
        }
        if (paid.compareTo(total) > 0) {
            throw ApiException.badRequest("Payments (" + JsNumbers.js(paid) + ") exceed the sale total ("
                    + JsNumbers.js(total) + "); remove the excess before confirming");
        }
        List<SaleLine> sale = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            if (l.get("productId") != null) {
                sale.add(new SaleLine(str(l.get("productId")), str(l.get("taxId")), dec(l.get("lineSubtotal")),
                        dec(l.get("lineTax"))));
            }
        }
        List<String> accountProblems = recognition.lineAccountProblems(partnerId, sale);
        if (!accountProblems.isEmpty()) {
            throw ApiException.badRequest("Branch sales profile is not valid: " + String.join("; ", accountProblems));
        }
        // S3 — confirmed; a prepaid sale adds no credit exposure (OD-6)
        CreditService.Decision decision = credit.prepaid("order_confirm", partnerId, "sales_order", id, number, total);
        orders.markCashConfirmed(o, decision.id());
        // S4–S6 — counter pickup: customer collection, stock issue, proof of handover, derived fulfilment
        Map<String, Object> load = deliveries.pickup(id, str(o.get("locationId")), handover.serials(),
                handover.receivedBy(), handover.receiverPhone(), handover.signature(), handover.notes());
        // S7–S8 — the invoice from the sale, posted as a system step
        Map<String, Object> draftInvoice = billing.createInvoice(id, Obj.of("mode", "regular"));
        String invoiceId = str(draftInvoice.get("id"));
        postAsSystemStep(invoiceId);
        String invoiceNumber = str(db.row("SELECT \"documentNumber\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?",
                invoiceId, org()).get("documentNumber"));
        // S9 — one receipt per verified tender, allocated to the invoice
        String today = LocalDate.now(BusinessZone.get()).toString();
        List<String> receipts = new ArrayList<>();
        for (Map<String, Object> t : verified) {
            BigDecimal amount = dec(t.get("amount"));
            Map<String, Object> receipt = payments.createReceipt(new PaymentInput(partnerId, today, amount,
                    str(t.get("method")), str(t.get("accountId")), str(t.get("reference")), str(o.get("branchId")),
                    List.of(new AllocationInput(invoiceId, null, amount)), null, false, null, null), true, false);
            db.update("UPDATE \"SalesOrderPayment\" SET status = 'posted', \"paymentId\" = ?, \"updatedAt\" = " + NOW
                    + " WHERE id = ? AND \"organizationId\" = ?", receipt.get("id"), t.get("id"), org());
            receipts.add(str(receipt.get("paymentNumber")));
        }
        // S10 — derived state in this transaction (events are notifications only, TA-3)
        billing.recomputeInvoiced(id);
        orders.refreshStatus(id);
        // S11 — fiscal outbox (no network call inside the transaction unless the provider is local)
        Map<String, Object> sold = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", id,
                org());
        fiscal.requestForSale(sold, "issue", invoiceId, Obj.of("invoiceNumber", invoiceNumber,
                "saleNumber", number, "total", JsNumbers.js(total), "customerName", str(sold.get("customerName")),
                "customerTin", str(sold.get("customerTin"))), null);
        // S12 — timeline and the operation's outcome
        String status = str(db.row("SELECT status FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", id,
                org()).get("status"));
        event(id, "confirm", "draft", status, "Handed over to " + handover.receivedBy() + "; invoice " + invoiceNumber,
                Obj.of("invoiceId", invoiceId, "invoiceNumber", invoiceNumber, "loadId", load.get("id"), "receipts",
                        receipts));
        audit.record(AuditInput.of("SalesOrder", id, "update", null, Obj.of("cashSale", "confirmed", "invoiceId",
                invoiceId, "receipts", receipts)));
        BusinessOutcome.record(db.jdbc(), Obj.of("id", id, "number", number, "invoiceId", invoiceId, "invoiceNumber",
                invoiceNumber), true);
        return orders.get(id);
    }

    /**
     * ADR-S094 §5.2 S8: the invoice from a cash sale is posted as a step of the confirm, which
     * {@code sales:cash_sale:confirm} authorizes, not as the cashier's own invoicing action. The invoice workflow checks
     * {@code invoice:post}, so for this one call the caller's context (same user, same organization) carries it too.
     */
    private void postAsSystemStep(String invoiceId) {
        List<String> held = permissions.current();
        if (held.contains(Permissions.INVOICE__POST)) {
            invoices.post(invoiceId);
            return;
        }
        TenantContext.TenantStore cur = TenantContext.current().orElseThrow();
        TenantContext.TenantStore step = new TenantContext.TenantStore(cur.organizationId(), cur.userId(), cur.email(),
                cur.posUserId(), cur.deviceId(), cur.branchId());
        List<String> granted = new ArrayList<>(held);
        granted.add(Permissions.INVOICE__POST);
        step.setPermissions(List.copyOf(granted));
        TenantContext.run(step, () -> invoices.post(invoiceId));
    }

    /**
     * The handover block (§5.3): {@code affirmed} (the customer physically received the goods) and
     * {@code receivedByName} are required; {@code lines} (optional) name each goods line with its full quantity and
     * the serial numbers handed over. A partial handover is not a pickup and is refused.
     */
    @SuppressWarnings("unchecked")
    private Handover handover(Object raw, List<Map<String, Object>> lines) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw ApiException.badRequest("handover should not be empty: a cash sale is confirmed when the customer"
                    + " receives the goods");
        }
        Map<String, Object> h = (Map<String, Object>) m;
        unknownFields(h, HANDOVER_FIELDS);
        if (!Boolean.TRUE.equals(h.get("affirmed"))) {
            throw ApiException.badRequest("Confirm that the customer has received these goods (handover.affirmed)");
        }
        String receivedBy = trimmed(h.get("receivedByName"));
        if (receivedBy == null) {
            throw ApiException.badRequest("handover.receivedByName should not be empty");
        }
        Map<String, Map<String, Object>> goods = new LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            if (l.get("productId") != null) {
                goods.put(str(l.get("id")), l);
            }
        }
        Map<String, List<String>> serials = new LinkedHashMap<>();
        if (h.get("lines") instanceof List<?> given) {
            Set<String> seen = new LinkedHashSet<>();
            for (Object x : given) {
                if (!(x instanceof Map<?, ?> gm)) {
                    throw ApiException.badRequest("handover.lines must be objects");
                }
                Map<String, Object> g = (Map<String, Object>) gm;
                unknownFields(g, Set.of("lineId", "quantity", "serialNumbers"));
                String lineId = str(g.get("lineId"));
                Map<String, Object> line = goods.get(lineId);
                if (line == null) {
                    throw ApiException.notFound("Order line not found");
                }
                if (!seen.add(lineId)) {
                    throw ApiException.badRequest("Each line is handed over once");
                }
                BigDecimal open = dec(line.get("quantity")).subtract(dec(line.get("qtyCancelled")));
                BigDecimal qty = g.get("quantity") == null ? open : dec(g.get("quantity"));
                if (qty.compareTo(open) != 0) {
                    throw ApiException.badRequest("A cash sale hands every line over in full at the counter: "
                            + line.get("description") + " is " + JsNumbers.js(qty) + " of " + JsNumbers.js(open));
                }
                List<String> sn = SalesDb.strings(g.get("serialNumbers")).stream().filter(s -> !blank(s)).map(String::trim)
                        .toList();
                if (!sn.isEmpty()) {
                    serials.put(lineId, sn);
                }
            }
            for (Map.Entry<String, Map<String, Object>> e : goods.entrySet()) {
                if (!seen.contains(e.getKey())) {
                    throw ApiException.badRequest("A cash sale hands every line over in full at the counter: "
                            + e.getValue().get("description") + " is missing from the handover");
                }
            }
        }
        return new Handover(receivedBy, trimmed(h.get("receivedByPhone")), trimmed(h.get("signature")),
                trimmed(h.get("notes")), serials);
    }

    // ------------------------------------------------------------------ helpers

    private static String trimmed(Object v) {
        String s = v == null ? null : v.toString().trim();
        return s == null || s.isEmpty() ? null : s;
    }

    static void unknownFields(Map<String, Object> in, Set<String> allowed) {
        for (String k : in.keySet()) {
            if (!allowed.contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
    }
}
