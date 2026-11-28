package com.inventoryerp.sync.application;

import com.inventoryerp.invoicing.application.PaymentService;
import com.inventoryerp.invoicing.application.PaymentService.AllocationInput;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.sales.application.SalesOrderService;
import com.inventoryerp.sales.application.SalesQuotationService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-S083 O5 — sales orders, quotations and customer payments recorded by a field rep on an offline device.
 *
 * <p>Every op reuses the online service ({@link SalesOrderService}, {@link SalesQuotationService},
 * {@link PaymentService}); approvals, credit checks and posting stay theirs. The O5 rules on top:
 * <ul>
 *   <li><b>Pricing audit.</b> The device's prices are never taken: lines are priced by the server as any online order
 *   is. When the server total differs from what the rep saw by more than {@code sync.varianceTolerance.<type>}, the
 *   document is kept as a draft with a {@code price_variance} review item instead of being confirmed / submitted.</li>
 *   <li><b>Credit.</b> Confirmation runs the normal credit check (a breach puts the order on hold, as online). On top,
 *   {@code sync.offlineOrderCap} caps what one rep may confirm from offline orders within the offline window; beyond
 *   it the order is put on hold with an {@code offline_cap} review item.</li>
 *   <li><b>Payments.</b> No tolerance (C6): the amount actually received is recorded. Allocations are applied up to each
 *   invoice's open balance at sync; what cannot be applied stays unallocated on the customer's account with a
 *   {@code payment_allocation} review item — never adjusted automatically.</li>
 *   <li>A step the server refuses after the document exists (confirmation blocked by stock policy, …) becomes a
 *   {@code confirm_refused} review item; the op is applied. A refusal before anything exists is a dead letter.</li>
 * </ul>
 */
@Service
public class SyncSalesService {

    private static final Logger log = LoggerFactory.getLogger(SyncSalesService.class);

    public static final String PRICE_VARIANCE = "price_variance";
    public static final String CONFIRM_REFUSED = "confirm_refused";
    public static final String OFFLINE_CAP = "offline_cap";
    public static final String PAYMENT_ALLOCATION = "payment_allocation";
    public static final Set<String> KINDS = Set.of(PRICE_VARIANCE, CONFIRM_REFUSED, OFFLINE_CAP, PAYMENT_ALLOCATION);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Order header fields a device may send (anything else is ignored). */
    private static final List<String> ORDER_FIELDS = List.of("contactId", "locationId", "siteId", "deliveryAddress",
            "deliveryMethod", "paymentTermId", "paymentMode", "requestedDate", "customerRef", "notes", "currencyCode");
    private static final List<String> QUOTE_FIELDS = List.of("contactId", "siteId", "deliveryAddress", "paymentTermId",
            "validUntil", "notes", "currencyCode");

    private final JdbcClient jdbc;
    private final SalesOrderService orders;
    private final SalesQuotationService quotes;
    private final PaymentService payments;
    private final SyncPolicyService policy;
    private final TransactionTemplate tx;

    public SyncSalesService(PosDb db, SalesOrderService orders, SalesQuotationService quotes, PaymentService payments,
            SyncPolicyService policy, PlatformTransactionManager txm) {
        this.jdbc = db.jdbc();
        this.orders = orders;
        this.quotes = quotes;
        this.payments = payments;
        this.policy = policy;
        this.tx = new TransactionTemplate(txm);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ---------------------------------------------------------------------------------------------- salesOrder.create

    /**
     * {@code salesOrder.create}: a draft order priced by the server, then confirmed ({@code confirm}, default true)
     * when the price is within tolerance and the rep may confirm.
     */
    public Map<String, Object> order(SyncStockService.OpContext c, Map<String, Object> p) {
        requirePermission(Permissions.SALES__ORDER_WRITE);
        Map<String, Object> in = header(p, ORDER_FIELDS, c);
        in.put("lines", serverPricedLines(p, true));
        Map<String, Object> created = orders.create(in);
        String id = str(created.get("id"));
        String number = str(created.get("number"));
        stamp("SalesOrder", id, c);
        BigDecimal actual = dec(created.get("total"));
        Map<String, Object> mapping = mapping(p, id, "salesOrderId", "orderNumber", number);

        String variance = variance(c, p, "salesOrder", "sales_order", id, number, actual);
        if (variance != null) {
            return result(id, mapping, "price_review", variance, actual);
        }
        if (Boolean.FALSE.equals(p.get("confirm"))) {
            return result(id, mapping, "draft", null, actual);
        }
        if (!TenantContext.permissions().contains(Permissions.SALES__ORDER_CONFIRM)) {
            String why = "The rep may not confirm orders; confirm it in the back office";
            review(CONFIRM_REFUSED, c, "sales_order", id, number, str(p.get("partnerId")), null, actual, null, why,
                    Map.of());
            return result(id, mapping, "draft", why, actual);
        }
        Map<String, Object> after;
        try {
            // ADR-S094 §4.2: the rep asked to confirm; an order that needs approval is submitted for it (the device's
            // explicit request), so the device sees the same outcome as before.
            if ("required".equals(orders.get(id).get("approvalStatus"))) {
                Map<String, Object> submitted = orders.submitApproval(id, null);
                return result(id, mapping, "pending_approval", String.join("; ", strings(submitted.get(
                        "approvalReasons"))), actual);
            }
            after = orders.confirm(id);
        } catch (ApiException e) {
            if (!businessRefusal(e)) {
                throw e;
            }
            review(CONFIRM_REFUSED, c, "sales_order", id, number, str(p.get("partnerId")), null, actual, null,
                    e.getMessage(), Map.of());
            log.info("offline order {} not confirmed: {}", number, e.getMessage());
            return result(id, mapping, "draft", e.getMessage(), actual);
        }
        String status = str(after.get("status"));
        if (!"confirmed".equals(status)) {
            // Over the limit with an "approve" policy: a credit request was opened, the order waits as a draft.
            return result(id, mapping, "credit_approval", "Over the customer's credit limit: a credit request was opened",
                    actual);
        }
        if (Boolean.TRUE.equals(after.get("onHold"))) {
            return result(id, mapping, "on_hold", str(after.get("holdReason")), actual);
        }
        String capped = capCheck(c, p, id, number, actual);
        return capped == null ? result(id, mapping, "confirmed", null, actual)
                : result(id, mapping, "on_hold", capped, actual);
    }

    /**
     * {@code sync.offlineOrderCap}: what one rep has confirmed from offline orders within the offline window, this order
     * included. Beyond the cap the order is put on hold for a person to release.
     */
    private String capCheck(SyncStockService.OpContext c, Map<String, Object> p, String id, String number,
            BigDecimal total) {
        BigDecimal cap = policy.offlineOrderCap(org());
        if (cap.signum() <= 0) {
            return null;
        }
        int hours = policy.offlineWindowHours(org(), null);
        BigDecimal used = jdbc.sql("SELECT COALESCE(sum(total), 0) FROM \"SalesOrder\" WHERE \"organizationId\" = ? AND"
                        + " \"recordedOffline\" AND \"ownerId\" = ? AND status NOT IN ('cancelled', 'draft') AND"
                        + " \"deletedAt\" IS NULL AND \"occurredAt\" >= ?")
                .params(org(), c.actorUserId(), SqlRows.ts(Instant.now().minus(hours, ChronoUnit.HOURS)))
                .query(BigDecimal.class).single();
        if (used.compareTo(cap) <= 0) {
            return null;
        }
        String why = "Offline order cap exceeded: " + plain(used) + " confirmed from offline orders in the last " + hours
                + " h against a cap of " + plain(cap);
        orders.hold(id, why);
        review(OFFLINE_CAP, c, "sales_order", id, number, str(p.get("partnerId")), cap, used, null, why,
                Map.of("orderTotal", total));
        return why;
    }

    // ------------------------------------------------------------------------------------------------- quote.create

    /** {@code quote.create}: a draft quotation priced by the server, then submitted ({@code submit}, default true). */
    public Map<String, Object> quote(SyncStockService.OpContext c, Map<String, Object> p) {
        requirePermission(Permissions.SALES__QUOTATION_WRITE);
        Map<String, Object> in = header(p, QUOTE_FIELDS, c);
        in.put("lines", serverPricedLines(p, false));
        Map<String, Object> created = quotes.create(in);
        String id = str(created.get("id"));
        String number = str(created.get("number"));
        stamp("SalesQuotation", id, c);
        BigDecimal actual = dec(created.get("total"));
        Map<String, Object> mapping = mapping(p, id, "quotationId", "quotationNumber", number);
        String variance = variance(c, p, "quote", "sales_quotation", id, number, actual);
        if (variance != null) {
            return result(id, mapping, "price_review", variance, actual);
        }
        if (Boolean.FALSE.equals(p.get("submit"))) {
            return result(id, mapping, "draft", null, actual);
        }
        Map<String, Object> after;
        try {
            after = quotes.submit(id);
        } catch (ApiException e) {
            if (!businessRefusal(e)) {
                throw e;
            }
            review(CONFIRM_REFUSED, c, "sales_quotation", id, number, str(p.get("partnerId")), null, actual, null,
                    e.getMessage(), Map.of());
            return result(id, mapping, "draft", e.getMessage(), actual);
        }
        String status = str(after.get("status"));
        return result(id, mapping, "approved".equals(status) ? "approved" : "pending_approval",
                "approved".equals(status) ? null : String.join("; ", strings(after.get("approvalReasons"))), actual);
    }

    // ---------------------------------------------------------------------------------------------- payment.receive

    /**
     * {@code payment.receive}: the amount actually received, allocated to the rep's chosen invoices up to what is still
     * open on each at sync; the rest stays unallocated (on account) and is reported for review.
     */
    public Map<String, Object> payment(SyncStockService.OpContext c, Map<String, Object> p) {
        // nested = true: the sync ledger records this op's outcome (with its mapping), as for POS settle — the
        // payment service must not complete the op's idempotency record with its own body.
        requirePermission(Permissions.PAYMENT__CREATE);
        String partnerId = str(p.get("partnerId"));
        BigDecimal amount = dec(p.get("amount"));
        if (partnerId == null) {
            throw ApiException.badRequest("partnerId should not be empty");
        }
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Payment amount must be positive");
        }
        String method = str(p.get("paymentMethod"));
        if (method != null && !Set.of("cash", "bank", "mobile_money", "card", "cheque").contains(method)) {
            throw ApiException.badRequest("paymentMethod must be one of the following values: cash, bank, mobile_money,"
                    + " card, cheque");
        }
        String date = str(p.get("paymentDate")) != null ? str(p.get("paymentDate"))
                : c.occurredAt() == null ? LocalDate.now(ZoneOffset.UTC).toString()
                        : EntityPatch.toInstant(c.occurredAt()).atOffset(ZoneOffset.UTC).toLocalDate().toString();

        List<Map<String, Object>> requested = new ArrayList<>();
        for (Object o : PosSql.list(p.get("allocations"))) {
            requested.add(PosSql.map(o));
        }
        List<AllocationInput> applied = new ArrayList<>();
        List<Map<String, Object>> notes = new ArrayList<>();
        BigDecimal requestedSum = BigDecimal.ZERO;
        BigDecimal left = amount;
        for (Map<String, Object> a : requested) {
            String docId = str(a.get("documentId"));
            BigDecimal want = dec(a.get("amount"));
            requestedSum = requestedSum.add(want.max(BigDecimal.ZERO));
            Map<String, Object> doc = docId == null ? null : SqlRows.one(jdbc.sql("SELECT id, \"documentNumber\","
                    + " \"amountResidual\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? AND \"partnerId\" = ?"
                    + " AND \"documentType\"::text IN ('sales_invoice', 'debit_note') AND status::text IN ('posted',"
                    + " 'approved', 'issued')").params(docId, org(), partnerId));
            BigDecimal open = doc == null ? BigDecimal.ZERO : dec(doc.get("amountResidual"));
            BigDecimal take = want.min(open).min(left).max(BigDecimal.ZERO);
            if (take.signum() > 0) {
                applied.add(new AllocationInput(docId, null, take));
                left = left.subtract(take);
            }
            if (take.compareTo(want) < 0) {
                notes.add(Obj.of("documentId", docId, "documentNumber", doc == null ? null : doc.get("documentNumber"),
                        "requested", want, "applied", take, "why", doc == null ? "not an open invoice of this customer"
                                : open.compareTo(want) < 0 ? "only " + plain(open) + " was still open" : "the payment"
                                        + " was fully allocated"));
            }
        }
        Map<String, Object> created;
        try {
            created = payments.createReceipt(paymentInput(c, p, partnerId, amount, method, date, applied), true, false);
        } catch (ApiException e) {
            if (applied.isEmpty() || !businessRefusal(e)) {
                throw e;
            }
            // An invoice changed between the check and the posting: take the money on account rather than lose it.
            log.info("offline payment allocations refused ({}); recording it unallocated", e.getMessage());
            notes.add(Obj.of("why", "allocations refused at posting: " + e.getMessage()));
            applied = List.of();
            created = payments.createReceipt(paymentInput(c, p, partnerId, amount, method, date, applied), true,
                    false);
        }
        String id = str(created.get("id"));
        String number = str(created.get("paymentNumber"));
        tx.executeWithoutResult(s -> jdbc.sql("UPDATE \"Payment\" SET \"recordedOffline\" = true, \"occurredAt\" = ?,"
                + " \"offlineActorId\" = ? WHERE id = ? AND \"organizationId\" = ?").params(c.occurredAt() == null ? null
                        : SqlRows.ts(EntityPatch.toInstant(c.occurredAt())), c.actorUserId(), id, org()).update());
        BigDecimal appliedSum = applied.stream().map(AllocationInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String state = "posted";
        String reason = null;
        if (!notes.isEmpty()) {
            state = "partly_unallocated";
            reason = plain(requestedSum.subtract(appliedSum)) + " of the requested allocation stays on the customer's"
                    + " account";
            review(PAYMENT_ALLOCATION, c, "payment", id, number, partnerId, requestedSum, appliedSum, null, reason,
                    Map.of("allocations", notes, "amount", amount));
        }
        Map<String, Object> mapping = mapping(p, id, "paymentId", "paymentNumber", number);
        Map<String, Object> out = result(id, mapping, state, reason, amount);
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) out.get("outcome");
        o.put("allocated", PosSql.js(appliedSum));
        return out;
    }

    private static PaymentInput paymentInput(SyncStockService.OpContext c, Map<String, Object> p, String partnerId,
            BigDecimal amount, String method, String date, List<AllocationInput> allocations) {
        return new PaymentInput(partnerId, date, amount, method == null ? "cash" : method, str(p.get("accountId")),
                str(p.get("reference")), c.branchId(), allocations, null, null, null, null, str(p.get("currencyCode")),
                null);
    }

    // ------------------------------------------------------------------------------------------ review queue + report

    /** The offline sales review queue (newest first, at most 500). */
    public List<Map<String, Object>> reviews(String kind, String status, String deviceId, String actorUserId) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder("r.\"organizationId\" = ?");
        for (String[] f : new String[][] {{"r.kind", kind}, {"r.status", status}, {"r.\"deviceId\"", deviceId},
            {"r.\"actorUserId\"", actorUserId}}) {
            if (f[1] != null && !f[1].isBlank()) {
                where.append(" AND ").append(f[0]).append(" = ?");
                params.add(f[1]);
            }
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT r.*, p.name AS \"partnerName\","
                + " trim(concat(u.\"firstName\", ' ', u.\"lastName\")) AS \"actorName\", d.name AS \"deviceName\" FROM \"SyncReviewItem\" r LEFT JOIN \"Partner\" p ON p.id ="
                + " r.\"partnerId\" LEFT JOIN \"User\" u ON u.id = r.\"actorUserId\" LEFT JOIN \"PosDevice\" d ON d.id ="
                + " r.\"deviceId\" WHERE " + where + " ORDER BY r.\"createdAt\" DESC LIMIT 500").params(params));
        for (Map<String, Object> r : rows) {
            for (String k : List.of("expectedAmount", "actualAmount", "variancePercent")) {
                r.put(k, r.get(k) == null ? null : PosSql.js(r.get(k)));
            }
            r.put("details", r.get("details") == null ? null : PosSql.java(r.get("details")));
        }
        return rows;
    }

    /** Resolve one review item with a note (the document itself is handled on its own screen). */
    public Map<String, Object> resolveReview(String id, String note) {
        if (note == null || note.isBlank()) {
            throw ApiException.badRequest("note should not be empty");
        }
        int n = tx.execute(s -> jdbc.sql("UPDATE \"SyncReviewItem\" SET status = 'resolved', \"resolvedAt\" = now(),"
                + " \"resolvedById\" = ?, \"resolutionNote\" = ? WHERE id = ? AND \"organizationId\" = ? AND status ="
                + " 'open'").params(TenantContext.userId(), note.trim(), id, org()).update());
        if (n == 0) {
            if (jdbc.sql("SELECT count(*) FROM \"SyncReviewItem\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(id, org()).query(Long.class).single() == 0) {
                throw ApiException.notFound("Review item not found");
            }
            throw ApiException.badRequest("Review item is already resolved");
        }
        return reviews(null, null, null, null).stream().filter(r -> id.equals(r.get("id"))).findFirst()
                .orElseGet(() -> Obj.of("id", id, "status", "resolved"));
    }

    /**
     * Offline payments per rep and day (by {@code occurredAt}, UTC): received, allocated as requested, allocated as
     * applied, left on account, and payments refused (dead letters) with what they claimed.
     */
    public List<Map<String, Object>> paymentReconciliation(String from, String to, String userId, String deviceId) {
        LocalDate f = from == null || from.isBlank() ? LocalDate.now(ZoneOffset.UTC).minusDays(30) : LocalDate.parse(from);
        LocalDate t = to == null || to.isBlank() ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(to);
        if (t.isBefore(f)) {
            throw ApiException.badRequest("to must not be before from");
        }
        Instant lo = f.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant hi = t.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<Object> params = new ArrayList<>(List.of(org(), SqlRows.ts(lo), SqlRows.ts(hi)));
        String filter = "";
        if (userId != null && !userId.isBlank()) {
            filter += " AND p.\"offlineActorId\" = ?";
            params.add(userId);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            filter += " AND EXISTS (SELECT 1 FROM \"SyncAppliedOp\" a WHERE a.\"organizationId\" = p.\"organizationId\""
                    + " AND a.\"opType\" = 'payment.receive' AND a.\"resultJson\"->>'id' = p.id AND a.\"deviceId\" = ?)";
            params.add(deviceId);
        }
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT p.\"offlineActorId\" AS \"userId\", (p.\"occurredAt\")::date"
                + " AS day, count(*) AS payments, COALESCE(sum(p.amount) FILTER (WHERE p.status::text <> 'cancelled'), 0) AS"
                + " received, COALESCE(sum(p.\"allocatedAmount\") FILTER (WHERE p.status::text <> 'cancelled'), 0) AS allocated,"
                + " COALESCE(sum(p.\"unallocatedAmount\") FILTER (WHERE p.status::text <> 'cancelled'), 0) AS unallocated,"
                + " count(*) FILTER (WHERE p.status::text = 'cancelled') AS voided,"
                + " COALESCE(sum((SELECT COALESCE(sum(r.\"expectedAmount\" - r.\"actualAmount\"), 0) FROM \"SyncReviewItem\" r"
                + " WHERE r.\"organizationId\" = p.\"organizationId\" AND r.kind = 'payment_allocation' AND r.\"documentId\""
                + " = p.id)), 0) AS \"notApplied\" FROM \"Payment\" p WHERE p.\"organizationId\" = ? AND"
                + " p.\"recordedOffline\" AND p.\"occurredAt\" >= ? AND p.\"occurredAt\" < ?" + filter
                + " GROUP BY 1, 2").params(params))) {
            Map<String, Object> row = row(byKey, str(r.get("userId")), String.valueOf(r.get("day")));
            row.put("payments", ((Number) r.get("payments")).intValue());
            row.put("voided", ((Number) r.get("voided")).intValue());
            row.put("received", PosSql.js(r.get("received")));
            row.put("allocated", PosSql.js(r.get("allocated")));
            row.put("requestedAllocation", PosSql.js(dec(r.get("allocated")).add(dec(r.get("notApplied")))));
            row.put("onAccount", PosSql.js(r.get("unallocated")));
        }
        List<Object> dl = new ArrayList<>(List.of(org(), SqlRows.ts(lo), SqlRows.ts(hi)));
        String dlFilter = "";
        if (userId != null && !userId.isBlank()) {
            dlFilter += " AND \"actorUserId\" = ?";
            dl.add(userId);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            dlFilter += " AND \"deviceId\" = ?";
            dl.add(deviceId);
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"actorUserId\" AS \"userId\", (\"occurredAt\")::date AS"
                + " day, count(*) AS n, COALESCE(sum(CASE WHEN payload->>'amount' ~ '^[0-9]+(\\.[0-9]+)?$' THEN"
                + " (payload->>'amount')::numeric END), 0) AS amount, count(*) FILTER (WHERE status IN ('open',"
                + " 'investigating')) AS open FROM \"SyncOpDeadLetter\" WHERE \"organizationId\" = ? AND \"opType\" ="
                + " 'payment.receive' AND \"occurredAt\" >= ? AND \"occurredAt\" < ?" + dlFilter + " GROUP BY 1, 2")
                .params(dl))) {
            Map<String, Object> row = row(byKey, str(r.get("userId")), String.valueOf(r.get("day")));
            row.put("refused", ((Number) r.get("n")).intValue());
            row.put("refusedOpen", ((Number) r.get("open")).intValue());
            row.put("refusedAmount", PosSql.js(r.get("amount")));
        }
        Map<String, String> names = new LinkedHashMap<>();
        List<Map<String, Object>> out = new ArrayList<>(byKey.values());
        for (Map<String, Object> r : out) {
            String u = str(r.get("userId"));
            if (u != null && !names.containsKey(u)) {
                names.put(u, jdbc.sql("SELECT trim(concat(\"firstName\", ' ', \"lastName\")) FROM \"User\" WHERE id = ?")
                        .param(u).query(String.class).optional()
                        .orElse(null));
            }
            r.put("userName", u == null ? null : names.get(u));
        }
        out.sort((a, b) -> {
            int d = String.valueOf(b.get("day")).compareTo(String.valueOf(a.get("day")));
            return d != 0 ? d : String.valueOf(a.get("userName")).compareTo(String.valueOf(b.get("userName")));
        });
        return out;
    }

    private static Map<String, Object> row(Map<String, Map<String, Object>> byKey, String userId, String day) {
        return byKey.computeIfAbsent(userId + "|" + day, k -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("userId", userId);
            r.put("day", day);
            r.put("payments", 0);
            r.put("voided", 0);
            r.put("received", 0);
            r.put("requestedAllocation", 0);
            r.put("allocated", 0);
            r.put("onAccount", 0);
            r.put("refused", 0);
            r.put("refusedOpen", 0);
            r.put("refusedAmount", 0);
            return r;
        });
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private Map<String, Object> header(Map<String, Object> p, List<String> fields, SyncStockService.OpContext c) {
        String partnerId = str(p.get("partnerId"));
        if (partnerId == null) {
            throw ApiException.badRequest("partnerId should not be empty");
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("partnerId", partnerId);
        for (String k : fields) {
            if (p.get(k) != null) {
                in.put(k, p.get(k));
            }
        }
        String branch = str(p.get("branchId")) != null ? str(p.get("branchId")) : c.branchId();
        if (branch != null) {
            in.put("branchId", branch);
        }
        return in;
    }

    /**
     * The device's lines without its prices: the server prices each one as for any order. A manual discount the rep
     * granted ({@code discountPercent}) is kept; the approval rules for discounts then apply as online.
     */
    private static List<Map<String, Object>> serverPricedLines(Map<String, Object> p, boolean order) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : PosSql.list(p.get("lines"))) {
            Map<String, Object> l = PosSql.map(o);
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : order ? List.of("productId", "variantId", "uomId", "quantity", "description",
                    "discountPercent", "sourceLocationId", "requestedDate") : List.of("productId", "variantId", "uomId",
                            "quantity", "description", "discountPercent")) {
                if (l.get(k) != null) {
                    m.put(k, l.get(k));
                }
            }
            out.add(m);
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("Add at least one line");
        }
        return out;
    }

    /** Price audit: a review item and the reason when the server total is beyond the tolerance of the rep's total. */
    private String variance(SyncStockService.OpContext c, Map<String, Object> p, String toleranceType, String docType,
            String id, String number, BigDecimal actual) {
        if (p.get("expectedTotal") == null) {
            return null;
        }
        BigDecimal expected = dec(p.get("expectedTotal"));
        BigDecimal pct = variancePercent(expected, actual);
        BigDecimal tolerance = policy.varianceTolerancePercent(org(), toleranceType);
        if (pct.compareTo(tolerance) <= 0) {
            return null;
        }
        String why = "Priced at " + plain(actual) + " on sync; the rep's device showed " + plain(expected) + " ("
                + pct.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "% against a "
                + tolerance.stripTrailingZeros().toPlainString() + "% tolerance)";
        review(PRICE_VARIANCE, c, docType, id, number, str(p.get("partnerId")), expected, actual, pct, why,
                Map.of("lines", PosSql.list(p.get("lines"))));
        return why;
    }

    /** |actual − expected| as a percent of the server's figure (100 when the server says 0 and the rep did not). */
    static BigDecimal variancePercent(BigDecimal expected, BigDecimal actual) {
        BigDecimal diff = actual.subtract(expected).abs();
        if (diff.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (actual.signum() == 0) {
            return HUNDRED;
        }
        return diff.multiply(HUNDRED).divide(actual.abs(), 6, RoundingMode.HALF_UP);
    }

    private void review(String kind, SyncStockService.OpContext c, String docType, String docId, String number,
            String partnerId, BigDecimal expected, BigDecimal actual, BigDecimal pct, String reason,
            Map<String, Object> details) {
        Instant at = c.occurredAt() == null ? null : EntityPatch.toInstant(c.occurredAt());
        tx.executeWithoutResult(s -> jdbc.sql("INSERT INTO \"SyncReviewItem\" (id, \"organizationId\", kind,"
                        + " \"documentType\", \"documentId\", \"documentNumber\", \"partnerId\", \"opId\", \"opType\","
                        + " \"deviceId\", \"actorUserId\", \"occurredAt\", \"expectedAmount\", \"actualAmount\","
                        + " \"variancePercent\", reason, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?::jsonb)")
                .params(UUID.randomUUID().toString(), org(), kind, docType, docId, number, partnerId, c.opId(),
                        c.opType(), c.deviceId(), c.actorUserId(), at == null ? null : SqlRows.ts(at), expected, actual,
                        pct, reason, EntityJson.toJson(details)).update());
    }

    private void stamp(String table, String id, SyncStockService.OpContext c) {
        Instant at = c.occurredAt() == null ? null : EntityPatch.toInstant(c.occurredAt());
        tx.executeWithoutResult(s -> jdbc.sql("UPDATE \"" + table + "\" SET \"recordedOffline\" = true, \"occurredAt\" = ?"
                + " WHERE id = ? AND \"organizationId\" = ?").params(at == null ? null : SqlRows.ts(at), id, org()).update());
    }

    private static Map<String, Object> mapping(Map<String, Object> p, String id, String idKey, String numberKey,
            String number) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (str(p.get("clientId")) != null) {
            m.put(str(p.get("clientId")), id);
        }
        m.put(idKey, id);
        m.put(numberKey, number);
        return m;
    }

    /** The push result body: id, mapping, and the additive {@code outcome} (state, reason, the server's total). */
    private static Map<String, Object> result(String id, Map<String, Object> mapping, String state, String reason,
            BigDecimal total) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("state", state);
        if (reason != null && !reason.isBlank()) {
            o.put("reason", reason);
        }
        o.put("total", PosSql.js(total));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("mapping", mapping);
        out.put("outcome", o);
        return out;
    }

    private static boolean businessRefusal(ApiException e) {
        HttpStatus s = e.status();
        return s == HttpStatus.BAD_REQUEST || s == HttpStatus.CONFLICT || s == HttpStatus.UNPROCESSABLE_ENTITY;
    }

    private static void requirePermission(String permission) {
        if (!TenantContext.permissions().contains(permission)) {
            throw ApiException.forbidden("Missing permission " + permission);
        }
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        for (Object o : PosSql.list(v)) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : PosSql.dec(v);
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v instanceof String x ? x : PosSql.jsString(v);
        return s.isEmpty() ? null : s;
    }
}
