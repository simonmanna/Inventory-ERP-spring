package com.inventoryerp.invoicing.application;

import static com.inventoryerp.invoicing.application.DocumentBuilderService.NOW;
import static com.inventoryerp.invoicing.application.DocumentBuilderService.ts;

import com.inventoryerp.core.application.PriceSnapshotService;
import com.inventoryerp.core.application.ProductLifecycleGuard;
import com.inventoryerp.accounting.application.JournalService;
import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.core.application.PaymentTermService;
import com.inventoryerp.core.application.PriceListService;
import com.inventoryerp.core.application.PriceListService.ListPrice;
import com.inventoryerp.core.application.PriceListService.PriceQuery;
import com.inventoryerp.core.application.UomConversionService.UnitRole;
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
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
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
    private final PriceSnapshotService snapshots;
    private final BranchScopeService scopes;
    private final PriceListService priceLists;
    private final TransactionTemplate newTx;

    /** Resolved at posting time, so the guards (which depend on modules that use this service) form no bean cycle. */
    private org.springframework.beans.factory.ObjectProvider<InvoicePostGuard> postGuards;

    @org.springframework.beans.factory.annotation.Autowired
    void setPostGuards(org.springframework.beans.factory.ObjectProvider<InvoicePostGuard> postGuards) {
        this.postGuards = postGuards;
    }

    /** ADR-S094 C1b: resolved lazily (the deposit service depends on payments, which depend on invoices). */
    private org.springframework.beans.factory.ObjectProvider<CustomerDepositService> deposits;

    @org.springframework.beans.factory.annotation.Autowired
    void setDeposits(org.springframework.beans.factory.ObjectProvider<CustomerDepositService> deposits) {
        this.deposits = deposits;
    }

    /** The PLM lifecycle policy (ADR-S086 §6), when the PLM application is part of the build. */
    private org.springframework.beans.factory.ObjectProvider<ProductLifecycleGuard> lifecycleGuard;

    @org.springframework.beans.factory.annotation.Autowired
    void setLifecycleGuard(org.springframework.beans.factory.ObjectProvider<ProductLifecycleGuard> lifecycleGuard) {
        this.lifecycleGuard = lifecycleGuard;
    }

    public InvoiceService(JdbcClient jdbc, EventBus events, WorkflowService workflow, DocumentBuilderService builder,
            ApprovalsService approvals, PaymentTermService terms, JournalService journals, CreditControlService credit,
            BranchScopeService scopes, PriceListService priceLists, PlatformTransactionManager txm,
            PriceSnapshotService snapshots) {
        this.snapshots = snapshots;
        this.jdbc = jdbc;
        this.events = events;
        this.workflow = workflow;
        this.builder = builder;
        this.approvals = approvals;
        this.terms = terms;
        this.journals = journals;
        this.credit = credit;
        this.scopes = scopes;
        this.priceLists = priceLists;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Sales readiness R2 (P1-4): the approval request it writes is reused on retry, so its commit must not keep the key.
        this.newTx.setName(BusinessOutcome.RETRY_SAFE_TX);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private void publish(String event, Map<String, Object> doc) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org());
        payload.put("documentId", doc.get("id"));
        payload.put("documentNumber", doc.get("documentNumber"));
        events.publishOnCommit(event, payload);
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
        DataScope scope = scopes.scope();
        if (scope.restricted()) {
            dw.append(" AND \"branchId\" = ANY(?::text[])");
            iw.append(" AND \"branchId\" = ANY(?::text[])");
            dp.add(scope.branchArray());
            ip.add(scope.branchArray());
        }
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
        // In SQL, because the rows are cut at LIMIT below: a later filter would miss matches past the first page.
        if (notBlank(f.paymentStatus())) {
            dw.append(" AND ").append(paymentSql(f.paymentStatus()));
            iw.append(" AND \"paymentStatus\"::text = ?");
            ip.add(f.paymentStatus());
        }
        if (notBlank(f.settlementStatus())) {
            dw.append(" AND ").append(settlementSql(f.settlementStatus()));
            iw.append(" AND \"settlementStatus\"::text = ?");
            ip.add(f.settlementStatus());
        }
        // Sales readiness X-7: merge the two sources on (id, createdAt) only, then load the page's rows and their
        // partners / line counts in batches (no per-row queries, no full rows beyond the page).
        int take = page * pageSize;
        List<Object[]> heads = new ArrayList<>();
        jdbc.sql("SELECT id, \"createdAt\" FROM \"Document\" WHERE" + dw + " ORDER BY \"createdAt\" DESC LIMIT " + take)
                .params(dp).query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant(), "d"}).list()
                .forEach(heads::add);
        jdbc.sql("SELECT id, \"createdAt\" FROM \"Invoice\" WHERE" + iw + " ORDER BY \"createdAt\" DESC LIMIT " + take)
                .params(ip).query((rs, n) -> new Object[] {rs.getString(1), rs.getTimestamp(2).toInstant(), "i"}).list()
                .forEach(heads::add);
        heads.sort(Comparator.comparing((Object[] h) -> (Instant) h[1]).reversed());
        int start = Math.min(heads.size(), (page - 1) * pageSize);
        List<Object[]> slice = heads.subList(start, Math.min(heads.size(), start + pageSize));
        List<String> docIds = slice.stream().filter(h -> "d".equals(h[2])).map(h -> (String) h[0]).toList();
        List<String> invIds = slice.stream().filter(h -> "i".equals(h[2])).map(h -> (String) h[0]).toList();
        Map<String, Map<String, Object>> rows = new HashMap<>();
        if (!docIds.isEmpty()) {
            List<Map<String, Object>> docs = SqlRows.list(com.inventoryerp.documents.domain.Document.class,
                    jdbc.sql("SELECT * FROM \"Document\" WHERE \"organizationId\" = :org AND id IN (:ids)")
                            .param("org", org()).param("ids", docIds));
            Map<String, Long> counts = groupCount("DocumentLine", "documentId", docIds);
            Map<String, Map<String, Object>> partners = new HashMap<>();
            List<String> pidList = docs.stream().map(d -> (String) d.get("partnerId")).filter(java.util.Objects::nonNull)
                    .distinct().toList();
            if (!pidList.isEmpty()) {
                for (Map<String, Object> p : SqlRows.list(com.inventoryerp.core.domain.Partner.class,
                        jdbc.sql("SELECT * FROM \"Partner\" WHERE id IN (:ids)").param("ids", pidList))) {
                    partners.put((String) p.get("id"), p);
                }
            }
            for (Map<String, Object> d : docs) {
                d.put("partner", d.get("partnerId") == null ? null : partners.get((String) d.get("partnerId")));
                d.put("_count", Obj.of("lines", counts.getOrDefault((String) d.get("id"), 0L)));
                d.put("source", "manual");
                String payment = paymentView(d);
                d.put("paymentStatus", payment);
                d.put("settlementStatus", settlementView(payment));
                rows.put((String) d.get("id"), d);
            }
        }
        if (!invIds.isEmpty()) {
            List<Map<String, Object>> invs = SqlRows.list(Invoice.class, jdbc.sql("SELECT * FROM \"Invoice\" WHERE"
                    + " \"organizationId\" = :org AND id IN (:ids)").param("org", org()).param("ids", invIds));
            Map<String, Long> counts = groupCount("InvoiceItem", "invoiceId", invIds);
            Map<String, String> names = new HashMap<>();
            LinkedHashSet<String> pids = new LinkedHashSet<>();
            invs.forEach(i -> {
                if (i.get("partnerId") != null) {
                    pids.add((String) i.get("partnerId"));
                }
            });
            if (!pids.isEmpty()) {
                for (Map<String, Object> p : jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id IN (:ids)"
                        + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(pids))
                        .param("org", org()).query().listOfRows()) {
                    names.put((String) p.get("id"), (String) p.get("name"));
                }
            }
            for (Map<String, Object> i : invs) {
                i.put("documentNumber", i.get("invoiceNumber"));
                i.put("documentType", "sales_invoice");
                i.put("source", "pos");
                i.put("partner", Obj.of("id", i.get("partnerId"), "name",
                        names.getOrDefault((String) i.get("partnerId"), "Walk-in")));
                i.put("_count", Obj.of("lines", counts.getOrDefault((String) i.get("id"), 0L)));
                rows.put((String) i.get("id"), i);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] h : slice) {
            Map<String, Object> r = rows.get((String) h[0]);
            if (r != null) {
                out.add(r);
            }
        }
        // The rows above stop at page * pageSize, so the real total has to be counted.
        long total = jdbc.sql("SELECT count(*) FROM \"Document\" WHERE" + dw).params(dp).query(Long.class).single()
                + jdbc.sql("SELECT count(*) FROM \"Invoice\" WHERE" + iw).params(ip).query(Long.class).single();
        return PageResult.of(out, page, pageSize, total);
    }

    /** The list's payment status of a back-office document, in the POS invoice vocabulary the screen shows. */
    static String paymentView(Map<String, Object> doc) {
        String status = String.valueOf(doc.get("status"));
        if (!"posted".equals(status) && !"paid".equals(status)) {
            return "unpaid";
        }
        BigDecimal residual = Money.dec(doc.get("amountResidual"));
        if (residual.signum() <= 0) {
            return "paid";
        }
        return residual.compareTo(Money.dec(doc.get("totalAmount"))) < 0 ? "partially_paid" : "unpaid";
    }

    /** {@link #paymentView} as a condition on {@code "Document"}; the value is matched, never concatenated. */
    private static String paymentSql(String payment) {
        String open = "status::text IN ('posted', 'paid')";
        return switch (payment) {
            case "paid" -> "(" + open + " AND \"amountResidual\" <= 0)";
            case "partially_paid" -> "(" + open + " AND \"amountResidual\" > 0 AND \"amountResidual\" < \"totalAmount\")";
            case "unpaid" -> "(NOT (" + open + ") OR (\"amountResidual\" > 0 AND \"amountResidual\" >= \"totalAmount\"))";
            default -> "false";
        };
    }

    private static String settlementSql(String settlement) {
        return switch (settlement) {
            case "settled" -> paymentSql("paid");
            case "partially_settled" -> paymentSql("partially_paid");
            case "unsettled" -> paymentSql("unpaid");
            default -> "false";
        };
    }

    static String settlementView(String payment) {
        return switch (payment) {
            case "paid" -> "settled";
            case "partially_paid" -> "partially_settled";
            default -> "unsettled";
        };
    }

    private Map<String, Long> groupCount(String table, String fk, List<String> ids) {
        Map<String, Long> out = new HashMap<>();
        jdbc.sql("SELECT \"" + fk + "\", count(*) FROM \"" + table + "\" WHERE \"" + fk + "\" IN (:ids) GROUP BY \"" + fk
                + "\"").param("ids", ids).query((rs, n) -> {
                    out.put(rs.getString(1), rs.getLong(2));
                    return null;
                }).list();
        return out;
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
        DataScope scope = scopes.scope();
        Map<String, Object> doc = builder.document(id, "sales_invoice");
        if (doc == null) {
            // Read-only: every write on /invoices still loads a sales_invoice, so a proforma cannot be posted or paid.
            doc = builder.document(id, "proforma_invoice");
        }
        if (doc != null && !scope.allowsBranch((String) doc.get("branchId"))) {
            return null;
        }
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
            if ("cancelled".equals(String.valueOf(doc.get("status")))) {
                withReversal(doc, id);
            }
            return doc;
        }
        Map<String, Object> inv = SqlRows.one(Invoice.class, jdbc.sql("SELECT * FROM \"Invoice\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (inv == null || !scope.allowsBranch((String) inv.get("branchId"))) {
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

    /**
     * Create a draft invoice. A product on it is a new sale, so the lifecycle policy's {@code sell} column applies
     * (ADR-S086 §6); the contract, dunning and opening-balance invoices that also come here bill accounts, not
     * products.
     */
    @Transactional
    public Map<String, Object> create(InvoiceInput dto) {
        builder.assertVariants(dto.lines(), false, false);
        requireSellable(dto.lines(), null);
        return createDraft(dto);
    }

    /**
     * ADR-S086 §6 (owner decision 2026-11-24): products the invoice adds must be sellable in their lifecycle state;
     * the ones already on it are never refused. Invoices copied from a delivery or an order ({@link #createCopied})
     * bill sales already made and are not checked.
     */
    private void requireSellable(List<LineInput> lines, String invoiceId) {
        ProductLifecycleGuard guard = lifecycleGuard == null ? null : lifecycleGuard.getIfAvailable();
        if (guard == null || lines == null || lines.isEmpty()) {
            return;
        }
        java.util.Set<String> had = new java.util.HashSet<>();
        if (invoiceId != null) {
            had.addAll(jdbc.sql("SELECT DISTINCT \"productId\" FROM \"DocumentLine\" WHERE \"documentId\" = ? AND"
                    + " \"productId\" IS NOT NULL").param(invoiceId).query(String.class).list());
        }
        guard.requireAllowed(ProductLifecycleGuard.SELL, lines.stream().map(LineInput::productId)
                .filter(p -> p != null && !had.contains(p)).toList());
    }

    /**
     * Draft invoice whose lines are copied from an earlier document (e.g. a dispatched delivery order): a variant
     * archived since then is kept, as on the source document (Phase I2, D2).
     */
    @Transactional
    public Map<String, Object> createCopied(InvoiceInput dto) {
        builder.assertVariants(dto.lines(), false, true);
        return createDraft(dto);
    }

    private Map<String, Object> createDraft(InvoiceInput dto) {
        Term term = resolveTerm(dto.paymentTermId(), dto.dueDate(), dto.issueDate());
        Names names = resolveNames(dto.fiscalPositionId(), dto.invoicingJournalId(), dto.salespersonId());
        String branchId = scopes.documentBranch(null);
        Map<String, Object> doc = builder.createDocument("sales_invoice", header(dto, term, names, branchId),
                priced(dto, branchId));
        publish("invoice.created", doc);
        return doc;
    }

    /**
     * Draft invoice of an approved branch cash sale (ADR-S056 P4), stamped with the submission's branch (not the
     * caller's scope), linked to the submission ({@code sourceType = cash_sale}) and marked so that posting neither
     * issues stock ({@code stockIssuedBy}) nor posts revenue / VAT ({@code revenueAccruedBy} = the accrual entry):
     * it only reclassifies the accrual to AR. Lines carry their frozen prices, so no repricing happens.
     */
    @Transactional
    public Map<String, Object> createForCashSale(InvoiceInput dto, String branchId, String cashSaleId,
            String cashSaleNumber, String accrualEntryId) {
        Term term = resolveTerm(dto.paymentTermId(), dto.dueDate(), dto.issueDate());
        Names names = resolveNames(dto.fiscalPositionId(), dto.invoicingJournalId(), dto.salespersonId());
        Map<String, Object> doc = builder.createDocument("sales_invoice", header(dto, term, names, branchId), dto.lines());
        String id = (String) doc.get("id");
        jdbc.sql("UPDATE \"Document\" SET \"sourceType\" = 'cash_sale', \"sourceId\" = ?, data = COALESCE(data,"
                        + " '{}'::jsonb) || jsonb_build_object('stockIssuedBy', ?::text, 'revenueAccruedBy', ?::text),"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(cashSaleId, cashSaleNumber, accrualEntryId, id, org()).update();
        publish("invoice.created", doc);
        return builder.document(id, "sales_invoice");
    }

    private static HeaderInput header(InvoiceInput dto, Term term, Names names, String branchId) {
        return new HeaderInput(dto.partnerId(), dto.issueDate(), term.dueDate(), term.paymentTermId(),
                term.paymentTermName(), dto.paymentMode(), dto.fiscalPositionId(), names.fiscalPositionName(),
                dto.invoicingJournalId(), names.invoicingJournalName(), dto.salespersonId(), names.salespersonName(),
                dto.deliveryDate(), dto.deliveryAddress(), dto.incoterm(), dto.incotermLocation(), dto.sourceDocument(),
                dto.currencyId(), dto.exchangeRate(), dto.reference(), dto.notes(), null, null, branchId);
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

    /**
     * SALES-3: lines that came with no price take the customer's price list. A line with a price, or a customer with no
     * list, passes through unchanged. Tier and unit prices apply; the catalogue price stays the fallback.
     */
    private List<LineInput> priced(InvoiceInput dto, String branchId) {
        if (dto.lines() == null) {
            return null;
        }
        java.time.LocalDate on = dto.issueDate() == null ? java.time.LocalDate.now(BusinessZone.get())
                : EntityPatch.toInstant(dto.issueDate()).atZone(BusinessZone.get()).toLocalDate();
        List<LineInput> out = new ArrayList<>();
        for (LineInput l : dto.lines()) {
            boolean product = l.lineType() == null || "product".equals(l.lineType());
            ListPrice p = !product || l.productId() == null || l.unitPrice() != null ? null
                    : priceLists.resolve(new PriceQuery(dto.partnerId(), l.productId(), l.quantity(), l.uomId(), branchId,
                            null, on, l.variantId()));
            out.add(p == null ? l : l.withPrice(p.unitPrice(),
                    l.discountPercent() != null ? l.discountPercent() : p.discountPercent()));
        }
        return out;
    }

    /** The journal entry the invoice would post, computed without writing anything. */
    @Transactional(readOnly = true)
    public Map<String, Object> previewJournal(InvoiceInput dto) {
        Totals totals = builder.prepareLines(priced(dto, scopes.documentBranch(null)), UnitRole.SALES,
                builder.fiscalTaxMap(dto.fiscalPositionId(), dto.partnerId()));
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
        // Sales readiness R1: lock before reading the status, so an edit never races a post.
        jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, org())
                .query().listOfRows();
        Map<String, Object> doc = builder.document(id, "sales_invoice");
        if (doc == null) {
            throw ApiException.notFound("Invoice not found");
        }
        assertNotGenerated(id);
        if (dto.lines() != null) {
            builder.assertSaleable(dto.lines());
            builder.assertVariants(dto.lines(), false, false);
        }
        scopes.assertBranch((String) doc.get("branchId"));
        if (!"draft".equals(String.valueOf(doc.get("status")))) {
            throw ApiException.badRequest("Only draft invoices can be edited");
        }
        requireSellable(dto.lines(), id);
        jdbc.sql("DELETE FROM \"DocumentLine\" WHERE \"documentId\" = ?").param(id).update();
        Totals totals = builder.prepareLines(priced(dto, (String) doc.get("branchId")), UnitRole.SALES,
                builder.fiscalTaxMap(dto.fiscalPositionId(), dto.partnerId()));
        Term term = resolveTerm(dto.paymentTermId(), dto.dueDate(), dto.issueDate());
        Names names = resolveNames(dto.fiscalPositionId(), dto.invoicingJournalId(), dto.salespersonId());
        jdbc.sql("UPDATE \"Document\" SET \"partnerId\" = ?, \"issueDate\" = ?, \"dueDate\" = ?, \"paymentTermId\" = ?,"
                        + " \"paymentTermName\" = ?, \"paymentMode\" = ?::\"InvoicePaymentMode\", \"fiscalPositionId\" = ?,"
                        + " \"fiscalPositionName\" = ?, \"invoicingJournalId\" = ?, \"invoicingJournalName\" = ?,"
                        + " \"salespersonId\" = ?, \"salespersonName\" = ?, \"deliveryDate\" = ?, \"deliveryAddress\" = ?,"
                        + " incoterm = ?, \"incotermLocation\" = ?, \"sourceDocument\" = ?, \"currencyId\" = ?,"
                        + " \"exchangeRate\" = ?, reference = ?, notes = ?, subtotal = ?, \"discountTotal\" = ?,"
                        + " \"taxAmount\" = ?, \"totalAmount\" = ?, \"amountResidual\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND status = 'draft'")
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

    /**
     * Sales readiness R1 (B1): a draft generated from a sales order or a branch cash sale carries exactly what its
     * source authorised (lines linked to order lines, prices, quantities, taxes). Editing it would let the invoice
     * diverge from the source; discard the draft ({@code POST /invoices/:id/cancel}) and invoice the source again.
     */
    private void assertNotGenerated(String id) {
        Map<String, Object> src = SqlRows.one(jdbc.sql("SELECT \"sourceType\", \"sourceDocument\" FROM \"Document\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(id, org()));
        String type = src == null ? null : (String) src.get("sourceType");
        if ("sales_order".equals(type) || "cash_sale".equals(type)) {
            throw ApiException.badRequest("This invoice was generated from " + ("cash_sale".equals(type) ? "cash sale"
                    : "sales order") + " " + src.get("sourceDocument") + "; cancel the draft and invoice it again");
        }
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
        boolean delivered = Boolean.TRUE.equals(jdbc.sql("SELECT data->>'stockIssuedBy' IS NOT NULL FROM \"Document\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(id, org()).query(Boolean.class).optional()
                .orElse(false));
        // Sales readiness R1: every draft with a commercial source is rechecked against it, goods or not.
        boolean sourced = jdbc.sql("SELECT \"sourceType\" IS NOT NULL FROM \"Document\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()).query(Boolean.class).optional().orElse(false);
        boolean guarded = (delivered || sourced) && "draft".equals(String.valueOf(doc.get("status")));
        if (guarded && postGuards != null) {
            postGuards.orderedStream().forEach(g -> g.beforePost(id));
        }
        if ("draft".equals(String.valueOf(doc.get("status")))) {
            // Sales readiness INV-11: the legal number is taken now, in this transaction (gap-free).
            doc.put("documentNumber", builder.numberAtPost(id));
        }
        workflow.transition("invoice", id, "post", null, doc);
        if (guarded && postGuards != null) {
            // ADR-S094 C1b: what the post settles (a deposit a deduction consumes) is booked in the same transaction.
            postGuards.orderedStream().forEach(g -> g.afterPost(id));
        }
        snapshots.record("invoice", id);
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
        if (doc != null) {
            scopes.assertBranch((String) doc.get("branchId"));
        }
        if (doc != null && "draft".equals(String.valueOf(doc.get("status")))) {
            boolean delivered = Boolean.TRUE.equals(jdbc.sql("SELECT data->>'stockIssuedBy' IS NOT NULL FROM \"Document\""
                    + " WHERE id = ? AND \"organizationId\" = ?").params(id, org()).query(Boolean.class).optional()
                    .orElse(false));
            // A sales-order invoice was credit-checked at order confirmation; its value is already in the exposure.
            boolean fromSalesOrder = "sales_order".equals(jdbc.sql("SELECT \"sourceType\" FROM \"Document\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(id, org()).query(String.class).optional().orElse(null));
            if (!delivered && !fromSalesOrder) {
                // Sales readiness INV-9: serialise credit checks of one customer (the POS CreditStatus lock), so two
                // posts at once cannot both fit under the same remaining limit. The invoice is locked first.
                jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, org())
                        .query().listOfRows();
                jdbc.sql("SELECT id FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                        .params(doc.get("partnerId"), org()).query().listOfRows();
                Object mode = doc.get("paymentMode");
                credit.assertCanSell("invoice_post", (String) doc.get("partnerId"), Money.dec(doc.get("totalAmount")),
                        "invoice", id, mode == null ? null : String.valueOf(mode), (String) doc.get("branchId"));
            }
        }
        return post(id);
    }

    /**
     * Tag a sales invoice with the customer site it was delivered to and that site's project (ADR-S075); stored in the
     * document's {@code data} as {@code customerSiteId} / {@code projectId}. A null site leaves the document unchanged.
     */
    @Transactional
    public void setSiteReference(String id, String customerSiteId, String projectId) {
        if (customerSiteId == null) {
            return;
        }
        jdbc.sql("UPDATE \"Document\" SET data = COALESCE(data, '{}'::jsonb) || jsonb_strip_nulls(jsonb_build_object("
                        + "'customerSiteId', ?::text, 'projectId', ?::text)), \"updatedAt\" = " + NOW + " WHERE id = ? AND"
                        + " \"organizationId\" = ?")
                .params(customerSiteId, projectId, id, org()).update();
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

    /**
     * Record the commercial source of a draft sales invoice (ADR-S011: a sales order) and, for a down-payment
     * invoice, the order it is an advance for ({@code data.downPaymentFor}).
     */
    @Transactional
    public void linkSource(String id, String sourceType, String sourceId, boolean downPayment) {
        int n = jdbc.sql("UPDATE \"Document\" SET \"sourceType\" = ?, \"sourceId\" = ?, data = CASE WHEN ? THEN"
                        + " COALESCE(data, '{}'::jsonb) || jsonb_build_object('downPaymentFor', ?::text) ELSE data END,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"documentType\" ="
                        + " 'sales_invoice' AND status = 'draft'")
                .params(sourceType, sourceId, downPayment, sourceId, id, org()).update();
        if (n == 0) {
            throw ApiException.badRequest("Only a draft sales invoice can be linked to its source");
        }
    }

    private com.inventoryerp.accounting.application.DocumentReversalService reversals;

    @org.springframework.beans.factory.annotation.Autowired
    void setReversals(com.inventoryerp.accounting.application.DocumentReversalService reversals) {
        this.reversals = reversals;
    }

    /**
     * Internal cancel for a workflow that owns the invoice and ran its own controls (cash-sale invoice cancel, which
     * unapplies its deposits first): no approval gate or separation-of-duties check, the same settlement checks, GL
     * reversal and reversal record as {@link #cancel(String, String)}.
     */
    @Transactional
    public Map<String, Object> cancel(String id) {
        return cancel(id, "Cancelled by its source workflow", false);
    }

    /**
     * Sales-invoice document reversal (owner decision 2026-11-17): cancel a posted invoice with a reason, behind the
     * {@code invoice_cancel} approval gate and the {@code document_reversal} separation-of-duties pair. Refused while
     * anything is settled against it — payments (void them; a deposit application is reversed from its deposit),
     * posted debit notes (reverse them), posted credit notes (the workflow names them). Under the invoice's row lock
     * the workflow reverses its journal entry, returns the auto-issued stock with its COGS, zeroes the residual and
     * stamps cancelledAt / cancelledBy; a {@code DocumentReversal} row keeps the reason and the entry links. A replay on
     * a cancelled invoice returns it unchanged.
     */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        return cancel(id, reason, true);
    }

    private Map<String, Object> cancel(String id, String reasonIn, boolean userAction) {
        Map<String, Object> doc = builder.document(id, "sales_invoice");
        if (doc == null) {
            throw ApiException.notFound("Invoice not found");
        }
        scopes.assertBranch((String) doc.get("branchId"));
        doc.put("allocations", SqlRows.list(PaymentAllocation.class, jdbc.sql("SELECT * FROM \"PaymentAllocation\""
                + " WHERE \"documentId\" = ?").param(id)));
        if ("cancelled".equals(String.valueOf(doc.get("status")))) {
            withReversal(doc, id);
            return doc;
        }
        if ("draft".equals(String.valueOf(doc.get("status")))) {
            // Sales readiness R1: a draft has no ledger, stock or settlement effect, so discarding it needs no approval
            // or reversal; its source (sales order lines, delivery orders) becomes invoiceable again.
            workflow.transition("invoice", id, "cancel", Obj.of("reason", reasonIn == null || reasonIn.isBlank()
                    ? "Draft discarded" : reasonIn), doc);
            publish("invoice.cancelled", doc);
            return builder.document(id);
        }
        String reason = reversals.requireReason(reasonIn, "cancel an invoice");
        if (userAction) {
            Map<String, Object> approval = newTx.execute(s -> approvals.checkOrRequestApproval("invoice_cancel", id,
                    Map.of()));
            if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
                throw ApiException.forbidden("Invoice cancellation requires approval. Request ID: "
                        + approval.get("requestId") + ". Please have an authorized person approve it via the approvals"
                        + " endpoint.");
            }
            reversals.assertSeparated("Document", id, java.util.Arrays.asList((String) doc.get("createdBy"),
                    (String) doc.get("postedBy")));
        }
        // Lock before reading what is settled against it: a payment allocation or credit note locks the invoice too.
        jdbc.sql("SELECT id FROM \"Document\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, org())
                .query().listOfRows();
        assertNothingSettled(id, (String) doc.get("documentNumber"));
        voidDownPaymentDeposit(id, (String) doc.get("documentNumber"), reason);
        // ADR-S094 C1b: the down payments this invoice deducted become available again.
        deposits.getObject().releaseConsumption(id);
        workflow.transition("invoice", id, "cancel", Obj.of("reason", reason), doc);
        reversals.record(new com.inventoryerp.accounting.application.DocumentReversalService.Recording("sales_invoice",
                id, (String) doc.get("documentNumber"), (String) doc.get("partnerId"), reason, "Document",
                List.of("invoice"), List.of(), List.of()));
        publish("invoice.cancelled", doc);
        Map<String, Object> out = builder.document(id);
        withReversal(out, id);
        return out;
    }

    /**
     * Sales readiness R2b (INV-2): a posted down-payment invoice registered a customer deposit (its Cr side). The cancel
     * reverses that entry, so the deposit must go too: refused while any of it was applied or refunded, otherwise voided
     * (it has no entry of its own). Before this the deposit stayed open and could clear receivables with no cash.
     */
    private void voidDownPaymentDeposit(String id, String number, String reason) {
        Map<String, Object> dep = SqlRows.one(jdbc.sql("SELECT id, number, status, \"appliedAmount\", \"refundedAmount\""
                + " FROM \"CustomerDeposit\" WHERE \"documentId\" = ? AND \"organizationId\" = ? AND source ="
                + " 'down_payment_invoice' FOR UPDATE").params(id, org()));
        if (dep == null || "void".equals(String.valueOf(dep.get("status")))) {
            return;
        }
        if (SqlRows.dec(dep, "appliedAmount").signum() > 0 || SqlRows.dec(dep, "refundedAmount").signum() > 0) {
            throw ApiException.badRequest("Invoice " + number + " cannot be cancelled: its down payment deposit "
                    + dep.get("number") + " has been applied or refunded (reverse its applications and refunds first).");
        }
        jdbc.sql("UPDATE \"CustomerDeposit\" SET status = 'void', notes = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params("Down-payment invoice " + number + " cancelled: " + reason, dep.get("id")).update();
    }

    /** Additive {@code reversal} block (reason, who, when, entry links) on a cancelled invoice. */
    private void withReversal(Map<String, Object> doc, String id) {
        Map<String, Object> r = reversals == null ? null : reversals.find("sales_invoice", id);
        if (r != null && doc != null) {
            doc.put("reversal", r);
        }
    }

    /** Payments (deposit applications included) and posted debit notes block a cancel; each is named with its action. */
    private void assertNothingSettled(String id, String number) {
        List<Map<String, Object>> paid = reversals.appliedPayments(id);
        BigDecimal amountPaid = jdbc.sql("SELECT \"amountPaid\" FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
        List<String> notes = reversals.postedDebitNotes(id);
        List<String> problems = new ArrayList<>();
        if (!paid.isEmpty()) {
            problems.add("payments applied: " + com.inventoryerp.accounting.application.DocumentReversalService
                    .describePayments(paid) + " (void each payment first; a deposit application is reversed from its"
                    + " customer deposit)");
        } else if (amountPaid != null && amountPaid.signum() > 0) {
            problems.add(JsNumbers.dec(amountPaid) + " is recorded as paid (unallocate it first)");
        }
        if (!notes.isEmpty()) {
            problems.add("posted debit notes raised against it: " + String.join(", ", notes) + " (reverse them first)");
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Invoice " + number + " cannot be cancelled while settlements are applied to it: "
                    + String.join("; ", problems) + ".");
        }
    }

    /** Lines with the Prisma relation shape (used by credit notes and bills). */
    List<Map<String, Object>> orderedLines(String id) {
        return SqlRows.list(DocumentLine.class, jdbc.sql("SELECT * FROM \"DocumentLine\" WHERE \"documentId\" = ?"
                + " ORDER BY \"lineNumber\" ASC").param(id));
    }
}
