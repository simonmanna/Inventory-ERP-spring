package com.inventoryerp.invoicing.application;

import static com.inventoryerp.invoicing.application.DocumentBuilderService.NOW;
import static com.inventoryerp.invoicing.application.DocumentBuilderService.ts;

import com.inventoryerp.accounting.application.JournalService;
import com.inventoryerp.core.application.PaymentTermService;
import com.inventoryerp.documents.domain.DocumentLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Grouped;
import com.inventoryerp.invoicing.application.DocumentBuilderService.HeaderInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.PreparedLine;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Totals;
import com.inventoryerp.invoicing.domain.Invoice;
import com.inventoryerp.invoicing.domain.InvoiceItem;
import com.inventoryerp.invoicing.domain.Payment;
import com.inventoryerp.invoicing.domain.PaymentAllocation;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.workflow.WorkflowService;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sales invoices (port of {@code invoice.service.ts}): the merged manual-document + POS-invoice list, detail,
 * draft create / edit with payment-term and master-name snapshots, journal preview, post and cancel through the
 * invoice workflow. Public API used by POS billing.
 */
@Service
public class InvoiceService {

    /** {@code CreateInvoiceDto}. */
    public record InvoiceInput(String partnerId, String issueDate, String dueDate, String paymentTermId,
            String paymentMode, String fiscalPositionId, String invoicingJournalId, String salespersonId,
            String deliveryDate, String deliveryAddress, String incoterm, String incotermLocation, String sourceDocument,
            String currencyId, BigDecimal exchangeRate, String reference, String notes, List<LineInput> lines) {}

    /** List filters. */
    public record ListFilter(Integer page, Integer pageSize, String search, String partnerId, String status,
            String paymentStatus, String settlementStatus, String dateFrom, String dateTo) {}

    private final JdbcClient jdbc;
    private final EventBus events;
    private final WorkflowService workflow;
    private final DocumentBuilderService builder;
    private final ApprovalsService approvals;
    private final PaymentTermService terms;
    private final JournalService journals;
    private final CreditControlService credit;
    private final TransactionTemplate newTx;

    public InvoiceService(JdbcClient jdbc, EventBus events, WorkflowService workflow, DocumentBuilderService builder,
            ApprovalsService approvals, PaymentTermService terms, JournalService journals, CreditControlService credit,
            PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.events = events;
        this.workflow = workflow;
        this.builder = builder;
        this.approvals = approvals;
        this.terms = terms;
        this.journals = journals;
        this.credit = credit;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private void publish(String event, Map<String, Object> doc) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org());
        payload.put("documentId", doc.get("id"));
        payload.put("documentNumber", doc.get("documentNumber"));
        events.publish(event, payload);
    }

    // ------------------------------------------------------------------ list

    /** Manual sales invoices and POS invoices merged newest-first. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(ListFilter f) {
        int page = Math.max(1, f.page() == null || f.page() == 0 ? 1 : f.page());
        int pageSize = Math.min(200, Math.max(1, f.pageSize() == null || f.pageSize() == 0 ? 25 : f.pageSize()));
        String search = f.search() == null ? null : f.search().trim();
        List<Object> dp = new ArrayList<>(List.of(org()));
        List<Object> ip = new ArrayList<>(List.of(org()));
        StringBuilder dw = new StringBuilder(" \"organizationId\" = ? AND \"documentType\" = 'sales_invoice'");
        StringBuilder iw = new StringBuilder(" \"organizationId\" = ?");
        if (notBlank(f.partnerId())) {
            dw.append(" AND \"partnerId\" = ?");
            iw.append(" AND \"partnerId\" = ?");
            dp.add(f.partnerId());
            ip.add(f.partnerId());
        }
        if (notBlank(f.status())) {
            dw.append(" AND status::text = ?");
            iw.append(" AND status::text = ?");
            dp.add(f.status());
            ip.add(f.status());
        }
        if (notBlank(f.dateFrom())) {
            dw.append(" AND \"issueDate\" >= ?");
            iw.append(" AND \"issueDate\" >= ?");
            dp.add(ts(f.dateFrom()));
            ip.add(ts(f.dateFrom()));
        }
        if (notBlank(f.dateTo())) {
            Instant end = LocalDateTime.of(EntityPatch.toInstant(f.dateTo()).atZone(BusinessZone.get()).toLocalDate(),
                    LocalTime.of(23, 59, 59, 999_000_000)).atZone(BusinessZone.get()).toInstant();
            dw.append(" AND \"issueDate\" <= ?");
            iw.append(" AND \"issueDate\" <= ?");
            dp.add(SqlRows.ts(end));
            ip.add(SqlRows.ts(end));
        }
        if (notBlank(search)) {
            String like = "%" + BaseCrudService.escapeLike(search) + "%";
            List<String> pIds = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                    + " AND name ILIKE ?").params(org(), like).query(String.class).list();
            String partnerOr = pIds.isEmpty() ? "" : " OR \"partnerId\" = ANY(?::text[])";
            dw.append(" AND (\"documentNumber\" ILIKE ? OR reference ILIKE ?").append(partnerOr).append(")");
            iw.append(" AND (\"invoiceNumber\" ILIKE ? OR reference ILIKE ?").append(partnerOr).append(")");
            dp.addAll(List.of(like, like));
            ip.addAll(List.of(like, like));
            if (!pIds.isEmpty()) {
                dp.add(pIds.toArray(String[]::new));
                ip.add(pIds.toArray(String[]::new));
            }
        }
        int take = page * pageSize;
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> d : SqlRows.list(com.inventoryerp.documents.domain.Document.class,
                jdbc.sql("SELECT * FROM \"Document\" WHERE" + dw + " ORDER BY \"createdAt\" DESC LIMIT " + take).params(dp))) {
            d.put("partner", builder.partner(d.get("partnerId")));
            d.put("_count", Obj.of("lines", count("DocumentLine", "documentId", d.get("id"))));
            d.put("source", "manual");
            d.put("paymentStatus", "unpaid");
            d.put("settlementStatus", "unsettled");
            merged.add(d);
        }
        List<Map<String, Object>> invs = SqlRows.list(Invoice.class, jdbc.sql("SELECT * FROM \"Invoice\" WHERE" + iw
                + " ORDER BY \"createdAt\" DESC LIMIT " + take).params(ip));
        Map<String, String> names = new HashMap<>();
        LinkedHashSet<String> pids = new LinkedHashSet<>();
        invs.forEach(i -> pids.add((String) i.get("partnerId")));
        if (!pids.isEmpty()) {
            for (Map<String, Object> p : jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id IN (:ids)"
                    + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(pids))
                    .param("org", org()).query().listOfRows()) {
                names.put((String) p.get("id"), (String) p.get("name"));
            }
        }
        for (Map<String, Object> i : invs) {
            long items = count("InvoiceItem", "invoiceId", i.get("id"));
            i.put("documentNumber", i.get("invoiceNumber"));
            i.put("documentType", "sales_invoice");
            i.put("source", "pos");
            i.put("partner", Obj.of("id", i.get("partnerId"), "name",
                    names.getOrDefault((String) i.get("partnerId"), "Walk-in")));
            i.put("_count", Obj.of("lines", items));
            merged.add(i);
        }
        merged.sort(Comparator.comparing((Map<String, Object> m) -> (Instant) m.get("createdAt")).reversed());
        if (notBlank(f.paymentStatus())) {
            merged = merged.stream().filter(m -> f.paymentStatus().equals(String.valueOf(m.get("paymentStatus")))).toList();
        }
        if (notBlank(f.settlementStatus())) {
            merged = merged.stream().filter(m -> f.settlementStatus().equals(String.valueOf(m.get("settlementStatus"))))
                    .toList();
        }
        int total = merged.size();
        int start = Math.min(total, (page - 1) * pageSize);
        return PageResult.of(new ArrayList<>(merged.subList(start, Math.min(total, start + pageSize))), page, pageSize,
                total);
    }

    private long count(String table, String fk, Object id) {
        return jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE \"" + fk + "\" = ?").param(id).query(Long.class)
                .single();
    }

    static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    // ------------------------------------------------------------------ detail

    /** A manual invoice (lines with account + tax) or a POS invoice normalised to the document shape; null if none. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> doc = builder.document(id, "sales_invoice");
        if (doc != null) {
            doc.put("partner", builder.partner(doc.get("partnerId")));
            doc.put("allocations", allocations("documentId", id));
            List<Map<String, Object>> lines = builder.lines(id, true);
            for (Map<String, Object> l : lines) {
                l.put("account", l.get("accountId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, code, name FROM"
                        + " \"Account\" WHERE id = ?").param(l.get("accountId"))));
                l.put("tax", l.get("taxId") == null ? null : SqlRows.one(jdbc.sql("SELECT id, name, rate FROM \"Tax\""
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(l.get("taxId"), org())));
            }
            doc.put("lines", lines);
            return doc;
        }
        Map<String, Object> inv = SqlRows.one(Invoice.class, jdbc.sql("SELECT * FROM \"Invoice\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (inv == null) {
            return null;
        }
        List<Map<String, Object>> items = SqlRows.list(InvoiceItem.class, jdbc.sql("SELECT * FROM \"InvoiceItem\" WHERE"
                + " \"invoiceId\" = ? ORDER BY \"lineNumber\" ASC").param(id));
        inv.put("items", items);
        inv.put("allocations", allocations("invoiceId", id));
        inv.put("documentNumber", inv.get("invoiceNumber"));
        inv.put("documentType", "sales_invoice");
        inv.put("source", "pos");
        inv.put("partner", SqlRows.one(jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(inv.get("partnerId"), org())));
        inv.put("lines", items);
        return inv;
    }

    /** {@code allocations: {include: {payment: true}}}. */
    List<Map<String, Object>> allocations(String fk, String id) {
        List<Map<String, Object>> rows = SqlRows.list(PaymentAllocation.class, jdbc.sql("SELECT * FROM \"PaymentAllocation\""
                + " WHERE \"" + fk + "\" = ?").param(id));
        for (Map<String, Object> a : rows) {
            a.put("payment", SqlRows.one(Payment.class, jdbc.sql("SELECT * FROM \"Payment\" WHERE id = ?")
                    .param(a.get("paymentId"))));
        }
        return rows;
    }

    // ------------------------------------------------------------------ create / edit

    /** Create a draft invoice. */
    @Transactional
    public Map<String, Object> create(InvoiceInput dto) {
        Term term = resolveTerm(dto.paymentTermId(), dto.dueDate(), dto.issueDate());
        Names names = resolveNames(dto.fiscalPositionId(), dto.invoicingJournalId(), dto.salespersonId());
        Map<String, Object> doc = builder.createDocument("sales_invoice", header(dto, term, names), dto.lines());
        publish("invoice.created", doc);
        return doc;
    }

    private static HeaderInput header(InvoiceInput dto, Term term, Names names) {
        return new HeaderInput(dto.partnerId(), dto.issueDate(), term.dueDate(), term.paymentTermId(),
                term.paymentTermName(), dto.paymentMode(), dto.fiscalPositionId(), names.fiscalPositionName(),
                dto.invoicingJournalId(), names.invoicingJournalName(), dto.salespersonId(), names.salespersonName(),
                dto.deliveryDate(), dto.deliveryAddress(), dto.incoterm(), dto.incotermLocation(), dto.sourceDocument(),
                dto.currencyId(), dto.exchangeRate(), dto.reference(), dto.notes(), null, null, null);
    }

    record Term(String paymentTermId, String paymentTermName, String dueDate) {}

    record Names(String fiscalPositionName, String invoicingJournalName, String salespersonName) {}

    /** Term id + name snapshot; derives the due date from the term when none was given. */
    Term resolveTerm(String paymentTermIdIn, String dueDateIn, String issueDate) {
        String paymentTermId = paymentTermIdIn == null || paymentTermIdIn.isEmpty() ? null : paymentTermIdIn;
        Instant due = dueDateIn != null ? EntityPatch.toInstant(dueDateIn) : null;
        String name = null;
        if (paymentTermId != null) {
            Map<String, Object> term = null;
            try {
                term = terms.get(paymentTermId);
            } catch (RuntimeException ignored) {
                // a missing / archived term never blocks the sale
            }
            if (term != null) {
                name = (String) term.get("name");
                if (due == null) {
                    due = terms.dueDateFor(paymentTermId, EntityPatch.toInstant(issueDate), BusinessZone.get());
                }
            }
        }
        return new Term(paymentTermId, name, due == null ? null : com.inventoryerp.kernel.web.JsonConfig.toJsIso(due));
    }

    Names resolveNames(String fiscalPositionId, String invoicingJournalId, String salespersonId) {
        String fp = fiscalPositionId == null ? null : jdbc.sql("SELECT name FROM \"FiscalPosition\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(fiscalPositionId, org())
                .query(String.class).optional().orElse(null);
        String journal = invoicingJournalId == null ? null : jdbc.sql("SELECT name FROM \"Journal\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(invoicingJournalId, org())
                .query(String.class).optional().orElse(null);
        String sp = null;
        if (salespersonId != null) {
            Map<String, Object> e = SqlRows.one(jdbc.sql("SELECT \"firstName\", \"lastName\" FROM \"HrEmployee\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(salespersonId, org()));
            if (e != null) {
                List<String> parts = new ArrayList<>();
                for (String k : List.of("firstName", "lastName")) {
                    if (e.get(k) != null && !String.valueOf(e.get(k)).isEmpty()) {
                        parts.add(String.valueOf(e.get(k)));
                    }
                }
                sp = String.join(" ", parts);
            }
        }
        return new Names(fp, journal, sp);
    }

    /** The journal entry the invoice would post, computed without writing anything. */
    @Transactional(readOnly = true)
    public Map<String, Object> previewJournal(InvoiceInput dto) {
        Totals totals = builder.prepareLines(dto.lines());
        Grouped g = builder.groupForPosting(dto.partnerId(), totals.prepared().stream().map(PreparedLine::asRow).toList(),
                "sales", null);
        var journal = journals.resolveSalesJournal(dto.invoicingJournalId());
        record Raw(String accountId, BigDecimal debit, BigDecimal credit, boolean partner, String description) {}
        List<Raw> raw = new ArrayList<>();
        raw.add(new Raw(g.counterAccount(), totals.total(), null, true, "Invoice (draft)"));
        g.itemByAccount().forEach((acc, amt) -> raw.add(new Raw(acc, null, amt, true, "Revenue")));
        g.taxByAccount().forEach((acc, amt) -> raw.add(new Raw(acc, null, amt, false, "Output tax")));
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        raw.forEach(r -> ids.add(r.accountId()));
        Map<String, Map<String, Object>> accounts = new HashMap<>();
        for (Map<String, Object> a : jdbc.sql("SELECT id, code, name FROM \"Account\" WHERE id IN (:ids)"
                + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(ids))
                .param("org", org()).query().listOfRows()) {
            accounts.put((String) a.get("id"), a);
        }
        String partnerName = jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(dto.partnerId(), org()).query(String.class).optional().orElse(null);
        BigDecimal debitTotal = Money.ZERO;
        BigDecimal creditTotal = Money.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        int i = 0;
        for (Raw r : raw) {
            Map<String, Object> a = accounts.get(r.accountId());
            debitTotal = debitTotal.add(r.debit() == null ? Money.ZERO : r.debit());
            creditTotal = creditTotal.add(r.credit() == null ? Money.ZERO : r.credit());
            lines.add(Obj.of("lineNumber", ++i, "account", a == null ? null
                            : Obj.of("id", a.get("id"), "code", a.get("code"), "name", a.get("name")),
                    "partnerName", r.partner() ? partnerName : null, "description", r.description(),
                    "debit", r.debit() != null ? JsNumbers.dec(r.debit()) : null,
                    "credit", r.credit() != null ? JsNumbers.dec(r.credit()) : null));
        }
        return Obj.of("journal", Obj.of("id", journal.getId(), "code", journal.getCode(), "name", journal.getName(),
                        "journalType", journal.getJournalType()),
                "postingDate", dto.issueDate(),
                "description", "Invoice (draft) — posted automatically to " + journal.getName() + " on \"Save & Post\"",
                "lines", lines, "debitTotal", JsNumbers.dec(debitTotal), "creditTotal", JsNumbers.dec(creditTotal),
                "balanced", debitTotal.subtract(creditTotal).abs().compareTo(new BigDecimal("0.01")) < 0);
    }

    /** Edit a draft invoice (lines replaced, totals recomputed). */
    @Transactional
    public Map<String, Object> update(String id, InvoiceInput dto) {
        Map<String, Object> doc = builder.document(id, "sales_invoice");
        if (doc == null) {
            throw ApiException.notFound("Invoice not found");
        }
        if (!"draft".equals(String.valueOf(doc.get("status")))) {
            throw ApiException.badRequest("Only draft invoices can be edited");
        }
        jdbc.sql("DELETE FROM \"DocumentLine\" WHERE \"documentId\" = ?").param(id).update();
        Totals totals = builder.prepareLines(dto.lines());
        Term term = resolveTerm(dto.paymentTermId(), dto.dueDate(), dto.issueDate());
        Names names = resolveNames(dto.fiscalPositionId(), dto.invoicingJournalId(), dto.salespersonId());
        jdbc.sql("UPDATE \"Document\" SET \"partnerId\" = ?, \"issueDate\" = ?, \"dueDate\" = ?, \"paymentTermId\" = ?,"
                        + " \"paymentTermName\" = ?, \"paymentMode\" = ?::\"InvoicePaymentMode\", \"fiscalPositionId\" = ?,"
                        + " \"fiscalPositionName\" = ?, \"invoicingJournalId\" = ?, \"invoicingJournalName\" = ?,"
                        + " \"salespersonId\" = ?, \"salespersonName\" = ?, \"deliveryDate\" = ?, \"deliveryAddress\" = ?,"
                        + " incoterm = ?, \"incotermLocation\" = ?, \"sourceDocument\" = ?, \"currencyId\" = ?,"
                        + " \"exchangeRate\" = ?, reference = ?, notes = ?, subtotal = ?, \"discountTotal\" = ?,"
                        + " \"taxAmount\" = ?, \"totalAmount\" = ?, \"amountResidual\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(dto.partnerId(), ts(dto.issueDate()), ts(term.dueDate()), term.paymentTermId(),
                        term.paymentTermName(), dto.paymentMode(), dto.fiscalPositionId(), names.fiscalPositionName(),
                        dto.invoicingJournalId(), names.invoicingJournalName(), dto.salespersonId(),
                        names.salespersonName(), ts(dto.deliveryDate()), dto.deliveryAddress(), dto.incoterm(),
                        dto.incotermLocation(), dto.sourceDocument(), dto.currencyId(),
                        dto.exchangeRate() != null ? dto.exchangeRate() : BigDecimal.ONE, dto.reference(), dto.notes(),
                        totals.subtotal(), totals.discountTotal(), totals.taxAmount(), totals.total(), totals.total(), id,
                        org())
                .update();
        builder.insertLines(id, totals.prepared(), false);
        return withLinesAndPartner(id);
    }

    Map<String, Object> withLinesAndPartner(String id) {
        Map<String, Object> doc = builder.document(id);
        doc.put("lines", builder.lines(id, false));
        doc.put("partner", builder.partner(doc.get("partnerId")));
        return doc;
    }

    /** Post (stock issue for tracked lines + AR / revenue / tax entry). */
    @Transactional
    public Map<String, Object> post(String id) {
        Map<String, Object> doc = builder.document(id, "sales_invoice");
        if (doc == null) {
            throw ApiException.notFound("Invoice not found");
        }
        doc.put("lines", builder.lines(id, false));
        workflow.transition("invoice", id, "post", null, doc);
        publish("invoice.posted", doc);
        return withLinesAndPartner(id);
    }

    /**
     * {@code POST /invoices/:id/post}: {@link #post} behind the credit check (SALES-1). Other callers of {@link #post}
     * (opening balances) are not sales and stay unchecked. An invoice for goods a delivery already took out of the yard
     * is not checked either: refusing it would only hide a debt that exists.
     */
    @Transactional
    public Map<String, Object> postChecked(String id) {
        Map<String, Object> doc = builder.document(id, "sales_invoice");
        if (doc != null && "draft".equals(String.valueOf(doc.get("status")))) {
            boolean delivered = Boolean.TRUE.equals(jdbc.sql("SELECT data->>'stockIssuedBy' IS NOT NULL FROM \"Document\""
                    + " WHERE id = ? AND \"organizationId\" = ?").params(id, org()).query(Boolean.class).optional()
                    .orElse(false));
            if (!delivered) {
                Object mode = doc.get("paymentMode");
                credit.assertCanSell((String) doc.get("partnerId"), Money.dec(doc.get("totalAmount")), null,
                        mode == null ? null : String.valueOf(mode));
            }
        }
        return post(id);
    }

    /**
     * Mark a <b>draft</b> sales invoice whose goods leave the yard through another module's delivery (fleet 1.3
     * dispatch issues the stock per load): posting it then skips the automatic stock issue of its tracked lines, so the
     * goods are issued exactly once. {@code deliveryReference} null clears the mark. Additive (ADR-S009 §2): invoices
     * without the mark — every POS and manual invoice — post exactly as before.
     */
    @Transactional
    public void setStockIssuedByDelivery(String id, String deliveryReference) {
        int n = jdbc.sql("UPDATE \"Document\" SET data = CASE WHEN ?::text IS NULL THEN COALESCE(data, '{}'::jsonb)"
                        + " - 'stockIssuedBy' ELSE COALESCE(data, '{}'::jsonb) || jsonb_build_object('stockIssuedBy',"
                        + " ?::text) END, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND"
                        + " \"documentType\" = 'sales_invoice' AND status = 'draft'")
                .params(deliveryReference, deliveryReference, id, org()).update();
        if (n == 0) {
            throw ApiException.badRequest("Only a draft sales invoice can hand its stock issue to a delivery");
        }
    }

    /** Cancel a posted, unpaid invoice (reversal), behind the {@code invoice_cancel} approval gate. */
    @Transactional
    public Map<String, Object> cancel(String id) {
        Map<String, Object> approval = newTx.execute(s -> approvals.checkOrRequestApproval("invoice_cancel", id, Map.of()));
        if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
            throw ApiException.forbidden("Invoice cancellation requires approval. Request ID: " + approval.get("requestId")
                    + ". Please have an authorized person approve it via the approvals endpoint.");
        }
        Map<String, Object> doc = builder.document(id, "sales_invoice");
        if (doc == null) {
            throw ApiException.notFound("Invoice not found");
        }
        doc.put("allocations", SqlRows.list(PaymentAllocation.class, jdbc.sql("SELECT * FROM \"PaymentAllocation\""
                + " WHERE \"documentId\" = ?").param(id)));
        if ("cancelled".equals(String.valueOf(doc.get("status")))) {
            return doc;
        }
        workflow.transition("invoice", id, "cancel", null, doc);
        publish("invoice.cancelled", doc);
        return builder.document(id);
    }

    /** Lines with the Prisma relation shape (used by credit notes and bills). */
    List<Map<String, Object>> orderedLines(String id) {
        return SqlRows.list(DocumentLine.class, jdbc.sql("SELECT * FROM \"DocumentLine\" WHERE \"documentId\" = ?"
                + " ORDER BY \"lineNumber\" ASC").param(id));
    }
}
