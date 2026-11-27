package com.inventoryerp.procurement.application;

import org.springframework.beans.factory.ObjectProvider;
import com.inventoryerp.core.application.ProductLifecycleGuard;
import com.inventoryerp.core.application.ProductVariantService;
import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.core.application.UomConversionService.LineUnit;
import com.inventoryerp.core.application.UomConversionService.UnitRole;
import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.defined;
import static com.inventoryerp.procurement.application.ProcurementSql.num;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.accounting.application.CashSessionService;
import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.inventory.application.StockService.GlReceiptContext;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.procurement.application.ProcurementBudgetService.Doc;
import com.inventoryerp.procurement.application.ProcurementBudgetService.DocLine;
import com.inventoryerp.procurement.domain.GoodsReceiptLine;
import com.inventoryerp.procurement.domain.GoodsReceiptNote;
import com.inventoryerp.procurement.domain.PurchaseOrder;
import com.inventoryerp.procurement.domain.PurchaseOrderLine;
import com.inventoryerp.procurement.domain.PurchasePayment;
import com.inventoryerp.procurement.domain.PurchaseRequest;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Purchase orders (port of {@code purchase-orders.service.ts}): register (approval gate → draft or active), receive
 * against the order (GRN + stock + GRNI voucher + cash settlement in one transaction), pay credit orders, update,
 * cancel, delete and activate. {@link #applyReceiptToPO} is the single writer of PO receipt status.
 */
@Service
public class PurchaseOrdersService {

    /** {@code CreatePODto}. */
    public record CreateInput(String partnerId, String branchId, String description, String expectedDeliveryDate,
            String currencyCode, BigDecimal exchangeRate, String paymentType, String notes, String terms,
            String warehouseId, String requestId, List<LineInput> lines, String departmentId) {}

    /** {@code POLineDto}. */
    public record LineInput(String productId, String description, BigDecimal quantity, String unitOfMeasureId,
            BigDecimal unitPrice, String taxId, BigDecimal taxRate, String notes, String variantId) {

        public LineInput(String productId, String description, BigDecimal quantity, String unitOfMeasureId,
                BigDecimal unitPrice, String taxId, BigDecimal taxRate, String notes) {
            this(productId, description, quantity, unitOfMeasureId, unitPrice, taxId, taxRate, notes, null);
        }
    }

    /** {@code ReceivePODto}. */
    public record ReceiveInput(String warehouseId, String receivedAt, String notes, List<ReceiveLineInput> lines) {}

    /** {@code ReceiveLineDto}. */
    public record ReceiveLineInput(String purchaseOrderLineId, String productId, String description,
            BigDecimal quantity, BigDecimal unitCost, String batchNumber, String expiryDate, String notes,
            String variantId) {

        public ReceiveLineInput(String purchaseOrderLineId, String productId, String description, BigDecimal quantity,
                BigDecimal unitCost, String batchNumber, String expiryDate, String notes) {
            this(purchaseOrderLineId, productId, description, quantity, unitCost, batchNumber, expiryDate, notes, null);
        }
    }

    /** {@code PayPODto}. */
    public record PayInput(BigDecimal amount, String paidAt, String reference, String notes, String method,
            String cashRegisterId, BigDecimal exchangeRate) {

        /** A payment without a stated exchange rate. */
        public PayInput(BigDecimal amount, String paidAt, String reference, String notes, String method,
                String cashRegisterId) {
            this(amount, paidAt, reference, notes, method, cashRegisterId, null);
        }
    }

    /** {@code UpdatePODto}. */
    public record UpdateInput(String description, String expectedDeliveryDate, String notes, String terms) {}

    /** A receipt line applied to a PO ({@link #applyReceiptToPO}). */
    record ReceiptLine(String purchaseOrderLineId, String productId, BigDecimal quantity, String description,
            BigDecimal uomFactor) {

        /** A receipt line in the PO line's own unit. */
        ReceiptLine(String purchaseOrderLineId, String productId, BigDecimal quantity, String description) {
            this(purchaseOrderLineId, productId, quantity, description, null);
        }
    }

    /** A receive line after PO-line resolution ({@code _resolvedLineId} / {@code _unitCost}). */
    private record Resolved(ReceiveLineInput line, String resolvedLineId, BigDecimal unitCost, LineUnit unit,
            String variantId) {}

    private final JdbcClient jdbc;
    private final SequenceService sequence;
    private final EventBus events;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final CashSessionService cashSession;
    private final ProcurementBudgetService budgets;
    private final ProcurementDelegationService delegations;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    private final UomConversionService units;
    private final SupplierService suppliers;
    private final ProcurementPolicyService policies;
    private final PurchaseCommitments commitments;
    private final ReceivingControls receiving;
    private final ProductVariantService variants;
    private final CurrencyService currencies;
    private final ObjectProvider<ProductLifecycleGuard> lifecycleGuard;

    public PurchaseOrdersService(JdbcClient jdbc, SequenceService sequence, EventBus events, AuditService audit,
            ApprovalsService approvals, StockService stock, StockPostingService stockPosting,
            CashSessionService cashSession, PlatformTransactionManager txm, UomConversionService units,
            ProcurementBudgetService budgets, ProcurementDelegationService delegations, SupplierService suppliers,
            ProcurementPolicyService policies, PurchaseCommitments commitments, ReceivingControls receiving,
            ProductVariantService variants, CurrencyService currencies,
            ObjectProvider<ProductLifecycleGuard> lifecycleGuard) {
        this.variants = variants;
        this.currencies = currencies;
        this.lifecycleGuard = lifecycleGuard;
        this.jdbc = jdbc;
        this.budgets = budgets;
        this.delegations = delegations;
        this.receiving = receiving;
        this.commitments = commitments;
        this.suppliers = suppliers;
        this.policies = policies;
        this.units = units;
        this.sequence = sequence;
        this.events = events;
        this.audit = audit;
        this.approvals = approvals;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.cashSession = cashSession;
        this.tx = new TransactionTemplate(txm);
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static int year() {
        return Instant.now().atOffset(ZoneOffset.UTC).getYear();
    }

    private static Instant nowMs() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    // ── Step 1: Register Purchase ──────────────────────────────────────────

    /** Register a PO: draft pending approval when a purchase_order workflow applies, else active. */
    public Map<String, Object> create(CreateInput input) {
        String orgId = org();
        if (input.lines() == null || input.lines().isEmpty()) {
            throw ApiException.badRequest("At least one line required");
        }
        CreateInput dto = withCheckedLines(input, orgId);
        // ADR-S086: the PLM lifecycle policy may refuse buying a product (obsolete, being phased out).
        lifecycleGuard.ifAvailable(g -> g.requireAllowed(ProductLifecycleGuard.PURCHASE,
                dto.lines().stream().map(LineInput::productId).toList()));
        if (dto.warehouseId() == null || dto.warehouseId().isEmpty()) {
            throw ApiException.badRequest("Warehouse is required");
        }
        if (!exists("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?",
                dto.warehouseId(), orgId)) {
            throw ApiException.notFound("Warehouse not found");
        }
        if (dto.partnerId() != null && !dto.partnerId().isEmpty()
                && !exists("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?", dto.partnerId(),
                        orgId)) {
            throw ApiException.notFound("Supplier not found");
        }
        // P0-6: foreign ids from the body must belong to this organisation.
        if (dto.branchId() != null && !dto.branchId().isEmpty()
                && !exists("SELECT count(*) FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ?", dto.branchId(),
                        orgId)) {
            throw ApiException.notFound("Branch not found");
        }
        if (dto.requestId() != null && !dto.requestId().isEmpty()
                && !exists("SELECT count(*) FROM \"PurchaseRequest\" WHERE id = ? AND \"organizationId\" = ?",
                        dto.requestId(), orgId)) {
            throw ApiException.notFound("Purchase request not found");
        }
        int year = year();
        String orderNumber = sequence.next("po:" + year, "PO-" + year + "-", 5);

        BigDecimal subtotal = Money.ZERO;
        BigDecimal taxAmount = Money.ZERO;
        for (LineInput ln : dto.lines()) {
            BigDecimal lineSubtotal = ln.quantity().multiply(ln.unitPrice());
            BigDecimal lineTax = Money.div(lineSubtotal.multiply(ln.taxRate() == null ? Money.ZERO : ln.taxRate()),
                    BigDecimal.valueOf(100));
            subtotal = subtotal.add(lineSubtotal);
            taxAmount = taxAmount.add(lineTax);
        }
        BigDecimal total = subtotal.add(taxAmount);
        BigDecimal sub = subtotal;
        BigDecimal taxes = taxAmount;

        // Blocker fix 2026-11-17 (B5, Nest deviation): an order without a currency is in the functional currency (Nest
        // stored 'USD'), and an order in another currency carries a real rate: the stated one, else the rate table,
        // else it is refused. RFQ awards, agreement releases and the portal used to book foreign orders at rate 1, so
        // stock, GRNI and AP were valued in the wrong currency.
        String functional = functionalCurrency();
        String currency = dto.currencyCode() != null && !dto.currencyCode().isBlank() ? dto.currencyCode()
                : functional != null ? functional : "USD";
        // Gap fix h03-F9: a stated rate of zero or less is refused, also on a functional-currency order (it used to be
        // ignored there and silently became 1).
        if (dto.exchangeRate() != null && dto.exchangeRate().signum() <= 0) {
            throw ApiException.badRequest("exchangeRate must be greater than zero");
        }
        BigDecimal rate = functional == null || functional.equalsIgnoreCase(currency) ? BigDecimal.ONE
                : currencies.resolveStated(currency, functional, dto.exchangeRate(), Instant.now(), "purchase").rate();

        // ADR-PROCUREMENT-001 policies (no active policy = the behaviour before policies existed).
        SupplierService.Eligibility eligibility = suppliers.eligibilityForProducts(dto.partnerId(), dto.branchId(),
                dto.lines().stream().map(LineInput::productId).toList(), java.time.LocalDate.now());
        if (!eligibility.eligible()) {
            throw ApiException.badRequest("Cannot order from this supplier: " + String.join("; ", eligibility.blocked()));
        }
        BigDecimal requisitionAbove = policies.number(ProcurementPolicyRegistry.ROUTE_REQUISITION_REQUIRED_ABOVE,
                ProcurementPolicyService.PolicyContext.branch(dto.branchId()));
        if (requisitionAbove != null && total.compareTo(requisitionAbove) > 0) {
            String prStatus = dto.requestId() == null || dto.requestId().isEmpty() ? null : jdbc.sql("SELECT"
                    + " status::text FROM \"PurchaseRequest\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(dto.requestId(), orgId).query(String.class).optional().orElse(null);
            if (!"approved".equals(prStatus) && !"converted".equals(prStatus)) {
                throw ApiException.badRequest("Orders above " + JsNumbers.dec(requisitionAbove) + " must be raised from"
                        + " an approved purchase request");
            }
        }

        // W5 budget control (policy budget.control; off by default).
        List<PurchaseCommitments.Draft> drafts = new ArrayList<>();
        for (int i = 0; i < dto.lines().size(); i++) {
            LineInput ln = dto.lines().get(i);
            drafts.add(new PurchaseCommitments.Draft("new-" + i, ln.productId(), ln.quantity(), ln.unitPrice(), null));
        }
        List<String> budgetWarnings = commitments.checkBudget(null, dto.branchId(), currency, rate, drafts);

        // Line unit + conversion snapshot (ADR-S003): validated up front, frozen on the line.
        List<LineUnit> lineUnits = new ArrayList<>();
        for (LineInput ln : dto.lines()) {
            variants.resolve(ln.productId(), ln.variantId()); // ADR-S058: a product with variants needs one
            lineUnits.add(units.resolveLineUnit(ln.productId(), ln.unitOfMeasureId(), UnitRole.PURCHASE));
        }

        // Spend control (PROC-3): both in base currency excluding tax. The budget check runs after the insert, under
        // the budget row lock, and before the approval request, so a refused order leaves nothing behind.
        List<DocLine> baseLines = baseLines(dto, rate);
        delegations.enforce(dto.branchId(), baseLines.stream().map(DocLine::amount).reduce(BigDecimal.ZERO,
                BigDecimal::add));

        Map<String, Object> order = tx.execute(st -> {
            String id = UUID.randomUUID().toString();
            Map<String, Object> created = SqlRows.one(PurchaseOrder.class, jdbc.sql("INSERT INTO \"PurchaseOrder\" (id,"
                            + " \"organizationId\", \"orderNumber\", \"partnerId\", \"branchId\", description,"
                            + " \"orderDate\", \"expectedDeliveryDate\", \"currencyCode\", \"exchangeRate\", subtotal,"
                            + " \"taxAmount\", \"totalAmount\", status, \"paymentType\", \"paymentStatus\", notes, terms,"
                            + " \"requestId\", \"departmentId\", \"createdBy\", \"createdAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?,"
                            + " ?, ?, ?, " + NOW + ", ?, ?, ?, ?, ?, ?, 'draft', ?::\"PaymentType\","
                            + " ?::\"PaymentStatus\", ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ") RETURNING *")
                    .params(id, orgId, orderNumber, dto.partnerId(), dto.branchId(), dto.description(),
                            SqlRows.ts(ProcurementSql.date(dto.expectedDeliveryDate())),
                            currency, rate, sub, taxes, total, dto.paymentType() != null ? dto.paymentType() : "cash",
                            "credit".equals(dto.paymentType()) ? "not_paid" : null, dto.notes(), dto.terms(),
                            dto.requestId(), dto.departmentId(), user()));
            List<Map<String, Object>> lines = new ArrayList<>();
            int idx = 0;
            for (LineInput ln : dto.lines()) {
                LineUnit unit = lineUnits.get(idx);
                lines.add(SqlRows.one(PurchaseOrderLine.class, jdbc.sql("INSERT INTO \"PurchaseOrderLine\" (id,"
                                + " \"organizationId\", \"purchaseOrderId\", \"productId\", description, quantity,"
                                + " \"unitOfMeasureId\", \"uomFactor\", \"unitPrice\", \"taxId\", \"taxRate\", subtotal,"
                                + " \"lineNumber\", notes, \"variantId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                + " RETURNING *")
                        .params(UUID.randomUUID().toString(), orgId, id, ln.productId(), ln.description(), ln.quantity(),
                                unit.uomId(), unit.factor(), ln.unitPrice(), ln.taxId(),
                                ln.taxRate() != null ? ln.taxRate() : BigDecimal.ZERO,
                                ln.quantity().multiply(ln.unitPrice()), ++idx, ln.notes(),
                                ln.variantId() == null || ln.variantId().isBlank() ? null : ln.variantId())));
            }
            created.put("lines", lines);
            budgets.enforce(new Doc(ProcurementBudgetService.ORDER, id, dto.branchId(), dto.departmentId(), baseLines,
                    dto.requestId()));

            // Approval gate: an active policy keeps the PO in draft; none → auto-approve to active.
            // B5: approval bands are in the functional currency, so a foreign order is measured at its functional value.
            BigDecimal approvalAmount = total.multiply(rate).setScale(6, RoundingMode.HALF_UP);
            Map<String, Object> approvalResult = newTx.execute(s -> approvals.requestApproval("purchase_order", id,
                    defined("amount", JsNumbers.js(approvalAmount), "partnerId", dto.partnerId(), "orderNumber", orderNumber,
                            "createdBy", user(), "branchId", dto.branchId(), "departmentId", dto.departmentId()), null));
            if (approvalResult == null) {
                jdbc.sql("UPDATE \"PurchaseOrder\" SET status = 'active', \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId).update();
                created.put("status", "active");
                commitments.sync(id);
            } else {
                audit.recordInTx(AuditInput.of("PurchaseOrder", id, "create", null, Obj.of("orderNumber", orderNumber,
                        "totalAmount", JsNumbers.js(total), "approvalRequestId", approvalResult.get("id"),
                        "status", "draft")));
            }
            return created;
        });

        audit.record(AuditInput.of("PurchaseOrder", (String) order.get("id"), "create", null,
                defined("orderNumber", orderNumber, "totalAmount", JsNumbers.js(total), "paymentType", dto.paymentType())));
        // Price history (one observation per order line product; ignored on replay).
        for (LineInput ln : dto.lines()) {
            suppliers.recordPrice(dto.partnerId(), ln.productId(), ln.unitOfMeasureId(), (String) order.get("currencyCode"),
                    ln.unitPrice(), ln.quantity(), null, null, "purchase_order", (String) order.get("id"));
        }
        if (!eligibility.warnings().isEmpty() || !budgetWarnings.isEmpty()) {
            List<String> warnings = new ArrayList<>(eligibility.warnings());
            warnings.addAll(budgetWarnings);
            order.put("warnings", warnings);
        }
        events.publish("purchase_order.created", defined("organizationId", orgId, "orderId", order.get("id"),
                "orderNumber", orderNumber, "partnerId", dto.partnerId()));
        return order;
    }

    /** Each line's value excluding tax, in base currency ({@code rate}: order currency → functional). */
    private static List<DocLine> baseLines(CreateInput dto, BigDecimal rate) {
        return dto.lines().stream().map(l -> new DocLine(l.productId(), l.quantity().multiply(l.unitPrice())
                .multiply(rate).setScale(6, RoundingMode.HALF_UP))).toList();
    }

    private boolean exists(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single() > 0;
    }

    // ── Listing & detail ───────────────────────────────────────────────────

    /** {@code GET /procurement/purchase-orders} filters. */
    public record ListQuery(String status, String paymentType, String paymentStatus, String partnerId, String search,
            int page, int pageSize, String dateFrom, String dateTo) {}

    /** Paged orders (latest order date first) with the supplier's id and name. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(ListQuery q) {
        int page = Math.max(1, q.page() == 0 ? 1 : q.page());
        int pageSize = Math.min(200, Math.max(1, q.pageSize() == 0 ? 25 : q.pageSize()));
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"deletedAt\" IS NULL");
        if (notBlank(q.status())) {
            where.append(" AND status = ?::\"PurchaseOrderStatus\"");
            params.add(q.status());
        }
        if (notBlank(q.paymentType())) {
            where.append(" AND \"paymentType\" = ?::\"PaymentType\"");
            params.add(q.paymentType());
        }
        if (notBlank(q.paymentStatus())) {
            where.append(" AND \"paymentStatus\" = ?::\"PaymentStatus\"");
            params.add(q.paymentStatus());
        }
        if (notBlank(q.partnerId())) {
            where.append(" AND \"partnerId\" = ?");
            params.add(q.partnerId());
        }
        if (notBlank(q.search())) {
            // B6 (post-parity): Nest answered 500 here (`contains` on enum columns). Search the text columns and the
            // supplier name instead; enum columns have their own equality filters.
            String like = "%" + q.search().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (\"orderNumber\" ILIKE ? OR description ILIKE ? OR notes ILIKE ? OR EXISTS (SELECT 1"
                    + " FROM \"Partner\" sp WHERE sp.id = \"PurchaseOrder\".\"partnerId\" AND sp.name ILIKE ?))");
            params.addAll(List.of(like, like, like, like));
        }
        if (notBlank(q.dateFrom())) {
            where.append(" AND \"orderDate\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(q.dateFrom())));
        }
        if (notBlank(q.dateTo())) {
            where.append(" AND \"orderDate\" <= ?");
            params.add(SqlRows.ts(ProcurementSql.endOfLocalDay(EntityPatch.toInstant(q.dateTo()))));
        }
        List<Map<String, Object>> data = SqlRows.list(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\""
                + " WHERE" + where + " ORDER BY \"orderDate\" DESC LIMIT " + pageSize + " OFFSET "
                + (page - 1) * pageSize).params(params));
        // Gap fix h03-F10: the suppliers load once for the page (one query per order before).
        Map<String, Map<String, Object>> partners = new HashMap<>();
        String[] partnerIds = data.stream().map(po -> (String) po.get("partnerId")).filter(p -> p != null).distinct()
                .toArray(String[]::new);
        if (partnerIds.length > 0) {
            for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id ="
                    + " ANY(?::text[]) AND \"organizationId\" = ?").params(partnerIds, org()))) {
                partners.put((String) p.get("id"), p);
            }
        }
        for (Map<String, Object> po : data) {
            Map<String, Object> partner = partners.get(po.get("partnerId"));
            po.put("partner", partner == null ? null : new LinkedHashMap<>(partner));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"PurchaseOrder\" WHERE" + where).params(params).query(Long.class)
                .single();
        return Obj.of("data", data, "meta", Obj.of("page", page, "pageSize", pageSize, "total", total, "totalPages",
                Math.max(1, (long) Math.ceil((double) total / pageSize))));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    /** Order with lines, receipts (with lines), payments and the originating request; {@code null} when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (po == null) {
            return null;
        }
        po.put("lines", withVariants(poLines(id)));
        List<Map<String, Object>> receipts = SqlRows.list(GoodsReceiptNote.class, jdbc.sql("SELECT * FROM"
                + " \"GoodsReceiptNote\" WHERE \"purchaseOrderId\" = ? AND \"organizationId\" = ?").params(id, org()));
        // Gap fix h03-F10: the receipts' lines load in one query (one per receipt before).
        Map<String, List<Map<String, Object>>> receiptLines = new HashMap<>();
        if (!receipts.isEmpty()) {
            for (Map<String, Object> l : SqlRows.list(GoodsReceiptLine.class, jdbc.sql("SELECT * FROM"
                    + " \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ANY(?::text[]) AND \"organizationId\" = ?"
                    + " ORDER BY \"goodsReceiptId\", \"lineNumber\", id").params(receipts.stream()
                    .map(r -> (String) r.get("id")).toArray(String[]::new), org()))) {
                receiptLines.computeIfAbsent((String) l.get("goodsReceiptId"), k -> new ArrayList<>()).add(l);
            }
        }
        for (Map<String, Object> r : receipts) {
            r.put("lines", withVariants(receiptLines.getOrDefault((String) r.get("id"), new ArrayList<>())));
        }
        po.put("receipts", receipts);
        po.put("payments", SqlRows.list(PurchasePayment.class, jdbc.sql("SELECT * FROM \"PurchasePayment\" WHERE"
                + " \"purchaseOrderId\" = ? AND \"organizationId\" = ?").params(id, org())));
        po.put("request", po.get("requestId") == null ? null : SqlRows.one(PurchaseRequest.class,
                jdbc.sql("SELECT * FROM \"PurchaseRequest\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(po.get("requestId"), org())));
        return po;
    }

    /**
     * Adds {@code variant: {id, name, sku}} (null without a variant) to each line, so a screen can name the variant a
     * line was ordered or received in (ADR-S058). Additive: the line's own fields are unchanged.
     */
    private List<Map<String, Object>> withVariants(List<Map<String, Object>> lines) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> l : lines) {
            if (l.get("variantId") != null) {
                ids.add((String) l.get("variantId"));
            }
        }
        Map<String, Map<String, Object>> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> v : SqlRows.list(jdbc.sql("SELECT id, name, sku FROM \"ProductVariant\" WHERE"
                    + " id = ANY(?::text[]) AND \"organizationId\" = ?").params(ids.toArray(String[]::new), org()))) {
                byId.put((String) v.get("id"), v);
            }
        }
        for (Map<String, Object> l : lines) {
            l.put("variant", l.get("variantId") == null ? null : byId.get(l.get("variantId")));
        }
        return lines;
    }

    private List<Map<String, Object>> poLines(Object purchaseOrderId) {
        return SqlRows.list(PurchaseOrderLine.class, jdbc.sql("SELECT * FROM \"PurchaseOrderLine\" WHERE"
                + " \"purchaseOrderId\" = ?").param(purchaseOrderId));
    }

    // ── Step 2: Receive Products ───────────────────────────────────────────

    /**
     * Apply received lines to a PO inside the caller's transaction: hard over-receipt block, bump each PO line's
     * receivedQuantity and recompute the PO status, all under optimistic locks. Shared by {@link #receive} and the
     * standalone goods-receipt post so the two never diverge.
     */
    String applyReceiptToPO(String purchaseOrderId, List<ReceiptLine> lines) {
        String orgId = org();
        Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(purchaseOrderId, orgId));
        if (po == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        Object status = po.get("status");
        if ("cancelled".equals(status)) {
            throw ApiException.badRequest("Cannot receive against a cancelled PO");
        }
        if ("received".equals(status)) {
            throw ApiException.badRequest("PO is fully received");
        }
        if (!List.of("active", "partially_received").contains(status)) {
            throw ApiException.badRequest("PO is in status \"" + status + "\" — cannot receive");
        }
        List<Map<String, Object>> poLineRows = poLines(purchaseOrderId);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> byProduct = new HashMap<>();
        for (Map<String, Object> l : poLineRows) {
            byId.put((String) l.get("id"), l);
            if (l.get("productId") != null) {
                byProduct.computeIfAbsent((String) l.get("productId"), k -> new ArrayList<>()).add(l);
            }
        }
        // Aggregate by PO line: two receipt lines can map to one PO line, and each line is version-locked once.
        Map<String, BigDecimal> addByLine = new LinkedHashMap<>();
        for (ReceiptLine ln : lines) {
            String lineId = resolve(ln, byId, byProduct, po);
            if (lineId == null) {
                continue;
            }
            addByLine.merge(lineId, inPoUnits(ln, byId.get(lineId)), BigDecimal::add);
        }
        // Over-receipt beyond the tolerance (policy receiving.overReceiptTolerancePct, default 0 = hard block) is
        // refused — check every affected line first.
        BigDecimal tolerancePct = Money.dec(policies.number(ProcurementPolicyRegistry.RECEIVING_OVER_RECEIPT_TOLERANCE,
                ProcurementPolicyService.PolicyContext.branch((String) po.get("branchId"))));
        for (Map.Entry<String, BigDecimal> e : addByLine.entrySet()) {
            Map<String, Object> poLine = byId.get(e.getKey());
            BigDecimal ordered = Money.dec(poLine.get("quantity"));
            BigDecimal already = Money.dec(poLine.get("receivedQuantity"));
            BigDecimal adding = e.getValue();
            BigDecimal limit = ordered.add(Money.div(ordered.multiply(tolerancePct), BigDecimal.valueOf(100)));
            if (already.add(adding).compareTo(limit) > 0) {
                throw ApiException.badRequest("Over-receiving PO line \"" + poLine.get("description") + "\": ordered "
                        + JsNumbers.dec(ordered) + ", already received " + JsNumbers.dec(already)
                        + ", trying to receive " + JsNumbers.dec(adding)
                        + (tolerancePct.signum() > 0 ? " (tolerance " + JsNumbers.dec(tolerancePct) + "%)" : ""));
            }
        }
        for (Map.Entry<String, BigDecimal> e : addByLine.entrySet()) {
            Map<String, Object> poLine = byId.get(e.getKey());
            int updated = jdbc.sql("UPDATE \"PurchaseOrderLine\" SET \"receivedQuantity\" = \"receivedQuantity\" + ?,"
                            + " version = version + 1 WHERE id = ? AND version = ? AND \"organizationId\" = ?")
                    .params(e.getValue(), e.getKey(), poLine.get("version"), orgId).update();
            if (updated == 0) {
                throw ApiException.badRequest("PO line \"" + poLine.get("description")
                        + "\" was modified by another user. Please refresh and try again.");
            }
        }
        boolean fullyReceived = true;
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT quantity, \"receivedQuantity\" FROM"
                + " \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ? AND \"organizationId\" = ?")
                .params(purchaseOrderId, orgId))) {
            if (Money.dec(l.get("receivedQuantity")).compareTo(Money.dec(l.get("quantity"))) < 0) {
                fullyReceived = false;
            }
        }
        String newStatus = fullyReceived ? "received" : "partially_received";
        int updatedPo = jdbc.sql("UPDATE \"PurchaseOrder\" SET status = ?::\"PurchaseOrderStatus\", version = version + 1,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND version = ?"
                        + " AND \"deletedAt\" IS NULL")
                .params(newStatus, purchaseOrderId, orgId, po.get("version")).update();
        if (updatedPo == 0) {
            throw ApiException.badRequest("Purchase order was modified by another user. Please refresh and try again.");
        }
        commitments.sync(purchaseOrderId);
        return newStatus;
    }

    /**
     * Review M-4: a receipt line's quantity in the PO line's unit. A line received in another unit (a free GRN line
     * matched by product, in the product's purchase unit) converts through both frozen factors; same unit or no factor
     * = as received.
     */
    static BigDecimal inPoUnits(ReceiptLine ln, Map<String, Object> poLine) {
        BigDecimal poFactor = poLine == null || poLine.get("uomFactor") == null ? null : Money.dec(poLine.get("uomFactor"));
        if (ln.uomFactor() == null || poFactor == null || poFactor.signum() <= 0
                || ln.uomFactor().compareTo(poFactor) == 0) {
            return ln.quantity();
        }
        return ln.quantity().multiply(ln.uomFactor()).divide(poFactor, 6, java.math.RoundingMode.HALF_UP);
    }

    /** Half a cent: a receiver's unit cost this close to the order price is the order price (rounding). */
    private static final BigDecimal PRICE_EPSILON = new BigDecimal("0.005");

    /**
     * Gap fix h03-F8: the order price of {@code poLine} in a receipt line's unit. A line received in another unit
     * converts through both frozen factors, as {@link #inPoUnits} converts its quantity.
     */
    static BigDecimal orderPriceInLineUnit(Map<String, Object> poLine, BigDecimal lineFactor) {
        BigDecimal price = Money.dec(poLine.get("unitPrice"));
        BigDecimal poFactor = poLine.get("uomFactor") == null ? null : Money.dec(poLine.get("uomFactor"));
        if (lineFactor == null || poFactor == null || poFactor.signum() <= 0 || lineFactor.compareTo(poFactor) == 0) {
            return price;
        }
        return price.multiply(lineFactor).divide(poFactor, 6, RoundingMode.HALF_UP);
    }

    /**
     * Gap fix h03-F8 (Nest deviation, owner-approved 2026-11-17): a purchase-order receipt is valued at the order
     * price, which stock, the receipt voucher's payable and the PO pay cap all follow; the receiver's unit cost is only
     * checked against it. A different cost (beyond half a cent of rounding) is refused: a price change goes through a
     * PO change, and a supplier's different price through the bill (price variance).
     */
    static BigDecimal receiptCost(Object description, BigDecimal given, BigDecimal orderPrice) {
        if (given != null && given.subtract(orderPrice).abs().compareTo(PRICE_EPSILON) > 0) {
            throw ApiException.badRequest("Receipt line \"" + description + "\" costs " + JsNumbers.dec(given)
                    + " but the order price is " + JsNumbers.dec(orderPrice) + "; a purchase order receipt is valued"
                    + " at the order price — change the order or bill the difference");
        }
        return orderPrice;
    }

    /** Bind a receipt line to exactly one PO line (explicit id, else a product on a single PO line). */
    private static String resolve(ReceiptLine ln, Map<String, Map<String, Object>> byId,
            Map<String, List<Map<String, Object>>> byProduct, Map<String, Object> po) {
        if (ln.purchaseOrderLineId() == null && ln.productId() == null) {
            return null;
        }
        if (ln.purchaseOrderLineId() != null) {
            if (!byId.containsKey(ln.purchaseOrderLineId())) {
                throw ApiException.badRequest("PO line " + ln.purchaseOrderLineId() + " not found");
            }
            return ln.purchaseOrderLineId();
        }
        List<Map<String, Object>> candidates = byProduct.getOrDefault(ln.productId(), List.of());
        if (candidates.size() == 1) {
            return (String) candidates.getFirst().get("id");
        }
        if (candidates.size() > 1) {
            throw ApiException.badRequest("Receipt line \"" + (ln.description() != null ? ln.description() : ln.productId())
                    + "\" matches " + candidates.size() + " PO lines — specify purchaseOrderLineId");
        }
        throw ApiException.badRequest("Receipt line \"" + (ln.description() != null ? ln.description()
                : ln.productId() != null ? ln.productId() : "unknown") + "\" is not on PO " + po.get("orderNumber"));
    }

    /** Insert the draft GRN (+ lines) shared by the immediate-post and approval-draft paths. */
    private com.inventoryerp.qualitycontrol.application.QcGate qcGate;

    /** Quality-control gate (ADR-S087); optional for plain constructions. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setQcGate(com.inventoryerp.qualitycontrol.application.QcGate qcGate) {
        this.qcGate = qcGate;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(Map<String, Object> grn) {
        return (List<Map<String, Object>>) grn.get("lines");
    }

    /** A one-shot receive before its GRN exists, for the pre-receipt check (lines in base units). */
    private static com.inventoryerp.qualitycontrol.application.QcSourceDoc qcDoc(String grnId, String receiptNumber, Map<String, Object> po,
            ReceiveInput dto, List<Resolved> resolved) {
        List<com.inventoryerp.qualitycontrol.application.QcSourceDoc.Line> out = new ArrayList<>();
        int i = 0;
        for (Resolved r : resolved) {
            i++;
            if (r.line().productId() == null) {
                continue;
            }
            BigDecimal factor = r.unit() == null || r.unit().factor() == null ? BigDecimal.ONE : r.unit().factor();
            out.add(new com.inventoryerp.qualitycontrol.application.QcSourceDoc.Line("line-" + i, r.line().productId(), r.variantId(),
                    r.line().quantity().multiply(factor), r.line().batchNumber()));
        }
        return new com.inventoryerp.qualitycontrol.application.QcSourceDoc("goods_receipt", grnId, receiptNumber, "receipt", null, null,
                null, (String) po.get("partnerId"), dto.warehouseId(), out);
    }

    private Map<String, Object> insertGrnDraft(String grnId, String orgId, String receiptNumber, Map<String, Object> po,
            ReceiveInput dto, List<Resolved> resolved) {
        Map<String, Object> grn = SqlRows.one(GoodsReceiptNote.class, jdbc.sql("INSERT INTO \"GoodsReceiptNote\" (id,"
                        + " \"organizationId\", \"receiptNumber\", \"purchaseOrderId\", \"partnerId\", \"branchId\","
                        + " \"warehouseId\", \"receivedAt\", status, notes, \"createdBy\", \"createdAt\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, " + (dto.receivedAt() != null ? "?" : NOW) + ", 'draft', ?, ?, "
                        + NOW + ", " + NOW + ") RETURNING *")
                .params(grnParams(grnId, orgId, receiptNumber, po, dto)));
        List<Map<String, Object>> lines = new ArrayList<>();
        int idx = 0;
        for (Resolved r : resolved) {
            ReceiveLineInput rln = r.line();
            lines.add(SqlRows.one(GoodsReceiptLine.class, jdbc.sql("INSERT INTO \"GoodsReceiptLine\" (id,"
                            + " \"organizationId\", \"goodsReceiptId\", \"purchaseOrderLineId\", \"productId\", description,"
                            + " quantity, \"unitCost\", \"batchNumber\", \"expiryDate\", notes, \"lineNumber\", \"uomId\","
                            + " \"uomFactor\", \"variantId\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *")
                    .params(UUID.randomUUID().toString(), orgId, grnId, r.resolvedLineId(), rln.productId(),
                            rln.description(), rln.quantity(), r.unitCost() != null ? r.unitCost() : BigDecimal.ZERO,
                            rln.batchNumber(), SqlRows.ts(ProcurementSql.date(rln.expiryDate())), rln.notes(), ++idx,
                            r.unit().uomId(), r.unit().factor(), r.variantId())));
        }
        grn.put("lines", lines);
        return grn;
    }

    private Object[] grnParams(String grnId, String orgId, String receiptNumber, Map<String, Object> po,
            ReceiveInput dto) {
        List<Object> p = new ArrayList<>(List.of(grnId, orgId, receiptNumber, po.get("id")));
        p.add(po.get("partnerId"));
        p.add(po.get("branchId"));
        p.add(dto.warehouseId());
        if (dto.receivedAt() != null) {
            p.add(SqlRows.ts(ProcurementSql.date(dto.receivedAt())));
        }
        p.add(dto.notes());
        p.add(user());
        return p.toArray();
    }

    /**
     * Cash purchase: settle exactly what one receipt vouchered to AP (Dr AP / Cr Cash) and record the drawer
     * pay-out. Shared by {@link #receive} and the goods-receipt post. Runs in the caller's transaction.
     */
    void settleCashReceipt(String purchaseOrderId, BigDecimal settleAmount, String grnId, String receiptNumber,
            Instant date) {
        String orgId = org();
        if (!(settleAmount.signum() > 0)) {
            return;
        }
        jdbc.sql("SELECT id FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(purchaseOrderId, orgId).query().listOfRows();
        Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(purchaseOrderId, orgId));
        if (po == null || !"cash".equals(po.get("paymentType"))) {
            return;
        }
        // FX-2 G: the settlement is in this receipt's functional value, at the rate the receipt was valued at
        BigDecimal grnRate = jdbc.sql("SELECT \"exchangeRate\" FROM \"GoodsReceiptNote\" WHERE id = ?").param(grnId)
                .query(BigDecimal.class).optional().orElse(null);
        BigDecimal rate = grnRate != null && grnRate.signum() > 0 ? grnRate : functionalRate(po);
        BigDecimal poAmount = rate.compareTo(BigDecimal.ONE) == 0 ? settleAmount : Money.div(settleAmount, rate);
        BigDecimal newTotalPaid = Money.dec(po.get("totalPaid")).add(poAmount);
        jdbc.sql("UPDATE \"PurchaseOrder\" SET \"totalPaid\" = ?, \"paymentStatus\" = ?::\"PaymentStatus\", \"updatedAt\" = "
                        + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(newTotalPaid, newTotalPaid.compareTo(Money.dec(po.get("totalAmount"))) >= 0 ? "paid" : "partial",
                        purchaseOrderId, orgId)
                .update();
        jdbc.sql("INSERT INTO \"PurchasePayment\" (id, \"organizationId\", \"purchaseOrderId\", amount, \"paidAt\","
                        + " \"paidById\", reference, \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, " + NOW + ", ?, ?, "
                        + NOW + ", " + NOW + ")")
                .params(UUID.randomUUID().toString(), orgId, purchaseOrderId, poAmount, user(),
                        "auto:GRN " + receiptNumber)
                .update();
        // GL: Dr AP / Cr Cash — relieve the payable the voucher just raised.
        Map<String, Object> session = cashSession.findOpen(null);
        if (session == null) {
            throw ApiException.badRequest("Select an open cashier register for this cash purchase");
        }
        // Gap fix h03-F3: a foreign PO's settlement carries the PO currency and amount, like the voucher's AP line, so
        // the currency open item it relieves is closed (an untagged line left it to FX revaluation).
        Map<String, Object> paymentJournal = stockPosting.postPurchasePayment((String) session.get("id"),
                (String) po.get("partnerId"), settleAmount, "cash", date, "purchase_order", purchaseOrderId,
                "Cash purchase " + po.get("orderNumber") + " · GRN " + receiptNumber,
                "purchase_payment:goods_receipt:" + grnId, (String) po.get("currencyCode"), poAmount);
        // Drawer artifact: the till's expected cash reflects the withdrawal. GL already done.
        cashSession.recordExternalPayOut((String) session.get("id"), settleAmount,
                "Cash purchase PO " + po.get("orderNumber"), (String) paymentJournal.get("id"));
    }

    /** Receive products against an active / partially received PO. */
    public Map<String, Object> receive(String id, ReceiveInput dto) {
        String orgId = org();
        if (dto.warehouseId() == null || dto.warehouseId().isEmpty()) {
            throw ApiException.badRequest("Warehouse is required for receiving stock");
        }
        if (dto.lines() == null || dto.lines().isEmpty()) {
            throw ApiException.badRequest("At least one line required");
        }
        if (!exists("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?",
                dto.warehouseId(), orgId)) {
            throw ApiException.notFound("Warehouse not found");
        }
        Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        if (po == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        Object status = po.get("status");
        if ("cancelled".equals(status)) {
            throw ApiException.badRequest("Cannot receive against a cancelled PO");
        }
        if ("received".equals(status)) {
            throw ApiException.badRequest("PO is fully received");
        }
        if (!List.of("active", "partially_received").contains(status)) {
            throw ApiException.badRequest("PO is in status \"" + status + "\" — cannot receive");
        }
        // ADR-PROCUREMENT-001 W6: a direct receipt has no weighbridge ticket (policy; off by default).
        receiving.requireEvidence(null, (String) po.get("branchId"), dto.lines().stream()
                .map(l -> new ReceivingControls.Line(null, l.productId(), l.description())).toList());
        Map<String, Map<String, Object>> poLines = new LinkedHashMap<>();
        Map<String, Map<String, Object>> poLinesByProduct = new HashMap<>();
        for (Map<String, Object> l : poLines(id)) {
            poLines.put((String) l.get("id"), l);
            if (l.get("productId") != null) {
                poLinesByProduct.put((String) l.get("productId"), l); // Map(entries): the last line wins
            }
        }
        // `_unitCost` falls back to the ordered price: receiving at 0 would never capitalise the inventory.
        List<Resolved> resolved = new ArrayList<>();
        for (ReceiveLineInput rln : dto.lines()) {
            String lineId = null;
            if (rln.purchaseOrderLineId() != null) {
                if (!poLines.containsKey(rln.purchaseOrderLineId())) {
                    throw ApiException.badRequest("PO line " + rln.purchaseOrderLineId() + " not found");
                }
                lineId = rln.purchaseOrderLineId();
            } else if (rln.productId() != null && poLinesByProduct.containsKey(rln.productId())) {
                lineId = (String) poLinesByProduct.get(rln.productId()).get("id");
            }
            Map<String, Object> poLine = lineId == null ? null : poLines.get(lineId);
            // Gap fix h03-F8: a PO-backed line is valued at the order price (it is received in the PO line's unit).
            BigDecimal unitCost = poLine != null
                    ? receiptCost(rln.description(), rln.unitCost(), Money.dec(poLine.get("unitPrice")))
                    : rln.unitCost();
            // ADR-S058: the PO line's variant wins; a free receipt line names its own.
            String variantId = poLine != null && poLine.get("variantId") != null ? (String) poLine.get("variantId")
                    : rln.variantId();
            if (rln.productId() != null) {
                ProductVariantService.VariantRef v = variants.resolve(rln.productId(), variantId);
                variantId = v == null ? null : v.id();
            }
            resolved.add(new Resolved(rln, lineId, unitCost, receiptUnit(poLine, rln.productId(), null), variantId));
        }

        int year = year();
        String receiptNumber = sequence.next("grn:" + year, "GRN-" + year + "-", 5);
        String grnId = UUID.randomUUID().toString();
        BigDecimal amount = Money.ZERO;
        for (Resolved r : resolved) {
            amount = amount.add(r.line().quantity().multiply(r.unitCost() != null ? r.unitCost() : Money.ZERO));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("amount", JsNumbers.js(amount));
        snapshot.put("receiptNumber", receiptNumber);
        snapshot.put("partnerId", po.get("partnerId"));
        snapshot.put("purchaseOrderId", id);
        snapshot.put("createdBy", user());
        Map<String, Object> gate = approvals.checkOrRequestApproval("goods_receipt", grnId, snapshot);
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            Map<String, Object> draft = tx.execute(st -> {
                Map<String, Object> d = insertGrnDraft(grnId, orgId, receiptNumber, po, dto, resolved);
                if (qcGate != null) {
                    qcGate.sync(GoodsReceiptsService.qcDoc(d, lines(d)));
                }
                return d;
            });
            audit.record(AuditInput.of("GoodsReceiptNote", grnId, "create", null, Obj.of("receiptNumber", receiptNumber,
                    "status", "draft", "purchaseOrderId", id, "approvalRequestId", gate.get("requestId"))));
            return Obj.of("grn", draft, "status", po.get("status"), "approvalRequired", true, "approvalRequestId",
                    gate.get("requestId"));
        }

        // ADR-S087 §6: a one-shot receive cannot do the checks a pre-receipt control point requires.
        if (qcGate != null && Boolean.TRUE.equals(tx.execute(st -> qcGate.preReceiptRequired(qcDoc(grnId, receiptNumber,
                po, dto, resolved))))) {
            throw ApiException.badRequest("Quality checks are required before this receipt is posted. Save it as a"
                    + " draft goods receipt, complete the checks, then post.");
        }
        Instant date = dto.receivedAt() != null ? ProcurementSql.date(dto.receivedAt()) : nowMs();
        Map<String, Object> result = tx.execute(st -> {
            // 1. GRN (draft)
            Map<String, Object> grn = insertGrnDraft(grnId, orgId, receiptNumber, po, dto, resolved);
            // 2. Advance the PO under optimistic locks (shared with the goods-receipt post).
            List<ReceiptLine> applied = new ArrayList<>();
            for (Resolved r : resolved) {
                applied.add(new ReceiptLine(r.resolvedLineId(), r.line().productId(), r.line().quantity(),
                        r.line().description(), r.unit() == null ? null : r.unit().factor()));
            }
            String newStatus = applyReceiptToPO(id, applied);
            // 3. Post the GRN
            jdbc.sql("UPDATE \"GoodsReceiptNote\" SET status = 'posted', \"postedAt\" = " + NOW + ", \"postedById\" = ?,"
                            + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(user(), grnId, orgId).update();
            // 4. Stock-in per stockable line: Dr Stock Valuation / Cr GRNI, accumulating the capitalised value + tax.
            BigDecimal receiptNet = Money.ZERO;
            BigDecimal receiptTax = Money.ZERO;
            // FX-2 G: valued at the receipt-date rate (the PO rate is a commitment value and is not rewritten)
            BigDecimal rate = receiptRate(po, grnId, date);
            for (Resolved r : resolved) {
                ReceiveLineInput rln = r.line();
                if (rln.productId() == null) {
                    continue;
                }
                Map<String, Object> product = product(rln.productId(), orgId);
                if (product == null || !stocked(product)) {
                    continue;
                }
                // P0-5: a foreign-currency PO prices in its own currency; stock and GRNI carry the functional value.
                BigDecimal unitCost = r.unitCost() != null ? r.unitCost().multiply(rate)
                        : Money.dec(product.get("costPrice"));
                StockMove move = StockMove.of(rln.productId(), dto.warehouseId(), rln.quantity()).unitCost(unitCost)
                        .variant(r.variantId())
                        .uom(stockUnit(r.unit(), product), r.unit().factor()).batch(rln.batchNumber())
                        .expiry(rln.expiryDate())
                        .reference("GRN " + receiptNumber);
                Received received = stock.receiveForDocument(move, GlReceiptContext.grni("goods_receipt", grnId, date));
                BigDecimal lineNet = Money.dec(received.actualValue());
                Map<String, Object> poLine = r.resolvedLineId() == null ? null : poLines.get(r.resolvedLineId());
                BigDecimal taxRate = poLine != null ? Money.dec(poLine.get("taxRate")) : Money.ZERO;
                receiptNet = receiptNet.add(lineNet);
                receiptTax = receiptTax.add(Money.div(lineNet.multiply(taxRate), BigDecimal.valueOf(100)));
            }
            // ADR-S087 §7: quarantine and post-receipt checks commit or roll back with the receipt.
            if (qcGate != null) {
                qcGate.onCompleted(GoodsReceiptsService.qcDoc(grn, lines(grn)));
            }
            // 4b. Voucher the receipt: Dr GRNI + Dr Input Tax / Cr AP (GRNI nets to zero).
            stockPosting.postReceiptVoucher((String) po.get("partnerId"), receiptNet, receiptTax, date, "goods_receipt",
                    grnId, "Goods received " + receiptNumber + " · PO " + po.get("orderNumber"),
                    "receipt_voucher:goods_receipt:" + grnId, (String) po.get("currencyCode"), rate);
            // 7. Cash purchase: settle only what this receipt vouchered.
            if ("cash".equals(po.get("paymentType"))) {
                settleCashReceipt(id, receiptNet.add(receiptTax), grnId, receiptNumber, date);
            }
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "receive", null, Obj.of("receiptNumber", receiptNumber,
                    "status", newStatus, "linesReceived", dto.lines().size())));
            return Obj.of("grn", grn, "status", newStatus);
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> postedGrn = (Map<String, Object>) result.get("grn");
        if (postedGrn != null && postedGrn.get("id") != null) {
            receiving.afterPost((String) postedGrn.get("id"));
        }
        events.publish("purchase_order.received", Obj.of("organizationId", orgId, "orderId", id, "receiptNumber",
                receiptNumber, "status", result.get("status")));
        return result;
    }

    /**
     * Unit snapshot for a receipt line (ADR-S003): a PO-backed line inherits the PO line's unit and factor so
     * receipt, PO progress and billing all count in the unit that was ordered. PO lines written before V5 carry
     * no factor and keep the legacy behaviour (null snapshot, converted with the product's purchase unit).
     * Lines without a PO line use {@code requestedUomId}, else the product's purchase unit.
     */
    LineUnit receiptUnit(Map<String, Object> poLine, String productId, String requestedUomId) {
        if (poLine != null) {
            Object factor = poLine.get("uomFactor");
            return factor == null ? new LineUnit(null, null)
                    : new LineUnit((String) poLine.get("unitOfMeasureId"), Money.dec(factor));
        }
        if (productId == null || productId.isEmpty()) {
            return new LineUnit(requestedUomId, null);
        }
        return units.resolveLineUnit(productId, requestedUomId, UnitRole.PURCHASE);
    }

    /**
     * Multiplier from the PO's currency to the organisation's functional currency: the PO exchange rate when the PO is
     * in another currency, else 1 (P0-5). POs written without a currency default to the functional currency.
     */
    BigDecimal functionalRate(Map<String, Object> po) {
        Object code = po.get("currencyCode");
        if (code == null) {
            return BigDecimal.ONE;
        }
        String functional = functionalCurrency();
        if (functional == null || functional.equalsIgnoreCase(String.valueOf(code))) {
            return BigDecimal.ONE;
        }
        BigDecimal rate = Money.dec(po.get("exchangeRate"));
        return rate.signum() > 0 ? rate : BigDecimal.ONE;
    }

    /**
     * FX-2 G (owner decision 2): the rate a foreign PO receipt is valued at, recorded on the receipt: the rate table on
     * the receipt date under the company's {@code goods_receipt} rate policy, else the PO rate (source {@code po}).
     * Functional-currency POs: 1, nothing recorded.
     */
    BigDecimal receiptRate(Map<String, Object> po, String grnId, Instant receivedAt) {
        BigDecimal poRate = functionalRate(po);
        Object code = po.get("currencyCode");
        String functional = functionalCurrency();
        if (code == null || functional == null || functional.equalsIgnoreCase(String.valueOf(code))) {
            return poRate;
        }
        String ccy = String.valueOf(code).toUpperCase();
        CurrencyService.Policy policy = currencies.policy("goods_receipt");
        BigDecimal table = currencies.findRate(ccy, functional, receivedAt == null ? Instant.now() : receivedAt,
                policy.rateType(), policy.requireApprovedRate());
        BigDecimal rate = table != null && table.signum() > 0 ? table : poRate;
        jdbc.sql("UPDATE \"GoodsReceiptNote\" SET \"currencyCode\" = ?, \"exchangeRate\" = ?, \"rateSource\" = ?"
                + " WHERE id = ?").params(ccy, rate, table != null && table.signum() > 0 ? "table" : "po", grnId).update();
        return rate;
    }

    /**
     * FX-2 G: the functional value per order-currency unit at which this PO's received goods sit on AP: the
     * value-weighted rate of its posted receipts, else the PO rate.
     */
    BigDecimal carryingRate(Map<String, Object> po) {
        BigDecimal poRate = functionalRate(po);
        if (poRate.compareTo(BigDecimal.ONE) == 0) {
            return poRate;
        }
        BigDecimal weighted = jdbc.sql("SELECT sum(l.quantity * l.\"unitCost\" * g.\"exchangeRate\") / nullif(sum(l.quantity"
                        + " * l.\"unitCost\"), 0) FROM \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON g.id ="
                        + " l.\"goodsReceiptId\" WHERE g.\"purchaseOrderId\" = ? AND g.status = 'posted' AND"
                        + " g.\"exchangeRate\" IS NOT NULL")
                .param(po.get("id")).query(BigDecimal.class).optional().orElse(null);
        return weighted == null || weighted.signum() <= 0 ? poRate : weighted.setScale(8, RoundingMode.HALF_UP);
    }

    /** The organization's functional currency, or {@code null} when it has none. */
    private String functionalCurrency() {
        return jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org())
                .query(String.class).optional().orElse(null);
    }

    /** Unit handed to the stock engine: the snapshot unit, or the product's purchase unit for legacy lines. */
    static String stockUnit(LineUnit unit, Map<String, Object> product) {
        return unit.factor() != null ? unit.uomId() : (String) product.get("purchaseUomId");
    }

    /**
     * P1-12: only inventory-tracked goods enter stock and are vouchered at receipt. Services and non-tracked items
     * are expensed by their vendor bill (which treats them as non-stockable) — receiving them into stock as well
     * would raise AP twice.
     */
    static boolean stocked(Map<String, Object> product) {
        return Boolean.TRUE.equals(product.get("trackInventory")) && !"service".equals(String.valueOf(product.get("productType")));
    }

    /** Product row scoped like {@code tx.product.findFirst} (org + not deleted). */
    Map<String, Object> product(String productId, String orgId) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(productId, orgId));
    }

    // ── Step 3: Register Payment (credit purchases only) ───────────────────

    /** Manual payment on a credit PO: Dr AP / Cr Cash|Bank (+ drawer pay-out for cash). */
    public Map<String, Object> pay(String id, PayInput dto) {
        String orgId = org();
        requireOwned(id);
        BigDecimal[] amount = {Money.ZERO};
        Map<String, Object> result = tx.execute(st -> {
            // Lock the PO BEFORE reading its balance so concurrent full-balance payments serialise.
            jdbc.sql("SELECT id FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(id, orgId).query().listOfRows();
            Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE"
                    + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
            if (po == null) {
                throw ApiException.notFound("Purchase order not found");
            }
            String refused = payStatusRefusal(po);
            if (refused != null) {
                throw ApiException.badRequest(refused);
            }
            // Gap fix h03 residual (b): the B2 cap reads the supplier's AP ledger, so PO payments to one supplier are
            // serialised on the supplier row (after the PO lock; key-share FK inserts are not blocked).
            jdbc.sql("SELECT id FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? FOR NO KEY UPDATE")
                    .params(po.get("partnerId"), orgId).query().listOfRows();
            PayFigures f = payFigures(po, orgId);
            BigDecimal remaining = f.remaining();
            BigDecimal owed = f.owed();
            BigDecimal rate = f.rate();
            if (f.received().signum() <= 0) {
                throw ApiException.badRequest(NOTHING_RECEIVED);
            }
            if (f.payable().compareTo(new BigDecimal("0.005")) < 0) {
                throw ApiException.badRequest(ALL_BILLED);
            }
            BigDecimal payAmount = dto.amount() != null ? dto.amount() : f.payableNow();
            if (payAmount.signum() <= 0) {
                if (remaining.signum() > 0 && owed.signum() <= 0) {
                    throw ApiException.badRequest(NOTHING_OWED);
                }
                throw ApiException.badRequest(remaining.signum() <= 0 ? "This purchase order is already fully paid"
                        : "Payment amount must be positive");
            }
            if (payAmount.compareTo(remaining) > 0) {
                throw ApiException.badRequest("Payment of " + JsNumbers.dec(payAmount) + " exceeds remaining balance of "
                        + JsNumbers.dec(remaining));
            }
            BigDecimal functionalAmount = payAmount.multiply(rate).setScale(6, java.math.RoundingMode.HALF_UP);
            if (functionalAmount.subtract(owed).compareTo(new BigDecimal("0.005")) > 0) {
                throw ApiException.badRequest("Payment of " + JsNumbers.dec(payAmount) + " would overpay the supplier:"
                        + " the ledger shows " + JsNumbers.dec(owed.max(Money.ZERO)) + " still owed to them (returns, credits"
                        + " or other payments have reduced it)");
            }
            amount[0] = payAmount;
            BigDecimal amountDec = payAmount;
            BigDecimal newTotalPaid = f.paidToDate().add(payAmount);
            String newPaymentStatus = newTotalPaid.compareTo(Money.dec(po.get("totalAmount"))) >= 0 ? "paid" : "partial";
            Instant paidAt = dto.paidAt() != null ? ProcurementSql.date(dto.paidAt()) : null;

            Map<String, Object> payment = SqlRows.one(PurchasePayment.class, jdbc.sql("INSERT INTO \"PurchasePayment\""
                            + " (id, \"organizationId\", \"purchaseOrderId\", amount, \"paidAt\", \"paidById\", reference,"
                            + " notes, \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, " + (paidAt != null ? "?" : NOW)
                            + ", ?, ?, ?, " + NOW + ", " + NOW + ") RETURNING *")
                    .params(paymentParams(orgId, id, amountDec, paidAt, dto)));
            jdbc.sql("UPDATE \"PurchaseOrder\" SET \"totalPaid\" = ?, \"paymentStatus\" = ?::\"PaymentStatus\","
                            + " version = version + 1, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                            + " AND \"deletedAt\" IS NULL")
                    .params(newTotalPaid, newPaymentStatus, id, orgId).update();

            String method = dto.method() != null ? dto.method() : "bank";
            Map<String, Object> session = "cash".equals(method) ? cashSession.findOpen(dto.cashRegisterId()) : null;
            if ("cash".equals(method) && session == null) {
                throw ApiException.badRequest("Select an open register for this cash payment");
            }
            // FX-3: a foreign PO is settled at the payment-date rate (stated, else the table under the payment_out
            // policy). AP is relieved at the receipts' carrying rate; the functional cash paid differs by realised FX.
            Instant payDate = paidAt != null ? paidAt : nowMs();
            String poCcy = po.get("currencyCode") == null ? null : String.valueOf(po.get("currencyCode")).toUpperCase();
            String functional = functionalCurrency();
            boolean foreignPo = poCcy != null && functional != null && !functional.equals(poCcy);
            // like receipts, a missing rate never blocks: with no stated and no table rate the payment settles at the
            // carrying rate (no FX), as before FX-3
            CurrencyService.Policy payPolicy = foreignPo && dto.exchangeRate() == null ? currencies.policy("payment_out")
                    : null;
            boolean rateKnown = foreignPo && (dto.exchangeRate() != null || currencies.findRate(poCcy, functional,
                    payDate, payPolicy.rateType(), payPolicy.requireApprovedRate()) != null);
            CurrencyService.Resolution payRate = rateKnown ? currencies.resolveStated(poCcy, functional,
                    dto.exchangeRate(), payDate, "payment_out") : null;
            BigDecimal cashAmount = payRate == null ? functionalAmount
                    : payAmount.multiply(payRate.rate()).setScale(6, RoundingMode.HALF_UP);
            BigDecimal fx = functionalAmount.subtract(cashAmount);
            if (payRate != null) {
                jdbc.sql("UPDATE \"PurchasePayment\" SET \"currencyCode\" = ?, \"exchangeRate\" = ?, \"carryingRate\" = ?,"
                        + " \"fxAmount\" = ? WHERE id = ?").params(poCcy, payRate.rate(), rate, fx, payment.get("id"))
                        .update();
            }
            Map<String, Object> paymentJournal = stockPosting.postPurchasePayment(
                    session == null ? null : (String) session.get("id"), (String) po.get("partnerId"), functionalAmount,
                    method, payDate, "purchase_order", id,
                    "Payment PO " + po.get("orderNumber") + (dto.reference() != null && !dto.reference().isEmpty()
                            ? " · " + dto.reference() : ""),
                    "purchase_payment:" + payment.get("id"), (String) po.get("currencyCode"), payAmount, cashAmount,
                    payRate);
            if ("cash".equals(method)) {
                cashSession.recordExternalPayOut((String) session.get("id"), cashAmount,
                        "Payment PO " + po.get("orderNumber"), (String) paymentJournal.get("id"));
            }
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "update", null, Obj.of("paymentStatus",
                    newPaymentStatus, "amountPaid", JsNumbers.js(amount[0]), "method", method)));
            return payment;
        });
        events.publish("purchase_order.paid", Obj.of("organizationId", orgId, "orderId", id, "amount",
                JsNumbers.js(amount[0])));
        return result;
    }

    private static final String NOTHING_RECEIVED = "Nothing has been received on this purchase order yet — receive the"
            + " goods (or post the supplier bill) before paying";
    private static final String ALL_BILLED = "The goods received on this purchase order have been billed — pay the"
            + " supplier bill instead";
    private static final String NOTHING_OWED = "Nothing is owed to this supplier in the ledger — returns, credits or"
            + " other payments have already settled what this order received";

    /**
     * The figures the pay route checks, in the order currency ({@code owed} and {@code rate} functional): what the
     * receipts vouchered (capped at the order total), what bills took over, settled claims, paid to date, PO payments
     * applied to bills, what is left on the order, and what the supplier's AP ledger still shows owed.
     */
    record PayFigures(BigDecimal received, BigDecimal billed, BigDecimal claimed, BigDecimal paidToDate,
            BigDecimal appliedToBills, BigDecimal payable, BigDecimal remaining, BigDecimal owed, BigDecimal rate) {

        /** The default payment: what is left on the order, never more than the ledger shows owed. */
        BigDecimal payableNow() {
            return Money.min(remaining, Money.div(owed, rate));
        }
    }

    private PayFigures payFigures(Map<String, Object> po, String orgId) {
        String id = (String) po.get("id");
        // Paid-to-date from the immutable payment rows, not the cached aggregate.
        BigDecimal paidSum = jdbc.sql("SELECT sum(amount) FROM \"PurchasePayment\" WHERE \"organizationId\" = ?"
                + " AND \"purchaseOrderId\" = ?").params(orgId, id).query(BigDecimal.class).optional().orElse(null);
        BigDecimal paidToDate = Money.max(Money.dec(paidSum), Money.dec(po.get("totalPaid")));
        // P0-4: the PO route settles the payable its receipts raised (receipt vouchers), never more — paying ahead of a
        // receipt would leave AP in debit. Lines without a receipt voucher are billed and paid as bills. Review
        // 2026-11-17 (ACR-2): a receipt a supplier bill has taken over is paid on that bill; the PO payments already
        // applied to bills no longer count against the PO.
        BigDecimal received = Money.min(Money.dec(po.get("totalAmount")), vouchered(id, orgId));
        BigDecimal billed = billedVouchered(id, orgId);
        BigDecimal rate = carryingRate(po); // FX-2 G: AP sits at the receipts' rates
        // Blocker fix 2026-11-17 (B2): a settled claim on this order or its receipts already credited the payable.
        BigDecimal claimed = Money.div(claimCredits(id, orgId, (String) po.get("partnerId")), rate);
        BigDecimal payable = received.subtract(billed);
        BigDecimal applied = appliedToBills(id, orgId);
        BigDecimal remaining = payable.subtract(claimed).subtract(paidToDate.subtract(applied));
        // Blocker fix 2026-11-17 (B2): a return to the supplier debits their payable without naming the order, so the
        // order's own figures can still show goods that have gone back. Never settle more than the AP ledger says is
        // owed to the supplier (the supplier-ledger measure), or AP ends in debit and the supplier is paid twice.
        BigDecimal owed = stockPosting.supplierOpenPayable((String) po.get("partnerId"));
        return new PayFigures(received, billed, claimed, paidToDate, applied, payable, remaining, owed, rate);
    }

    /** Why the order takes no manual payment at all, or {@code null}. */
    private static String payStatusRefusal(Map<String, Object> po) {
        if (!"credit".equals(po.get("paymentType"))) {
            return "Only credit purchases can accept manual payments";
        }
        if ("cancelled".equals(po.get("status"))) {
            return "Cannot pay a cancelled PO";
        }
        if ("draft".equals(po.get("status"))) {
            return "Cannot pay a purchase order that is not approved yet";
        }
        return null;
    }

    /**
     * Gap fix h06-F6 (additive route): the server's payable figures for the Pay screen, which used to compute its own
     * balance from the order total. {@code payableNow} is what a payment without an amount would pay; {@code message}
     * is why nothing can be paid now (the refusal the pay route would give), else {@code null}. Order currency,
     * except {@code supplierOwedFunctional}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> payable(String id) {
        String orgId = org();
        Map<String, Object> po = requireOwned(id);
        PayFigures f = payFigures(po, orgId);
        String message = payStatusRefusal(po);
        BigDecimal now = f.payableNow();
        if (message == null) {
            if (f.received().signum() <= 0) {
                message = NOTHING_RECEIVED;
            } else if (f.payable().compareTo(new BigDecimal("0.005")) < 0) {
                message = ALL_BILLED;
            } else if (now.signum() <= 0) {
                message = f.remaining().signum() > 0 && f.owed().signum() <= 0 ? NOTHING_OWED
                        : "This purchase order is already fully paid";
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purchaseOrderId", id);
        out.put("orderNumber", po.get("orderNumber"));
        out.put("currencyCode", po.get("currencyCode"));
        out.put("paymentType", po.get("paymentType"));
        out.put("totalAmount", JsNumbers.dec(Money.dec(po.get("totalAmount"))));
        out.put("received", JsNumbers.dec(f.received()));
        out.put("billed", JsNumbers.dec(f.billed()));
        out.put("claimed", JsNumbers.dec(f.claimed()));
        out.put("paid", JsNumbers.dec(f.paidToDate()));
        out.put("appliedToBills", JsNumbers.dec(f.appliedToBills()));
        out.put("remaining", JsNumbers.dec(f.remaining()));
        out.put("supplierOwed", JsNumbers.dec(Money.div(f.owed(), f.rate())));
        out.put("supplierOwedFunctional", JsNumbers.dec(f.owed()));
        out.put("payableNow", JsNumbers.dec(message == null ? now.max(Money.ZERO) : Money.ZERO));
        out.put("canPay", message == null);
        out.put("message", message);
        return out;
    }

    /**
     * What this PO's posted receipts vouchered to AP, in the PO currency: Σ received stock-line value incl. the PO
     * line's tax (the same base {@code postReceiptVoucher} used). Reversed receipts no longer count.
     */
    private BigDecimal vouchered(String poId, String orgId) {
        // Blocker fix 2026-11-17 (B2): only lines the receipt actually vouchered — stocked products, the predicate of
        // stocked() in GoodsReceiptsService.post. A service line raises no AP at receipt; its bill is the payable, so
        // counting it here let the PO route pay it a second time.
        return Money.dec(jdbc.sql("SELECT COALESCE(SUM(l.quantity * l.\"unitCost\" * (1 + COALESCE(pl.\"taxRate\", 0) / 100)),"
                + " 0) FROM \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON g.id = l.\"goodsReceiptId\""
                + " JOIN \"Product\" p ON p.id = l.\"productId\" AND p.\"organizationId\" = g.\"organizationId\""
                + " LEFT JOIN \"PurchaseOrderLine\" pl ON pl.id = l.\"purchaseOrderLineId\" WHERE g.\"organizationId\" = ?"
                + " AND g.\"purchaseOrderId\" = ? AND g.status = 'posted' AND g.\"deletedAt\" IS NULL"
                + " AND " + STOCKED_SQL).params(orgId, poId).query(BigDecimal.class).single());
    }

    /**
     * Blocker fix 2026-11-17 (B2): the AP debit (functional) of settled supplier claims on this order or one of its
     * receipts. A claim on a bill lowers that bill instead, so it is left out (the bill route pays it).
     */
    private BigDecimal claimCredits(String poId, String orgId, String partnerId) {
        if (partnerId == null) {
            return Money.ZERO;
        }
        return Money.dec(jdbc.sql("SELECT COALESCE(SUM(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"SupplierClaim\" c"
                        + " JOIN \"JournalEntry\" e ON e.id = c.\"journalEntryId\" AND e.status = 'posted'"
                        + " JOIN \"JournalLine\" l ON l.\"journalEntryId\" = e.id AND l.\"partnerId\" = ?"
                        + " AND l.\"accountId\" = ANY(?::text[])"
                        + " WHERE c.\"organizationId\" = ? AND c.status = 'settled' AND c.\"vendorBillId\" IS NULL"
                        + " AND (c.\"purchaseOrderId\" = ? OR c.\"goodsReceiptId\" IN (SELECT g.id FROM"
                        + " \"GoodsReceiptNote\" g WHERE g.\"organizationId\" = ? AND g.\"purchaseOrderId\" = ?))")
                .params(partnerId, stockPosting.supplierPayableAccounts(partnerId).toArray(String[]::new), orgId, poId,
                        orgId, poId)
                .query(BigDecimal.class).single());
    }

    /** {@link #stocked} in SQL over {@code Product p}: the receipt vouchers (and stocks) these lines only. */
    private static final String STOCKED_SQL = "p.\"trackInventory\" AND COALESCE(p.\"productType\"::text, '') <> 'service'";

    /** Receipt-priced value of the PO's received quantity that supplier bills have taken over (ACR-2). */
    private BigDecimal billedVouchered(String poId, String orgId) {
        return Money.dec(jdbc.sql("SELECT COALESCE(SUM(LEAST(l.\"billedQuantity\", l.quantity) * l.\"unitCost\" * (1 +"
                + " COALESCE(pl.\"taxRate\", 0) / 100)), 0) FROM \"GoodsReceiptLine\" l JOIN \"GoodsReceiptNote\" g ON"
                + " g.id = l.\"goodsReceiptId\" JOIN \"Product\" p ON p.id = l.\"productId\" AND p.\"organizationId\" ="
                + " g.\"organizationId\" LEFT JOIN \"PurchaseOrderLine\" pl ON pl.id = l.\"purchaseOrderLineId\""
                + " WHERE g.\"organizationId\" = ? AND g.\"purchaseOrderId\" = ? AND g.status = 'posted' AND"
                + " g.\"deletedAt\" IS NULL AND " + STOCKED_SQL).params(orgId, poId).query(BigDecimal.class)
                .single());
    }

    /** PO payments applied to the PO's bills (ACR-2). */
    private BigDecimal appliedToBills(String poId, String orgId) {
        return Money.dec(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM \"PurchaseOrderBillApplication\" WHERE"
                + " \"organizationId\" = ? AND \"purchaseOrderId\" = ? AND \"releasedAt\" IS NULL").params(orgId, poId)
                .query(BigDecimal.class).single());
    }

    private static Object[] paymentParams(String orgId, String id, BigDecimal amount, Instant paidAt, PayInput dto) {
        List<Object> p = new ArrayList<>(List.of(UUID.randomUUID().toString(), orgId, id, amount));
        if (paidAt != null) {
            p.add(SqlRows.ts(paidAt));
        }
        p.add(user());
        p.add(dto.reference());
        p.add(dto.notes());
        return p.toArray();
    }

    // ── Update / cancel / delete / activate ────────────────────────────────

    /** Edit an active PO's header (version-guarded). */
    public Map<String, Object> update(String id, UpdateInput dto) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE"
                    + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
            if (po == null) {
                throw ApiException.notFound("Purchase order not found");
            }
            if (!"active".equals(po.get("status"))) {
                throw ApiException.badRequest("Only active POs can be updated");
            }
            StringBuilder set = new StringBuilder("\"expectedDeliveryDate\" = ?, version = version + 1, \"updatedAt\" = "
                    + NOW);
            List<Object> params = new ArrayList<>();
            params.add(SqlRows.ts(ProcurementSql.date(dto.expectedDeliveryDate())));
            Map<String, Object> newValues = new LinkedHashMap<>();
            setIfPresent(set, params, newValues, "description", dto.description());
            newValues.put("expectedDeliveryDate", dto.expectedDeliveryDate());
            setIfPresent(set, params, newValues, "notes", dto.notes());
            setIfPresent(set, params, newValues, "terms", dto.terms());
            params.addAll(List.of(id, orgId, po.get("version")));
            int updated = jdbc.sql("UPDATE \"PurchaseOrder\" SET " + set + " WHERE id = ? AND \"organizationId\" = ?"
                    + " AND version = ? AND \"deletedAt\" IS NULL").params(params).update();
            if (updated == 0) {
                throw ApiException.badRequest(
                        "Purchase order was modified by another user. Please refresh and try again.");
            }
            Map<String, Object> oldValues = new LinkedHashMap<>();
            oldValues.put("description", po.get("description"));
            oldValues.put("expectedDeliveryDate", po.get("expectedDeliveryDate"));
            oldValues.put("notes", po.get("notes"));
            oldValues.put("terms", po.get("terms"));
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "update", oldValues, newValues));
            return SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        });
    }

    /** Prisma skips {@code undefined} data keys; an explicit {@code null} clears the column. */
    private static void setIfPresent(StringBuilder set, List<Object> params, Map<String, Object> newValues, String col,
            String value) {
        if (EntityPatch.present(col, value)) {
            set.append(", ").append(col).append(" = ?");
            params.add(value);
            newValues.put(col, value);
        }
    }

    /** Cancel unless received / billed / closed / already cancelled. */
    public Map<String, Object> cancel(String id, String reason) {
        String orgId = org();
        return tx.execute(st -> {
            Map<String, Object> po = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE"
                    + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
            if (po == null) {
                throw ApiException.notFound("Purchase order not found");
            }
            if (List.of("received", "cancelled", "billed", "closed").contains(po.get("status"))) {
                throw ApiException.badRequest("Cannot cancel PO in status " + po.get("status"));
            }
            returnOpenBalance(id);
            int updated = jdbc.sql("UPDATE \"PurchaseOrder\" SET status = 'cancelled', notes = ?, version = version + 1,"
                            + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND version = ?"
                            + " AND \"deletedAt\" IS NULL")
                    .params(reason != null ? reason : po.get("notes"), id, orgId, po.get("version")).update();
            if (updated == 0) {
                throw ApiException.badRequest(
                        "Purchase order was modified by another user. Please refresh and try again.");
            }
            commitments.sync(id);
            Map<String, Object> cancelled = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\""
                    + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "cancel", Obj.of("status", po.get("status")),
                    Obj.of("status", "cancelled", "reason", reason, "orderNumber", po.get("orderNumber"))));
            events.publish("purchase_order.cancelled", Obj.of("organizationId", orgId, "orderId", id, "reason",
                    reason != null ? reason : ""));
            return cancelled;
        });
    }

    /**
     * Give a closing order's undelivered balance back to where it was sourced from (ADR-PROCUREMENT-001): request lines
     * get it back as quantity still to order (a fully ordered request returns to {@code approved}), agreement lines
     * get it back as quantity / value still to release (an exhausted agreement becomes active again). Runs in the
     * caller's transaction, before the order lines record the cancelled quantity.
     */
    void returnOpenBalance(String purchaseOrderId) {
        String orgId = org();
        jdbc.sql("UPDATE \"PurchaseRequestLine\" rl SET \"orderedQuantity\" = GREATEST(0, rl.\"orderedQuantity\" -"
                + " GREATEST(0, pl.quantity - pl.\"receivedQuantity\" - pl.\"cancelledQuantity\")) FROM \"PurchaseOrderLine\" pl"
                + " WHERE pl.\"purchaseOrderId\" = ? AND pl.\"organizationId\" = ? AND pl.\"requestLineId\" = rl.id")
                .params(purchaseOrderId, orgId).update();
        jdbc.sql("UPDATE \"PurchaseRequest\" SET status = 'approved', \"updatedAt\" = " + NOW + " WHERE \"organizationId\" = ?"
                + " AND status = 'converted' AND id IN (SELECT rl.\"purchaseRequestId\" FROM \"PurchaseRequestLine\" rl JOIN"
                + " \"PurchaseOrderLine\" pl ON pl.\"requestLineId\" = rl.id WHERE pl.\"purchaseOrderId\" = ? AND rl.quantity"
                + " > rl.\"orderedQuantity\")").params(orgId, purchaseOrderId).update();
        jdbc.sql("UPDATE \"PurchaseAgreement\" a SET \"releasedValue\" = GREATEST(0, a.\"releasedValue\" - x.v), status ="
                + " CASE WHEN a.status = 'exhausted' THEN 'active' ELSE a.status END, \"updatedAt\" = " + NOW + " FROM"
                + " (SELECT al.\"agreementId\", SUM(GREATEST(0, pl.quantity - pl.\"receivedQuantity\" - pl.\"cancelledQuantity\")"
                + " * pl.\"unitPrice\") AS v FROM \"PurchaseOrderLine\" pl JOIN \"PurchaseAgreementLine\" al ON al.id ="
                + " pl.\"agreementLineId\" WHERE pl.\"purchaseOrderId\" = ? AND pl.\"organizationId\" = ? GROUP BY"
                + " al.\"agreementId\") x WHERE a.id = x.\"agreementId\"").params(purchaseOrderId, orgId).update();
        jdbc.sql("UPDATE \"PurchaseAgreementLine\" al SET \"releasedQuantity\" = GREATEST(0, al.\"releasedQuantity\" -"
                + " GREATEST(0, pl.quantity - pl.\"receivedQuantity\" - pl.\"cancelledQuantity\")) FROM \"PurchaseOrderLine\" pl"
                + " WHERE pl.\"purchaseOrderId\" = ? AND pl.\"organizationId\" = ? AND pl.\"agreementLineId\" = al.id")
                .params(purchaseOrderId, orgId).update();
    }

    /**
     * Hard delete (not received / cancelled). Gap fix h03-F9: one transaction under the order's row lock — the checks
     * run under the lock, the undelivered balance goes back to its request / agreement lines and the budget
     * commitments are released before the row goes (Nest left request lines ordered and commitments held).
     */
    public Map<String, Object> remove(String id) {
        requireOwned(id);
        tx.executeWithoutResult(st -> {
            jdbc.sql("SELECT id FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                    .params(id, org()).query().listOfRows();
            Map<String, Object> po = requireOwned(id);
            if ("received".equals(po.get("status")) || "cancelled".equals(po.get("status"))) {
                throw ApiException.badRequest("Cannot delete a received or cancelled PO");
            }
            // P0-2: a PO with receipts, payments or bills is evidence for posted GL — deleting it would orphan
            // vouchered receipts (FK SET NULL) and let a later bill raise AP a second time. Cancel it instead.
            long evidence = jdbc.sql("SELECT (SELECT count(*) FROM \"GoodsReceiptNote\" WHERE \"organizationId\" = ?"
                    + " AND \"purchaseOrderId\" = ? AND status <> 'cancelled') + (SELECT count(*) FROM \"PurchasePayment\""
                    + " WHERE \"organizationId\" = ? AND \"purchaseOrderId\" = ?) + (SELECT count(*) FROM \"VendorBillLink\""
                    + " WHERE \"organizationId\" = ? AND \"purchaseOrderId\" = ?)").params(org(), id, org(), id, org(), id)
                    .query(Long.class).single();
            if (evidence > 0) {
                throw ApiException.badRequest("Cannot delete a purchase order that has goods receipts, payments or"
                        + " bills — cancel it instead");
            }
            returnOpenBalance(id);
            // The commitments follow the order's status: release them as for a cancelled order before the row goes.
            jdbc.sql("UPDATE \"PurchaseOrder\" SET status = 'cancelled' WHERE id = ? AND \"organizationId\" = ?")
                    .params(id, org()).update();
            commitments.sync(id);
            int deleted = jdbc.sql("DELETE FROM \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL").params(id, org()).update();
            if (deleted == 0) {
                throw new IllegalStateException("No record was found for a delete.");
            }
            audit.recordInTx(AuditInput.of("PurchaseOrder", id, "delete", Obj.of("orderNumber", po.get("orderNumber"),
                    "status", po.get("status"), "totalAmount", po.get("totalAmount")), null));
        });
        return Obj.of("ok", true);
    }

    /** Activate a draft PO once no approval request is pending. */
    public Map<String, Object> activate(String id) {
        Map<String, Object> po = requireOwned(id);
        if (!"draft".equals(po.get("status"))) {
            throw ApiException.badRequest("Only draft POs can be activated, current status: " + po.get("status"));
        }
        long pending = jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\""
                        + " = 'purchase_order' AND \"entityId\" = ? AND status = 'pending'")
                .params(org(), id).query(Long.class).single();
        if (pending > 0) {
            throw ApiException.forbidden(
                    "This purchase order is awaiting approval. Approve or reject the request first.");
        }
        // P1-2: the latest decision counts — a rejected order is not activated by skipping the approver.
        String latest = jdbc.sql("SELECT status::text FROM \"ApprovalRequest\" WHERE \"organizationId\" = ?"
                        + " AND \"entityType\" = 'purchase_order' AND \"entityId\" = ? ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(org(), id).query(String.class).optional().orElse(null);
        if ("rejected".equals(latest)) {
            throw ApiException.forbidden("This purchase order's approval was rejected — it cannot be activated");
        }
        // Gap fix h03-F6 (Nest deviation, owner-approved 2026-11-17): activating a draft is approving it, so it passes
        // the purchase_order gate again — a draft whose request was withdrawn or never raised no longer activates on
        // the creator's word while a workflow applies. No workflow: the order would have been active when created.
        Map<String, Object> gate = approvals.checkOrRequestApproval("purchase_order", id, activationSnapshot(po));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.forbidden(
                    "This purchase order is awaiting approval. Approve or reject the request first.");
        }
        // Gap fix h03-F9: only a draft activates (a concurrent activation or cancel wins).
        Map<String, Object> updated = SqlRows.one(PurchaseOrder.class, jdbc.sql("UPDATE \"PurchaseOrder\" SET status ="
                        + " 'active', \"approvedAt\" = " + NOW + ", \"approvedById\" = ?, version = version + 1,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = 'draft'"
                        + " AND \"deletedAt\" IS NULL RETURNING *")
                .params(user(), id, org()));
        if (updated == null) {
            throw ApiException.badRequest("Only draft POs can be activated, current status: "
                    + requireOwned(id).get("status"));
        }
        commitments.sync(id);
        audit.record(AuditInput.of("PurchaseOrder", id, "approve", null, Obj.of("status", "active")));
        events.publish("purchase_order.activated", Obj.of("organizationId", org(), "orderId", id, "orderNumber",
                po.get("orderNumber")));
        return updated;
    }

    /**
     * Activate a draft order whose approval was granted (called by {@link PurchaseOrderApprovalSubscriber}). Returns
     * {@code false} when the order is gone or no longer draft (already activated / cancelled), so replays are no-ops.
     */
    public boolean activateApproved(String id, String approverId) {
        Map<String, Object> updated = SqlRows.one(PurchaseOrder.class, jdbc.sql("UPDATE \"PurchaseOrder\" SET status ="
                        + " 'active', \"approvedAt\" = " + NOW + ", \"approvedById\" = ?, version = version + 1, \"updatedAt\" = "
                        + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = 'draft' AND \"deletedAt\" IS NULL"
                        + " RETURNING *").params(approverId, id, org()));
        if (updated == null) {
            return false;
        }
        commitments.sync(id);
        audit.record(AuditInput.of("PurchaseOrder", id, "approve", Obj.of("status", "draft"), Obj.of("status", "active",
                "via", "approval")));
        events.publish("purchase_order.approved", Obj.of("organizationId", org(), "orderId", id, "orderNumber",
                updated.get("orderNumber"), "approverId", approverId == null ? "" : approverId));
        return true;
    }

    /** The order as its approval request saw it at creation (functional amount, the maker, branch, department). */
    private Map<String, Object> activationSnapshot(Map<String, Object> po) {
        BigDecimal amount = Money.dec(po.get("totalAmount")).multiply(functionalRate(po)).setScale(6,
                RoundingMode.HALF_UP);
        String department = jdbc.sql("SELECT \"departmentId\" FROM \"PurchaseOrder\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(po.get("id"), org()).query(String.class).optional().orElse(null);
        return defined("amount", JsNumbers.js(amount), "partnerId", po.get("partnerId"), "orderNumber",
                po.get("orderNumber"), "createdBy", po.get("createdBy"), "branchId", po.get("branchId"),
                "departmentId", department);
    }

    /**
     * Purchase readiness H10b (2026-11-27): the line rules of every order-creating path (the route, RFQ awards, agreement
     * releases, requisition conversion, the portal), not only the route DTO. A quantity above 0, a price of 0 or more, a
     * tax rate between 0 and 100. A line naming a tax takes the Tax master's rate: a different client rate is refused;
     * an omitted rate is the master's (an inclusive or withholding tax adds no input VAT on an order line, so its
     * omitted rate stays 0).
     */
    private CreateInput withCheckedLines(CreateInput dto, String orgId) {
        Map<String, Map<String, Object>> taxes = new HashMap<>();
        List<LineInput> lines = new ArrayList<>();
        for (LineInput ln : dto.lines()) {
            String what = ln.description() == null || ln.description().isBlank() ? "A line"
                    : "Line \"" + ln.description() + "\"";
            if (ln.quantity() == null || ln.quantity().signum() <= 0) {
                throw ApiException.badRequest(what + ": quantity must be greater than 0");
            }
            if (ln.unitPrice() == null || ln.unitPrice().signum() < 0) {
                throw ApiException.badRequest(what + ": unitPrice must not be negative");
            }
            BigDecimal rate = ln.taxRate();
            if (rate != null && (rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw ApiException.badRequest(what + ": taxRate must be between 0 and 100");
            }
            if (ln.taxId() != null && !ln.taxId().isBlank()) {
                Map<String, Object> tax = taxes.computeIfAbsent(ln.taxId(), t -> SqlRows.one(jdbc.sql("SELECT name, rate,"
                        + " \"isInclusive\", type::text AS type FROM \"Tax\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(t, orgId)));
                if (tax == null) {
                    throw ApiException.notFound("Tax not found");
                }
                BigDecimal master = Money.dec(tax.get("rate"));
                if (rate != null && rate.compareTo(master) != 0) {
                    throw ApiException.badRequest(what + ": tax rate " + JsNumbers.dec(rate) + "% does not match tax "
                            + tax.get("name") + " (" + JsNumbers.dec(master) + "%); leave the rate empty to use the"
                            + " tax's rate");
                }
                if (rate == null && !Boolean.TRUE.equals(tax.get("isInclusive")) && !"withholding".equals(tax.get("type"))) {
                    rate = master;
                }
            }
            lines.add(new LineInput(ln.productId(), ln.description(), ln.quantity(), ln.unitOfMeasureId(), ln.unitPrice(),
                    ln.taxId(), rate, ln.notes(), ln.variantId()));
        }
        return new CreateInput(dto.partnerId(), dto.branchId(), dto.description(), dto.expectedDeliveryDate(),
                dto.currencyCode(), dto.exchangeRate(), dto.paymentType(), dto.notes(), dto.terms(), dto.warehouseId(),
                dto.requestId(), lines, dto.departmentId());
    }

    /** Re-state an order's budget commitments after another service changed its quantities (GRN reverse). */
    void commitmentsChanged(String purchaseOrderId) {
        commitments.sync(purchaseOrderId);
    }

    private Map<String, Object> requireOwned(String id) {
        Map<String, Object> order = SqlRows.one(PurchaseOrder.class, jdbc.sql("SELECT * FROM \"PurchaseOrder\" WHERE"
                + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (order == null) {
            throw ApiException.notFound("Purchase order not found");
        }
        return order;
    }

    /** Partner row for the debit-note / GRN readers (unfiltered include). */
    Map<String, Object> partner(Object partnerId) {
        return partnerId == null ? null : SqlRows.one(Partner.class, jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(partnerId, org()));
    }
}
