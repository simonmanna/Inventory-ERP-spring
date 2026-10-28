package com.inventoryerp.invoicing.application;

import com.inventoryerp.core.application.UomConversionService.UnitRole;
import static com.inventoryerp.invoicing.application.DocumentBuilderService.NOW;
import static com.inventoryerp.invoicing.application.DocumentBuilderService.ts;

import com.inventoryerp.documents.domain.Document;
import com.inventoryerp.invoicing.application.DocumentBuilderService.HeaderInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.invoicing.domain.PaymentAllocation;
import com.inventoryerp.kernel.approvals.ApprovalsService;
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
            String notes, String branchId, List<String> purchaseOrderIds, List<LineInput> lines) {}

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt", "issueDate", "dueDate",
            "documentNumber", "totalAmount", "amountResidual", "status", "reference");

    private final JdbcClient jdbc;
    private final EventBus events;
    private final WorkflowService workflow;
    private final DocumentBuilderService builder;
    private final ThreeWayMatchService twm;
    private final ApprovalsService approvals;
    private final AuditService audit;
    private final TransactionTemplate newTx;

    public VendorBillService(JdbcClient jdbc, EventBus events, WorkflowService workflow, DocumentBuilderService builder,
            ThreeWayMatchService twm, ApprovalsService approvals, AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.events = events;
        this.workflow = workflow;
        this.builder = builder;
        this.twm = twm;
        this.approvals = approvals;
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
        return d;
    }

    /** Create a draft bill; an approval request may hold it (returned as {@code approvalRequestId}). */
    @Transactional
    public Map<String, Object> create(BillInput dto) {
        validateDates(dto.issueDate(), dto.dueDate());
        Map<String, Object> doc = builder.createDocument("vendor_bill", new HeaderInput(dto.partnerId(), dto.issueDate(),
                dto.dueDate(), null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                dto.currencyId(), null, dto.reference(), dto.notes(), null, null, dto.branchId()), dto.lines());
        String id = (String) doc.get("id");
        syncPoLinks(id, dto.purchaseOrderIds(), Money.dec(doc.get("totalAmount")).doubleValue());
        double amount = Money.dec(doc.get("totalAmount")).doubleValue();
        Map<String, Object> snapshot = Obj.of("amount", JsNumbers.js(amount), "partnerId", doc.get("partnerId"),
                "documentNumber", doc.get("documentNumber"), "createdBy", TenantContext.userId());
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
        jdbc.sql("DELETE FROM \"DocumentLine\" WHERE \"documentId\" = ?").param(id).update();
        Totals totals = builder.prepareLines(dto.lines(), UnitRole.PURCHASE);
        jdbc.sql("UPDATE \"Document\" SET \"partnerId\" = ?, \"issueDate\" = ?, \"dueDate\" = ?, \"currencyId\" = ?,"
                        + " reference = ?, notes = ?, subtotal = ?, \"discountTotal\" = ?, \"taxAmount\" = ?,"
                        + " \"totalAmount\" = ?, \"amountResidual\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(dto.partnerId(), ts(dto.issueDate()), ts(dto.dueDate()), dto.currencyId(), dto.reference(),
                        dto.notes(), totals.subtotal(), totals.discountTotal(), totals.taxAmount(), totals.total(),
                        totals.total(), id, org())
                .update();
        builder.insertLines(id, totals.prepared(), false);
        syncPoLinks(id, dto.purchaseOrderIds(), totals.total().doubleValue());
        Map<String, Object> out = builder.document(id);
        out.put("lines", builder.lines(id, false));
        out.put("partner", builder.partner(out.get("partnerId")));
        return out;
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
        BigDecimal share = new BigDecimal(JsNumbers.toJsString(total / ids.size()));
        for (String po : ids) {
            jdbc.sql("INSERT INTO \"VendorBillLink\" (id, \"organizationId\", \"vendorBillId\", \"purchaseOrderId\", amount)"
                            + " VALUES (?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), vendorBillId, po, share).update();
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
            jdbc.sql("UPDATE \"ApprovalRequest\" SET status = 'approved', \"decidedAt\" = now(), \"updatedAt\" = now()"
                    + " WHERE id = ?").param(pending).update();
            audit.record(AuditInput.of("ApprovalRequest", pending, "approve"));
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
        return out;
    }

    /** Void a posted bill (reversal, receipt quantities released). */
    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> doc = builder.document(id, "vendor_bill");
        if (doc == null) {
            throw ApiException.notFound("Vendor bill not found");
        }
        doc.put("allocations", SqlRows.list(PaymentAllocation.class, jdbc.sql("SELECT * FROM \"PaymentAllocation\""
                + " WHERE \"documentId\" = ?").param(id)));
        if ("cancelled".equals(String.valueOf(doc.get("status")))) {
            return doc;
        }
        workflow.transition("vendor_bill", id, "cancel", null, doc);
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org());
        payload.put("documentId", id);
        payload.put("documentNumber", doc.get("documentNumber"));
        events.publish("bill.cancelled", payload);
        return builder.document(id);
    }

    private static void validateDates(String issueDate, String dueDate) {
        Instant issue = EntityPatch.toInstant(issueDate);
        if (issue.isAfter(Instant.now())) {
            throw ApiException.badRequest("Issue date cannot be in the future");
        }
        if (dueDate != null && EntityPatch.toInstant(dueDate).isBefore(issue)) {
            throw ApiException.badRequest("Due date cannot be earlier than issue date");
        }
    }

    /** Supplier ledger: bills (debit) and outbound payments (credit) with a running balance. */
    @Transactional(readOnly = true)
    public Map<String, Object> ledger(String partnerId) {
        String org = org();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT * FROM \"Document\" WHERE \"organizationId\" = ?"
                + " AND \"partnerId\" = ? AND \"documentType\" = 'vendor_bill' AND status IN ('draft', 'posted', 'cancelled')"
                + " ORDER BY \"issueDate\" ASC").params(org, partnerId))) {
            List<Map<String, Object>> lines = builder.lines((String) b.get("id"), true);
            Object description = !lines.isEmpty() && lines.getFirst().get("description") != null
                    ? lines.getFirst().get("description") : b.get("notes") != null ? b.get("notes") : "Bill";
            items.add(Obj.of("id", b.get("id"), "date", b.get("issueDate"), "type", "bill", "reference",
                    b.get("documentNumber"), "description", description, "debit",
                    Money.dec(b.get("totalAmount")).doubleValue(), "credit", 0.0, "residual",
                    JsNumbers.js(Money.dec(b.get("amountResidual"))), "status", b.get("status")));
        }
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT * FROM \"Payment\" WHERE \"organizationId\" = ?"
                + " AND \"partnerId\" = ? AND direction = 'outbound' AND status IN ('posted', 'cancelled')"
                + " ORDER BY \"paymentDate\" ASC").params(org, partnerId))) {
            items.add(Obj.of("id", p.get("id"), "date", p.get("paymentDate"), "type",
                    "cancelled".equals(String.valueOf(p.get("status"))) ? "payment_void" : "payment", "reference",
                    p.get("paymentNumber"), "description", "Payment (" + p.get("paymentMethod") + ")", "debit", 0.0,
                    "credit", Money.dec(p.get("amount")).doubleValue(), "status", p.get("status")));
        }
        items.sort((a, b) -> ((Instant) a.get("date")).compareTo((Instant) b.get("date")));
        double running = 0;
        List<Map<String, Object>> tx = new ArrayList<>();
        for (Map<String, Object> it : items) {
            double debit = (double) it.get("debit");
            double credit = (double) it.get("credit");
            running = running + debit - credit;
            Map<String, Object> row = new LinkedHashMap<>(it);
            row.put("debit", JsNumbers.js(debit));
            row.put("credit", JsNumbers.js(credit));
            row.put("balance", JsNumbers.js(running));
            tx.add(row);
        }
        double opening = 0;
        if (!tx.isEmpty()) {
            Map<String, Object> first = tx.getFirst();
            opening = ((Number) first.get("balance")).doubleValue() - ((Number) first.get("debit")).doubleValue()
                    + ((Number) first.get("credit")).doubleValue();
        }
        return Obj.of("openingBalance", JsNumbers.js(opening), "closingBalance", JsNumbers.js(running), "transactions", tx);
    }
}
