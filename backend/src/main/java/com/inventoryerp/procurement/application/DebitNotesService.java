package com.inventoryerp.procurement.application;

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
            String taxId, String notes) {}

    private final JdbcClient jdbc;
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
            StockService stock, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
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
        double subtotal = 0;
        for (LineInput ln : input.lines()) {
            subtotal += ln.quantity().doubleValue() * ln.unitPrice().doubleValue();
        }
        double sub = subtotal;
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
            params.add(num(sub));
            params.add(num(sub));
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
                BigDecimal lineSub = num(ln.quantity().doubleValue() * ln.unitPrice().doubleValue());
                lines.add(SqlRows.one(DebitNoteLine.class, jdbc.sql("INSERT INTO \"DebitNoteLine\" (id, \"organizationId\","
                                + " \"debitNoteId\", \"productId\", description, quantity, \"unitPrice\", \"taxId\", subtotal,"
                                + " total, \"lineNumber\", notes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *")
                        .params(UUID.randomUUID().toString(), orgId, id, ln.productId(), ln.description(), ln.quantity(),
                                ln.unitPrice(), ln.taxId(), lineSub, lineSub, ++idx, ln.notes())));
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
        note.put("partner", SqlRows.one(Partner.class, jdbc.sql("SELECT * FROM \"Partner\" WHERE id = ?")
                .param(note.get("partnerId"))));
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
        double amount = 0;
        for (Map<String, Object> ln : noteLines) {
            amount += Money.dec(ln.get("subtotal")).doubleValue();
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
        for (Map<String, Object> ln : noteLines) {
            Number amt = JsNumbers.js(Money.dec(ln.get("subtotal")));
            // Dr AR (outbound) / Cr AP (inbound) per line; the partner tag is not carried onto the posting line.
            postingLines.add(outbound ? Line.debit(counterAccount, amt, null) : Line.credit(counterAccount, amt, null));
        }
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
            postingLines.add(Line.credit(accountId, JsNumbers.js(Money.dec(ln.get("subtotal"))), null));
        }
        // The journal and the status flip land together.
        Map<String, Object> updated = tx.execute(st -> {
            posting.post(PostingRequest.of(journalCode, (Instant) note.get("issueDate"),
                    (outbound ? "Debit note to customer" : "Debit note from supplier") + " " + note.get("noteNumber"),
                    "debit_note", id, "debit_note:" + id, postingLines));
            Map<String, Object> row = markPosted(id, orgId);
            audit.recordInTx(AuditInput.of("DebitNote", id, "update", null, Obj.of("status", "posted")));
            return row;
        });
        events.publish("debit_note.posted", Obj.of("organizationId", orgId, "noteId", id, "direction",
                note.get("direction"), "amount", JsNumbers.toJsString(Money.dec(note.get("totalAmount")).doubleValue())));
        return updated;
    }

    private Map<String, Object> markPosted(String id, String orgId) {
        Map<String, Object> row = SqlRows.one(DebitNote.class, jdbc.sql("UPDATE \"DebitNote\" SET status = 'posted',"
                        + " \"postedAt\" = " + NOW + ", \"postedById\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL RETURNING *")
                .params(user(), id, orgId));
        if (row == null) {
            throw new IllegalStateException("No record was found for an update.");
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
                if (isStock && qty.doubleValue() > 0 && location != null) {
                    StockMove move = StockMove.of((String) ln.get("productId"), (String) location.get("id"), qty)
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
            return markPosted(id, orgId);
        });
        audit.record(AuditInput.of("DebitNote", id, "update", null, Obj.of("status", "posted", "kind",
                "return_to_vendor")));
        events.publish("debit_note.posted", Obj.of("organizationId", orgId, "noteId", id, "direction",
                note.get("direction"), "amount", JsNumbers.toJsString(Money.dec(note.get("totalAmount")).doubleValue())));
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
            r.put("partner", SqlRows.one(jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ?").param(r.get("partnerId"))));
        }
        return rows;
    }

    /** Note with lines and partner; {@code null} when missing. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        return loadForPost(id, org());
    }
}
