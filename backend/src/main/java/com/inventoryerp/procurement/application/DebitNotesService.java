package com.inventoryerp.procurement.application;

import com.inventoryerp.core.application.UomConversionService.UnitRole;
import com.inventoryerp.core.application.UomConversionService.LineUnit;
import com.inventoryerp.core.application.UomConversionService;
import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.num;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.procurement.domain.DebitNote;
import com.inventoryerp.procurement.domain.DebitNoteLine;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Debit notes (port of {@code debit-notes.service.ts}): outbound (to a customer, Dr AR / Cr revenue) and inbound
 * (from a supplier). An inbound {@code returned_goods} note is a return-to-vendor: stock leaves at cost and the note
 * owns the balanced GL (Dr AP / Cr Stock Valuation ± return variance).
 */
@Service
public class DebitNotesService {

    /** {@code CreateDebitNoteDto}. */
    public record CreateInput(String direction, String partnerId, String documentId, String reason, String reasonNote,
            String issueDate, String currencyCode, BigDecimal exchangeRate, String notes, String locationId,
            List<LineInput> lines) {}

    /** {@code DebitNoteLineDto}. */
    public record LineInput(String productId, String description, BigDecimal quantity, BigDecimal unitPrice,
            String taxId, String notes, String uomId) {

        public LineInput(String productId, String description, BigDecimal quantity, BigDecimal unitPrice,
                String taxId, String notes) {
            this(productId, description, quantity, unitPrice, taxId, notes, null);
        }
    }

    private final JdbcClient jdbc;
    private final UomConversionService units;
    private final SequenceService sequence;
    private final EventBus events;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final StockService stock;
    private final TransactionTemplate tx;

    public DebitNotesService(JdbcClient jdbc, SequenceService sequence, EventBus events, AuditService audit,
            ApprovalsService approvals, PostingService posting, AccountDeterminationService determination,
            StockService stock, PlatformTransactionManager txm, UomConversionService units) {
        this.jdbc = jdbc;
        this.units = units;
        this.sequence = sequence;
        this.events = events;
        this.audit = audit;
        this.approvals = approvals;
        this.posting = posting;
        this.determination = determination;
        this.stock = stock;
        this.tx = new TransactionTemplate(txm);
    }

    /** Draft note with lines; tax is computed at post time. */
    public Map<String, Object> create(CreateInput input) {
        String orgId = org();
        if (input.lines() == null || input.lines().isEmpty()) {
            throw ApiException.badRequest("At least one line required");
        }
        long partner = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(input.partnerId(), orgId).query(Long.class).single();
        if (partner == 0) {
            throw ApiException.notFound("Partner not found");
        }
        int year = Instant.now().atOffset(ZoneOffset.UTC).getYear();
        String prefix = "outbound".equals(input.direction()) ? "DBN-OUT-" + year + "-" : "DBN-IN-" + year + "-";
        String noteNumber = sequence.next("debitnote:" + year, prefix, 5);
        BigDecimal subtotal = Money.ZERO;
        for (LineInput ln : input.lines()) {
            subtotal = subtotal.add(ln.quantity().multiply(ln.unitPrice()));
        }
        BigDecimal sub = subtotal;
        // Returned quantities are in the line unit (default: the product's purchase unit), frozen with its factor.
        List<LineUnit> lineUnits = new ArrayList<>();
        for (LineInput ln : input.lines()) {
            lineUnits.add(units.resolveLineUnit(ln.productId(), ln.uomId(), UnitRole.PURCHASE));
        }
        Map<String, Object> note = tx.execute(st -> {
            String id = UUID.randomUUID().toString();
            Instant issueDate = ProcurementSql.date(input.issueDate());
            List<Object> params = new ArrayList<>(List.of(id, orgId, noteNumber, input.direction(), input.partnerId()));
            params.add(input.documentId());
            params.add(input.reason());
            params.add(input.reasonNote());
            if (issueDate != null) {
                params.add(SqlRows.ts(issueDate));
            }
            params.add(input.currencyCode() != null ? input.currencyCode() : "USD");
            params.add(input.exchangeRate() != null ? input.exchangeRate() : BigDecimal.ONE);
            params.add(sub);
            params.add(sub);
            params.add(input.notes());
            params.add(input.locationId() != null && !input.locationId().isEmpty()
                    ? JsonNodeText.of("locationId", input.locationId()) : "{}");
            params.add(user());
            Map<String, Object> created = SqlRows.one(DebitNote.class, jdbc.sql("INSERT INTO \"DebitNote\" (id,"
                            + " \"organizationId\", \"noteNumber\", direction, \"partnerId\", \"documentId\", reason,"
                            + " \"reasonNote\", \"issueDate\", \"currencyCode\", \"exchangeRate\", subtotal, \"taxAmount\","
                            + " \"totalAmount\", status, notes, \"customFields\", \"createdBy\", \"createdAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?::\"DebitNoteReason\", ?, " + (issueDate != null ? "?" : NOW)
                            + ", ?, ?, ?, 0, ?, 'draft', ?, ?::jsonb, ?, " + NOW + ", " + NOW + ") RETURNING *")
                    .params(params));
            List<Map<String, Object>> lines = new ArrayList<>();
            int idx = 0;
            for (LineInput ln : input.lines()) {
                BigDecimal lineSub = ln.quantity().multiply(ln.unitPrice());
                LineUnit unit = lineUnits.get(idx);
                lines.add(SqlRows.one(DebitNoteLine.class, jdbc.sql("INSERT INTO \"DebitNoteLine\" (id, \"organizationId\","
                                + " \"debitNoteId\", \"productId\", description, quantity, \"unitPrice\", \"taxId\", subtotal,"
                                + " total, \"lineNumber\", notes, \"uomId\", \"uomFactor\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?) RETURNING *")
                        .params(UUID.randomUUID().toString(), orgId, id, ln.productId(), ln.description(), ln.quantity(),
                                ln.unitPrice(), ln.taxId(), lineSub, lineSub, ++idx, ln.notes(), unit.uomId(),
                                unit.factor())));
            }
            created.put("lines", lines);
            return created;
        });
        audit.record(AuditInput.of("DebitNote", (String) note.get("id"), "create", null, Obj.of("noteNumber", noteNumber,
                "direction", input.direction())));
        events.publish("debit_note.created", Obj.of("organizationId", orgId, "noteId", note.get("id"), "noteNumber",
                noteNumber, "direction", input.direction()));
        return note;
    }

    /** Tiny JSON object writer for the {@code customFields} stash. */
    private static final class JsonNodeText {
        static String of(String key, String value) {
            return "{\"" + key + "\":" + tools.jackson.databind.json.JsonMapper.builder().build()
                    .writeValueAsString(value) + "}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadForPost(String id, String orgId) {
        Map<String, Object> note = SqlRows.one(DebitNote.class, jdbc.sql("SELECT * FROM \"DebitNote\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        if (note == null) {
            return null;
        }
        note.put("lines", lines(id));
        note.put("partner", SqlRows.one(Partner.class, jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(note.get("partnerId"), orgId)));
        return note;
    }

    private List<Map<String, Object>> lines(Object noteId) {
        return SqlRows.list(DebitNoteLine.class, jdbc.sql("SELECT * FROM \"DebitNoteLine\" WHERE \"debitNoteId\" = ?")
                .param(noteId));
    }

    private static String partnerCol(Map<String, Object> note, String col) {
        @SuppressWarnings("unchecked")
        Map<String, Object> partner = (Map<String, Object>) note.get("partner");
        return partner == null ? null : (String) partner.get(col);
    }

    /** Post behind the debit_note approval gate; returned goods from a supplier take the RTV path. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String id) {
        String orgId = org();
        Map<String, Object> note = loadForPost(id, orgId);
        if (note == null) {
            throw ApiException.notFound("Debit note not found");
        }
        if (!"draft".equals(note.get("status"))) {
            throw ApiException.badRequest("Cannot post debit note in status " + note.get("status"));
        }
        List<Map<String, Object>> noteLines = (List<Map<String, Object>>) note.get("lines");
        BigDecimal amount = Money.ZERO;
        for (Map<String, Object> ln : noteLines) {
            amount = amount.add(Money.dec(ln.get("subtotal")));
        }
        Map<String, Object> gate = approvals.checkOrRequestApproval("debit_note", id, Obj.of("amount",
                JsNumbers.js(amount), "direction", note.get("direction"), "reason", note.get("reason")));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.badRequest("Approval required before posting debit note. Pending approval request "
                    + gate.get("requestId") + ".");
        }
        boolean outbound = "outbound".equals(note.get("direction"));
        if (!outbound && "inbound".equals(note.get("direction")) && "returned_goods".equals(note.get("reason"))) {
            return postSupplierReturn(note);
        }

        String journalCode = outbound ? "SALES" : "PURCH";
        String counterAccount = outbound ? determination.receivableAccount(partnerCol(note, "receivableAccountId"))
                : determination.payableAccount(partnerCol(note, "payableAccountId"));
        List<Line> postingLines = new ArrayList<>();
        // ACC-4 (B8): each line's tax (from its taxId) is part of the note: input VAT on a supplier's note, output VAT
        // on a note to a customer; the AR / AP leg carries subtotal + tax.
        Map<String, BigDecimal> taxByAccount = new java.util.LinkedHashMap<>();
        BigDecimal noteTax = Money.ZERO;
        for (Map<String, Object> ln : noteLines) {
            BigDecimal tax = lineTax(ln, outbound, taxByAccount);
            noteTax = noteTax.add(tax);
            // Dr AR (outbound) / Cr AP (inbound) per line, tagged with the partner so the sub-ledger and ageing see it.
            String v = JsNumbers.dec(Money.dec(ln.get("subtotal")).add(tax));
            String partnerId = (String) note.get("partnerId");
            postingLines.add(outbound ? new Line(counterAccount, v, null, partnerId, null, null, null, null)
                    : new Line(counterAccount, null, v, partnerId, null, null, null, null));
        }
        taxByAccount.forEach((acc, amt) -> postingLines.add(outbound ? Line.credit(acc, JsNumbers.dec(amt), "Output tax")
                : Line.debit(acc, JsNumbers.dec(amt), "Input tax")));
        BigDecimal noteTotal = Money.dec(note.get("subtotal")).add(noteTax);
        BigDecimal taxTotal = noteTax;
        for (Map<String, Object> ln : noteLines) {
            String accountId = null;
            if (ln.get("productId") != null) {
                Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT c.\"incomeAccountId\", c.\"expenseAccountId\""
                        + " FROM \"Product\" p LEFT JOIN \"ProductCategory\" c ON c.id = p.\"categoryId\" WHERE p.id = ?"
                        + " AND p.\"organizationId\" = ?").params(ln.get("productId"), orgId));
                if (product != null) {
                    if (outbound && product.get("incomeAccountId") != null) {
                        accountId = (String) product.get("incomeAccountId");
                    } else if (!outbound && "inbound".equals(note.get("direction"))
                            && product.get("expenseAccountId") != null) {
                        accountId = (String) product.get("expenseAccountId");
                    }
                }
            }
            if (accountId == null) {
                accountId = outbound ? determination.mapped("sales_revenue") : determination.mapped("default_expense");
            }
            // B1 (post-parity): Nest credited the expense leg too, so an inbound note never balanced. A supplier's
            // debit note raises what we owe: Dr expense / Cr AP.
            Number amt = JsNumbers.js(Money.dec(ln.get("subtotal")));
            postingLines.add(outbound ? Line.credit(accountId, amt, null) : Line.debit(accountId, amt, null));
        }
        // The status claim, the journal and the flip land together; the claim serialises concurrent posts (P0-3).
        Map<String, Object> updated = tx.execute(st -> {
            Map<String, Object> row = markPosted(id, orgId);
            posting.post(PostingRequest.of(journalCode, (Instant) note.get("issueDate"),
                    (outbound ? "Debit note to customer" : "Debit note from supplier") + " " + note.get("noteNumber"),
                    "debit_note", id, "debit_note:" + id, postingLines));
            jdbc.sql("UPDATE \"DebitNote\" SET \"taxAmount\" = ?, \"totalAmount\" = ? WHERE id = ?")
                    .params(taxTotal, noteTotal, id).update();
            row.put("taxAmount", taxTotal);
            row.put("totalAmount", noteTotal);
            raiseReferencedDocument((String) note.get("documentId"), outbound, noteTotal);
            audit.recordInTx(AuditInput.of("DebitNote", id, "update", null, Obj.of("status", "posted")));
            return row;
        });
        events.publish("debit_note.posted", Obj.of("organizationId", orgId, "noteId", id, "direction",
                note.get("direction"), "amount", JsNumbers.dec(Money.dec(note.get("totalAmount")))));
        return updated;
    }

    /** Tax of one note line at its tax's rate (exclusive, rounded to cents), accumulated per GL account. */
    private BigDecimal lineTax(Map<String, Object> ln, boolean outbound, Map<String, BigDecimal> byAccount) {
        if (ln.get("taxId") == null) {
            return Money.ZERO;
        }
        Map<String, Object> tax = SqlRows.one(jdbc.sql("SELECT rate, \"accountId\", type::text AS type FROM \"Tax\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(ln.get("taxId"), org()));
        if (tax == null || "withholding".equals(tax.get("type"))) {
            return Money.ZERO;
        }
        BigDecimal amount = Money.dec(ln.get("subtotal")).multiply(Money.dec(tax.get("rate")))
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
        if (amount.signum() == 0) {
            return Money.ZERO;
        }
        String account = outbound
                ? (tax.get("accountId") != null ? (String) tax.get("accountId") : determination.mapped("tax_payable"))
                : determination.mapped("tax_receivable");
        byAccount.merge(account, amount, BigDecimal::add);
        return amount;
    }

    /**
     * ACC-4 (B8): a note raised against a posted bill (from the supplier) or invoice (to the customer) adds to that
     * document's open amount, so it is settled with it and the sub-ledger matches the GL.
     */
    private void raiseReferencedDocument(String documentId, boolean outbound, BigDecimal amount) {
        if (documentId == null || amount.signum() <= 0) {
            return;
        }
        jdbc.sql("UPDATE \"Document\" SET \"amountResidual\" = \"amountResidual\" + ?, status = CASE WHEN status::text ="
                        + " 'paid' THEN 'posted'::\"DocumentStatus\" ELSE status END, \"paymentStatus\" = CASE WHEN"
                        + " \"amountPaid\" > 0 THEN 'partial'::\"PaymentStatus\" ELSE 'not_paid'::\"PaymentStatus\" END,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"documentType\"::text"
                        + " = ? AND status::text IN ('posted', 'paid')")
                .params(amount, documentId, org(), outbound ? "sales_invoice" : "vendor_bill").update();
    }

    private Map<String, Object> markPosted(String id, String orgId) {
        Map<String, Object> row = SqlRows.one(DebitNote.class, jdbc.sql("UPDATE \"DebitNote\" SET status = 'posted',"
                        + " \"postedAt\" = " + NOW + ", \"postedById\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ? AND status = 'draft' AND \"deletedAt\" IS NULL RETURNING *")
                .params(user(), id, orgId));
        if (row == null) {
            // P0-3: only one concurrent post can claim the draft; the loser must not move stock or post again.
            throw ApiException.conflict("Debit note was already posted by another request");
        }
        return row;
    }

    /**
     * Return-to-vendor: ship stock back at cost (quantity-only issue) and post Dr AP / Cr Stock Valuation, with the
     * supplier-credit vs cost variance to stock adjustment income / expense. One transaction.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postSupplierReturn(Map<String, Object> note) {
        String orgId = org();
        String id = (String) note.get("id");
        Map<String, Object> updated = tx.execute(st -> {
            Map<String, Object> claimed = markPosted(id, orgId);
            Map<String, Object> location = resolveReturnLocation(note, orgId);
            String apAccount = determination.payableAccount(partnerCol(note, "payableAccountId"));
            String stockValAccount = determination.mapped("stock_valuation");
            List<Line> postingLines = new ArrayList<>();
            BigDecimal totalAp = Money.ZERO;
            for (Map<String, Object> ln : (List<Map<String, Object>>) note.get("lines")) {
                BigDecimal price = Money.dec(ln.get("subtotal"));
                BigDecimal qty = Money.dec(ln.get("quantity"));
                Map<String, Object> product = ln.get("productId") == null ? null : SqlRows.one(jdbc.sql("SELECT"
                        + " \"trackInventory\", \"productType\"::text AS \"productType\" FROM \"Product\" WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(ln.get("productId"), orgId));
                boolean isStock = product != null && Boolean.TRUE.equals(product.get("trackInventory"))
                        && ("stockable".equals(product.get("productType"))
                                || "consumable".equals(product.get("productType")));
                totalAp = totalAp.add(price);
                if (isStock && qty.signum() > 0 && location != null) {
                    StockMove move = StockMove.of((String) ln.get("productId"), (String) location.get("id"), qty)
                            .uom((String) ln.get("uomId"), ln.get("uomFactor") == null ? null
                                    : Money.dec(ln.get("uomFactor")))
                            .moveType("return_to_supplier").skipGl().source("debit_note", id)
                            .reference((String) note.get("noteNumber"));
                    Issued issueRes = stock.issue(move);
                    BigDecimal cost = issueRes == null ? Money.ZERO : Money.dec(issueRes.totalValue());
                    if (cost.signum() > 0) {
                        postingLines.add(Line.credit(stockValAccount, JsNumbers.dec(cost), null));
                    }
                    // Return variance: supplier credit (price) vs the stock cost removed.
                    BigDecimal variance = price.subtract(cost);
                    if (variance.signum() > 0) {
                        postingLines.add(Line.credit(determination.mapped("stock_adjustment_income"),
                                JsNumbers.dec(variance), null));
                    } else if (variance.signum() < 0) {
                        postingLines.add(Line.debit(determination.mapped("stock_adjustment_expense"),
                                JsNumbers.dec(variance.negate()), null));
                    }
                } else if (price.signum() > 0) {
                    // Non-stock line on an RTV (e.g. a credited service/fee): reduce expense.
                    postingLines.add(Line.credit(determination.mapped("default_expense"), JsNumbers.dec(price), null));
                }
            }
            if (totalAp.signum() > 0) {
                postingLines.addFirst(new Line(apAccount, JsNumbers.dec(totalAp), null, (String) note.get("partnerId"),
                        null, null, null, null));
            }
            posting.post(PostingRequest.of("PURCH", (Instant) note.get("issueDate"), "Return to vendor "
                    + note.get("noteNumber"), "debit_note", id, "debit_note_rtv:" + id, postingLines));
            return claimed;
        });
        audit.record(AuditInput.of("DebitNote", id, "update", null, Obj.of("status", "posted", "kind",
                "return_to_vendor")));
        events.publish("debit_note.posted", Obj.of("organizationId", orgId, "noteId", id, "direction",
                note.get("direction"), "amount", JsNumbers.dec(Money.dec(note.get("totalAmount")))));
        return updated;
    }

    /** RTV source warehouse: the stashed {@code customFields.locationId}, else the org's active warehouse. */
    private Map<String, Object> resolveReturnLocation(Map<String, Object> note, String orgId) {
        Object custom = note.get("customFields");
        String stashed = null;
        if (custom instanceof JsonNode node && node.has("locationId") && !node.get("locationId").isNull()) {
            stashed = node.get("locationId").asString();
        } else if (custom instanceof Map<?, ?> m && m.get("locationId") != null) {
            stashed = String.valueOf(m.get("locationId"));
        }
        if (stashed != null && !stashed.isEmpty()) {
            Map<String, Object> loc = SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryLocation\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(stashed, orgId));
            if (loc != null) {
                return loc;
            }
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'warehouse'"
                + " AND \"isActive\" = true AND \"deletedAt\" IS NULL LIMIT 1").param(orgId));
    }

    /** Cancel a draft (posted notes are reversed with a credit note). */
    public Map<String, Object> cancel(String id) {
        String orgId = org();
        Map<String, Object> note = SqlRows.one(DebitNote.class, jdbc.sql("SELECT * FROM \"DebitNote\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, orgId));
        if (note == null) {
            throw ApiException.notFound("Debit note not found");
        }
        if ("posted".equals(note.get("status"))) {
            throw ApiException.badRequest("Cannot cancel a posted debit note; reverse with a credit note");
        }
        Map<String, Object> row = SqlRows.one(DebitNote.class, jdbc.sql("UPDATE \"DebitNote\" SET status = 'cancelled',"
                + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " RETURNING *").params(id, orgId));
        if (row == null) {
            throw new IllegalStateException("No record was found for an update.");
        }
        return row;
    }

    /** Latest 200 notes (by issue date) with lines and the partner name. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String direction, String status) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = " \"organizationId\" = ? AND \"deletedAt\" IS NULL";
        if (direction != null && !direction.isEmpty()) {
            where += " AND direction = ?";
            params.add(direction);
        }
        if (status != null && !status.isEmpty()) {
            where += " AND status = ?";
            params.add(status);
        }
        List<Map<String, Object>> rows = SqlRows.list(DebitNote.class, jdbc.sql("SELECT * FROM \"DebitNote\" WHERE"
                + where + " ORDER BY \"issueDate\" DESC LIMIT 200").params(params));
        for (Map<String, Object> r : rows) {
            r.put("lines", lines(r.get("id")));
            r.put("partner", SqlRows.one(jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(r.get("partnerId"), org())));
        }
        return rows;
    }

    /** Note with lines and partner; {@code null} when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        return loadForPost(id, org());
    }
}
