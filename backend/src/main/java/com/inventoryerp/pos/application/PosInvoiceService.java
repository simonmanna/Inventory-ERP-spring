package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.dec;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;
import static com.inventoryerp.pos.application.PosSql.user;

import com.inventoryerp.core.application.PriceSnapshotService;
import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.OrgDates;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.TenderAccount;
import com.inventoryerp.core.domain.Product;
import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockReservationService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Grouped;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.invoicing.application.PaymentService;
import com.inventoryerp.invoicing.domain.Invoice;
import com.inventoryerp.invoicing.domain.InvoiceItem;
import com.inventoryerp.invoicing.domain.InvoiceItemModifier;
import com.inventoryerp.invoicing.domain.InvoiceItemRecipeIngredient;
import com.inventoryerp.invoicing.domain.Receipt;
import com.inventoryerp.invoicing.domain.ReceiptItem;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.Savepoints;
import com.inventoryerp.kernel.common.OccurredAt;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.kernel.workflow.WorkflowService;
import com.inventoryerp.pos.domain.InventoryException;
import com.inventoryerp.pos.domain.Order;
import com.inventoryerp.pos.domain.OrderItem;
import com.inventoryerp.pos.domain.OrderItemModifier;
import com.inventoryerp.pos.domain.PosRefund;
import com.inventoryerp.pos.domain.StockPostingJob;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * POS billing on the Invoice table (port of {@code billing/pos-invoice.service.ts} and {@code refund-operation.ts}):
 * the Invoice posts its own GL (Dr AR / Cr Revenue+Tax), payments allocate to it, receipts are generated from it,
 * stock is relieved asynchronously by durable {@code StockPostingJob}s.
 */
@Service
public class PosInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(PosInvoiceService.class);
    private static final Map<String, String> MODE_FROM_METHOD = Map.of("cash", "cash", "bank", "card", "card", "card",
            "mobile_money", "mobile_money", "store_credit", "mixed");

    /** Context threaded through stock posting. */
    record Ctx(String invoiceId, String invoiceNumber, String orderId) {}

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final DocumentBuilderService builder;
    private final SequenceService sequence;
    private final PaymentService payments;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final StockService stock;
    private final StockReservationService reservations;
    private final PosReceiptsService receipts;
    private final PosOverridesService overrides;
    private final WorkflowService workflows;
    private final SettingResolverService settings;
    private final NotificationsService notifications;
    private final PricingPolicy pricing;
    private final PosStockLocation locations;
    private final TransactionTemplate tx;

    private final PriceSnapshotService snapshots;

    public PosInvoiceService(PosDb db, AuditService audit, EventBus events, DocumentBuilderService builder,
            SequenceService sequence, PaymentService payments, PostingService posting,
            AccountDeterminationService determination, StockService stock, StockReservationService reservations,
            PosReceiptsService receipts, PosOverridesService overrides, WorkflowService workflows,
            SettingResolverService settings, NotificationsService notifications, PricingPolicy pricing,
            PosStockLocation locations, PlatformTransactionManager txm, PriceSnapshotService snapshots) {
        this.snapshots = snapshots;
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.events = events;
        this.builder = builder;
        this.sequence = sequence;
        this.payments = payments;
        this.posting = posting;
        this.determination = determination;
        this.stock = stock;
        this.reservations = reservations;
        this.receipts = receipts;
        this.overrides = overrides;
        this.workflows = workflows;
        this.settings = settings;
        this.notifications = notifications;
        this.pricing = pricing;
        this.locations = locations;
        this.tx = new TransactionTemplate(txm);
    }

    // ─── preflight ───

    /** Reject known tender / configuration problems before creating financial work. */
    public void preflightPayment(double total, Map<String, Object> input, String partnerId) {
        if ("credit".equals(input.get("settleMode"))) {
            tx.executeWithoutResult(st -> assertCreditAllowed(partnerId, total));
            return;
        }
        List<Map<String, Object>> tenders = normalizeTenders(input, total, false);
        Object handedRaw = input.get("amountTendered");
        boolean anyCash = tenders.stream().anyMatch(t -> "cash".equals(t.get("method")));
        if (handedRaw != null) {
            double handed = num(handedRaw);
            BigDecimal handedDec = Double.isFinite(handed) ? PosSql.dec(handedRaw) : null;
            BigDecimal totalDec = PosSql.decOf(total);
            if (handedDec == null || handedDec.compareTo(totalDec) < 0 || (!anyCash && handedDec.compareTo(totalDec) > 0)) {
                throw ApiException.badRequest("Cash handed over is invalid for these tenders");
            }
        }
        tx.executeWithoutResult(st -> {
            for (Map<String, Object> t : tenders) {
                TenderAccount.resolve(jdbc, determination, org(), (String) t.get("method"), (String) t.get("accountId"),
                        (String) input.get("cashSessionId"));
            }
            BigDecimal creditTotal = BigDecimal.ZERO;
            for (Map<String, Object> t : tenders) {
                if ("store_credit".equals(t.get("method"))) {
                    creditTotal = creditTotal.add(dec(t.get("amount")));
                }
            }
            if (creditTotal.signum() > 0) {
                if (partnerId == null) {
                    throw ApiException.badRequest("Select the customer who owns this store credit");
                }
                Map<String, Object> credit = SqlRows.one(jdbc.sql("SELECT * FROM \"StoreCredit\" WHERE \"organizationId\" = ?"
                        + " AND \"partnerId\" = ? LIMIT 1").params(org(), partnerId));
                if (credit == null || !Boolean.TRUE.equals(credit.get("isActive"))
                        || (credit.get("expiresAt") != null && !((Instant) credit.get("expiresAt")).isAfter(Instant.now()))
                        || creditTotal.compareTo(SqlRows.dec(credit, "balance")) > 0) {
                    throw ApiException.badRequest("Customer store credit is unavailable, expired or insufficient");
                }
            }
        });
    }

    /** Saved-order variant of {@link #preflightPayment}. */
    public void preflightOrderPayment(Map<String, Object> order, Map<String, Object> input) {
        List<Map<String, Object>> items = order.get("items") != null ? PosOrdersService.maps(order.get("items"))
                : db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false", order.get("id"));
        Map<String, Object> discountInput = new LinkedHashMap<>(input);
        discountInput.put("transactionDiscountType", input.get("transactionDiscountType") != null
                ? input.get("transactionDiscountType")
                : order.get("transactionDiscountType") != null ? order.get("transactionDiscountType") : "percentage");
        discountInput.put("transactionDiscountAmount", input.get("transactionDiscountAmount") != null
                ? input.get("transactionDiscountAmount")
                : order.get("transactionDiscountAmount") != null ? order.get("transactionDiscountAmount") : 0);
        discountInput.put("transactionDiscountPercent", input.get("transactionDiscountPercent") != null
                ? input.get("transactionDiscountPercent")
                : order.get("transactionDiscountPercent") != null ? order.get("transactionDiscountPercent") : 0);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> i : items) {
            if (Boolean.TRUE.equals(i.get("cancelled"))) {
                continue;
            }
            Map<String, Object> l = new LinkedHashMap<>(i);
            l.put("quantity", num(i.get("quantity")));
            l.put("unitPrice", num(i.get("unitPrice")));
            l.put("discountPercent", num(i.get("discountPercent")));
            l.put("discountAmount", num(i.get("discountAmount")));
            l.put("discountType", i.get("discountType") == null ? null : i.get("discountType").toString());
            lines.add(l);
        }
        Totals totals = builder.prepareLines(PosLines.lineInputs(PricingPolicy.discountedLines(lines, discountInput)));
        String partnerId = input.get("partnerId") != null ? (String) input.get("partnerId") : (String) order.get("partnerId");
        preflightPayment(totals.total().doubleValue(), input, partnerId);
    }

    /** {@code inventory.stockPostingTiming}. */
    public String stockPostingTiming() {
        return settings.resolveString("inventory.stockPostingTiming", SettingContext.EMPTY);
    }

    /** Enqueue a durable stock-posting job with its recipe snapshot; idempotent per (trigger, invoice|order). */
    public void enqueueStockPosting(String orderId, String invoiceId, String invoiceNumber, String trigger) {
        String orgId = org();
        String idempotencyKey = trigger + ":" + (invoiceId != null ? invoiceId : orderId);
        Map<String, Object> recipeSnapshot = new LinkedHashMap<>();
        for (Map<String, Object> item : SqlRows.list(jdbc.sql("SELECT id, \"menuItemId\", \"comboId\", \"variantId\" FROM"
                + " \"OrderItem\" WHERE \"orderId\" = ? AND \"organizationId\" = ? AND cancelled = false").params(orderId, orgId))) {
            if (item.get("menuItemId") != null) {
                Boolean tracked = jdbc.sql("SELECT \"isInventoryTracked\" FROM \"MenuItem\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(item.get("menuItemId"), orgId).query(Boolean.class)
                        .optional().orElse(null);
                List<Map<String, Object>> ingredients = new ArrayList<>();
                for (Map<String, Object> ing : SqlRows.list(jdbc.sql("SELECT \"productId\", quantity, \"uomId\" FROM \"MenuProduct\""
                        + " WHERE \"menuItemId\" = ? AND \"organizationId\" = ?").params(item.get("menuItemId"), orgId))) {
                    ingredients.add(Obj.of("productId", ing.get("productId"), "quantity", PosSql.js(ing.get("quantity")),
                            "uomId", ing.get("uomId")));
                }
                Object multiplier = item.get("variantId") == null ? null : jdbc.sql("SELECT \"qtyMultiplier\" FROM"
                                + " \"MenuItemVariant\" WHERE id = ? AND \"menuItemId\" = ? AND \"organizationId\" = ?"
                                + " AND \"deletedAt\" IS NULL")
                        .params(item.get("variantId"), item.get("menuItemId"), orgId).query(PosSql.SCALAR).optional().orElse(null);
                recipeSnapshot.put((String) item.get("id"), Obj.of("kind", "menu", "tracked", Boolean.TRUE.equals(tracked),
                        "multiplier", multiplier == null ? 1 : PosSql.js(multiplier), "ingredients", ingredients));
            } else if (item.get("comboId") != null) {
                List<Map<String, Object>> components = new ArrayList<>();
                boolean active = jdbc.sql("SELECT count(*) FROM \"Combo\" WHERE id = ? AND \"organizationId\" = ? AND \"isActive\" = true")
                        .params(item.get("comboId"), orgId).query(Long.class).single() > 0;
                if (active) {
                    for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT ci.id, ci.\"productId\", ci.quantity,"
                            + " p.\"trackInventory\", p.\"productType\"::text AS \"productType\", p.\"salesUomId\" FROM \"ComboItem\" ci"
                            + " LEFT JOIN \"Product\" p ON p.id = ci.\"productId\" WHERE ci.\"comboId\" = ?")
                            .param(item.get("comboId")))) {
                        components.add(Obj.of("id", c.get("id"), "productId", c.get("productId"),
                                "quantity", PosSql.js(c.get("quantity")), "trackInventory", Boolean.TRUE.equals(c.get("trackInventory")),
                                "productType", c.get("productType"), "uomId", c.get("salesUomId")));
                    }
                }
                recipeSnapshot.put((String) item.get("id"), Obj.of("kind", "combo", "components", components));
            }
        }
        try {
            Savepoints.run(jdbc, () -> db.insert(StockPostingJob.class, Obj.of("organizationId", orgId,
                    "invoiceId", invoiceId, "invoiceNumber", invoiceNumber, "orderId", orderId, "postingTrigger", trigger,
                    "idempotencyKey", idempotencyKey, "recipeSnapshot", PosDb.json(recipeSnapshot),
                    "nextRetryAt", PosDb.raw(NOW))));
        } catch (DuplicateKeyException e) {
            // already enqueued
        }
    }

    // ─── generate invoice ───

    /** Read an invoice with items and receipts. */
    public Map<String, Object> findInvoice(String invoiceId) {
        Map<String, Object> inv = db.first(Invoice.class, "id = ? AND \"organizationId\" = ?", invoiceId, org());
        if (inv == null) {
            return null;
        }
        inv.put("items", db.list(InvoiceItem.class, "\"invoiceId\" = ? ORDER BY \"lineNumber\" ASC", invoiceId));
        inv.put("receipts", db.list(Receipt.class, "\"invoiceId\" = ? ORDER BY \"createdAt\", id", invoiceId));
        return inv;
    }

    public Map<String, Object> generateInvoice(String orderId, Map<String, Object> dto) {
        return generateInvoice(orderId, dto, false);
    }

    /**
     * Bill an order: Invoice + items, GL, stock job, order → completed. {@code external} = inside the caller's
     * transaction (post-commit side effects skipped).
     */
    public Map<String, Object> generateInvoice(String orderId, Map<String, Object> dtoIn, boolean external) {
        Map<String, Object> dto = dtoIn == null ? Map.of() : dtoIn;
        String orgId = org();
        Map<String, Object> order = db.first(Order.class, "id = ? AND \"organizationId\" = ?", orderId, orgId);
        if (order == null) {
            throw ApiException.notFound("Order not found");
        }
        if ("cancelled".equals(order.get("status"))) {
            throw ApiException.badRequest("Cannot bill a cancelled order");
        }
        if (order.get("invoiceId") != null) {
            return findInvoice((String) order.get("invoiceId"));
        }
        if (dto.get("expectedVersion") != null && num(dto.get("expectedVersion")) != PosSql.intOf(order.get("version"))) {
            throw ApiException.badRequest("Saved order changed; review the current order before billing");
        }
        List<Map<String, Object>> items = itemsWithModifiers(orderId);
        if (items.isEmpty()) {
            throw ApiException.badRequest("Order has no items to bill");
        }
        List<String> unquantified = new ArrayList<>();
        for (Map<String, Object> it : items) {
            if (num(it.get("quantity")) <= 0) {
                unquantified.add((String) it.get("description"));
            }
        }
        if (!unquantified.isEmpty()) {
            throw ApiException.badRequest("Set a quantity before billing: " + String.join(", ", unquantified));
        }
        String timing = stockPostingTiming();
        String atpMode = settings.resolveString("inventory.atpMode", SettingContext.EMPTY);
        if (atpMode != null && !"post_commit".equals(atpMode)) {
            atpCheck(items, atpMode);
        }
        String txDiscType = dto.get("transactionDiscountType") != null ? dto.get("transactionDiscountType").toString()
                : order.get("transactionDiscountType") != null ? order.get("transactionDiscountType").toString() : "percentage";
        double txDiscValue = "fixed_amount".equals(txDiscType)
                ? num(dto.get("transactionDiscountAmount") != null ? dto.get("transactionDiscountAmount")
                        : order.get("transactionDiscountAmount"))
                : num(dto.get("transactionDiscountPercent") != null ? dto.get("transactionDiscountPercent")
                        : order.get("transactionDiscountPercent"));
        Object discountReason = dto.get("discountReason") != null ? dto.get("discountReason") : order.get("discountReason");
        List<Map<String, Object>> fullLineInputs = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("productId", it.get("productId"));
            l.put("menuItemId", it.get("menuItemId"));
            l.put("variantId", it.get("variantId"));
            l.put("variantName", it.get("variantName"));
            l.put("description", it.get("description"));
            l.put("quantity", num(it.get("quantity")));
            l.put("unitPrice", num(it.get("unitPrice")));
            l.put("taxId", it.get("taxId"));
            l.put("discountPercent", num(it.get("discountPercent")));
            l.put("discountType", it.get("discountType") == null ? "percentage" : it.get("discountType").toString());
            l.put("discountAmount", PosSql.truthy(it.get("discountAmount")) ? num(it.get("discountAmount")) : null);
            l.put("discountReason", it.get("discountReason"));
            l.put("discountSource", "manual");
            l.put("taxInclusive", it.get("taxInclusive"));
            fullLineInputs.add(l);
        }
        Map<String, Object> discountInput = new LinkedHashMap<>(dto);
        discountInput.put("transactionDiscountType", txDiscType);
        discountInput.put("transactionDiscountAmount", "fixed_amount".equals(txDiscType) ? txDiscValue : 0);
        discountInput.put("transactionDiscountPercent", "percentage".equals(txDiscType) ? txDiscValue : 0);
        discountInput.put("discountReason", discountReason);
        pricing.assertAuthority(fullLineInputs, discountInput);
        List<Map<String, Object>> lineInputs = PricingPolicy.discountedLines(fullLineInputs, discountInput);
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        Instant occurredAt = OccurredAt.resolve((String) dto.get("occurredAt"));

        Map<String, Object> invoice = tx.execute(st -> {
            String cashSessionId = order.get("cashSessionId") != null ? (String) order.get("cashSessionId")
                    : (String) dto.get("cashSessionId");
            if (order.get("cashSessionId") != null && dto.get("cashSessionId") != null
                    && !order.get("cashSessionId").equals(dto.get("cashSessionId"))) {
                throw ApiException.badRequest("Order belongs to another register session");
            }
            if (cashSessionId != null) {
                jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(cashSessionId, orgId).query().listOfRows();
                String status = jdbc.sql("SELECT status::text FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(cashSessionId, orgId).query(String.class).optional().orElse(null);
                if (!"open".equals(status)) {
                    throw ApiException.badRequest("The order register session is closed");
                }
            }
            jdbc.sql("SELECT id FROM \"Order\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(orderId, orgId)
                    .query().listOfRows();
            Map<String, Object> locked = SqlRows.one(jdbc.sql("SELECT \"invoiceId\", version FROM \"Order\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(orderId, orgId));
            if (locked != null && locked.get("invoiceId") != null) {
                return db.first(Invoice.class, "id = ?", locked.get("invoiceId"));
            }
            if (locked == null || PosSql.intOf(locked.get("version")) != PosSql.intOf(order.get("version"))) {
                throw ApiException.badRequest("Order changed while billing. Refresh the saved order and quote.");
            }
            // Post-parity (owner-approved 2026-11-10): re-check split bills under the Order lock. The settle routes
            // checked before taking any lock, so a full settle racing a split-bill settle billed the split items twice.
            if (jdbc.sql("SELECT count(*) FROM \"SplitBill\" WHERE \"sourceOrderId\" = ? AND \"organizationId\" = ?"
                    + " AND status::text <> 'void'").params(orderId, orgId).query(Long.class).single() > 0) {
                throw ApiException.badRequest("This order has a split in progress — settle each split bill instead.");
            }
            if (order.get("cashSessionId") == null && cashSessionId != null) {
                db.update(Order.class, Obj.of("cashSessionId", cashSessionId), "id = ?", orderId);
            }
            Totals totals = builder.prepareLines(PosLines.lineInputs(lineInputs));
            if (dto.get("expectedTotal") != null
                    && totals.total().subtract(dec(dto.get("expectedTotal"))).abs().compareTo(new BigDecimal("0.000001")) > 0) {
                throw ApiException.badRequest("The price has changed. Review the refreshed quote before taking payment.");
            }
            String invoiceNumber = sequence.next("invoice:" + year, "INV-" + year + "-", 6);
            Instant issueDate = occurredAt != null ? occurredAt : Instant.now();
            ZoneId zone;
            try {
                zone = OrgDates.orgTimezone(jdbc, orgId);
            } catch (RuntimeException e) {
                zone = ZoneOffset.UTC;
            }
            Instant businessDate = OrgDates.tradingDate(issueDate, zone);
            Map<String, Object> inv = db.insert(Invoice.class, Obj.of("organizationId", orgId,
                    "invoiceNumber", invoiceNumber, "orderId", orderId, "partnerId", order.get("partnerId"),
                    "branchId", dto.get("branchId") != null ? dto.get("branchId") : order.get("branchId"),
                    "cashSessionId", cashSessionId, "waiterId", order.get("waiterId"), "tableId", order.get("tableId"),
                    "issueDate", issueDate, "businessDate", businessDate.atOffset(ZoneOffset.UTC).toLocalDate(),
                    "subtotal", totals.subtotal(), "discountTotal", totals.discountTotal(), "discountType", txDiscType,
                    "discountValue", PosSql.decOf(txDiscValue), "discountSource", "manual", "discountReason", discountReason,
                    "discountAppliedBy", user(), "discountApprovedBy", dto.get("overrideById"),
                    "discountApprovedAt", dto.get("overrideById") != null ? PosDb.raw(NOW) : null,
                    "taxAmount", totals.taxAmount(), "totalAmount", totals.total(), "amountResidual", totals.total(),
                    "status", "draft", "settlementStatus", "unsettled", "paymentMode", dto.get("paymentMode"),
                    "paymentTermId", dto.get("paymentTermId") != null ? dto.get("paymentTermId") : order.get("paymentTermId"),
                    "reference", order.get("orderNumber"), "notes", order.get("notes"), "createdBy", user()));
            String invId = (String) inv.get("id");
            for (int i = 0; i < totals.prepared().size(); i++) {
                PreparedLine p = totals.prepared().get(i);
                Map<String, Object> src = items.get(i);
                Grouped accounts = builder.groupForPosting((String) order.get("partnerId"), List.of(p.asRow()), "sales", null);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("organizationId", orgId);
                data.put("invoiceId", invId);
                data.put("productId", p.productId());
                data.put("menuItemId", p.menuItemId());
                data.put("variantId", p.variantId());
                data.put("variantName", p.variantName());
                data.put("accompanimentOptionIds", PosSql.strings(src.get("accompanimentOptionIds")));
                data.put("accountId", accounts.itemByAccount().isEmpty() ? null : accounts.itemByAccount().keySet().iterator().next());
                data.put("taxAccountId", accounts.taxByAccount().isEmpty() ? null
                        : accounts.taxByAccount().keySet().iterator().next());
                data.put("description", p.description());
                data.put("quantity", p.quantity());
                data.put("unitPrice", p.unitPrice());
                data.put("discountPercent", p.discountPercent());
                data.put("discountType", p.discountType() == null ? "percentage" : p.discountType());
                data.put("discountAmount", p.discountAmount() == null ? BigDecimal.ZERO : p.discountAmount());
                data.put("discountReason", p.discountReason());
                data.put("discountSource", p.discountSource() == null ? "manual" : p.discountSource());
                data.put("discountAppliedBy", user());
                data.put("taxId", p.taxId());
                data.put("taxInclusive", p.taxInclusive());
                data.put("subtotal", p.subtotal());
                data.put("taxAmount", p.taxAmount());
                data.put("total", p.total());
                data.put("lineNumber", p.lineNumber());
                data.put("uomId", p.uomId());
                data.put("uomFactor", p.uomFactor());
                data.put("punchedById", src.get("punchedById") != null ? src.get("punchedById") : order.get("waiterId"));
                data.put("punchedByName", src.get("punchedByName"));
                Map<String, Object> item = db.insert(InvoiceItem.class, data);
                for (Object o : PosSql.list(src.get("modifiers"))) {
                    Map<String, Object> m = PosSql.map(o);
                    db.insert(InvoiceItemModifier.class, Obj.of("organizationId", orgId, "invoiceItemId", item.get("id"),
                            "modifierId", m.get("modifierId"), "name", m.get("name"), "kitchenPrintName", m.get("kitchenPrintName"),
                            "priceDelta", m.get("priceDelta")));
                }
            }
            BusinessOutcome.record(jdbc, Obj.of("invoiceId", invId, "invoiceNumber", invoiceNumber, "orderId", orderId,
                    "orderNumber", order.get("orderNumber"), "total", PosSql.js(inv.get("totalAmount"))), false);
            List<Map<String, Object>> dbItems = db.list(InvoiceItem.class, "\"invoiceId\" = ?", invId);
            Map<String, Object> dims = new LinkedHashMap<>();
            dims.put("source", "pos");
            if (cashSessionId != null) {
                dims.put("cashSessionId", cashSessionId);
            }
            if (order.get("waiterId") != null) {
                dims.put("cashierId", order.get("waiterId"));
            }
            if (order.get("deviceId") != null) {
                dims.put("registerId", order.get("deviceId"));
            }
            String journalEntryId = postInvoiceGl(inv, dbItems, dims);
            db.update(Invoice.class, Obj.of("journalEntryId", journalEntryId, "status", "posted", "postedAt", PosDb.raw(NOW),
                    "postedBy", user()), "id = ?", invId);
            if ("at_invoice".equals(timing)) {
                enqueueStockPosting(orderId, invId, invoiceNumber, "at_invoice");
            }
            workflows.transition("order", orderId, "complete", Obj.of("invoiceId", invId, "invoiceNumber", invoiceNumber),
                    order);
            db.update(Order.class, Obj.of("invoiceId", invId), "id = ?", orderId);
            snapshots.record("order", orderId);
            Map<String, Object> out = db.first(Invoice.class, "id = ?", invId);
            out.put("items", db.list(InvoiceItem.class, "\"invoiceId\" = ? ORDER BY \"lineNumber\" ASC", invId));
            return out;
        });
        if (!external) {
            try {
                reserveForInvoice(invoice, orderId);
            } catch (RuntimeException e) {
                log.warn("reservation skipped for {}: {}", invoice.get("invoiceNumber"), e.getMessage());
            }
            fiscalizeInvoice(invoice);
            events.publish(DomainEvents.POS_ORDER_INVOICED, Obj.of("organizationId", orgId, "orderId", orderId,
                    "invoiceId", invoice.get("id"), "invoiceNumber", invoice.get("invoiceNumber")));
            audit.record(AuditInput.of("Invoice", (String) invoice.get("id"), "post", null, Obj.of("kind", "invoice_generated",
                    "orderId", orderId, "total", JsNumbers.dec(SqlRows.dec(invoice, "totalAmount")))));
        }
        return invoice;
    }

    private void atpCheck(List<Map<String, Object>> items, String atpMode) {
        String orgId = org();
        String warehouse = locations.resolve(orgId, null);
        if (warehouse == null) {
            return;
        }
        for (Map<String, Object> it : items) {
            if (it.get("comboId") != null) {
                List<Map<String, Object>> comps = SqlRows.list(jdbc.sql("SELECT ci.\"productId\", ci.quantity FROM \"ComboItem\" ci"
                        + " JOIN \"Combo\" c ON c.id = ci.\"comboId\" WHERE c.id = ? AND c.\"organizationId\" = ?"
                        + " AND c.\"isActive\" = true").params(it.get("comboId"), orgId));
                if (comps.isEmpty()) {
                    throw new IllegalStateException("Combo is unavailable or has no stock components");
                }
                for (Map<String, Object> c : comps) {
                    BigDecimal q = Money.qtyMul(c.get("quantity"), it.get("quantity"));
                    if (!(q.signum() > 0)) {
                        throw new IllegalStateException("Combo component quantities must be greater than zero");
                    }
                    atp(it, (String) c.get("productId"), warehouse, q, "Low stock for \"" + it.get("description")
                            + "\": need " + JsNumbers.dec(q) + " of component, %s available", atpMode);
                }
            } else if (it.get("menuItemId") != null) {
                for (Map<String, Object> ing : SqlRows.list(jdbc.sql("SELECT \"productId\", quantity FROM \"MenuProduct\""
                        + " WHERE \"menuItemId\" = ? AND \"organizationId\" = ?").params(it.get("menuItemId"), orgId))) {
                    BigDecimal q = Money.qtyMul(ing.get("quantity"), it.get("quantity"));
                    atp(it, (String) ing.get("productId"), warehouse, q, "Low stock for \"" + it.get("description")
                            + "\": need " + JsNumbers.dec(q) + " of ingredient, %s available", atpMode);
                }
            } else if (it.get("productId") != null) {
                BigDecimal q = dec(it.get("quantity"));
                atp(it, (String) it.get("productId"), warehouse, q, "Low stock for \"" + it.get("description") + "\": need "
                        + JsFormat.str(q.doubleValue()) + ", %s available", atpMode);
            }
        }
    }

    private void atp(Map<String, Object> it, String productId, String warehouse, BigDecimal q, String template,
            String atpMode) {
        Map<String, Object> atp = reservations.availableToPromise(productId, warehouse, null);
        BigDecimal available = dec(atp.get("available"));
        if (q.compareTo(available) > 0) {
            String msg = String.format(template, PosSql.jsString(atp.get("available")));
            if ("strict".equals(atpMode)) {
                throw ApiException.badRequest(msg);
            }
            log.warn("[atp] {}", msg);
        }
    }

    private List<Map<String, Object>> itemsWithModifiers(String orderId) {
        List<Map<String, Object>> items = db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false"
                + " ORDER BY \"lineNumber\" ASC", orderId);
        PosModifiers.attach(jdbc, items, OrderItemModifier.class, "OrderItemModifier", "orderItemId");
        return items;
    }

    /** Dr AR / Cr revenue + output tax at the discounted amounts. */
    private String postInvoiceGl(Map<String, Object> invoice, List<Map<String, Object>> items, Map<String, Object> dims) {
        Grouped g = builder.groupForPosting((String) invoice.get("partnerId"), items, "sales", null);
        String counter = g.counterAccount();
        db.update(Invoice.class, Obj.of("receivableAccountId", counter), "id = ?", invoice.get("id"));
        String partnerId = (String) invoice.get("partnerId");
        List<PostingRequest.Line> lines = new ArrayList<>();
        lines.add(new PostingRequest.Line(counter, JsNumbers.dec(SqlRows.dec(invoice, "totalAmount")), null, partnerId,
                "Invoice " + invoice.get("invoiceNumber"), null, null, null));
        g.itemByAccount().forEach((acc, amount) -> lines.add(new PostingRequest.Line(acc, null, JsNumbers.dec(amount),
                partnerId, "Revenue", null, null, null)));
        g.taxByAccount().forEach((acc, amount) -> lines.add(new PostingRequest.Line(acc, null, JsNumbers.dec(amount), null,
                "Output tax", null, null, null)));
        Map<String, Object> entry = posting.post(new PostingRequest("SALES", (Instant) invoice.get("issueDate"),
                "Invoice " + invoice.get("invoiceNumber"), (String) invoice.get("currencyId"),
                PosSql.js(invoice.get("exchangeRate")), "pos_invoice", (String) invoice.get("id"), "primary",
                "pos_invoice:" + invoice.get("id") + ":primary", (String) invoice.get("branchId"), null, dims, lines));
        return (String) entry.get("id");
    }

    // ─── payments ───

    /** Tolerance of the tender equality checks (Nest: 0.000001 on JS numbers). */
    private static final BigDecimal TENDER_EPSILON = new BigDecimal("0.000001");

    /**
     * Sum of the tender amounts as an exact decimal. Post-parity: Nest added JS doubles, so a change or tender sum
     * could come back as {@code 10.670000000000002}; each amount is now taken as the decimal the client sent.
     */
    @SuppressWarnings("unchecked")
    static BigDecimal tenderSum(List<Map<String, Object>> tenders) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> t : tenders) {
            sum = sum.add(PosSql.dec(t.get("amount")));
        }
        return sum;
    }

    /** {@code max(0, handed − tendered)} as an exact decimal, as a JSON number. */
    static Number changeOf(Object amountTendered, BigDecimal tendersSum) {
        BigDecimal handed = amountTendered != null ? PosSql.dec(amountTendered) : tendersSum;
        return PosSql.js(handed.subtract(tendersSum).max(BigDecimal.ZERO));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeTenders(Map<String, Object> dto, double residualIn, boolean allowPartial) {
        List<Object> tenders = PosSql.list(dto.get("tenders"));
        if (!tenders.isEmpty()) {
            for (Object o : tenders) {
                double amt = num(((Map<String, Object>) o).get("amount"));
                if (!Double.isFinite(amt) || amt <= 0) {
                    throw ApiException.badRequest("Tender amounts must be positive finite numbers");
                }
            }
            List<Map<String, Object>> out = new ArrayList<>();
            tenders.forEach(o -> out.add((Map<String, Object>) o));
            BigDecimal sum = tenderSum(out);
            BigDecimal residual = PosSql.decOf(residualIn);
            if (sum.compareTo(residual.add(TENDER_EPSILON)) > 0) {
                throw ApiException.badRequest("Tenders sum " + JsFormat.str(sum.doubleValue()) + " exceeds amount due "
                        + JsFormat.str(residualIn));
            }
            if (sum.compareTo(residual.subtract(TENDER_EPSILON)) < 0 && !allowPartial) {
                throw ApiException.badRequest("Tenders sum " + JsFormat.str(sum.doubleValue()) + " does not match amount due "
                        + JsFormat.str(residualIn));
            }
            return out;
        }
        Object method = dto.get("paymentMethod");
        return List.of(Obj.of("method", method == null ? "cash" : method, "amount", residualIn));
    }

    private boolean sharedDrawer() {
        Object cfg = jdbc.sql("SELECT config FROM \"OrganizationModule\" WHERE \"organizationId\" = ? AND \"moduleName\" = 'pos'")
                .param(org()).query(PosSql.SCALAR).optional().map(PosSql::java).orElse(null);
        return cfg instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("sharedDrawer"));
    }

    public Map<String, Object> receivePayment(String invoiceId, Map<String, Object> dto) {
        return receivePayment(invoiceId, dto, false);
    }

    /** Receive one or more payments against an invoice and settle it (one tx, invoice row locked). */
    public Map<String, Object> receivePayment(String invoiceId, Map<String, Object> dto, boolean external) {
        String orgId = org();
        Map<String, Object> result = tx.execute(st -> {
            Object cashSessionId = dto.get("cashSessionId");
            if (cashSessionId != null) {
                jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(cashSessionId, orgId).query().listOfRows();
            }
            jdbc.sql("SELECT id FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(invoiceId, orgId)
                    .query().listOfRows();
            Map<String, Object> invoice = db.first(Invoice.class, "id = ? AND \"organizationId\" = ?", invoiceId, orgId);
            if (invoice == null) {
                throw ApiException.notFound("Invoice not found");
            }
            if ("cancelled".equals(invoice.get("status")) || "refunded".equals(invoice.get("status"))) {
                throw ApiException.badRequest("Invoice is " + invoice.get("status"));
            }
            double residual = num(invoice.get("amountResidual"));
            if (residual <= 0) {
                throw ApiException.badRequest("Invoice is already fully paid");
            }
            boolean skipGl = invoice.get("receivableAccountId") == null
                    && List.of("cash", "card", "mobile_money").contains(invoice.get("paymentMode"));
            if (skipGl) {
                throw ApiException.badRequest(
                        "Legacy invoice uses a cash/bank posting. Reconcile its original journal before collecting payment.");
            }
            List<Map<String, Object>> tenders = normalizeTenders(dto, residual, Boolean.TRUE.equals(dto.get("allowPartial")));
            BigDecimal tendersSum = tenderSum(tenders);
            boolean anyCash = tenders.stream().anyMatch(t -> "cash".equals(t.get("method")));
            if (anyCash && !PosSql.truthy(cashSessionId)) {
                throw ApiException.badRequest("Select an open register for a cash payment");
            }
            Object amountTendered = dto.get("amountTendered");
            if (amountTendered != null) {
                double h = num(amountTendered);
                if (!Double.isFinite(h) || PosSql.dec(amountTendered).compareTo(tendersSum) < 0
                        || (!anyCash && PosSql.dec(amountTendered).compareTo(tendersSum) > 0)) {
                    throw ApiException.badRequest("Cash handed over is invalid for these tenders");
                }
            }
            Instant occurredAt = OccurredAt.resolve((String) dto.get("occurredAt"));
            String paymentDate = JsonConfig.toJsIso(occurredAt != null ? occurredAt : Instant.now());
            boolean shared = sharedDrawer();
            String lastPaymentId = null;
            List<String> paymentIds = new ArrayList<>();
            for (Map<String, Object> t : tenders) {
                BigDecimal amount = dec(t.get("amount"));
                Map<String, Object> payment = payments.createReceipt(new PaymentService.PaymentInput(
                        (String) invoice.get("partnerId"), paymentDate, amount, (String) t.get("method"),
                        (String) t.get("accountId"), (String) t.get("reference"), null,
                        List.of(new PaymentService.AllocationInput(null, invoiceId, amount)), (String) cashSessionId,
                        null, null, null), true, shared);
                if (payment != null && payment.get("id") != null) {
                    lastPaymentId = (String) payment.get("id");
                    paymentIds.add(lastPaymentId);
                }
            }
            Map<String, Object> fresh = db.first(Invoice.class, "id = ?", invoiceId);
            boolean settled = num(fresh.get("amountResidual")) <= 0;
            boolean wasCredit = "credit".equals(invoice.get("paymentMode"));
            String settlementStatus = settled ? "settled" : "partially_settled";
            Object paymentMode = invoice.get("paymentMode");
            if (settled) {
                if (wasCredit) {
                    paymentMode = "credit";
                } else {
                    Set<String> modes = new LinkedHashSet<>();
                    for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT p.\"paymentMethod\", p.direction::text AS direction"
                            + " FROM \"PaymentAllocation\" a LEFT JOIN \"Payment\" p ON p.id = a.\"paymentId\""
                            + " WHERE a.\"invoiceId\" = ? AND a.\"organizationId\" = ? ORDER BY a.id").params(invoiceId, orgId))) {
                        String dir = a.get("direction") == null ? "inbound" : (String) a.get("direction");
                        if ("inbound".equals(dir)) {
                            modes.add(MODE_FROM_METHOD.getOrDefault(String.valueOf(a.get("paymentMethod")), "cash"));
                        }
                    }
                    paymentMode = modes.size() > 1 ? "mixed" : modes.isEmpty() ? "cash" : modes.iterator().next();
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("settlementStatus", settlementStatus);
            data.put("status", settled ? "paid" : invoice.get("status"));
            data.put("settledBy", user());
            data.put("version", PosDb.raw("version + 1"));
            if (settled) {
                data.put("paymentMode", paymentMode);
            }
            if (settled && amountTendered != null) {
                data.put("amountTendered", dec(amountTendered));
            }
            db.update(Invoice.class, data, "id = ?", invoiceId);
            String receiptType = settled ? (wasCredit ? "settlement_receipt" : "payment_receipt") : "partial_payment_receipt";
            Map<String, Object> receipt = createReceipt(invoice, receiptType, lastPaymentId);
            if (settled) {
                createReceipt(invoice, "merchant_copy", lastPaymentId);
            }
            Map<String, Object> closed = settled ? closeOrderForInvoice(invoiceId) : null;
            Number change = changeOf(amountTendered, tendersSum);
            BusinessOutcome.record(jdbc, Obj.of("invoiceId", invoiceId, "invoiceNumber", invoice.get("invoiceNumber"),
                    "total", PosSql.js(invoice.get("totalAmount")), "settlementStatus", settlementStatus,
                    "paymentMode", paymentMode, "receiptId", receipt.get("id"), "paymentIds", paymentIds,
                    "change", change, "amountDue", PosSql.js(fresh.get("amountResidual")), "settleMode", "tender"),
                    true);
            return Obj.of("paymentIds", paymentIds, "tendersSum", tendersSum, "settled", settled, "paymentMode", paymentMode,
                    "settlementStatus", settlementStatus, "receiptId", receipt.get("id"), "closed", closed,
                    "invoiceNumber", invoice.get("invoiceNumber"));
        });
        if (!external) {
            printReceiptSafe(invoiceId);
        }
        if (Boolean.TRUE.equals(result.get("settled")) && !external) {
            Map<String, Object> closed = PosSql.map(result.get("closed"));
            if (closed != null) {
                events.publish(DomainEvents.POS_ORDER_CLOSED, Obj.of("organizationId", orgId, "orderId", closed.get("orderId"),
                        "invoiceId", invoiceId));
            }
            events.publish(DomainEvents.POS_INVOICE_SETTLED, Obj.of("organizationId", orgId, "invoiceId", invoiceId,
                    "invoiceNumber", result.get("invoiceNumber"), "paymentMode", result.get("paymentMode")));
        }
        BigDecimal tendersSum = (BigDecimal) result.get("tendersSum");
        return Obj.of("paymentIds", result.get("paymentIds"), "invoiceId", invoiceId,
                "settlementStatus", result.get("settlementStatus"), "paymentMode", result.get("paymentMode"),
                "receiptId", result.get("receiptId"), "change", changeOf(dto.get("amountTendered"), tendersSum));
    }

    /** Post-commit side effects of a payment taken inside a caller's transaction. */
    public void afterExternalPaymentCommit(String invoiceId) {
        Map<String, Object> invoice = findInvoice(invoiceId);
        if (invoice == null) {
            return;
        }
        printReceiptSafe(invoiceId);
        events.publish(DomainEvents.POS_INVOICE_SETTLED, Obj.of("organizationId", org(), "invoiceId", invoiceId,
                "invoiceNumber", invoice.get("invoiceNumber"), "paymentMode",
                invoice.get("paymentMode") == null ? "mixed" : invoice.get("paymentMode")));
    }

    /** Settle on credit (postpaid house account). */
    public Map<String, Object> settleCredit(String invoiceId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = dtoIn == null ? Map.of() : dtoIn;
        String orgId = org();
        Map<String, Object> result = tx.execute(st -> {
            jdbc.sql("SELECT id FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(invoiceId, orgId)
                    .query().listOfRows();
            Map<String, Object> invoice = db.first(Invoice.class, "id = ? AND \"organizationId\" = ?", invoiceId, orgId);
            if (invoice == null) {
                throw ApiException.notFound("Invoice not found");
            }
            if ("cancelled".equals(invoice.get("status")) || "refunded".equals(invoice.get("status"))) {
                throw ApiException.badRequest("Invoice is " + invoice.get("status"));
            }
            if ("credit".equals(invoice.get("paymentMode"))) {
                throw ApiException.badRequest("Invoice is already settled on credit");
            }
            if (num(invoice.get("amountResidual")) <= 0.001) {
                throw ApiException.badRequest("Invoice is already settled");
            }
            String code = jdbc.sql("SELECT code FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(invoice.get("partnerId"), orgId).query(String.class).optional().orElse(null);
            if (code == null || "WALKIN".equals(code)) {
                throw ApiException.badRequest(
                        "A credit sale needs a named customer — walk-in invoices cannot be settled on account");
            }
            assertCreditAllowed((String) invoice.get("partnerId"), num(invoice.get("amountResidual")));
            db.update(Invoice.class, Obj.of("paymentMode", "credit", "settlementStatus", "unsettled", "settledBy", user(),
                    "version", PosDb.raw("version + 1")), "id = ?", invoiceId);
            Map<String, Object> receipt = createReceipt(invoice, "credit_issue_receipt", null);
            Map<String, Object> closed = closeOrderForInvoice(invoiceId);
            BusinessOutcome.record(jdbc, Obj.of("invoiceId", invoiceId, "invoiceNumber", invoice.get("invoiceNumber"),
                    "total", PosSql.js(invoice.get("totalAmount")), "receiptId", receipt.get("id"),
                    "settlementStatus", "unsettled", "paymentMode", "credit", "settleMode", "credit",
                    "amountDue", PosSql.js(invoice.get("amountResidual")), "change", 0), true);
            return Obj.of("receiptId", receipt.get("id"), "closed", closed, "invoiceNumber", invoice.get("invoiceNumber"),
                    "partnerId", invoice.get("partnerId"), "amount", JsNumbers.dec(SqlRows.dec(invoice, "amountResidual")));
        });
        Map<String, Object> closed = PosSql.map(result.get("closed"));
        if (closed != null) {
            events.publish(DomainEvents.POS_ORDER_CLOSED, Obj.of("organizationId", orgId, "orderId", closed.get("orderId"),
                    "invoiceId", invoiceId));
        }
        events.publish(DomainEvents.POS_INVOICE_CREDITED, Obj.of("organizationId", orgId, "invoiceId", invoiceId,
                "invoiceNumber", result.get("invoiceNumber"), "partnerId", result.get("partnerId"),
                "amount", result.get("amount")));
        audit.record(AuditInput.of("Invoice", invoiceId, "update", null, Obj.of("kind", "credit_issue",
                "notes", dto.get("notes"))));
        return Obj.of("invoiceId", invoiceId, "settlementStatus", "unsettled", "paymentMode", "credit",
                "receiptId", result.get("receiptId"));
    }

    /** Write off the outstanding balance (Dr bad debt / Cr AR). */
    public Map<String, Object> writeOff(String invoiceId, Map<String, Object> dto) {
        List<String> perms = pricing.currentPermissions(user());
        if (!perms.contains("pos:write_off")) {
            throw ApiException.badRequest("Write-off permission is required");
        }
        String reason = dto.get("reason") == null ? "" : dto.get("reason").toString();
        if (reason.trim().isEmpty()) {
            throw ApiException.badRequest("A reason is required to write off an invoice");
        }
        String orgId = org();
        return tx.execute(st -> {
            jdbc.sql("SELECT id FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(invoiceId, orgId)
                    .query().listOfRows();
            Map<String, Object> invoice = db.first(Invoice.class, "id = ? AND \"organizationId\" = ?", invoiceId, orgId);
            if (invoice == null) {
                throw ApiException.notFound("Invoice not found");
            }
            BigDecimal residual = SqlRows.dec(invoice, "amountResidual");
            if (residual.compareTo(new BigDecimal("0.001")) <= 0) {
                throw ApiException.badRequest("Nothing to write off");
            }
            String badDebt = determination.mapped("bad_debt");
            String ar = (String) invoice.get("receivableAccountId");
            if (ar == null) {
                throw ApiException.badRequest(
                        "Verify the original invoice receivable account before writing off a legacy invoice");
            }
            String amt = JsNumbers.dec(residual);
            posting.post(new PostingRequest("GEN", Instant.now(), "Write-off " + invoice.get("invoiceNumber") + " — " + reason,
                    null, null, "pos_invoice_writeoff", invoiceId, null, null, (String) invoice.get("branchId"), null, null,
                    List.of(new PostingRequest.Line(badDebt, amt, null, null, null, null, null, null),
                            new PostingRequest.Line(ar, null, amt, (String) invoice.get("partnerId"), null, null, null, null))));
            db.update(Invoice.class, Obj.of("settlementStatus", "written_off", "amountResidual", 0,
                    "version", PosDb.raw("version + 1")), "id = ?", invoiceId);
            List<String> tables = jdbc.sql("SELECT \"tableId\" FROM \"Order\" WHERE \"invoiceId\" = ? AND \"organizationId\" = ?")
                    .params(invoiceId, orgId).query(String.class).list();
            db.updateMany(Order.class, Obj.of("status", "closed", "closedAt", PosDb.raw(NOW)),
                    "\"invoiceId\" = ? AND \"organizationId\" = ?", invoiceId, orgId);
            for (String t : tables) {
                TableStatus.recompute(jdbc, orgId, t);
            }
            audit.recordInTx(AuditInput.of("Invoice", invoiceId, "update", null, Obj.of("kind", "write_off",
                    "reason", reason, "amount", amt)));
            events.publish(DomainEvents.POS_INVOICE_WRITTEN_OFF, Obj.of("organizationId", orgId, "invoiceId", invoiceId,
                    "invoiceNumber", invoice.get("invoiceNumber"), "amount", amt));
            Map<String, Object> outcome = Obj.of("invoiceId", invoiceId, "settlementStatus", "written_off", "amount", amt);
            BusinessOutcome.record(jdbc, outcome, true);
            return outcome;
        });
    }

    // ─── refund ───

    /** Refund / void a settled invoice (full or per line); GL reversal, payout over original tenders, disposition. */
    public Map<String, Object> refund(String invoiceId, String reason, String overrideById, String overridePin,
            String stockDisposition, String cashSessionId, List<Map<String, Object>> linesIn) {
        if (reason == null || reason.trim().isEmpty()) {
            throw ApiException.badRequest("A refund reason is required");
        }
        if (overrideById == null || overrideById.isEmpty()) {
            throw ApiException.badRequest("Manager approval is required for a refund");
        }
        overrides.verifyOperationApproval(overrideById, overridePin, "manual_refund");
        if (!List.of("restock", "waste", "no_return").contains(stockDisposition == null ? "" : stockDisposition)) {
            throw ApiException.badRequest("Choose the returned goods disposition");
        }
        String orgId = org();
        return tx.execute(st -> {
            if (cashSessionId != null) {
                jdbc.sql("SELECT id FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(cashSessionId, orgId).query().listOfRows();
            }
            jdbc.sql("SELECT id FROM \"Invoice\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(invoiceId, orgId)
                    .query().listOfRows();
            Map<String, Object> inv = db.first(Invoice.class, "id = ? AND \"organizationId\" = ?", invoiceId, orgId);
            if (inv == null) {
                throw ApiException.notFound("Invoice not found");
            }
            List<Map<String, Object>> invItems = db.list(InvoiceItem.class, "\"invoiceId\" = ?", invoiceId);
            if ("refunded".equals(inv.get("status")) || "cancelled".equals(inv.get("status"))
                    || "written_off".equals(inv.get("settlementStatus"))) {
                throw ApiException.badRequest("Invoice cannot be refunded");
            }
            if (inv.get("receivableAccountId") == null) {
                throw ApiException.badRequest("Reconcile this legacy invoice’s original posting before refunding it");
            }
            Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
            invItems.forEach(it -> byId.put((String) it.get("id"), it));
            List<Map<String, Object>> requested = new ArrayList<>();
            if (linesIn != null) {
                requested.addAll(linesIn);
            } else {
                for (Map<String, Object> it : invItems) {
                    double q = SqlRows.dec(it, "quantity").subtract(SqlRows.dec(it, "refundedQty")).doubleValue();
                    if (q > 0) {
                        requested.add(Obj.of("lineId", it.get("id"), "quantity", q));
                    }
                }
            }
            if (requested.isEmpty()) {
                throw ApiException.badRequest("Nothing remains to refund");
            }
            Set<Object> seen = new HashSet<>();
            List<Object[]> selections = new ArrayList<>();
            for (Map<String, Object> sel : requested) {
                if (!seen.add(sel.get("lineId"))) {
                    throw ApiException.badRequest("Duplicate refund line");
                }
                Map<String, Object> src = byId.get((String) sel.get("lineId"));
                if (src == null) {
                    throw ApiException.badRequest("Refund line is not on this invoice");
                }
                BigDecimal quantity = dec(sel.get("quantity"));
                BigDecimal remaining = SqlRows.dec(src, "quantity").subtract(SqlRows.dec(src, "refundedQty"));
                if (quantity.signum() <= 0 || quantity.compareTo(remaining) > 0) {
                    throw ApiException.badRequest("Refund quantity exceeds the remaining quantity");
                }
                if ("restock".equals(stockDisposition) && src.get("productId") == null && src.get("menuItemId") == null) {
                    throw ApiException.badRequest("Cannot restock this line type");
                }
                selections.add(new Object[] {src, quantity});
            }
            BigDecimal total = BigDecimal.ZERO;
            List<PostingRequest.Line> journal = new ArrayList<>();
            List<Map<String, Object>> refundItems = new ArrayList<>();
            String partnerId = (String) inv.get("partnerId");
            // Value already refunded per line (earlier partial refunds), for the last-units rule below.
            Map<String, BigDecimal[]> refundedValue = new HashMap<>();
            jdbc.sql("SELECT e->>'lineId' AS line, coalesce(sum((e->>'subtotal')::numeric), 0) AS sub,"
                            + " coalesce(sum((e->>'taxAmount')::numeric), 0) AS tax FROM \"PosRefund\" r,"
                            + " jsonb_array_elements(r.items::jsonb) e WHERE r.\"invoiceId\" = ? AND r.\"organizationId\" = ?"
                            + " GROUP BY 1")
                    .params(invoiceId, orgId).query((rs, n) -> refundedValue.put(rs.getString("line"),
                            new BigDecimal[] {rs.getBigDecimal("sub"), rs.getBigDecimal("tax")}))
                    .list();
            for (Object[] s : selections) {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) s[0];
                BigDecimal quantity = (BigDecimal) s[1];
                BigDecimal fraction = Money.div(quantity, SqlRows.dec(src, "quantity"));
                BigDecimal subtotal = SqlRows.dec(src, "subtotal").multiply(fraction).setScale(6, RoundingMode.HALF_UP);
                BigDecimal tax = SqlRows.dec(src, "taxAmount").multiply(fraction).setScale(6, RoundingMode.HALF_UP);
                // Post-parity: the last units of a line refund exactly what is left of its value. Nest rounded each
                // partial refund on its own, so the overshoot (e.g. 6 × 6.666667 = 40.000002) rejected the last unit.
                BigDecimal remainingQty = SqlRows.dec(src, "quantity").subtract(SqlRows.dec(src, "refundedQty"));
                if (quantity.compareTo(remainingQty) == 0) {
                    BigDecimal[] done = refundedValue.getOrDefault((String) src.get("id"),
                            new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    subtotal = SqlRows.dec(src, "subtotal").subtract(done[0]).max(BigDecimal.ZERO);
                    tax = SqlRows.dec(src, "taxAmount").subtract(done[1]).max(BigDecimal.ZERO);
                }
                total = total.add(subtotal).add(tax);
                if (src.get("accountId") == null) {
                    throw ApiException.badRequest(
                            "Original line revenue account is missing; reconcile the invoice before refunding");
                }
                journal.add(new PostingRequest.Line((String) src.get("accountId"), JsNumbers.dec(subtotal), null, partnerId,
                        null, null, null, null));
                if (tax.signum() > 0) {
                    if (src.get("taxAccountId") == null) {
                        throw ApiException.badRequest("Original tax posting account is missing");
                    }
                    journal.add(new PostingRequest.Line((String) src.get("taxAccountId"), JsNumbers.dec(tax), null, null, null,
                            null, null, null));
                }
                refundItems.add(Obj.of("lineId", src.get("id"), "quantity", JsNumbers.dec(quantity),
                        "subtotal", JsNumbers.dec(subtotal), "taxAmount", JsNumbers.dec(tax)));
            }
            if (total.signum() <= 0 || SqlRows.dec(inv, "amountRefunded").add(total)
                    .compareTo(SqlRows.dec(inv, "totalAmount").add(new BigDecimal("0.000001"))) > 0) {
                throw ApiException.badRequest("Refund exceeds remaining invoice value");
            }
            Map<String, Object> refund = db.insert(PosRefund.class, Obj.of("cashSessionId", cashSessionId,
                    "organizationId", orgId, "invoiceId", invoiceId, "amount", total, "reason", reason.trim(),
                    "stockDisposition", stockDisposition, "items", PosDb.json(refundItems), "payments", PosDb.json(List.of()),
                    "approvedById", overrideById, "createdBy", user()));
            String refundId = (String) refund.get("id");
            journal.add(new PostingRequest.Line((String) inv.get("receivableAccountId"), null, JsNumbers.dec(total), partnerId,
                    null, null, null, null));
            Map<String, Object> entry = posting.post(new PostingRequest("SALES", Instant.now(),
                    "Refund " + inv.get("invoiceNumber") + ": " + reason, null, null, "pos_refund", refundId, null,
                    "pos_refund:" + refundId, (String) inv.get("branchId"), null, null, journal));
            BigDecimal residual = SqlRows.dec(inv, "amountResidual");
            BigDecimal arReduction = residual.compareTo(total) < 0 ? residual : total;
            BigDecimal payout = total.subtract(arReduction);
            List<Map<String, Object>> allocs = SqlRows.list(jdbc.sql("SELECT a.id, a.\"paymentId\", a.amount, a.\"refundedAmount\","
                    + " p.\"paymentMethod\", p.\"accountId\", p.\"paymentNumber\" FROM \"PaymentAllocation\" a JOIN \"Payment\" p"
                    + " ON p.id = a.\"paymentId\" WHERE a.\"invoiceId\" = ? AND a.\"organizationId\" = ?"
                    + " AND p.direction::text = 'inbound' AND p.status::text <> 'cancelled' ORDER BY a.id ASC").params(invoiceId, orgId));
            for (String pid : new TreeSet<>(allocs.stream().map(a -> (String) a.get("paymentId")).toList())) {
                jdbc.sql("SELECT id FROM \"Payment\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(pid, orgId)
                        .query().listOfRows();
            }
            List<Object[]> available = new ArrayList<>();
            BigDecimal collected = BigDecimal.ZERO;
            for (Map<String, Object> a : allocs) {
                BigDecimal amt = SqlRows.dec(a, "amount").subtract(SqlRows.dec(a, "refundedAmount"));
                if (amt.signum() > 0) {
                    available.add(new Object[] {a, amt});
                    collected = collected.add(amt);
                }
            }
            if (payout.compareTo(collected) > 0) {
                throw ApiException.badRequest("Collected payment allocations do not cover this refund");
            }
            List<Map<String, Object>> paymentLinks = new ArrayList<>();
            BigDecimal payoutTotal = payout;
            for (int i = 0; i < available.size() && payout.signum() > 0; i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> a = (Map<String, Object>) available.get(i)[0];
                BigDecimal amount = (BigDecimal) available.get(i)[1];
                BigDecimal share = i == available.size() - 1 ? payout
                        : Money.div(payoutTotal.multiply(amount), collected).setScale(6, RoundingMode.HALF_UP);
                if (share.signum() <= 0) {
                    continue;
                }
                String method = (String) a.get("paymentMethod");
                if ("cash".equals(method) && cashSessionId == null) {
                    throw ApiException.badRequest("Select the current open register for the cash refund");
                }
                Map<String, Object> returned = payments.createCustomerRefund(new PaymentService.PaymentInput(partnerId,
                        JsonConfig.toJsIso(Instant.now()), PosSql.decOf(share.doubleValue()), method,
                        "cash".equals(method) ? null : (String) a.get("accountId"),
                        "Refund " + refundId + " of " + a.get("paymentNumber"), null, null, cashSessionId, null, null,
                        (String) a.get("paymentId")), true, true);
                jdbc.sql("UPDATE \"Payment\" SET \"refundedAmount\" = \"refundedAmount\" + ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ?").params(share, a.get("paymentId")).update();
                jdbc.sql("UPDATE \"PaymentAllocation\" SET \"refundedAmount\" = \"refundedAmount\" + ? WHERE id = ?")
                        .params(share, a.get("id")).update();
                paymentLinks.add(Obj.of("originalPaymentId", a.get("paymentId"), "paymentId", returned.get("id"),
                        "amount", JsNumbers.dec(share), "accountId", returned.get("accountId")));
                payout = payout.subtract(share);
            }
            if ("restock".equals(stockDisposition)) {
                long jobs = jdbc.sql("SELECT count(*) FROM \"StockPostingJob\" WHERE \"invoiceId\" = ? AND \"organizationId\" = ?"
                        + " AND status <> 'done'").params(invoiceId, orgId).query(Long.class).single();
                if (jobs > 0) {
                    throw ApiException.badRequest("Resolve pending stock posting before restocking this return");
                }
                for (Object[] s : selections) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> src = (Map<String, Object>) s[0];
                    restockLine(inv, invItems, src, (BigDecimal) s[1], refundId);
                }
            }
            for (Object[] s : selections) {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) s[0];
                jdbc.sql("UPDATE \"InvoiceItem\" SET \"refundedQty\" = \"refundedQty\" + ? WHERE id = ?")
                        .params(s[1], src.get("id")).update();
            }
            BigDecimal refunded = SqlRows.dec(inv, "amountRefunded").add(total);
            boolean fully = refunded.compareTo(SqlRows.dec(inv, "totalAmount").subtract(new BigDecimal("0.000001"))) >= 0;
            Map<String, Object> data = Obj.of("amountRefunded", refunded, "amountResidual", residual.subtract(arReduction),
                    "refundedBy", user(), "version", PosDb.raw("version + 1"));
            if (fully) {
                data.put("status", "refunded");
                data.put("settlementStatus", "settled");
            }
            db.update(Invoice.class, data, "id = ?", invoiceId);
            db.update(PosRefund.class, Obj.of("payments", PosDb.json(paymentLinks), "journalEntryId", entry.get("id")),
                    "id = ?", refundId);
            audit.recordInTx(AuditInput.of("Invoice", invoiceId, "update", null, Obj.of("refundId", refundId,
                    "amount", JsNumbers.dec(total), "reason", reason, "stockDisposition", stockDisposition,
                    "payments", paymentLinks, "approvedById", overrideById)));
            Map<String, Object> closed = fully ? closeOrderForInvoice(invoiceId) : null;
            Map<String, Object> outcome = Obj.of("invoiceId", invoiceId, "refundId", refundId,
                    "status", fully ? "refunded" : "partially_refunded", "amount", JsNumbers.dec(total),
                    "amountReturned", JsNumbers.dec(payoutTotal), "payments", paymentLinks, "closed", closed);
            BusinessOutcome.record(jdbc, outcome, true);
            return outcome;
        });
    }

    private void restockLine(Map<String, Object> inv, List<Map<String, Object>> invItems, Map<String, Object> src,
            BigDecimal quantity, String refundId) {
        String orgId = org();
        List<Map<String, Object>> ingredients = db.list(InvoiceItemRecipeIngredient.class,
                "\"invoiceItemId\" = ? AND \"organizationId\" = ?", src.get("id"), orgId);
        if (!ingredients.isEmpty()) {
            String locId = saleLocation(inv);
            if (locId == null) {
                throw ApiException.badRequest("No active warehouse configured — cannot restock inventory");
            }
            BigDecimal fraction = Money.div(quantity, SqlRows.dec(src, "quantity"));
            for (Map<String, Object> ing : ingredients) {
                BigDecimal baseQty = ing.get("baseQuantity") != null ? SqlRows.dec(ing, "baseQuantity")
                        : SqlRows.dec(ing, "unitCost").signum() > 0
                                ? Money.div(SqlRows.dec(ing, "totalValue"), SqlRows.dec(ing, "unitCost"))
                                : SqlRows.dec(ing, "quantity");
                BigDecimal returnQty = baseQty.multiply(fraction).setScale(6, RoundingMode.HALF_UP);
                if (returnQty.signum() <= 0) {
                    continue;
                }
                BigDecimal returnValue = SqlRows.dec(ing, "totalValue").multiply(fraction);
                StockMove mv = StockMove.of((String) ing.get("productId"), locId, PosSql.decOf(returnQty.doubleValue()));
                mv.unitCost = PosSql.decOf((SqlRows.dec(ing, "totalValue").signum() > 0
                        ? Money.div(returnValue, returnQty) : SqlRows.dec(ing, "unitCost")).doubleValue());
                mv.reference = "Refund " + refundId;
                mv.sourceType = "pos_refund";
                mv.sourceId = refundId;
                stock.receiveReturn(mv);
            }
            return;
        }
        List<Map<String, Object>> issues = SqlRows.list(jdbc.sql("SELECT \"locationId\", \"quantityChange\", \"unitCost\""
                + " FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ?"
                + " AND \"referenceId\" = ? AND \"referenceType\" = 'pos_invoice' AND \"quantityChange\" < 0")
                .params(orgId, src.get("productId"), src.get("variantId"), inv.get("id")));
        Set<Object> locs = new LinkedHashSet<>();
        issues.forEach(i -> locs.add(i.get("locationId")));
        if (locs.size() != 1) {
            throw ApiException.badRequest(
                    "Original stock issue location is missing or ambiguous; resolve the stock disposition first");
        }
        Map<String, Object> product = db.first(Product.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                src.get("productId"), orgId);
        if (product == null || Boolean.TRUE.equals(product.get("serialTracking")) || Boolean.TRUE.equals(product.get("batchTracking"))) {
            throw ApiException.badRequest("Tracked returns require an identified stock return; choose no return and record"
                    + " the stock disposition separately");
        }
        BigDecimal issuedQty = BigDecimal.ZERO;
        BigDecimal issuedValue = BigDecimal.ZERO;
        for (Map<String, Object> i : issues) {
            BigDecimal q = SqlRows.dec(i, "quantityChange").abs();
            issuedQty = issuedQty.add(q);
            issuedValue = issuedValue.add(q.multiply(SqlRows.dec(i, "unitCost")));
        }
        BigDecimal unitCost = Money.div(issuedValue, issuedQty);
        BigDecimal soldQty = BigDecimal.ZERO;
        for (Map<String, Object> it : invItems) {
            if (java.util.Objects.equals(it.get("productId"), src.get("productId"))
                    && java.util.Objects.equals(it.get("variantId"), src.get("variantId")) && it.get("menuItemId") == null) {
                soldQty = soldQty.add(SqlRows.dec(it, "quantity"));
            }
        }
        BigDecimal returned = Money.div(issuedQty.multiply(quantity), soldQty).setScale(6, RoundingMode.HALF_UP);
        StockMove mv = StockMove.of((String) src.get("productId"), (String) locs.iterator().next(),
                PosSql.decOf(returned.doubleValue()));
        mv.variantId = (String) src.get("variantId");
        mv.unitCost = PosSql.decOf(unitCost.doubleValue());
        mv.reference = "Refund " + refundId;
        mv.sourceType = "pos_refund";
        mv.sourceId = refundId;
        stock.receiveReturn(mv);
    }

    private String saleLocation(Map<String, Object> inv) {
        String orgId = org();
        String preferred = null;
        if (inv.get("cashSessionId") != null) {
            Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT cs.\"registerLocationId\", cr.\"locationId\" FROM \"CashSession\" cs"
                    + " LEFT JOIN \"CashRegister\" cr ON cr.id = cs.\"cashRegisterId\" WHERE cs.id = ? AND cs.\"organizationId\" = ?")
                    .params(inv.get("cashSessionId"), orgId));
            if (s != null) {
                preferred = s.get("registerLocationId") != null ? (String) s.get("registerLocationId")
                        : (String) s.get("locationId");
            }
        }
        return locations.resolve(orgId, preferred);
    }

    // ─── receipts / close ───

    private Map<String, Object> createReceipt(Map<String, Object> invoice, String type, String paymentId) {
        String orgId = org();
        String receiptNumber = sequence.next("receipt", "RCT-", 6);
        Map<String, Object> receipt = db.insert(Receipt.class, Obj.of("organizationId", orgId, "invoiceId", invoice.get("id"),
                "receiptNumber", receiptNumber, "type", type, "paymentId", paymentId, "printedById", user()));
        for (Map<String, Object> it : db.list(InvoiceItem.class, "\"invoiceId\" = ? ORDER BY \"lineNumber\" ASC",
                invoice.get("id"))) {
            db.insert(ReceiptItem.class, Obj.of("organizationId", orgId, "receiptId", receipt.get("id"),
                    "invoiceItemId", it.get("id"), "description", it.get("description"), "quantity", it.get("quantity"),
                    "unitPrice", it.get("unitPrice"), "total", it.get("total"), "lineNumber", it.get("lineNumber")));
        }
        return receipt;
    }

    private void printReceiptSafe(String invoiceId) {
        try {
            receipts.printReceipt(invoiceId, user(), false);
        } catch (RuntimeException e) {
            log.warn("auto-print receipt failed for {}: {}", invoiceId, e.getMessage());
        }
    }

    /** Close the order of an invoice and free its table (caller's transaction). */
    Map<String, Object> closeOrderForInvoice(String invoiceId) {
        String orgId = org();
        Map<String, Object> order = db.first(Order.class, "\"invoiceId\" = ? AND \"organizationId\" = ?", invoiceId, orgId);
        if (order == null) {
            return null;
        }
        if (!"closed".equals(order.get("status"))) {
            workflows.transition("order", (String) order.get("id"), "close", Obj.of("invoiceId", invoiceId), order);
        }
        db.update(Order.class, Obj.of("closedAt", PosDb.raw(NOW)), "id = ?", order.get("id"));
        String tableId = (String) order.get("tableId");
        if (tableId != null) {
            jdbc.sql("UPDATE \"PosTableOrder\" SET \"closedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE"
                    + " \"organizationId\" = ? AND \"tableId\" = ? AND \"orderId\" = ? AND \"closedAt\" IS NULL")
                    .params(orgId, tableId, order.get("id")).update();
        }
        TableStatus.recompute(jdbc, orgId, tableId);
        return Obj.of("orderId", order.get("id"), "tableId", tableId);
    }

    /** Credit control under row locks (D3); an unset limit fails closed unless the org allows unlimited credit. */
    public void assertCreditAllowed(String partnerId, double amount) {
        String orgId = org();
        CreditStatus status = CreditStatus.resolve(jdbc, orgId, partnerId, true);
        if (status.creditHold()) {
            throw ApiException.badRequest("This customer is on credit hold — new credit sales are blocked");
        }
        if (status.creditLimit() <= 0) {
            Object settingsJson = jdbc.sql("SELECT settings FROM \"Organization\" WHERE id = ?").param(orgId)
                    .query(PosSql.SCALAR).optional().map(PosSql::java).orElse(null);
            Map<String, Object> credit = settingsJson instanceof Map<?, ?> m ? PosSql.map(PosSql.map(m).get("credit")) : null;
            if (credit != null && Boolean.TRUE.equals(credit.get("allowUnlimited"))) {
                return;
            }
            throw ApiException.badRequest(
                    "This customer has no credit limit set, so they cannot buy on account. Set a credit limit on the customer first.");
        }
        if (status.outstanding() + amount > status.creditLimit() + 0.01) {
            throw ApiException.badRequest("Credit limit exceeded: outstanding " + JsFormat.str(status.outstanding()) + " + "
                    + JsFormat.str(amount) + " > limit " + JsFormat.str(status.creditLimit()));
        }
    }

    // ─── reservations / fiscal ───

    private void reserveForInvoice(Map<String, Object> invoice, String orderId) {
        String mode = reservations.mode();
        if (mode == null || "none".equals(mode)) {
            return;
        }
        String orgId = org();
        String warehouse = locations.resolve(orgId, null);
        if (warehouse == null) {
            return;
        }
        Map<String, Double> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> it : db.list(OrderItem.class, "\"orderId\" = ? AND cancelled = false AND \"organizationId\" = ?",
                orderId, orgId)) {
            if (it.get("rentalAgreementLineId") != null || it.get("repairOrderLineId") != null) {
                continue;
            }
            double lineQty = num(it.get("quantity"));
            if (it.get("menuItemId") != null) {
                Boolean tracked = jdbc.sql("SELECT \"isInventoryTracked\" FROM \"MenuItem\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(it.get("menuItemId"), orgId).query(Boolean.class).optional().orElse(null);
                if (!Boolean.TRUE.equals(tracked)) {
                    continue;
                }
                for (Map<String, Object> ing : SqlRows.list(jdbc.sql("SELECT \"productId\", quantity FROM \"MenuProduct\""
                        + " WHERE \"menuItemId\" = ? AND \"organizationId\" = ?").params(it.get("menuItemId"), orgId))) {
                    double q = Money.qtyMul(ing.get("quantity"), lineQty).doubleValue();
                    if (q > 0) {
                        byProduct.merge((String) ing.get("productId"), q, Double::sum);
                    }
                }
            } else if (it.get("productId") != null) {
                Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT \"trackInventory\", \"productType\"::text AS t FROM \"Product\""
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(it.get("productId"), orgId));
                if (p != null && Boolean.TRUE.equals(p.get("trackInventory"))
                        && ("stockable".equals(p.get("t")) || "consumable".equals(p.get("t"))) && lineQty > 0) {
                    byProduct.merge((String) it.get("productId"), lineQty, Double::sum);
                }
            }
        }
        for (Map.Entry<String, Double> e : byProduct.entrySet()) {
            reservations.reserve(new StockReservationService.Reserve(e.getKey(), null, warehouse, PosSql.decOf(e.getValue()),
                    "invoice", (String) invoice.get("id"), "POS bill " + invoice.get("invoiceNumber")));
        }
    }

    private void fiscalizeInvoice(Map<String, Object> invoice) {
        String provider = System.getenv().getOrDefault("FISCAL_PROVIDER", "none").toLowerCase();
        if ("none".equals(provider)) {
            return;
        }
        try {
            jdbc.sql("UPDATE \"Invoice\" SET \"fiscalStatus\" = 'pending', \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .param(invoice.get("id")).update();
        } catch (RuntimeException ignored) {
            // best-effort
        }
        audit.record(AuditInput.of("Invoice", (String) invoice.get("id"), "update", null, Obj.of("kind",
                "fiscalization_pending", "provider", provider, "invoiceNumber", invoice.get("invoiceNumber"))));
        log.warn("[fiscal] provider '{}' set but no adapter wired — invoice {} not fiscally signed", provider,
                invoice.get("invoiceNumber"));
    }

    // ─── stock posting jobs ───

    /** Process one durable stock-posting job (worker tenant scope). */
    public void processStockPostingJob(String jobId) {
        String orgId = org();
        Map<String, Object> job = db.first(StockPostingJob.class, "id = ? AND \"organizationId\" = ?", jobId, orgId);
        if (job == null || "done".equals(job.get("status"))) {
            return;
        }
        Ctx ctx = new Ctx((String) job.get("invoiceId"), (String) job.get("invoiceNumber"), (String) job.get("orderId"));
        try {
            Integer failures = tx.execute(st -> {
                List<String> locked = jdbc.sql("SELECT status FROM \"StockPostingJob\" WHERE id = ? FOR UPDATE").param(jobId)
                        .query(String.class).list();
                if (locked.isEmpty() || "done".equals(locked.getFirst())) {
                    return null;
                }
                Map<String, Object> current = db.first(StockPostingJob.class, "id = ?", jobId);
                List<Map<String, Object>> items = ctx.orderId() == null ? List.of() : itemsWithModifiers(ctx.orderId());
                Object snapshotRaw = PosSql.java(current.get("recipeSnapshot") != null ? current.get("recipeSnapshot")
                        : job.get("recipeSnapshot"));
                Map<String, Object> snapshots = snapshotRaw instanceof Map<?, ?> ? PosSql.map(snapshotRaw) : Map.of();
                Object[] result = issueStockForItems(items, ctx, new LinkedHashSet<>(PosSql.strings(current.get("postedLineKeys"))),
                        snapshots);
                completeJob(jobId, (int) result[0], PosSql.strings(result[1]), PosSql.intOf(current.get("attempts")),
                        PosSql.intOf(current.get("maxAttempts")), (String) result[2]);
                return (int) result[0];
            });
            if (failures == null) {
                return;
            }
            if (failures > 0) {
                alertStockPostingFailure(ctx, failures + " line(s) could not be deducted", failures);
            }
            if (ctx.invoiceId() != null && failures == 0) {
                try {
                    reservations.consume("invoice", ctx.invoiceId());
                } catch (RuntimeException e) {
                    log.warn("reservation consume failed for {}: {}", ctx.invoiceNumber(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            int attempts = PosSql.intOf(job.get("attempts")) + 1;
            String raw = e.getMessage() == null ? e.toString() : e.getMessage();
            String msg = raw.length() > 500 ? raw.substring(0, 500) : raw;
            if (attempts >= PosSql.intOf(job.get("maxAttempts"))) {
                db.update(StockPostingJob.class, Obj.of("status", "failed", "attempts", attempts, "lastError", msg,
                        "claimToken", null, "claimedAt", null, "processedAt", PosDb.raw(NOW)), "id = ?", jobId);
                if (ctx.invoiceId() != null) {
                    try {
                        reservations.release("invoice", ctx.invoiceId());
                    } catch (RuntimeException re) {
                        log.warn("reservation release failed for {}: {}", ctx.invoiceNumber(), re.getMessage());
                    }
                }
                recordInventoryException(ctx, "whole_invoice", null, null, "whole-invoice stock deduction", 0, null, msg,
                        stackOf(e), null);
                alertStockPostingFailure(ctx, msg, 0);
            } else {
                long backoff = Math.min(60_000L * attempts, 15 * 60_000L);
                db.update(StockPostingJob.class, Obj.of("status", "pending", "attempts", attempts, "lastError", msg,
                        "claimToken", null, "claimedAt", null, "nextRetryAt", Instant.now().plusMillis(backoff)), "id = ?", jobId);
            }
        }
    }

    private static String stackOf(Throwable e) {
        java.io.StringWriter w = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(w));
        String s = w.toString();
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    private void completeJob(String jobId, int failures, List<String> postedLineKeys, int attemptsIn, int maxAttempts,
            String lastError) {
        if (failures == 0) {
            db.update(StockPostingJob.class, Obj.of("status", "done", "processedAt", PosDb.raw(NOW), "claimToken", null,
                    "claimedAt", null, "lastError", null, "postedLineKeys", postedLineKeys), "id = ?", jobId);
            return;
        }
        int attempts = attemptsIn + 1;
        boolean exhausted = attempts >= maxAttempts;
        String err = (failures + " line(s) could not be deducted and need review" + (lastError != null ? ": " + lastError : ""));
        Map<String, Object> data = Obj.of("status", exhausted ? "failed" : "pending", "attempts", attempts,
                "postedLineKeys", postedLineKeys, "lastError", err.length() > 500 ? err.substring(0, 500) : err,
                "claimToken", null, "claimedAt", null,
                "nextRetryAt", Instant.now().plusMillis(Math.min(60_000L * attempts, 15 * 60_000L)));
        if (exhausted) {
            data.put("processedAt", PosDb.raw(NOW));
        }
        db.update(StockPostingJob.class, data, "id = ?", jobId);
    }

    /** {failures, postedLineKeys, lastError}. Throws only on a systemic failure (no warehouse). */
    private Object[] issueStockForItems(List<Map<String, Object>> items, Ctx ctx, Set<String> alreadyPosted,
            Map<String, Object> snapshots) {
        String orgId = org();
        String preferred = null;
        if (ctx.invoiceId() != null) {
            Map<String, Object> inv = db.first(Invoice.class, "id = ? AND \"organizationId\" = ?", ctx.invoiceId(), orgId);
            if (inv != null) {
                String loc = null;
                if (inv.get("cashSessionId") != null) {
                    Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT cs.\"registerLocationId\", cr.\"locationId\" FROM"
                            + " \"CashSession\" cs LEFT JOIN \"CashRegister\" cr ON cr.id = cs.\"cashRegisterId\""
                            + " WHERE cs.id = ? AND cs.\"organizationId\" = ?").params(inv.get("cashSessionId"), orgId));
                    if (s != null) {
                        loc = s.get("registerLocationId") != null ? (String) s.get("registerLocationId") : (String) s.get("locationId");
                    }
                }
                preferred = loc;
            }
        }
        String warehouse = locations.resolve(orgId, preferred);
        if (warehouse == null) {
            throw new IllegalStateException("No active warehouse configured — cannot deduct stock");
        }
        Map<Integer, String> invoiceItemByLine = new LinkedHashMap<>();
        Map<Integer, Map<String, Object>> unitByLine = new LinkedHashMap<>();
        if (ctx.invoiceId() != null) {
            for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, \"lineNumber\", \"uomId\", \"uomFactor\" FROM"
                    + " \"InvoiceItem\" WHERE \"invoiceId\" = ? AND \"organizationId\" = ?").params(ctx.invoiceId(), orgId))) {
                invoiceItemByLine.put(PosSql.intOf(r.get("lineNumber")), (String) r.get("id"));
                if (r.get("uomFactor") != null) {
                    unitByLine.put(PosSql.intOf(r.get("lineNumber")), r);
                }
            }
        }
        String reference = ctx.invoiceNumber();
        String ref = "POS bill " + reference;
        int[] failures = {0};
        String[] lastError = {null};
        Set<String> posted = new LinkedHashSet<>(alreadyPosted);
        for (Map<String, Object> it : items) {
            if (it.get("rentalAgreementLineId") != null || it.get("repairOrderLineId") != null) {
                continue;
            }
            String invoiceItemId = invoiceItemByLine.get(PosSql.intOf(it.get("lineNumber")));
            String lineKey = String.valueOf(it.get("id"));
            // Issue in the unit frozen on the invoice item (ADR-S003), not the product's current sales unit.
            Map<String, Object> unit = unitByLine.get(PosSql.intOf(it.get("lineNumber")));
            Map<String, Object> line = unit == null ? it : new LinkedHashMap<>(it);
            if (unit != null) {
                line.put("uomId", unit.get("uomId"));
                line.put("uomFactor", unit.get("uomFactor"));
            }
            if (!posted.contains(lineKey)) {
                try {
                    Savepoints.run(jdbc, () -> issueLine(line, warehouse, ctx, ref, invoiceItemId,
                            PosSql.map(snapshots.get((String) it.get("id")))));
                    posted.add(lineKey);
                } catch (RuntimeException e) {
                    log.error("[stock] issue failed for \"{}\" on {} (sale kept): {}", it.get("description"), reference,
                            e.getMessage());
                    recordFailure(ctx, failures, lastError, it.get("menuItemId") != null ? "menu_recipe" : "product",
                            (String) it.get("productId"), (String) it.get("menuItemId"), (String) it.get("description"),
                            num(it.get("quantity")), warehouse, e.getMessage() == null ? e.toString() : e.getMessage(),
                            stackOf(e), Obj.of("orderItemId", it.get("id"), "invoiceItemId", invoiceItemId, "reference", reference),
                            lineKey);
                }
            }
            for (Object[] f : moveLineExtras(it, num(it.get("quantity")), warehouse, ref, ctx, invoiceItemId, posted)) {
                RuntimeException err = (RuntimeException) f[3];
                recordFailure(ctx, failures, lastError, "line_extras", (String) f[2], (String) it.get("menuItemId"),
                        f[1] + " on " + (it.get("description") != null ? it.get("description")
                                : it.get("productId") != null ? it.get("productId") : it.get("menuItemId")),
                        num(it.get("quantity")), warehouse, err.getMessage() == null ? err.toString() : err.getMessage(),
                        stackOf(err), Obj.of("orderItemId", it.get("id"), "invoiceItemId", invoiceItemId, "reference", reference,
                                "componentId", f[0], "componentType", f[1]), lineKey + ":" + f[0]);
            }
        }
        return new Object[] {failures[0], new ArrayList<>(posted), lastError[0]};
    }

    private void recordFailure(Ctx ctx, int[] failures, String[] lastError, String kind, String productId,
            String menuItemId, String description, double quantity, String locationId, String reason, String stack,
            Map<String, Object> payload, String lineKey) {
        failures[0]++;
        lastError[0] = reason != null ? reason : lastError[0];
        long existing = jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ?"
                        + " AND \"invoiceId\" IS NOT DISTINCT FROM ? AND status = 'open' AND kind = ? AND payload->>'lineKey' = ?")
                .params(org(), ctx.invoiceId(), kind, lineKey).query(Long.class).single();
        if (existing > 0) {
            return;
        }
        Map<String, Object> p = new LinkedHashMap<>(payload);
        p.put("lineKey", lineKey);
        try {
            Savepoints.run(jdbc, () -> recordInventoryException(ctx, kind, productId, menuItemId, description, quantity,
                    locationId, reason, stack, p));
        } catch (RuntimeException ignored) {
            // recording drift must not break the job
        }
    }

    private void issueLine(Map<String, Object> it, String warehouse, Ctx ctx, String ref, String invoiceItemId,
            Map<String, Object> snapshot) {
        String orgId = org();
        if (it.get("comboId") != null) {
            List<Object> components = snapshot != null && "combo".equals(snapshot.get("kind"))
                    ? PosSql.list(snapshot.get("components")) : List.of();
            if (components.isEmpty()) {
                throw new IllegalStateException("Combo has no immutable stock-component snapshot");
            }
            for (Object o : components) {
                Map<String, Object> c = PosSql.map(o);
                BigDecimal quantity = Money.qtyMul(c.get("quantity"), it.get("quantity"));
                if (quantity.signum() <= 0) {
                    throw new IllegalStateException("Combo component quantities must be greater than zero");
                }
                if (!Boolean.TRUE.equals(c.get("trackInventory"))
                        || !List.of("stockable", "consumable").contains(String.valueOf(c.get("productType")))) {
                    continue;
                }
                StockMove mv = StockMove.of((String) c.get("productId"), warehouse, quantity);
                mv.uomId = (String) c.get("uomId");
                mv.reference = ref;
                mv.sourceType = "pos_invoice_combo";
                mv.sourceId = it.get("id") + ":" + c.get("id");
                StockMove.Issued r = stock.issue(mv);
                if (invoiceItemId != null) {
                    snapshotRow(invoiceItemId, ctx, (String) c.get("productId"), r, quantity, BigDecimal.ONE, "combo",
                            (String) c.get("id"));
                }
            }
        } else if (it.get("menuItemId") != null) {
            issueMenuItemRecipe((String) it.get("menuItemId"), it.get("quantity"), warehouse, ctx, ref,
                    (String) it.get("variantId"), invoiceItemId, snapshot);
        } else if (it.get("productId") != null) {
            Map<String, Object> product = db.first(Product.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                    it.get("productId"), orgId);
            if (product != null && Boolean.TRUE.equals(product.get("trackInventory"))
                    && List.of("stockable", "consumable").contains(String.valueOf(product.get("productType")))) {
                StockMove mv = StockMove.of((String) it.get("productId"), warehouse, dec(it.get("quantity")));
                // Sell-in-sales-unit, frozen on the invoice item (ADR-S003); pre-V5 items use the live sales unit.
                mv.uomId = it.get("uomFactor") != null ? (String) it.get("uomId") : (String) product.get("salesUomId");
                mv.uomFactor = it.get("uomFactor") != null ? dec(it.get("uomFactor")) : null;
                mv.reference = ref;
                mv.sourceType = "pos_invoice";
                mv.sourceId = ctx.invoiceId();
                StockMove.Issued r = stock.issue(mv);
                if (invoiceItemId != null) {
                    snapshotRow(invoiceItemId, ctx, (String) it.get("productId"), r, dec(it.get("quantity")), BigDecimal.ONE,
                            null, null);
                }
            }
        }
    }

    private void snapshotRow(String invoiceItemId, Ctx ctx, String productId, StockMove.Issued r, BigDecimal fallbackQty,
            BigDecimal multiplier, String componentType, String componentId) {
        db.insert(InvoiceItemRecipeIngredient.class, Obj.of("organizationId", org(), "invoiceItemId", invoiceItemId,
                "invoiceId", ctx.invoiceId(), "productId", productId,
                "quantity", r != null && r.baseQuantity() != null ? r.baseQuantity() : fallbackQty,
                "baseQuantity", r == null ? null : r.baseQuantity(), "baseUomId", r == null ? null : r.baseUomId(),
                "unitCost", r == null || r.unitCost() == null ? BigDecimal.ZERO : PosSql.decOf(r.unitCost().doubleValue()),
                "totalValue", r == null || r.totalValue() == null ? BigDecimal.ZERO : PosSql.decOf(r.totalValue().doubleValue()),
                "variantMultiplier", multiplier, "componentType", componentType, "componentId", componentId));
    }

    /** All ingredients or none; any failure throws (caller's savepoint rolls back). */
    private void issueMenuItemRecipe(String menuItemId, Object lineQty, String warehouse, Ctx ctx, String ref,
            String variantId, String invoiceItemId, Map<String, Object> snapshot) {
        String orgId = org();
        Map<String, Object> menuItem = SqlRows.one(jdbc.sql("SELECT \"isInventoryTracked\", name FROM \"MenuItem\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(menuItemId, orgId));
        boolean isSnap = snapshot != null && "menu".equals(snapshot.get("kind"));
        boolean tracked = isSnap ? Boolean.TRUE.equals(snapshot.get("tracked"))
                : menuItem != null && Boolean.TRUE.equals(menuItem.get("isInventoryTracked"));
        if (!tracked) {
            return;
        }
        double multiplier = isSnap ? (snapshot.get("multiplier") == null ? 1 : num(snapshot.get("multiplier"))) : 1;
        if (!isSnap && variantId != null) {
            Object m = jdbc.sql("SELECT \"qtyMultiplier\" FROM \"MenuItemVariant\" WHERE id = ? AND \"menuItemId\" = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(variantId, menuItemId, orgId)
                    .query(PosSql.SCALAR).optional().orElse(null);
            double mv = m == null ? 1 : num(m);
            if (Double.isFinite(mv) && mv > 0) {
                multiplier = mv;
            }
        }
        BigDecimal effective = Money.qtyMul(lineQty, multiplier);
        List<Object> recipe = new ArrayList<>();
        if (isSnap) {
            recipe.addAll(PosSql.list(snapshot.get("ingredients")));
        } else {
            recipe.addAll(SqlRows.list(jdbc.sql("SELECT \"productId\", quantity, \"uomId\" FROM \"MenuProduct\""
                    + " WHERE \"menuItemId\" = ? AND \"organizationId\" = ?").params(menuItemId, orgId)));
        }
        if (recipe.isEmpty()) {
            log.warn("[stock] menu item {} is inventory-tracked but has no recipe on {} — no COGS relieved (sale kept)",
                    menuItemId, ctx.invoiceNumber());
            throw new IllegalStateException("Menu item is inventory-tracked but has no recipe (BOM); COGS was not relieved."
                    + " Add a recipe or turn off inventory tracking for this item.");
        }
        String name = menuItem == null || menuItem.get("name") == null ? "menu item" : (String) menuItem.get("name");
        for (Object o : recipe) {
            Map<String, Object> ing = PosSql.map(o);
            BigDecimal qty = Money.qtyMul(ing.get("quantity"), effective);
            if (qty.signum() <= 0) {
                continue;
            }
            StockMove mv = StockMove.of((String) ing.get("productId"), warehouse, qty);
            mv.uomId = (String) ing.get("uomId");
            mv.reference = ref;
            mv.sourceType = "menu_recipe";
            mv.sourceId = ctx.invoiceId();
            mv.notes = name + " → " + ref;
            StockMove.Issued r = stock.issue(mv);
            if (invoiceItemId != null) {
                snapshotRow(invoiceItemId, ctx, (String) ing.get("productId"), r, qty, PosSql.decOf(multiplier), "recipe",
                        null);
            }
        }
    }

    /** Paid modifiers + accompaniments; returns {componentId, componentType, productId, error} per failure. */
    private List<Object[]> moveLineExtras(Map<String, Object> item, double lineQty, String warehouse, String reference,
            Ctx ctx, String invoiceItemId, Set<String> posted) {
        List<Object[]> failures = new ArrayList<>();
        if (!(lineQty > 0)) {
            return failures;
        }
        for (Object o : PosSql.list(item.get("modifiers"))) {
            Map<String, Object> m = PosSql.map(o);
            String modifierId = (String) m.get("modifierId");
            if (modifierId == null) {
                continue;
            }
            try {
                Map<String, Object> mod = SqlRows.one(jdbc.sql("SELECT \"inventoryItemId\", \"consumptionQty\", \"consumptionUomId\""
                        + " FROM \"Modifier\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                        .params(modifierId, org()));
                if (mod != null && mod.get("inventoryItemId") != null) {
                    moveExtra(item, (String) mod.get("inventoryItemId"), modifierId, num(mod.get("consumptionQty")),
                            (String) mod.get("consumptionUomId"), "modifier", lineQty, warehouse, reference, ctx,
                            invoiceItemId, posted);
                }
            } catch (RuntimeException e) {
                log.error("[stock] modifier extra issue failed ({}) on {} (kept): {}", modifierId, reference, e.getMessage());
                failures.add(new Object[] {modifierId, "modifier", null, e});
            }
        }
        List<String> accIds = PosSql.strings(item.get("accompanimentOptionIds"));
        if (!accIds.isEmpty()) {
            try {
                List<Object> params = new ArrayList<>(accIds);
                params.add(org());
                for (Map<String, Object> opt : SqlRows.list(jdbc.sql("SELECT id, \"inventoryItemId\", \"consumptionQty\","
                        + " \"consumptionUomId\" FROM \"AccompanimentOption\" WHERE id IN (" + PosSql.marks(accIds.size())
                        + ") AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(params))) {
                    if (opt.get("inventoryItemId") == null) {
                        continue;
                    }
                    try {
                        moveExtra(item, (String) opt.get("inventoryItemId"), (String) opt.get("id"),
                                num(opt.get("consumptionQty")), (String) opt.get("consumptionUomId"), "accompaniment", lineQty,
                                warehouse, reference, ctx, invoiceItemId, posted);
                    } catch (RuntimeException e) {
                        log.error("[stock] accompaniment extra issue failed on {} (kept): {}", reference, e.getMessage());
                        failures.add(new Object[] {opt.get("id"), "accompaniment_option", opt.get("inventoryItemId"), e});
                    }
                }
            } catch (RuntimeException e) {
                log.error("[stock] accompaniment lookup failed on {} (kept): {}", reference, e.getMessage());
                failures.add(new Object[] {String.join(",", accIds), "accompaniment_option", null, e});
            }
        }
        return failures;
    }

    private void moveExtra(Map<String, Object> item, String productId, String componentId, double consumptionQty,
            String uomId, String componentType, double lineQty, String warehouse, String reference, Ctx ctx,
            String invoiceItemId, Set<String> posted) {
        String componentKey = item.get("id") + ":" + componentId;
        if (posted.contains(componentKey)) {
            return;
        }
        Savepoints.run(jdbc, () -> {
            double qty = lineQty * (Double.isFinite(consumptionQty) && consumptionQty > 0 ? consumptionQty : 1);
            StockMove mv = StockMove.of(productId, warehouse, PosSql.decOf(qty));
            mv.reference = reference;
            mv.uomId = uomId;
            mv.sourceType = "pos_invoice_extra";
            mv.sourceId = componentKey;
            StockMove.Issued r = stock.issue(mv);
            if (invoiceItemId != null) {
                snapshotRow(invoiceItemId, ctx, productId, r, PosSql.decOf(qty), BigDecimal.ONE, componentType, componentId);
            }
        });
        posted.add(componentKey);
    }

    private void recordInventoryException(Ctx ctx, String kind, String productId, String menuItemId, String description,
            double quantity, String locationId, String reason, String stackTrace, Map<String, Object> payload) {
        try {
            String r = reason == null ? "" : reason;
            db.insert(InventoryException.class, Obj.of("organizationId", org(), "invoiceId", ctx.invoiceId(),
                    "invoiceNumber", ctx.invoiceNumber(), "locationId", locationId, "productId", productId,
                    "menuItemId", menuItemId, "description", description, "quantity", PosSql.decOf(quantity), "kind", kind,
                    "reason", r.length() > 1000 ? r.substring(0, 1000) : r, "stackTrace", stackTrace,
                    "payload", payload == null ? null : PosDb.json(payload), "status", "open"));
        } catch (RuntimeException e) {
            log.error("[stock] failed to record InventoryException for {}: {}", ctx.invoiceNumber(), e.toString());
        }
    }

    /** Queue-health alert (tenant scope set by the caller). */
    public void raiseStockPostingHealthAlert(long failed, long lagging) {
        List<String> parts = new ArrayList<>();
        if (failed > 0) {
            parts.add(failed + " failed job(s)");
        }
        if (lagging > 0) {
            parts.add(lagging + " job(s) unposted for over 15 minutes");
        }
        alertStockPostingFailure(new Ctx(null, null, null), "Stock posting queue unhealthy: " + String.join(" and ", parts),
                (int) (failed + lagging));
    }

    private void alertStockPostingFailure(Ctx ctx, String reason, int lines) {
        String orgId = org();
        try {
            long recent = jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE \"organizationId\" = ? AND category = 'inventory'"
                    + " AND \"createdAt\" >= ? AND title LIKE 'Stock posting%'").params(orgId,
                    SqlRows.ts(Instant.now().minusSeconds(3600))).query(Long.class).single();
            if (recent > 0) {
                return;
            }
            long open = jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND status = 'open'")
                    .param(orgId).query(Long.class).single();
            notifications.send(new NotificationsService.SendInput(orgId, null, "in_app", "inventory",
                    "Stock posting needs review", reason + (ctx.invoiceNumber() != null ? " on " + ctx.invoiceNumber() : "")
                            + ". On-hand is behind actual sales until this is resolved — " + open
                            + " open exception(s) in the Posting Monitor.",
                    Obj.of("kind", "stock_posting_failed", "invoiceId", ctx.invoiceId(), "invoiceNumber", ctx.invoiceNumber(),
                            "orderId", ctx.orderId(), "lines", lines, "openExceptions", open)));
        } catch (RuntimeException e) {
            log.error("[stock] failed to raise a posting-failure alert: {}", e.toString());
        }
    }
}
