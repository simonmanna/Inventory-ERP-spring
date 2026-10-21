package com.inventoryerp.invoicing.application;

import static com.inventoryerp.invoicing.application.DocumentBuilderService.NOW;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.documents.domain.Document;
import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.Grouped;
import com.inventoryerp.invoicing.application.DocumentBuilderService.HeaderInput;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.Savepoints;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Credit notes (port of {@code credit-note.service.ts}): draft create, post (Dr revenue + tax / Cr AR), apply the
 * credit to the reversed invoice's residual, and restock (COGS reversal) or scrap the returned goods.
 */
@Service
public class CreditNoteService {

    private static final Logger log = LoggerFactory.getLogger(CreditNoteService.class);

    /** {@code CreateCreditNoteDto}. */
    public record CreditNoteInput(String partnerId, String issueDate, String currencyId, String reference, String notes,
            String reversedDocumentId, List<LineInput> lines) {}

    private final JdbcClient jdbc;
    private final EventBus events;
    private final PostingService posting;
    private final DocumentBuilderService builder;
    private final StockService stock;
    private final ApprovalsService approvals;
    private final PosStockLocation posLocation;
    private final TransactionTemplate newTx;

    public CreditNoteService(JdbcClient jdbc, EventBus events, PostingService posting, DocumentBuilderService builder,
            StockService stock, ApprovalsService approvals, PosStockLocation posLocation, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.events = events;
        this.posting = posting;
        this.builder = builder;
        this.stock = stock;
        this.approvals = approvals;
        this.posLocation = posLocation;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Credit notes newest first (search on number) with partner and line count. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery q) {
        int page = Math.max(1, q.page() == null || q.page() == 0 ? 1 : q.page());
        int pageSize = Math.min(200, Math.max(1, q.pageSize() == null || q.pageSize() == 0 ? 25 : q.pageSize()));
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = " \"organizationId\" = ? AND \"documentType\" = 'credit_note'";
        if (q.search() != null && !q.search().isEmpty()) {
            where += " AND \"documentNumber\" ILIKE ?";
            params.add("%" + BaseCrudService.escapeLike(q.search()) + "%");
        }
        List<Map<String, Object>> rows = SqlRows.list(Document.class, jdbc.sql("SELECT * FROM \"Document\" WHERE" + where
                + " ORDER BY \"createdAt\" DESC LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize).params(params));
        for (Map<String, Object> d : rows) {
            d.put("partner", builder.partner(d.get("partnerId")));
            d.put("_count", Obj.of("lines", jdbc.sql("SELECT count(*) FROM \"DocumentLine\" WHERE \"documentId\" = ?")
                    .param(d.get("id")).query(Long.class).single()));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"Document\" WHERE" + where).params(params).query(Long.class).single();
        return PageResult.of(rows, page, pageSize, total);
    }

    /** One credit note with ordered lines and partner; null when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> d = builder.document(id, "credit_note");
        if (d == null) {
            return null;
        }
        d.put("lines", builder.lines(id, true));
        d.put("partner", builder.partner(d.get("partnerId")));
        return d;
    }

    /** Create a draft credit note. */
    @Transactional
    public Map<String, Object> create(CreditNoteInput dto) {
        return builder.createDocument("credit_note", new HeaderInput(dto.partnerId(), dto.issueDate(), null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, dto.currencyId(), null,
                dto.reference(), dto.notes(), dto.reversedDocumentId(), null, null), dto.lines());
    }

    /** Post a credit note; {@code disposition} restock (default) or scrap. */
    @Transactional
    public Map<String, Object> post(String id, String disposition) {
        Map<String, Object> approval = newTx.execute(s -> approvals.checkOrRequestApproval("credit_note", id, Map.of()));
        if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
            throw ApiException.forbidden("Credit note posting requires approval. Request ID: " + approval.get("requestId")
                    + ". Please have an authorized person approve it via the approvals endpoint.");
        }
        Map<String, Object> cn = builder.document(id, "credit_note");
        if (cn == null) {
            throw ApiException.notFound("Credit note not found");
        }
        String status = String.valueOf(cn.get("status"));
        if ("posted".equals(status) || "paid".equals(status)) {
            throw ApiException.badRequest("Credit note is already posted");
        }
        if ("cancelled".equals(status)) {
            throw ApiException.badRequest("Credit note is cancelled");
        }
        List<Map<String, Object>> lines = builder.lines(id, false);
        String partnerId = (String) cn.get("partnerId");
        Grouped g = builder.groupForPosting(partnerId, lines, "sales", null);
        List<Line> post = new ArrayList<>();
        g.itemByAccount().forEach((acc, amt) -> post.add(new Line(acc, JsNumbers.dec(amt), null, partnerId,
                "Revenue (credit)", null, null, null)));
        g.taxByAccount().forEach((acc, amt) -> post.add(Line.debit(acc, JsNumbers.dec(amt), "Tax (credit)")));
        BigDecimal total = Money.dec(cn.get("totalAmount"));
        post.add(new Line(g.counterAccount(), null, JsNumbers.dec(total), partnerId,
                "Credit note " + cn.get("documentNumber"), null, null, null));
        Instant issueDate = (Instant) cn.get("issueDate");
        Map<String, Object> entry = posting.post(new PostingRequest("SALES", issueDate, "Credit note " + cn.get("documentNumber"),
                (String) cn.get("currencyId"), Money.dec(cn.get("exchangeRate")), "credit_note", id, null, null, null,
                null, null, post));
        jdbc.sql("UPDATE \"Document\" SET status = 'posted', \"postedAt\" = " + NOW + ", \"postedBy\" = ?,"
                        + " \"journalEntryId\" = ?, \"amountResidual\" = 0, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(TenantContext.userId(), entry.get("id"), id).update();
        String reversed = (String) cn.get("reversedDocumentId");
        if (reversed != null) {
            Map<String, Object> inv = builder.document(reversed, "sales_invoice");
            String invStatus = inv == null ? null : String.valueOf(inv.get("status"));
            if (inv != null && ("posted".equals(invStatus) || "paid".equals(invStatus))) {
                BigDecimal residual = Money.dec(inv.get("amountResidual"));
                BigDecimal reduce = total.compareTo(residual) > 0 ? residual : total;
                if (reduce.signum() > 0) {
                    BigDecimal newResidual = residual.subtract(reduce);
                    boolean paid = newResidual.signum() <= 0;
                    jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = ?, \"paymentStatus\" = ?::\"PaymentStatus\","
                                    + " status = ?::\"DocumentStatus\", \"updatedAt\" = " + NOW + " WHERE id = ?")
                            .params(newResidual, paid ? "paid" : "partial", paid ? "paid" : invStatus, reversed).update();
                }
            }
        }
        if ("restock".equals(disposition)) {
            String warehouse = posLocation.resolve(org(), null);
            if (warehouse != null) {
                for (Map<String, Object> ln : lines) {
                    String productId = (String) ln.get("productId");
                    BigDecimal qty = Money.dec(ln.get("quantity"));
                    if (productId == null || qty.signum() <= 0) {
                        continue;
                    }
                    Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT \"trackInventory\", \"productType\"::text"
                            + " AS t FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                            .params(productId, org()));
                    if (product != null && Boolean.TRUE.equals(product.get("trackInventory"))
                            && ("stockable".equals(product.get("t")) || "consumable".equals(product.get("t")))) {
                        try {
                            Savepoints.run(jdbc, () -> stock.receiveReturn(StockMove.of(productId, warehouse, qty)
                                    .reference((String) cn.get("documentNumber")).source("credit_note", id)
                                    .date(issueDate)));
                        } catch (RuntimeException e) {
                            log.warn("credit-note {} restock of {} skipped: {}", cn.get("documentNumber"), productId,
                                    e.getMessage());
                        }
                    }
                }
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org());
        payload.put("documentId", id);
        payload.put("documentNumber", cn.get("documentNumber"));
        events.publish("creditnote.issued", payload);
        Map<String, Object> out = builder.document(id);
        out.put("lines", builder.lines(id, false));
        out.put("partner", builder.partner(out.get("partnerId")));
        return out;
    }
}
