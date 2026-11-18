package com.inventoryerp.invoicing.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.OrgDates;
import com.inventoryerp.core.application.UomConversionService.UnitRole;
import static com.inventoryerp.invoicing.application.DocumentBuilderService.NOW;
import static com.inventoryerp.invoicing.application.DocumentBuilderService.ts;

import com.inventoryerp.documents.domain.Document;
import com.inventoryerp.invoicing.application.DocumentBuilderService.HeaderInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.invoicing.domain.PaymentAllocation;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.approvals.ControlledActionService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.threewaymatch.ThreeWayMatchService;
import com.inventoryerp.kernel.workflow.WorkflowService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Vendor bills / Accounts Payable (port of {@code vendor-bill.service.ts}): draft create (PO links + approval
 * request), edit, post behind the three-way-match and approval gates via the vendor-bill workflow, void, and the
 * supplier ledger.
 */
@Service
public class VendorBillService {

    /** {@code CreateVendorBillDto}. */
    public record BillInput(String partnerId, String issueDate, String dueDate, String currencyId, String reference,
            String notes, String branchId, List<String> purchaseOrderIds, List<LineInput> lines,
            String withholdingTaxId) {

        /** A bill without header withholding. */
        public BillInput(String partnerId, String issueDate, String dueDate, String currencyId, String reference,
                String notes, String branchId, List<String> purchaseOrderIds, List<LineInput> lines) {
            this(partnerId, issueDate, dueDate, currencyId, reference, notes, branchId, purchaseOrderIds, lines, null);
        }

        /**
         * Lines with the header withholding tax (ADR-S071) added to each product line's tax list; a line without a
         * list keeps its single tax (stated or the product's) next to the withholding.
         */
        public List<LineInput> effectiveLines(java.util.function.Function<LineInput, String> productTax) {
            if (withholdingTaxId == null || withholdingTaxId.isBlank() || lines == null) {
                return lines;
            }
            List<LineInput> out = new ArrayList<>();
            for (LineInput l : lines) {
                if (l.lineType() != null && !"product".equals(l.lineType())) {
                    out.add(l);
                    continue;
                }
                List<String> ids = new ArrayList<>();
                if (l.taxIds() != null && !l.taxIds().isEmpty()) {
                    ids.addAll(l.taxIds());
                } else {
                    String single = l.taxId() != null ? l.taxId() : productTax.apply(l);
                    if (single != null) {
                        ids.add(single);
                    }
                }
                if (!ids.contains(withholdingTaxId)) {
                    ids.add(withholdingTaxId);
                }
                out.add(l.withTaxIds(ids));
            }
            return out;
        }
    }

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt", "issueDate", "dueDate",
            "documentNumber", "totalAmount", "amountResidual", "status", "reference");

    private final JdbcClient jdbc;
    private final EventBus events;
    private final WorkflowService workflow;
    private final DocumentBuilderService builder;
    private final ThreeWayMatchService twm;
    private final ApprovalsService approvals;
    private final ControlledActionService controlled;
    private final AuditService audit;
    private final TransactionTemplate newTx;
    private final AccountDeterminationService determination;
    private final List<VendorBillPostGuard> guards;

    public VendorBillService(JdbcClient jdbc, EventBus events, WorkflowService workflow, DocumentBuilderService builder,
            ThreeWayMatchService twm, ApprovalsService approvals, ControlledActionService controlled,
            AuditService audit, PlatformTransactionManager txm, AccountDeterminationService determination,
            List<VendorBillPostGuard> guards) {
        this.jdbc = jdbc;
        this.guards = guards;
        this.determination = determination;
        this.events = events;
        this.workflow = workflow;
        this.builder = builder;
        this.twm = twm;
        this.approvals = approvals;
        this.controlled = controlled;
        this.audit = audit;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Bills (search on number/reference, optional branch and {@code sort=field:dir,...}). */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery q, String sort, String branchId) {
        return list(q, sort, branchId, null, null);
    }

    /** {@link #list(PageQuery, String, String)} with optional document status and supplier filters. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery q, String sort, String branchId, String status,
            String partnerId) {
        int page = Math.max(1, q.page() == null || q.page() == 0 ? 1 : q.page());
        int pageSize = Math.min(200, Math.max(1, q.pageSize() == null || q.pageSize() == 0 ? 25 : q.pageSize()));
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"documentType\" = 'vendor_bill'");
        if (q.search() != null && !q.search().isEmpty()) {
            String like = "%" + BaseCrudService.escapeLike(q.search()) + "%";
            where.append(" AND (\"documentNumber\" ILIKE ? OR reference ILIKE ?)");
            params.add(like);
            params.add(like);
        }
        if (branchId != null && !branchId.isEmpty()) {
            where.append(" AND \"branchId\" = ?");
            params.add(branchId);
        }
        if (status != null && !status.isEmpty()) {
            where.append(" AND status::text = ?");
            params.add(status);
        }
        if (partnerId != null && !partnerId.isEmpty()) {
            where.append(" AND \"partnerId\" = ?");
            params.add(partnerId);
        }
        List<Map<String, Object>> rows = SqlRows.list(Document.class, jdbc.sql("SELECT * FROM \"Document\" WHERE" + where
                + " ORDER BY " + orderBy(sort) + " LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize).params(params));
        for (Map<String, Object> d : rows) {
            d.put("partner", builder.partner(d.get("partnerId")));
            d.put("_count", Obj.of("lines", jdbc.sql("SELECT count(*) FROM \"DocumentLine\" WHERE \"documentId\" = ?")
                    .param(d.get("id")).query(Long.class).single()));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"Document\" WHERE" + where).params(params).query(Long.class).single();
        return PageResult.of(rows, page, pageSize, total);
    }

    private static String orderBy(String sort) {
        if (sort == null || sort.isEmpty()) {
            return "\"createdAt\" DESC";
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        String[] items = sort.split(",");
        for (int i = 0; i < Math.min(3, items.length); i++) {
            String[] parts = items[i].split(":");
            String field = parts[0];
            if (!SORT_FIELDS.contains(field)) {
                continue;
            }
            parsed.put(field, parts.length > 1 && "desc".equals(parts[1]) ? "DESC" : "ASC");
        }
        if (parsed.isEmpty()) {
            return "\"createdAt\" DESC";
        }
        List<String> out = new ArrayList<>();
        parsed.forEach((f, dir) -> out.add("\"" + f + "\" " + dir));
        return String.join(", ", out);
    }

    /** Bill with ordered lines, partner and allocations; null when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> d = builder.document(id, "vendor_bill");
        if (d == null) {
            return null;
        }
        d.put("lines", builder.lines(id, true));
        d.put("partner", builder.partner(d.get("partnerId")));
        d.put("allocations", SqlRows.list(PaymentAllocation.class, jdbc.sql("SELECT * FROM \"PaymentAllocation\""
                + " WHERE \"documentId\" = ?").param(id)));
        if ("cancelled".equals(String.valueOf(d.get("status")))) {
            withReversal(d, id);
        }
        return d;
    }

    /** Create a draft bill; an approval request may hold it (returned as {@code approvalRequestId}). */
    @Transactional
    public Map<String, Object> create(BillInput dto) {
        validateDates(dto.issueDate(), dto.dueDate());
        builder.assertVariants(dto.effectiveLines(this::productTax), true, false);
        Map<String, Object> doc = builder.createDocument("vendor_bill", new HeaderInput(dto.partnerId(), dto.issueDate(),
                dto.dueDate(), null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                dto.currencyId(), null, dto.reference(), dto.notes(), null, null, dto.branchId()),
                dto.effectiveLines(this::productTax));
        String id = (String) doc.get("id");
        // Who entered the bill: the supplier-bill separation-of-duties pair needs it at posting.
        jdbc.sql("UPDATE \"Document\" SET \"createdBy\" = ? WHERE id = ? AND \"organizationId\" = ?")
                .params(TenantContext.userId(), id, org()).update();
        syncPoLinks(id, dto.purchaseOrderIds(), Money.dec(doc.get("totalAmount")).doubleValue());
        double amount = Money.dec(doc.get("totalAmount")).doubleValue();
        Map<String, Object> snapshot = Obj.of("amount", JsNumbers.js(amount), "partnerId", doc.get("partnerId"),
                "documentNumber", doc.get("documentNumber"), "createdBy", TenantContext.userId());
        // Gap fix h03-F6: the request records what was submitted, so a post can tell an edit made after the decision.
        snapshot.put("contentHash", billContentHash(id));
        Map<String, Object> request = approvals.requestApproval("vendor_bill", id, snapshot, null);
        if (request != null) {
            audit.record(AuditInput.of("VendorBill", id, "create", null, Obj.of("documentNumber", doc.get("documentNumber"),
                    "approvalRequestId", request.get("id"), "status", "pending_approval")));
        }
        doc.put("approvalRequestId", request == null ? null : request.get("id"));
        return doc;
    }

    /**
     * Raise and post a supplier bill on behalf of an approved landed cost voucher (one per supplier invoice). The
     * voucher's own approval stands in for the bill approval, so no approval request is opened; posting still needs
     * {@code expense:post}. Lines carry their GL account (the landed-cost clearing account), so the bill debits
     * clearing and credits the supplier's payable.
     */
    @Transactional
    public Map<String, Object> createPostedForLandedCost(String partnerId, String issueDate, String dueDate,
            String currencyCode, BigDecimal exchangeRate, String reference, String sourceDocument, String notes,
            List<LineInput> lines) {
        validateDates(issueDate, dueDate);
        Map<String, Object> doc = builder.createDocument("vendor_bill", new HeaderInput(partnerId, issueDate, dueDate,
                null, null, null, null, null, null, null, null, null, null, null, null, null, sourceDocument,
                currencyCode, exchangeRate, reference, notes, null, "landed_cost", null), lines);
        String id = (String) doc.get("id");
        Map<String, Object> full = builder.document(id, "vendor_bill");
        full.put("lines", builder.lines(id, false));
        workflow.transition("vendor_bill", id, "post", null, full);
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org());
        payload.put("documentId", id);
        payload.put("documentNumber", doc.get("documentNumber"));
        events.publish("bill.posted", payload);
        audit.record(AuditInput.of("VendorBill", id, "create", null, Obj.of("documentNumber", doc.get("documentNumber"),
                "source", "landed_cost", "sourceDocument", sourceDocument)));
        return builder.document(id);
    }

    /** Edit a draft bill. */
    @Transactional
    public Map<String, Object> update(String id, BillInput dto) {
        validateDates(dto.issueDate(), dto.dueDate());
        Map<String, Object> doc = builder.document(id, "vendor_bill");
        if (doc == null) {
            throw ApiException.notFound("Vendor bill not found");
        }
        if (!"draft".equals(String.valueOf(doc.get("status")))) {
            throw ApiException.badRequest("Only draft bills can be edited");
        }
        builder.assertVariants(dto.effectiveLines(this::productTax), true, false);
        jdbc.sql("DELETE FROM \"DocumentLine\" WHERE \"documentId\" = ?").param(id).update();
        Totals totals = builder.prepareLines(dto.effectiveLines(this::productTax), UnitRole.PURCHASE);
        jdbc.sql("UPDATE \"Document\" SET \"partnerId\" = ?, \"issueDate\" = ?, \"dueDate\" = ?, \"currencyId\" = ?,"
                        + " reference = ?, notes = ?, subtotal = ?, \"discountTotal\" = ?, \"taxAmount\" = ?,"
                        + " \"totalAmount\" = ?, \"amountResidual\" = ?, \"withholdingAmount\" = ?, \"updatedAt\" = "
                        + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(dto.partnerId(), ts(dto.issueDate()), ts(dto.dueDate()), dto.currencyId(), dto.reference(),
                        dto.notes(), totals.subtotal(), totals.discountTotal(), totals.taxAmount(), totals.total(),
                        totals.residual(), totals.withholdingAmount(), id, org())
                .update();
        builder.insertLines(id, totals.prepared(), false);
        syncPoLinks(id, dto.purchaseOrderIds(), totals.total().doubleValue());
        Map<String, Object> out = builder.document(id);
        out.put("lines", builder.lines(id, false));
        out.put("partner", builder.partner(out.get("partnerId")));
        return out;
    }

    /** The product's default tax (a line without a stated tax falls back to it). */
    private String productTax(LineInput l) {
        if (l.productId() == null) {
            return null;
        }
        return jdbc.sql("SELECT \"taxId\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?")
                .params(l.productId(), org()).query(String.class).optional().orElse(null);
    }

    private void syncPoLinks(String vendorBillId, List<String> purchaseOrderIds, double total) {
        if (purchaseOrderIds == null) {
            return;
        }
        jdbc.sql("DELETE FROM \"VendorBillLink\" WHERE \"organizationId\" = ? AND \"vendorBillId\" = ?")
                .params(org(), vendorBillId).update();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        purchaseOrderIds.forEach(i -> {
            if (i != null && !i.isEmpty()) {
                ids.add(i);
            }
        });
        if (ids.isEmpty()) {
            return;
        }
        long found = jdbc.sql("SELECT count(*) FROM \"PurchaseOrder\" WHERE id IN (:ids) AND \"organizationId\" = :org")
                .param("ids", List.copyOf(ids)).param("org", org()).query(Long.class).single();
        if (found != ids.size()) {
            throw ApiException.badRequest("One or more purchase orders do not exist in this organization");
        }
        // ACC-3 (B12): exact decimal split at currency precision; the last link takes the remainder (Σ links = total).
        BigDecimal whole = new BigDecimal(JsNumbers.toJsString(total));
        BigDecimal share = whole.divide(BigDecimal.valueOf(ids.size()), 2, java.math.RoundingMode.DOWN);
        BigDecimal assigned = BigDecimal.ZERO;
        int n = 0;
        for (String po : ids) {
            BigDecimal amount = ++n == ids.size() ? whole.subtract(assigned) : share;
            assigned = assigned.add(amount);
            jdbc.sql("INSERT INTO \"VendorBillLink\" (id, \"organizationId\", \"vendorBillId\", \"purchaseOrderId\", amount)"
                            + " VALUES (?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), vendorBillId, po, amount).update();
        }
    }

    /** Post behind the three-way-match and approval gates. */
    @Transactional
    public Map<String, Object> post(String id, Boolean override3WM, Boolean overrideApproval) {
        String org = org();
        String userId = TenantContext.userId();
        Map<String, Object> doc = builder.document(id, "vendor_bill");
        if (doc == null) {
            throw ApiException.notFound("Vendor bill not found");
        }
        doc.put("lines", builder.lines(id, false));
        List<String> links = jdbc.sql("SELECT \"purchaseOrderId\" FROM \"VendorBillLink\" WHERE \"organizationId\" = ?"
                + " AND \"vendorBillId\" = ?").params(org, id).query(String.class).list();
        if (!links.isEmpty()) {
            Map<String, Object> validation = twm.validateBillForPosting(id);
            if (!Boolean.TRUE.equals(validation.get("ok")) && !Boolean.TRUE.equals(override3WM)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> matches = (List<Map<String, Object>>) validation.get("matches");
                long blocked = matches.stream().filter(m -> "blocked".equals(String.valueOf(m.get("status")))).count();
                throw ApiException.forbidden("Three-way match failed: " + blocked + " line(s) blocked. Resolve the"
                        + " mismatches or post with override (requires three_way_match:override permission).");
            }
            if (!Boolean.TRUE.equals(override3WM)) {
                audit.record(AuditInput.of("VendorBill", id, "update", null, Obj.of("threeWayMatchOk", true,
                        "blockedCount", 0)));
            } else {
                if (!TenantContext.permissions().contains("three_way_match:override")) {
                    throw ApiException.forbidden("You do not have three_way_match:override permission");
                }
                audit.record(AuditInput.of("VendorBill", id, "update", null, Obj.of("threeWayMatchOverride", true,
                        "approver", userId)));
            }
        }
        String pending = jdbc.sql("SELECT id FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\" ="
                + " 'vendor_bill' AND \"entityId\" = ? AND status = 'pending' LIMIT 1").params(org, id).query(String.class)
                .optional().orElse(null);
        if (pending != null && !Boolean.TRUE.equals(overrideApproval)) {
            throw ApiException.forbidden("This bill is awaiting approval. Wait for approval or post with override.");
        }
        if (pending != null) {
            if (!TenantContext.permissions().contains("approvals:decide")) {
                throw ApiException.forbidden("You do not have approvals:decide permission");
            }
            // P1-5 (ADR-PROCUREMENT-001): segregation of duties — whoever raised the bill / its approval request cannot
            // also approve it by posting with override.
            String requester = jdbc.sql("SELECT \"createdById\" FROM \"ApprovalRequest\" WHERE id = ?").param(pending)
                    .query(String.class).optional().orElse(null);
            if (userId != null && (userId.equals(requester) || userId.equals(doc.get("createdBy")))) {
                throw ApiException.forbidden("You cannot approve a bill you created — another approver must decide");
            }
            jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'approved', \"decidedAt\" = now(), \"updatedAt\" = now()"
                    + " WHERE id = ?").param(pending).update();
            audit.record(AuditInput.of("ApprovalRequest", pending, "approve"));
        } else {
            String refused = decidedApprovalRefusal(id, doc);
            if (refused != null) {
                throw ApiException.forbidden(refused);
            }
        }
        authorizePosting(id, doc);
        List<String> warnings = new ArrayList<>();
        for (VendorBillPostGuard g : guards) {
            warnings.addAll(g.beforePost(id, doc));
        }
        workflow.transition("vendor_bill", id, "post", null, doc);
        for (String po : links) {
            twm.recomputeForOrder(po);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org);
        payload.put("documentId", id);
        payload.put("documentNumber", doc.get("documentNumber"));
        events.publish("bill.posted", payload);
        Map<String, Object> out = builder.document(id);
        out.put("lines", builder.lines(id, false));
        out.put("partner", builder.partner(out.get("partnerId")));
        if (!warnings.isEmpty()) {
            out.put("warnings", warnings);
        }
        return out;
    }

    /**
     * Gap fix h03-F6 (Nest deviation, owner-approved 2026-11-17): the latest decision on the bill's own approval counts
     * at posting, not only a pending request. A rejected bill does not post unchanged; a bill edited after its approval
     * (amount, supplier or lines differ from the request's snapshot) or whose request was withdrawn needs a new decision
     * when a workflow applies — the new request is committed on its own, so it survives this refusal. A bill that never
     * needed an approval posts as before. Returns the refusal, or {@code null}.
     */
    private String decidedApprovalRefusal(String id, Map<String, Object> doc) {
        Map<String, Object> last = jdbc.sql("SELECT id, status::text AS status, snapshot->>'amount' AS amount,"
                        + " snapshot->>'partnerId' AS \"partnerId\", snapshot->>'contentHash' AS \"contentHash\" FROM"
                        + " \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\" = 'vendor_bill' AND"
                        + " \"entityId\" = ? ORDER BY \"createdAt\" DESC, id DESC LIMIT 1")
                .params(org(), id).query().listOfRows().stream().findFirst().orElse(null);
        if (last == null || "pending".equals(last.get("status"))) {
            return null;
        }
        String status = (String) last.get("status");
        String hash = billContentHash(id);
        BigDecimal total = Money.dec(doc.get("totalAmount"));
        boolean unchanged = last.get("amount") != null
                && total.subtract(Money.dec(last.get("amount"))).abs().compareTo(new BigDecimal("0.005")) <= 0
                && java.util.Objects.equals(last.get("partnerId"), doc.get("partnerId"))
                && (last.get("contentHash") == null || last.get("contentHash").equals(hash));
        if ("approved".equals(status) && unchanged) {
            return null;
        }
        if ("rejected".equals(status) && unchanged) {
            return "This bill's approval was rejected — change the bill and post it again to ask for a new decision";
        }
        Map<String, Object> snapshot = Obj.of("amount", JsNumbers.js(total), "partnerId", doc.get("partnerId"),
                "documentNumber", doc.get("documentNumber"), "createdBy", doc.get("createdBy"), "contentHash", hash);
        Map<String, Object> raised = newTx.execute(s -> {
            if ("approved".equals(status)) {
                jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'cancelled', \"updatedAt\" = now() WHERE id = ? AND"
                        + " status = 'approved'").param(last.get("id")).update();
                audit.recordInTx(AuditInput.of("ApprovalRequest", (String) last.get("id"), "cancel", null, Obj.of(
                        "status", "cancelled", "reason", "bill changed after approval")));
            }
            return approvals.requestApproval("vendor_bill", id, snapshot, null);
        });
        if (raised == null) {
            return null;
        }
        return switch (status) {
            case "approved" -> "This bill changed after it was approved; it needs approval again (request "
                    + raised.get("id") + ")";
            case "rejected" -> "This bill changed after its approval was rejected; it needs approval again (request "
                    + raised.get("id") + ")";
            default -> "This bill is awaiting approval (request " + raised.get("id") + "). Wait for approval or post"
                    + " with override.";
        };
    }

    /** What an approval of the bill covered: supplier, currency, totals and every line (h03-F6). */
    private String billContentHash(String id) {
        return jdbc.sql("SELECT md5(concat_ws('|', COALESCE(d.\"partnerId\", ''), COALESCE(d.\"currencyId\", ''),"
                        + " d.\"totalAmount\"::text, COALESCE(d.\"withholdingAmount\"::text, ''), COALESCE((SELECT"
                        + " string_agg(concat_ws(':', COALESCE(l.\"productId\", ''), COALESCE(l.\"variantId\", ''),"
                        + " COALESCE(l.\"accountId\", ''), COALESCE(l.description, ''), l.quantity::text,"
                        + " l.\"unitPrice\"::text, COALESCE(l.\"discountPercent\"::text, ''), COALESCE(l.\"taxId\", ''),"
                        + " COALESCE(l.\"uomId\", '')), ';' ORDER BY l.\"lineNumber\", l.id) FROM \"DocumentLine\" l"
                        + " WHERE l.\"documentId\" = d.id), ''))) FROM \"Document\" d WHERE d.id = ? AND"
                        + " d.\"organizationId\" = ?")
                .params(id, org()).query(String.class).optional().orElse(null);
    }

    /**
     * Sensitive pair: posting a supplier bill needs an approval when a {@code supplier_bill_approval} workflow exists,
     * and whoever entered the bill may not clear it alone once the pair is switched on.
     */
    private void authorizePosting(String id, Map<String, Object> doc) {
        Object total = doc.get("totalAmount");
        ControlledActionService.Outcome outcome = controlled.authorize(new ControlledActionService.Action(
                SegregationOfDutiesService.SUPPLIER_BILL_APPROVAL, "VendorBill", id,
                total == null ? null : new BigDecimal(String.valueOf(total)), (String) doc.get("branchId"), null,
                java.util.Arrays.asList((String) doc.get("createdBy"), (String) doc.get("updatedBy")),
                Obj.of("documentNumber", doc.get("documentNumber"))));
        if (!outcome.allowed()) {
            throw ApiException.badRequest("Approval required before posting bill " + doc.get("documentNumber")
                    + ". Pending request " + outcome.approvalRequestId() + ".");
        }
    }

    private com.inventoryerp.accounting.application.DocumentReversalService reversals;

    @org.springframework.beans.factory.annotation.Autowired
    void setReversals(com.inventoryerp.accounting.application.DocumentReversalService reversals) {
        this.reversals = reversals;
    }

    /**
     * Internal void for a workflow that owns the bill and ran its own controls (a landed-cost voucher reversing the
     * bills it generated): no approval gate or separation-of-duties check, the same settlement checks, GL reversal and
     * reversal record as {@link #cancel(String, String)}.
     */
    @Transactional
    public Map<String, Object> cancel(String id) {
        return cancel(id, "Voided by its source workflow", false);
    }

    /**
     * Supplier-bill document reversal (owner decision 2026-11-17): void a posted bill with a reason, behind the
     * {@code vendor_bill_cancel} approval gate and the {@code document_reversal} separation-of-duties pair. Refused
     * while anything is settled against it — supplier payments (void them), posted debit notes (reverse them), settled
     * supplier claims. Under the bill's row lock the workflow reverses its journal entry and FX voucher, releases the
     * receipt matches, returns auto-received stock, cancels issued withholding certificates and zeroes the residual; a
     * {@code DocumentReversal} row keeps the reason and the entry links. A replay on a voided bill returns it unchanged.
     */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        return cancel(id, reason, true);
    }

    private Map<String, Object> cancel(String id, String reasonIn, boolean userAction) {
        Map<String, Object> doc = builder.document(id, "vendor_bill");
        if (doc == null) {
            throw ApiException.notFound("Vendor bill not found");
        }
        doc.put("allocations", SqlRows.list(PaymentAllocation.class, jdbc.sql("SELECT * FROM \"PaymentAllocation\""
                + " WHERE \"documentId\" = ?").param(id)));
        if ("cancelled".equals(String.valueOf(doc.get("status")))) {
            withReversal(doc, id);
            return doc;
        }
        String reason = reversals.requireReason(reasonIn, "void a bill");
        String number = (String) doc.get("documentNumber");
        if (userAction) {
            reversals.requireApproval("vendor_bill_cancel", id, "bill " + number, Money.dec(doc.get("totalAmount")));
            reversals.assertSeparated("Document", id, java.util.Arrays.asList((String) doc.get("createdBy"),
                    (String) doc.get("postedBy")));
        }
        jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, org())
                .query().listOfRows();
        assertNothingSettled(id, number);
        workflow.transition("vendor_bill", id, "cancel", Obj.of("reason", reason), doc);
        reversals.record(new com.inventoryerp.accounting.application.DocumentReversalService.Recording("vendor_bill", id,
                number, (String) doc.get("partnerId"), reason, "Document", List.of("vendor_bill"),
                List.of("vendor_bill_cancel"), List.of()));
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org());
        payload.put("documentId", id);
        payload.put("documentNumber", doc.get("documentNumber"));
        events.publish("bill.cancelled", payload);
        Map<String, Object> out = builder.document(id);
        withReversal(out, id);
        return out;
    }

    /** Additive {@code reversal} block (reason, who, when, entry links) on a voided bill. */
    private void withReversal(Map<String, Object> doc, String id) {
        Map<String, Object> r = reversals == null ? null : reversals.find("vendor_bill", id);
        if (r != null && doc != null) {
            doc.put("reversal", r);
        }
    }

    /** Supplier payments, posted debit notes and settled supplier claims block a void; each is named with its action. */
    private void assertNothingSettled(String id, String number) {
        List<Map<String, Object>> paid = reversals.appliedPayments(id);
        BigDecimal amountPaid = jdbc.sql("SELECT \"amountPaid\" FROM \"Document\" WHERE id = ?").param(id)
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        List<String> notes = reversals.postedDebitNotes(id);
        List<String> claims = jdbc.sql("SELECT \"claimNumber\" FROM \"SupplierClaim\" WHERE \"vendorBillId\" = ? AND"
                + " \"organizationId\" = ? AND status = 'settled' ORDER BY \"claimNumber\"").params(id, org())
                .query(String.class).list();
        List<String> problems = new ArrayList<>();
        BigDecimal writtenOff = jdbc.sql("SELECT COALESCE(SUM(l.debit), 0) FROM \"JournalLine\" l JOIN \"JournalEntry\" e"
                + " ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND e.\"sourceType\" ="
                + " 'vendor_bill_writeoff' AND e.\"sourceId\" = ? AND e.status = 'posted' AND l.\"partnerId\" IS NOT NULL"
                + " AND l.debit > 0").params(org(), id).query(BigDecimal.class).single();
        if (!paid.isEmpty()) {
            problems.add("supplier payments applied: " + com.inventoryerp.accounting.application.DocumentReversalService
                    .describePayments(paid) + " (void each payment first)");
        }
        if (writtenOff.signum() > 0) {
            problems.add(JsNumbers.dec(writtenOff) + " written off (a written-off bill cannot be voided)");
        }
        if (paid.isEmpty() && writtenOff.signum() <= 0 && amountPaid != null && amountPaid.signum() > 0) {
            problems.add(JsNumbers.dec(amountPaid) + " is recorded as paid (unallocate it first)");
        }
        if (!notes.isEmpty()) {
            problems.add("posted debit notes raised against it: " + String.join(", ", notes) + " (reverse them first)");
        }
        if (!claims.isEmpty()) {
            problems.add("supplier claims settled against it: " + String.join(", ", claims) + " (a settled claim has no"
                    + " document-level reversal; correct the supplier balance with a debit note instead)");
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Bill " + number + " cannot be voided while settlements are applied to it: "
                    + String.join("; ", problems) + ".");
        }
    }

    private void validateDates(String issueDate, String dueDate) {
        Instant issue = EntityPatch.toInstant(issueDate);
        // Dates are stored as UTC wall time of the calendar day; "future" means after today in the company's zone,
        // so a bill dated today in Kampala at 01:00 (still yesterday in UTC) is accepted.
        LocalDate today = LocalDate.now(OrgDates.orgTimezone(jdbc, org()));
        if (issue.atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)) {
            throw ApiException.badRequest("Issue date cannot be in the future");
        }
        if (dueDate != null && EntityPatch.toInstant(dueDate).isBefore(issue)) {
            throw ApiException.badRequest("Due date cannot be earlier than issue date");
        }
    }

    /** Supplier ledger: bills (debit) and outbound payments (credit) with a running balance. */
    @Transactional(readOnly = true)
    public Map<String, Object> ledger(String partnerId) {
        // B2 (post-parity): the statement is the supplier's AP sub-ledger — every posted journal line on a payable
        // account tagged with this supplier (bills, receipt vouchers, payments, debit notes, reversals) in date order.
        // Its closing balance therefore equals the GL and AP ageing; Nest summed draft / cancelled bills and dropped
        // paid ones. "debit" keeps the Nest meaning (what we owe goes up), "credit" what we paid / were credited.
        String org = org();
        String partnerAp = jdbc.sql("SELECT \"payableAccountId\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(partnerId, org).query(String.class).optional().orElse(null);
        List<String> accounts = new ArrayList<>();
        accounts.add(determination.payableAccount(null));
        if (partnerAp != null && !accounts.contains(partnerAp)) {
            accounts.add(partnerAp);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT e.id, e.\"postingDate\", e.\"entryNumber\","
                + " e.description, e.\"sourceType\", e.\"sourceId\", e.\"reversalOfId\", COALESCE(SUM(l.\"baseCredit\"), 0)"
                + " AS cr, COALESCE(SUM(l.\"baseDebit\"), 0) AS dr FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON"
                + " e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" = ? AND l.\"partnerId\" = ? AND l.\"accountId\" ="
                + " ANY(?::text[]) AND e.status IN ('posted', 'reversed') GROUP BY e.id ORDER BY e.\"postingDate\" ASC,"
                + " e.\"createdAt\" ASC, e.\"entryNumber\" ASC").params(org, partnerId, accounts.toArray(String[]::new)));
        BigDecimal running = Money.ZERO;
        List<Map<String, Object>> tx = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            BigDecimal debit = Money.dec(r.get("cr"));
            BigDecimal credit = Money.dec(r.get("dr"));
            running = running.add(debit).subtract(credit);
            String source = String.valueOf(r.get("sourceType"));
            String type = r.get("reversalOfId") != null ? "reversal" : switch (source) {
                case "vendor_bill" -> "bill";
                case VendorBillWriteOffService.SOURCE -> "write_off";
                case "payment" -> "payment";
                case "goods_receipt" -> "receipt";
                case "purchase_order" -> "purchase_payment";
                case "debit_note" -> "debit_note";
                default -> source;
            };
            Object reference = r.get("entryNumber");
            Object residual = null;
            Object status = "posted";
            if ("vendor_bill".equals(source) && r.get("sourceId") != null) {
                Map<String, Object> bill = SqlRows.one(jdbc.sql("SELECT \"documentNumber\", \"amountResidual\","
                        + " status::text AS status FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(r.get("sourceId"), org));
                if (bill != null) {
                    reference = bill.get("documentNumber");
                    residual = JsNumbers.js(Money.dec(bill.get("amountResidual")));
                    status = bill.get("status");
                }
            } else if ("payment".equals(source) && r.get("sourceId") != null) {
                Map<String, Object> pay = SqlRows.one(jdbc.sql("SELECT \"paymentNumber\", status::text AS status FROM"
                        + " \"Payment\" WHERE id = ? AND \"organizationId\" = ?").params(r.get("sourceId"), org));
                if (pay != null) {
                    reference = pay.get("paymentNumber");
                    status = pay.get("status");
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.get("sourceId") != null ? r.get("sourceId") : r.get("id"));
            row.put("journalEntryId", r.get("id"));
            row.put("date", r.get("postingDate"));
            row.put("type", type);
            row.put("reference", reference);
            row.put("description", r.get("description"));
            row.put("debit", JsNumbers.js(debit));
            row.put("credit", JsNumbers.js(credit));
            row.put("residual", residual);
            row.put("status", status);
            row.put("balance", JsNumbers.js(running));
            tx.add(row);
        }
        return Obj.of("openingBalance", 0, "closingBalance", JsNumbers.js(running), "transactions", tx);
    }
}
