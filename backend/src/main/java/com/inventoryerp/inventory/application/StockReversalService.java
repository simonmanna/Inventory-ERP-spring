package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.dec;
import static com.inventoryerp.inventory.application.InventoryLookups.str;
import static com.inventoryerp.inventory.application.StockService.NOW;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posted reversals (port of {@code stock-reversal.service.ts}): never edits history. Posts the inverse of every
 * ledger row a document wrote ({@code reversal_in}/{@code reversal_out}, referenceType {@code <kind>_reversal}),
 * mirrors every journal it posted line-for-line, and squares any value difference as a stock adjustment.
 */
@Service
public class StockReversalService {

    private record Spec(String table, String codeField, String label, String entity, String ledgerReferenceType,
            List<String> journalSourceTypes) {}

    /** Parameters of {@link #reverseLedgerSource}. */
    public record LedgerSource(String referenceType, String referenceId, String reversalSourceType,
            String reversalSourceId, List<String> journalSourceTypes, String notes, String legacyNotesPrefix) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PostingService posting;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final StockDocService docs;

    public StockReversalService(JdbcClient jdbc, AuditService audit, PostingService posting, StockService stock,
            StockPostingService stockPosting, StockDocService docs) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.posting = posting;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.docs = docs;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static Spec spec(String kind) {
        return switch (kind) {
            case "stock_out" -> new Spec("StockOut", "outCode", "Stock-out", "StockOut", "stock_out", List.of("stock_out"));
            case "waste" -> new Spec("WasteRecord", "wasteCode", "Waste record", "WasteRecord", "waste", List.of("waste"));
            case "stock_adjustment" -> new Spec("StockAdjustment", "adjCode", "Adjustment", "StockAdjustment",
                    "stock_adjustment", List.of("stock_adjustment", "stock_adjust"));
            default -> new Spec("StockTransfer", "transferCode", "Transfer", "StockTransfer", "stock_transfer",
                    List.of("stock_transfer"));
        };
    }

    /** Reverse a completed stock document. */
    @Transactional
    public Map<String, Object> reverseDocument(String kind, String id, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw ApiException.badRequest("A reason is required to reverse a posted document");
        }
        Spec spec = spec(kind);
        Map<String, Object> doc = header(spec, id);
        if (doc == null) {
            throw ApiException.notFound(spec.label() + " not found");
        }
        String code = str(doc, spec.codeField());
        if (doc.get("reversedAt") != null || "reversed".equals(str(doc, "status"))) {
            throw ApiException.conflict(spec.label() + " " + code + " is already reversed");
        }
        if (!"completed".equals(str(doc, "status")) || doc.get("postedAt") == null) {
            throw ApiException.badRequest("Only posted " + spec.label().toLowerCase() + "s can be reversed (this one is "
                    + doc.get("status") + "); cancel it instead");
        }
        int claim = jdbc.sql("UPDATE \"" + spec.table() + "\" SET status = 'reversed', \"reversedAt\" = " + NOW
                        + ", \"reversedById\" = ?, \"reversalReason\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"reversedAt\" IS NULL AND status = 'completed'")
                .params(TenantContext.userId(), reason.trim(), id, org()).update();
        if (claim == 0) {
            throw ApiException.conflict(spec.label() + " " + code + " was reversed concurrently");
        }
        String reversalSource = kind + "_reversal";
        String notes = "Reversal of " + code + ": " + reason.trim();
        if ("stock_transfer".equals(kind) && "transit".equals(str(doc, "mode"))) {
            reverseTransitTransfer(doc, code, notes);
        } else if ("stock_transfer".equals(kind)) {
            reverseTransferMovements(doc, code, notes);
        } else {
            reverseLedgerSource(new LedgerSource(spec.ledgerReferenceType(), code, reversalSource, code,
                    spec.journalSourceTypes(), notes, "stock_adjustment".equals(kind) ? code + " ·" : null));
        }
        audit.recordInTx(AuditInput.of(spec.entity(), id, "update", null,
                Obj.of("status", "reversed", "reversalReason", reason.trim(), "code", code)));
        return docs.withItems(header(spec, id));
    }

    private Map<String, Object> header(Spec spec, String id) {
        Class<?> type = switch (spec.table()) {
            case "StockOut" -> com.inventoryerp.inventory.domain.StockOut.class;
            case "WasteRecord" -> com.inventoryerp.inventory.domain.WasteRecord.class;
            case "StockAdjustment" -> com.inventoryerp.inventory.domain.StockAdjustment.class;
            default -> com.inventoryerp.inventory.domain.StockTransfer.class;
        };
        return SqlRows.one(type, jdbc.sql("SELECT * FROM \"" + spec.table() + "\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
    }

    private record Cell(String productId, String variantId, String locationId) {}

    private Map<Cell, BigDecimal> sum(List<Map<String, Object>> rows, boolean abs) {
        Map<Cell, BigDecimal> m = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Cell c = new Cell(str(r, "productId"), str(r, "variantId"), str(r, "locationId"));
            BigDecimal q = dec(r, "quantityChange");
            m.merge(c, abs ? q.abs() : q, BigDecimal::add);
        }
        return m;
    }

    private List<Map<String, Object>> ledgerRows(String referenceType, String referenceId, String type) {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                        + " AND \"referenceType\" = ? AND \"referenceId\" = ?" + (type != null ? " AND type::text = ?" : "")
                        + " ORDER BY \"createdAt\" ASC")
                .params(type != null ? List.of(org(), referenceType, referenceId, type)
                        : List.of(org(), referenceType, referenceId)));
    }

    private void reverseTransferMovements(Map<String, Object> doc, String code, String notes) {
        List<Map<String, Object>> rows = ledgerRows("stock_transfer", code, "transfer_in");
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Transfer " + code + " has no posted movements to reverse");
        }
        sum(rows, false).forEach((c, qty) -> stock.transfer(StockMove.transfer(c.productId(), c.locationId(),
                        str(doc, "fromLocId"), qty).variant(c.variantId()).source("stock_transfer_reversal", code)
                .notes(notes).people(null, TenantContext.userId())));
    }

    private void reverseTransitTransfer(Map<String, Object> doc, String code, String notes) {
        List<Map<String, Object>> received = ledgerRows("stock_transfer_receipt", code, "transfer_in");
        sum(received, true).forEach((c, qty) -> stock.transfer(StockMove.transfer(c.productId(), c.locationId(),
                        str(doc, "fromLocId"), qty).variant(c.variantId()).source("stock_transfer_reversal", code)
                .notes(notes).people(null, TenantContext.userId())));
        List<Map<String, Object>> losses = ledgerRows("stock_transfer_loss", code, null);
        if (!losses.isEmpty()) {
            reverseLedgerSource(new LedgerSource("stock_transfer_loss", code, "stock_transfer_reversal", code,
                    List.of("stock_transfer_loss"), notes, null));
            sum(losses, true).forEach((c, qty) -> stock.transfer(StockMove.transfer(c.productId(), c.locationId(),
                            str(doc, "fromLocId"), qty).variant(c.variantId()).source("stock_transfer_reversal", code)
                    .notes(notes).people(null, TenantContext.userId())));
        }
        if (received.isEmpty() && losses.isEmpty()) {
            throw ApiException.badRequest("Transfer " + code + " has no received or written-off quantities to reverse");
        }
    }

    /**
     * Post the inverse of every ledger row under (referenceType, referenceId), mirror its journals and square any
     * value difference. Runs in the caller's transaction (also used by goods-receipt reversal).
     */
    @Transactional
    public Map<String, Object> reverseLedgerSource(LedgerSource p) {
        String org = org();
        List<Object> params = new ArrayList<>(List.of(org, p.referenceType(), p.referenceId()));
        String legacy = "";
        if (p.legacyNotesPrefix() != null) {
            legacy = " OR (l.\"referenceType\" = 'stock_adjust' AND l.notes LIKE ? ESCAPE '\\')";
            params.add(p.legacyNotesPrefix().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.*, b.\"batchNumber\" AS \"_batchNumber\","
                        + " b.\"expiryDate\" AS \"_expiryDate\", s.\"serialNumber\" AS \"_serialNumber\","
                        + " p.\"batchTracking\" AS \"_batchTracking\" FROM \"InventoryLedger\" l"
                        + " LEFT JOIN \"InventoryBatch\" b ON b.id = l.\"batchId\""
                        + " LEFT JOIN \"InventorySerial\" s ON s.id = l.\"serialId\""
                        + " JOIN \"Product\" p ON p.id = l.\"productId\""
                        + " WHERE l.\"organizationId\" = ? AND ((l.\"referenceType\" = ? AND l.\"referenceId\" = ?)" + legacy
                        + ") ORDER BY l.\"createdAt\" ASC")
                .params(params));
        if (rows.isEmpty()) {
            throw ApiException.badRequest(p.referenceId() + " has no posted stock movements to reverse");
        }
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        sourceIds.add(p.referenceId());
        rows.forEach(r -> sourceIds.add(str(r, "ledgerCode")));
        List<Map<String, Object>> journals = SqlRows.list(jdbc.sql("SELECT e.*, j.code AS \"_journalCode\" FROM"
                        + " \"JournalEntry\" e JOIN \"Journal\" j ON j.id = e.\"journalId\" WHERE e.\"organizationId\" = :org"
                        + " AND e.status = 'posted' AND e.\"sourceType\" IN (:types) AND e.\"sourceId\" IN (:ids)"
                        + " AND e.\"reversalOfId\" IS NULL")
                .param("org", org).param("types", p.journalSourceTypes()).param("ids", List.copyOf(sourceIds)));

        Map<String, BigDecimal> glValue = new LinkedHashMap<>();
        Map<String, BigDecimal> moved = new LinkedHashMap<>();
        String user = TenantContext.userId();
        for (Map<String, Object> r : rows) {
            BigDecimal change = dec(r, "quantityChange");
            BigDecimal qty = change.abs();
            if (qty.signum() == 0) {
                continue;
            }
            BigDecimal original = dec(r, "totalValue");
            glValue.merge(str(r, "productId"), change.signum() > 0 ? original : original.negate(), BigDecimal::add);
            String batchNumber = str(r, "_batchNumber");
            List<String> serials = r.get("_serialNumber") != null ? List.of(str(r, "_serialNumber")) : null;
            if (change.signum() < 0) {
                boolean batchTracking = Boolean.TRUE.equals(r.get("_batchTracking"));
                StockMove m = StockMove.of(str(r, "productId"), str(r, "locationId"), qty).variant(str(r, "variantId"))
                        .source(p.reversalSourceType(), p.reversalSourceId()).notes(p.notes())
                        .people(str(r, "responsibleById"), user).serials(serials).unitCost(dec(r, "unitCost"))
                        .moveType("reversal_in")
                        .batch(batchTracking ? (batchNumber != null ? batchNumber : "REV-" + p.reversalSourceId()) : null)
                        .expiry(batchTracking && r.get("_expiryDate") != null
                                ? JsonConfig.toJsIso((Instant) r.get("_expiryDate")) : null);
                Received res = stock.receive(m);
                moved.merge(str(r, "productId"), res.totalValue().negate(), BigDecimal::add);
            } else {
                StockMove m = StockMove.of(str(r, "productId"), str(r, "locationId"), qty).variant(str(r, "variantId"))
                        .source(p.reversalSourceType(), p.reversalSourceId()).notes(p.notes())
                        .people(str(r, "responsibleById"), user).serials(serials).batch(batchNumber)
                        .moveType("reversal_out").strategy(batchNumber != null ? "MANUAL" : null).skipGl();
                Issued res = stock.issue(m);
                moved.merge(str(r, "productId"), res.totalValue(), BigDecimal::add);
            }
        }

        for (Map<String, Object> je : journals) {
            List<PostingRequest.Line> lines = new ArrayList<>();
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"JournalLine\" WHERE \"journalEntryId\" = ?")
                    .param(je.get("id")))) {
                BigDecimal debit = dec(l, "debit");
                BigDecimal credit = dec(l, "credit");
                if (debit.signum() <= 0 && credit.signum() <= 0) {
                    continue;
                }
                String desc = ("Reversal: " + (l.get("description") == null ? "" : l.get("description"))).trim();
                lines.add(new PostingRequest.Line(str(l, "accountId"),
                        credit.signum() > 0 ? JsNumbers.dec(credit) : null,
                        debit.signum() > 0 ? JsNumbers.dec(debit) : null, str(l, "partnerId"), desc, null, null, null));
            }
            posting.post(new PostingRequest(str(je, "_journalCode"), Instant.now(),
                    "Reversal of " + je.get("entryNumber") + " · " + p.notes(), null, null, p.reversalSourceType(),
                    p.reversalSourceId(), "reversal", "inventory:reversal:" + org + ":" + je.get("id"),
                    str(je, "branchId"), str(je, "costCenterId"), null, lines));
        }

        if (!journals.isEmpty()) {
            for (Map.Entry<String, BigDecimal> e : glValue.entrySet()) {
                BigDecimal diff = e.getValue().subtract(moved.getOrDefault(e.getKey(), Money.ZERO));
                if (diff.abs().compareTo(new BigDecimal("0.005")) < 0) {
                    continue;
                }
                stockPosting.postAdjustment(e.getKey(), diff.signum() > 0 ? BigDecimal.ONE : BigDecimal.ONE.negate(),
                        diff.abs(), Instant.now(), p.reversalSourceType(), p.reversalSourceId(),
                        "Reversal cost difference · " + p.referenceId());
            }
        }
        return Obj.of("rows", rows.size(), "journals", journals.size());
    }
}
