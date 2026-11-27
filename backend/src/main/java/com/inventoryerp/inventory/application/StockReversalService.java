package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.dec;
import static com.inventoryerp.inventory.application.InventoryLookups.str;
import static com.inventoryerp.inventory.application.StockService.NOW;

import com.inventoryerp.accounting.application.InventoryValuationService;
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

    /**
     * Parameters of {@link #reverseLedgerSource}. {@code shortfallLabel} non-null (purchase readiness H9a, 2026-11-27):
     * every inflow being taken back must still be on hand (and not reserved) at its location, in its lot / serial
     * units, whatever {@code inventory.allowNegativeStock} says; a shortfall is refused with
     * {@code "<shortfallLabel>: only N of M units of <product> are still in <location>. <shortfallAdvice>"}.
     */
    public record LedgerSource(String referenceType, String referenceId, String reversalSourceType,
            String reversalSourceId, List<String> journalSourceTypes, String notes, String legacyNotesPrefix,
            String shortfallLabel, String shortfallAdvice) {

        public LedgerSource(String referenceType, String referenceId, String reversalSourceType,
                String reversalSourceId, List<String> journalSourceTypes, String notes, String legacyNotesPrefix) {
            this(referenceType, referenceId, reversalSourceType, reversalSourceId, journalSourceTypes, notes,
                    legacyNotesPrefix, null, null);
        }
    }

    /**
     * The original issue of a return (R1): ledger rows of {@code referenceType} under any of {@code referenceIds}, or
     * the rows of the given {@code ledgerCodes}. Only outflow rows ({@code quantityChange < 0}) are returned against.
     */
    public record IssueSource(String referenceType, List<String> referenceIds, List<String> ledgerCodes) {

        public static IssueSource of(String referenceType, String referenceId) {
            return new IssueSource(referenceType, List.of(referenceId), null);
        }

        public static IssueSource of(String referenceType, List<String> referenceIds) {
            return new IssueSource(referenceType, List.copyOf(referenceIds), null);
        }

        public static IssueSource ledgerCodes(List<String> codes) {
            return new IssueSource(null, null, List.copyOf(codes));
        }
    }

    /**
     * One returned line: {@code quantity} of (product, variant) in {@code uomId} (snapshot {@code uomFactor} first;
     * base unit when both null), optionally only from the original rows of {@code issueLineId} (their
     * {@code sourceLineId}) and from the named {@code serialNumbers}; {@code sourceLineId} is recorded on the new rows.
     */
    public record ReturnLine(String productId, String variantId, BigDecimal quantity, String uomId,
            BigDecimal uomFactor, String issueLineId, List<String> serialNumbers, String sourceLineId) {

        public static ReturnLine of(String productId, String variantId, BigDecimal baseQuantity) {
            return new ReturnLine(productId, variantId, baseQuantity, null, null, null, null, null);
        }
    }

    /**
     * Parameters of {@link #returnFromSource}. {@code lines} null returns everything still returnable from the source.
     * {@code locationId} null returns each unit to the location it left from. {@code postRestockGl} posts the
     * restock (Dr Stock Valuation / Cr COGS at the original cost, as {@link StockService#receiveReturn}); false
     * leaves the GL to the caller. {@code moveType} null = {@code return_in}.
     */
    public record ReturnFromSource(IssueSource source, List<ReturnLine> lines, String sourceType, String sourceId,
            String reference, String notes, Instant date, String locationId, boolean postRestockGl, String moveType) {}

    /** One movement {@link #returnFromSource} posted, and the original row it brought back. */
    public record ReturnedRow(String ledgerCode, String reversesLedgerId, String productId, String variantId,
            String locationId, String batchNumber, String serialNumber, BigDecimal quantity, BigDecimal unitCost,
            BigDecimal totalValue) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PostingService posting;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final StockDocService docs;
    private final LocationScope scope;

    public StockReversalService(JdbcClient jdbc, AuditService audit, PostingService posting, StockService stock,
            StockPostingService stockPosting, StockDocService docs, LocationScope scope) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.posting = posting;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.docs = docs;
        this.scope = scope;
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

    /**
     * Reverse a completed stock document (the user route). The header is row-locked first and the caller's branch
     * scope is checked on it inside the moving transaction (R2): the document's location, for a transfer its source.
     */
    @Transactional
    public Map<String, Object> reverseDocument(String kind, String id, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw ApiException.badRequest("A reason is required to reverse a posted document");
        }
        Spec spec = spec(kind);
        jdbc.sql("SELECT id FROM \"" + spec.table() + "\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, org()).query().listOfRows();
        Map<String, Object> doc = header(spec, id);
        if (doc == null) {
            throw ApiException.notFound(spec.label() + " not found");
        }
        scope.assertAllowed(str(doc, "StockTransfer".equals(spec.table()) ? "fromLocId" : "locationId"));
        String code = str(doc, spec.codeField());
        if (doc.get("reversedAt") != null || "reversed".equals(str(doc, "status"))) {
            throw ApiException.conflict(spec.label() + " " + code + " is already reversed");
        }
        if (!"completed".equals(str(doc, "status")) || doc.get("postedAt") == null) {
            throw ApiException.badRequest("Only posted " + spec.label().toLowerCase() + "s can be reversed (this one is "
                    + doc.get("status") + "); cancel it instead");
        }
        assertNoLivePickOrder(kind, id, code, spec.label());
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

    /**
     * R4: a stock-out or transfer that a pick order not yet shipped or cancelled is sourced to stays as posted: its
     * goods are still being picked against that issue, and reversing it underneath would leave the pick order shipping
     * goods the ledger has taken back.
     */
    private void assertNoLivePickOrder(String kind, String id, String code, String label) {
        String sourceType = switch (kind) {
            case "stock_out" -> "stock_out";
            case "stock_transfer" -> "transfer";
            default -> null;
        };
        if (sourceType == null) {
            return;
        }
        List<String> live = jdbc.sql("SELECT code FROM \"WarehousePickOrder\" WHERE \"organizationId\" = ?"
                        + " AND \"sourceType\" = ? AND (\"sourceId\" = ? OR \"sourceRef\" = ?) AND status IN "
                        + WarehouseStore.OPEN + " ORDER BY code")
                .params(org(), sourceType, id, code).query(String.class).list();
        if (!live.isEmpty()) {
            throw ApiException.badRequest(label + " " + code + " has open pick order(s) " + String.join(", ", live)
                    + "; ship or cancel them before reversing it");
        }
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

    private static Cell cell(Map<String, Object> ledgerRow) {
        return new Cell(str(ledgerRow, "productId"), str(ledgerRow, "variantId"), str(ledgerRow, "locationId"));
    }

    private static final java.util.Comparator<Cell> CELL_ORDER = java.util.Comparator.comparing(Cell::productId)
            .thenComparing(c -> c.variantId() == null ? "" : c.variantId()).thenComparing(Cell::locationId);

    /** Quantity per cell, cells in the stock engine's lock order (product, variant, location) to avoid deadlocks. */
    private Map<Cell, BigDecimal> sum(List<Map<String, Object>> rows, boolean abs) {
        Map<Cell, BigDecimal> m = new java.util.TreeMap<>(CELL_ORDER);
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
                        + " ORDER BY \"createdAt\" ASC, seq ASC")
                .params(type != null ? List.of(org(), referenceType, referenceId, type)
                        : List.of(org(), referenceType, referenceId)));
    }

    private void reverseTransferMovements(Map<String, Object> doc, String code, String notes) {
        List<Map<String, Object>> rows = ledgerRows("stock_transfer", code, "transfer_in");
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Transfer " + code + " has no posted movements to reverse");
        }
        Map<Cell, BigDecimal> cells = sum(rows, false);
        lockReversalCells(cells.keySet(), List.of(), str(doc, "fromLocId"));
        Map<Cell, List<List<String>>> arrived = arrivedLayers(str(doc, "id"));
        cells.forEach((c, qty) -> stock.transfer(preferArrived(StockMove.transfer(c.productId(), c.locationId(),
                        str(doc, "fromLocId"), qty).variant(c.variantId()).source("stock_transfer_reversal", code)
                .notes(notes).people(null, TenantContext.userId()), arrived, c)));
    }

    /**
     * Hardening R5b (P2-1): every quant a transfer reversal will move — the received cells, the transit cells of the
     * written-off units and the source cells they all go back to — locked up front in the engine's order, so the
     * moves below never take a lock below one they hold (the loss pass used to restart from the first product).
     */
    private void lockReversalCells(java.util.Collection<Cell> received, List<Map<String, Object>> losses,
            String fromLocId) {
        List<StockService.Cell> all = new ArrayList<>();
        received.forEach(c -> {
            all.add(new StockService.Cell(c.productId(), c.variantId(), c.locationId()));
            all.add(new StockService.Cell(c.productId(), c.variantId(), fromLocId));
        });
        losses.forEach(r -> {
            all.add(new StockService.Cell(str(r, "productId"), str(r, "variantId"), str(r, "locationId")));
            all.add(new StockService.Cell(str(r, "productId"), str(r, "variantId"), fromLocId));
        });
        stock.lockCells(all);
    }

    /**
     * Hardening R5b (P2-2): the lots a transfer created at a destination and the serial units it moved, per received
     * cell ({@code [lot ids, serial ids]}), from the transfer's own layers (recorded since R5b; empty for an older
     * transfer, which keeps the oldest-first behaviour). A lot is listed under the cell it was created in.
     */
    private Map<Cell, List<List<String>>> arrivedLayers(String transferId) {
        Map<Cell, List<List<String>>> out = new java.util.HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"batchId\", l.\"serialId\", i.\"productId\","
                        + " i.\"variantId\", b.\"locationId\" AS \"lotLocationId\" FROM \"StockTransferLayer\" l"
                        + " JOIN \"StockTransferItem\" i ON i.id = l.\"transferItemId\""
                        + " LEFT JOIN \"InventoryBatch\" b ON b.id = l.\"batchId\" AND b.\"organizationId\" = l.\"organizationId\""
                        + " WHERE l.\"organizationId\" = ? AND l.\"transferId\" = ? ORDER BY l.\"createdAt\"")
                .params(org(), transferId))) {
            if (r.get("batchId") != null && r.get("lotLocationId") != null) {
                out.computeIfAbsent(new Cell(str(r, "productId"), str(r, "variantId"), str(r, "lotLocationId")),
                        k -> List.of(new ArrayList<>(), new ArrayList<>())).get(0).add(str(r, "batchId"));
            } else if (r.get("serialId") != null) {
                // A serial unit keeps its id wherever it moves: listed for the product, matched at any location.
                out.computeIfAbsent(new Cell(str(r, "productId"), str(r, "variantId"), null),
                        k -> List.of(new ArrayList<>(), new ArrayList<>())).get(1).add(str(r, "serialId"));
            }
        }
        return out;
    }

    /** Prefer the transfer's own lots at the cell and its serial units (then any other, oldest first). */
    private static StockMove preferArrived(StockMove move, Map<Cell, List<List<String>>> arrived, Cell c) {
        List<List<String>> lots = arrived.get(c);
        List<List<String>> serials = arrived.get(new Cell(c.productId(), c.variantId(), null));
        if (lots == null && serials == null) {
            return move;
        }
        return move.prefer(lots == null ? null : lots.get(0), serials == null ? null : serials.get(1));
    }

    private void reverseTransitTransfer(Map<String, Object> doc, String code, String notes) {
        List<Map<String, Object>> received = ledgerRows("stock_transfer_receipt", code, "transfer_in");
        List<Map<String, Object>> losses = ledgerRows("stock_transfer_loss", code, null);
        Map<Cell, BigDecimal> receivedCells = sum(received, true);
        lockReversalCells(receivedCells.keySet(), losses, str(doc, "fromLocId"));
        Map<Cell, List<List<String>>> arrived = arrivedLayers(str(doc, "id"));
        receivedCells.forEach((c, qty) -> stock.transfer(preferArrived(StockMove.transfer(c.productId(),
                        c.locationId(), str(doc, "fromLocId"), qty).variant(c.variantId())
                .source("stock_transfer_reversal", code).notes(notes).people(null, TenantContext.userId()),
                arrived, c)));
        if (!losses.isEmpty()) {
            reverseLedgerSource(new LedgerSource("stock_transfer_loss", code, "stock_transfer_reversal", code,
                    List.of("stock_transfer_loss"), notes, null));
            // Gaps h-00 F9: move back the units the reversal just restored in transit (their lots and serials, at the
            // cost they were written off at), not the oldest lots there, which can be another transfer's in transit.
            List<Map<String, Object>> restored = ledgerRows("stock_transfer_reversal", code, "reversal_in");
            sum(losses, true).forEach((c, qty) -> {
                List<String> lots = new ArrayList<>();
                restored.stream().filter(r -> c.equals(cell(r)) && r.get("batchId") != null)
                        .forEach(r -> lots.add(str(r, "batchId")));
                List<String> serials = new ArrayList<>();
                BigDecimal value = Money.ZERO;
                for (Map<String, Object> r : losses) {
                    if (c.equals(cell(r))) {
                        value = value.add(dec(r, "totalValue").abs());
                        if (r.get("serialId") != null) {
                            serials.add(str(r, "serialId"));
                        }
                    }
                }
                stock.transfer(StockMove.transfer(c.productId(), c.locationId(), str(doc, "fromLocId"), qty)
                        .variant(c.variantId()).source("stock_transfer_reversal", code).notes(notes)
                        .people(null, TenantContext.userId()).pin(lots, serials, Money.div(value, qty)));
            });
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
                        + ") ORDER BY l.\"createdAt\" ASC, l.seq ASC")
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
                        + " AND e.\"reversalOfId\" IS NULL"
                        // Negative-stock cost corrections square stock against GL at the time; the inverse moves post
                        // their own, so mirroring them would put the two apart again.
                        + " AND (e.\"postingKey\" IS NULL OR e.\"postingKey\" NOT LIKE :corr)")
                .param("org", org).param("types", p.journalSourceTypes()).param("ids", List.copyOf(sourceIds))
                .param("corr", StockService.COST_CORRECTION_KEY + "%"));

        Map<String, BigDecimal> glValue = new LinkedHashMap<>();
        Map<String, BigDecimal> moved = new LinkedHashMap<>();
        String user = TenantContext.userId();
        // Inverse moves in the engine's lock order (product, variant, location; then the original order).
        List<Map<String, Object>> inLockOrder = new ArrayList<>(rows);
        // H9a: what each cell gives back in total, and what this reversal already took from it.
        boolean strictOnHand = p.shortfallLabel() != null;
        Map<String, BigDecimal> toTakeBack = new LinkedHashMap<>();
        Map<String, BigDecimal> takenBack = new LinkedHashMap<>();
        if (strictOnHand) {
            rows.stream().filter(r -> dec(r, "quantityChange").signum() > 0)
                    .forEach(r -> toTakeBack.merge(cellKey(r), dec(r, "quantityChange"), BigDecimal::add));
        }
        inLockOrder.sort(java.util.Comparator.comparing((Map<String, Object> r) -> str(r, "productId"))
                .thenComparing(r -> r.get("variantId") == null ? "" : str(r, "variantId"))
                .thenComparing(r -> str(r, "locationId")));
        for (Map<String, Object> r : inLockOrder) {
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
                        .moveType("reversal_in").reverses(str(r, "id"))
                        .batch(batchTracking ? (batchNumber != null ? batchNumber : "REV-" + p.reversalSourceId()) : null)
                        .expiry(batchTracking && r.get("_expiryDate") != null
                                ? JsonConfig.toJsIso((Instant) r.get("_expiryDate")) : null);
                Received res = stock.receive(m);
                moved.merge(str(r, "productId"), res.totalValue().negate(), BigDecimal::add);
            } else {
                StockMove m = StockMove.of(str(r, "productId"), str(r, "locationId"), qty).variant(str(r, "variantId"))
                        .source(p.reversalSourceType(), p.reversalSourceId()).notes(p.notes())
                        .people(str(r, "responsibleById"), user).serials(serials).batch(batchNumber)
                        .moveType("reversal_out").strategy(batchNumber != null ? "MANUAL" : null).skipGl()
                        .reverses(str(r, "id")).requireAvailable(strictOnHand);
                Issued res;
                if (strictOnHand) {
                    String key = cellKey(r);
                    try {
                        res = stock.issue(m);
                    } catch (ApiException e) {
                        String msg = e.getMessage() == null ? "" : e.getMessage();
                        if (!msg.startsWith("Insufficient stock") && !msg.startsWith("Serial number(s) not available")) {
                            throw e;
                        }
                        // The quant locks are still held by this transaction: the figures below are current.
                        throw shortfall(p, r, batchNumber, takenBack.getOrDefault(key, Money.ZERO),
                                toTakeBack.getOrDefault(key, qty), Money.ZERO);
                    }
                    takenBack.merge(key, qty, BigDecimal::add);
                    BigDecimal reserved = activeReservations(org, r);
                    if (reserved.signum() > 0) {
                        BigDecimal left = cellOnHand(org, r);
                        if (left.compareTo(reserved) < 0) {
                            throw shortfall(p, r, null, takenBack.get(key), toTakeBack.getOrDefault(key, qty), reserved);
                        }
                    }
                } else {
                    res = stock.issue(m);
                }
                moved.merge(str(r, "productId"), res.totalValue(), BigDecimal::add);
            }
        }

        String baseCurrency = journals.isEmpty() ? null : posting.baseCurrency();
        // R3: the product of an entry posted before stock lines carried it — its movement (ledger code), else the only
        // product the reversed rows move.
        Map<String, String> productByCode = new LinkedHashMap<>();
        rows.forEach(r -> productByCode.put(str(r, "ledgerCode"), str(r, "productId")));
        String onlyProduct = glValue.size() == 1 ? glValue.keySet().iterator().next() : null;
        for (Map<String, Object> je : journals) {
            List<PostingRequest.Line> lines = new ArrayList<>();
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT * FROM \"JournalLine\" WHERE \"journalEntryId\" = ?")
                    .param(je.get("id")))) {
                PostingRequest.Line mirrored = mirrorLine(l, baseCurrency);
                if (mirrored != null) {
                    lines.add(mirrored);
                }
            }
            String entryProduct = productByCode.getOrDefault(str(je, "sourceId"), onlyProduct);
            lines = stockPosting.stamp(lines, entryProduct, null);
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

    private static String cellKey(Map<String, Object> r) {
        return str(r, "productId") + "|" + (r.get("variantId") == null ? "" : str(r, "variantId")) + "|"
                + str(r, "locationId");
    }

    private BigDecimal cellOnHand(String org, Map<String, Object> r) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND \"variantKey\" = COALESCE(?, '') AND \"locationId\" = ?")
                .params(org, str(r, "productId"), str(r, "variantId"), str(r, "locationId")).query(BigDecimal.class)
                .single();
    }

    private BigDecimal activeReservations(String org, Map<String, Object> r) {
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND \"variantKey\" = COALESCE(?, '') AND \"locationId\" = ? AND"
                        + " status = 'active'")
                .params(org, str(r, "productId"), str(r, "variantId"), str(r, "locationId")).query(BigDecimal.class)
                .single();
    }

    /**
     * H9a: the refusal of a strict reversal. N = what is still in the cell now (in the row's lot when it has one) plus
     * what this reversal already took from it ({@code taken}), less what is {@code reserved} there.
     */
    private ApiException shortfall(LedgerSource p, Map<String, Object> r, String batchNumber, BigDecimal taken,
            BigDecimal total, BigDecimal reserved) {
        String org = org();
        BigDecimal here = cellOnHand(org, r);
        if (batchNumber != null) {
            here = here.min(jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE"
                            + " \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND"
                            + " \"locationId\" = ? AND \"batchNumber\" = ? AND quantity > 0 AND \"isActive\" = true")
                    .params(org, str(r, "productId"), str(r, "variantId"), str(r, "locationId"), batchNumber)
                    .query(BigDecimal.class).single());
        }
        if (r.get("serialId") != null) {
            here = jdbc.sql("SELECT count(*) FROM \"InventorySerial\" WHERE id = ? AND \"organizationId\" = ? AND"
                            + " \"locationId\" = ? AND status = 'in_stock'")
                    .params(str(r, "serialId"), org, str(r, "locationId")).query(Long.class).single() > 0
                    ? BigDecimal.ONE : Money.ZERO;
        }
        BigDecimal n = here.add(taken).subtract(reserved).min(total).max(Money.ZERO);
        String product = jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?").param(str(r, "productId"))
                .query(String.class).optional().orElse(str(r, "productId"));
        String variant = r.get("variantId") == null ? null : jdbc.sql("SELECT name FROM \"ProductVariant\" WHERE id = ?")
                .param(str(r, "variantId")).query(String.class).optional().orElse(null);
        String location = jdbc.sql("SELECT name FROM \"InventoryLocation\" WHERE id = ?").param(str(r, "locationId"))
                .query(String.class).optional().orElse(str(r, "locationId"));
        String what = product + (variant == null || variant.isBlank() ? "" : " (" + variant + ")")
                + (batchNumber == null ? "" : ", lot " + batchNumber)
                + (r.get("_serialNumber") == null ? "" : ", serial " + str(r, "_serialNumber"));
        String held = reserved.signum() > 0 ? " (" + JsNumbers.dec(reserved) + " of what is there is reserved)" : "";
        return ApiException.badRequest(p.shortfallLabel() + ": only " + JsNumbers.dec(n) + " of " + JsNumbers.dec(total)
                + " units of " + what + " are still in " + location + held + "."
                + (p.shortfallAdvice() == null ? "" : " " + p.shortfallAdvice()));
    }

    /**
     * R1 (owner-approved hardening, 2026-11-23): return goods against their original issue. The original outflow rows
     * of {@code p.source()} are the authority: each returned unit comes back with the lot (into the very lot row when
     * it returns to the location it left — never a new lot), expiry, serial, location and unit cost of the row it left
     * on, and the new row names that row ({@code reversesLedgerId}). The requested quantity is allocated across the
     * rows in their original order, net of what earlier returns already brought back; asking for more is refused
     * ({@code 400 "Cannot return more than was issued"}), as is a source without issue rows. The original rows are
     * locked first, so two returns of the same issue cannot both take the same units. Runs in the caller's
     * transaction; moves are posted in the engine's lock order.
     */
    @Transactional
    public List<ReturnedRow> returnFromSource(ReturnFromSource p) {
        String org = org();
        List<Map<String, Object>> rows = issueRows(org, p.source());
        if (rows.isEmpty()) {
            throw ApiException.badRequest("No stock issue was found to return against");
        }
        jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND id = ANY(?::text[]) ORDER BY id"
                        + " FOR UPDATE")
                .params(org, rows.stream().map(r -> str(r, "id")).toArray(String[]::new)).query().listOfRows();
        Map<String, BigDecimal> remaining = returnable(org, rows);
        List<Object[]> allocations = new ArrayList<>(); // {row, base quantity, trace unit, trace factor, line}
        if (p.lines() == null) {
            for (Map<String, Object> r : rows) {
                BigDecimal left = remaining.get(str(r, "id"));
                if (left.signum() > 0) {
                    allocations.add(new Object[] {r, left, null, null, null});
                }
            }
        } else {
            for (ReturnLine line : p.lines()) {
                if (line.quantity() == null || line.quantity().signum() <= 0) {
                    continue;
                }
                BigDecimal need = stock.returnBaseQuantity(line.productId(), line.quantity(), line.uomId(),
                        line.uomFactor());
                BigDecimal factor = line.uomId() == null && line.uomFactor() == null ? null
                        : line.uomFactor() != null ? line.uomFactor()
                                : need.divide(line.quantity(), 10, java.math.RoundingMode.HALF_UP);
                List<Map<String, Object>> candidates = rows.stream()
                        .filter(r -> line.productId().equals(str(r, "productId"))
                                && java.util.Objects.equals(line.variantId(), str(r, "variantId"))
                                && (line.issueLineId() == null || line.issueLineId().equals(str(r, "sourceLineId"))))
                        .toList();
                for (String serial : line.serialNumbers() == null ? List.<String>of() : line.serialNumbers()) {
                    Map<String, Object> hit = candidates.stream().filter(r -> serial.equals(str(r, "_serialNumber"))
                            && remaining.get(str(r, "id")).signum() > 0).findFirst().orElse(null);
                    if (hit == null) {
                        throw ApiException.badRequest("Serial number " + serial
                                + " was not issued by this document or is already returned");
                    }
                    BigDecimal take = Money.min(need, remaining.get(str(hit, "id")));
                    remaining.merge(str(hit, "id"), take.negate(), BigDecimal::add);
                    need = need.subtract(take);
                    allocations.add(new Object[] {hit, take, line.uomId(), factor, line});
                }
                for (Map<String, Object> r : candidates) {
                    if (need.signum() <= 0) {
                        break;
                    }
                    BigDecimal left = remaining.get(str(r, "id"));
                    if (left.signum() <= 0) {
                        continue;
                    }
                    BigDecimal take = Money.min(need, left);
                    remaining.merge(str(r, "id"), take.negate(), BigDecimal::add);
                    need = need.subtract(take);
                    allocations.add(new Object[] {r, take, line.uomId(), factor, line});
                }
                if (need.signum() > 0) {
                    throw ApiException.badRequest("Cannot return more than was issued");
                }
            }
        }
        // Receipts in the engine's lock order (product, variant, destination location; then the original order).
        List<Object[]> ordered = new ArrayList<>(allocations);
        ordered.sort(java.util.Comparator.comparing((Object[] a) -> str(row(a), "productId"))
                .thenComparing(a -> row(a).get("variantId") == null ? "" : str(row(a), "variantId"))
                .thenComparing(a -> p.locationId() != null ? p.locationId() : str(row(a), "locationId")));
        List<ReturnedRow> out = new ArrayList<>();
        for (Object[] a : ordered) {
            Map<String, Object> r = row(a);
            BigDecimal qty = (BigDecimal) a[1];
            ReturnLine line = (ReturnLine) a[4];
            String location = p.locationId() != null ? p.locationId() : str(r, "locationId");
            boolean batchTracking = Boolean.TRUE.equals(r.get("_batchTracking"));
            String batchNumber = batchTracking ? (r.get("_batchNumber") != null ? str(r, "_batchNumber")
                    : "RET-" + (p.sourceId() != null ? p.sourceId() : str(r, "ledgerCode"))) : null;
            String serial = str(r, "_serialNumber");
            StockMove m = StockMove.of(str(r, "productId"), location, qty).variant(str(r, "variantId"))
                    .unitCost(dec(r, "unitCost")).source(p.sourceType(), p.sourceId()).reference(p.reference())
                    .notes(p.notes()).date(p.date()).reverses(str(r, "id"))
                    .sourceLine(line == null ? null : line.sourceLineId()).batch(batchNumber)
                    .expiry(batchTracking && r.get("_expiryDate") != null
                            ? JsonConfig.toJsIso((Instant) r.get("_expiryDate")) : null)
                    .serials(serial != null ? List.of(serial) : null);
            m.moveType = p.moveType() != null ? p.moveType() : "return_in";
            if (batchTracking && r.get("batchId") != null && location.equals(str(r, "locationId"))) {
                m.returnBatchId = str(r, "batchId");
            }
            if (a[3] != null) {
                m.traceUomId = (String) a[2];
                m.traceFactor = (BigDecimal) a[3];
            }
            Received res;
            if (p.postRestockGl()) {
                res = stock.receiveReturn(m);
            } else {
                res = stock.receive(m);
                // R3 (P1-9): the caller reverses the original cost in the GL; a STANDARD unit comes back at standard.
                BigDecimal original = dec(r, "unitCost").multiply(qty);
                stock.postStandardReturnVariance(str(r, "productId"), qty, original, res,
                        stockPosting.stockAccount(str(r, "productId"), original), p.date(), p.sourceType(),
                        p.sourceId());
            }
            out.add(new ReturnedRow(res.ledgerCode(), str(r, "id"), str(r, "productId"), str(r, "variantId"), location,
                    batchNumber, serial, qty, dec(r, "unitCost"), dec(r, "unitCost").multiply(qty)));
        }
        return out;
    }

    /** What a source can still return of one product and variant, in base units ({@link #returnableByProduct}). */
    public record Returnable(String productId, String variantId, BigDecimal quantity) {}

    /**
     * R4: per product and variant, what is still returnable from the issue rows of {@code src} (issued, net of earlier
     * returns as {@link #returnFromSource} counts them), in base units; products with nothing left are omitted.
     */
    @Transactional
    public List<Returnable> returnableByProduct(IssueSource src) {
        String org = org();
        List<Map<String, Object>> rows = issueRows(org, src);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, BigDecimal> remaining = returnable(org, rows);
        Map<List<String>, BigDecimal> byProduct = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byProduct.merge(java.util.Arrays.asList(str(r, "productId"), str(r, "variantId")), remaining.get(str(r, "id")),
                    BigDecimal::add);
        }
        List<Returnable> out = new ArrayList<>();
        byProduct.forEach((k, q) -> {
            if (q.signum() > 0) {
                out.add(new Returnable(k.get(0), k.get(1), q));
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Object[] allocation) {
        return (Map<String, Object>) allocation[0];
    }

    /** The outflow rows of a return source with their lot, expiry and serial, in original order. */
    private List<Map<String, Object>> issueRows(String org, IssueSource src) {
        List<Object> params = new ArrayList<>(List.of(org));
        String where;
        if (src.ledgerCodes() != null) {
            if (src.ledgerCodes().isEmpty()) {
                return List.of();
            }
            where = "l.\"ledgerCode\" = ANY(?::text[])";
            params.add(src.ledgerCodes().toArray(String[]::new));
        } else {
            if (src.referenceIds() == null || src.referenceIds().isEmpty()) {
                return List.of();
            }
            where = "l.\"referenceType\" = ? AND l.\"referenceId\" = ANY(?::text[])";
            params.add(src.referenceType());
            params.add(src.referenceIds().toArray(String[]::new));
        }
        return SqlRows.list(jdbc.sql("SELECT l.*, b.\"batchNumber\" AS \"_batchNumber\", b.\"expiryDate\" AS"
                        + " \"_expiryDate\", s.\"serialNumber\" AS \"_serialNumber\", p.\"batchTracking\" AS"
                        + " \"_batchTracking\" FROM \"InventoryLedger\" l"
                        + " LEFT JOIN \"InventoryBatch\" b ON b.id = l.\"batchId\""
                        + " LEFT JOIN \"InventorySerial\" s ON s.id = l.\"serialId\""
                        + " JOIN \"Product\" p ON p.id = l.\"productId\""
                        + " WHERE l.\"organizationId\" = ? AND " + where + " AND l.\"quantityChange\" < 0"
                        + " ORDER BY l.\"createdAt\" ASC, l.seq ASC")
                .params(params));
    }

    /**
     * Per original row, what is still returnable: its quantity minus what rows naming it brought back, net of the rows
     * that in turn reversed those (a cancelled credit note gives its units back to the issue).
     */
    private Map<String, BigDecimal> returnable(String org, List<Map<String, Object>> rows) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        rows.forEach(r -> out.put(str(r, "id"), dec(r, "quantityChange").abs()));
        String[] ids = out.keySet().toArray(String[]::new);
        jdbc.sql("SELECT r.\"reversesLedgerId\" AS id, SUM(r.\"quantityChange\" - COALESCE(u.q, 0)) AS returned"
                        + " FROM \"InventoryLedger\" r LEFT JOIN (SELECT x.\"reversesLedgerId\" AS rid,"
                        + " SUM(-x.\"quantityChange\") AS q FROM \"InventoryLedger\" x WHERE x.\"organizationId\" = ?"
                        + " AND x.\"quantityChange\" < 0 AND x.\"reversesLedgerId\" IN"
                        + " (SELECT y.id FROM \"InventoryLedger\" y"
                        + " WHERE y.\"organizationId\" = ? AND y.\"reversesLedgerId\" = ANY(?::text[])) GROUP BY 1) u"
                        + " ON u.rid = r.id WHERE r.\"organizationId\" = ? AND r.\"reversesLedgerId\" = ANY(?::text[])"
                        + " AND r.\"quantityChange\" > 0 GROUP BY 1")
                .params(org, org, ids, org, ids)
                .query((rs, n) -> out.merge(rs.getString("id"), rs.getBigDecimal("returned").negate(),
                        BigDecimal::add))
                .list();
        out.replaceAll((k, v) -> v.max(BigDecimal.ZERO));
        return out;
    }

    /**
     * The stock product / location keys (R3) of a stored line's {@code dimensions} (JSON tree or map); null when none.
     * Other dimensions are not carried to the reversal (as before), so a value retired since cannot block it.
     */
    static Map<String, Object> dimensions(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of(InventoryValuationService.PRODUCT_DIMENSION,
                InventoryValuationService.LOCATION_DIMENSION)) {
            Object v = raw instanceof Map<?, ?> m ? m.get(key)
                    : raw instanceof tools.jackson.databind.JsonNode n && n.isObject() && n.get(key) != null
                            && n.get(key).isValueNode() && !n.get(key).isNull() ? n.get(key).asString() : null;
            if (v != null) {
                out.put(key, String.valueOf(v));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * The inverse of one journal line, or {@code null} for an empty line. Gap fix h03-F3: amounts are the line's
     * functional (base) amounts; a line carried in a foreign currency stores its debit/credit in that currency, so
     * re-posting those as base left the reversal unbalanced (a foreign GRN could not be reversed). Such a line keeps
     * its currency and foreign amount, so a reversed foreign payable also leaves the currency open item and FX
     * revaluation. Legacy lines without base amounts fall back to debit/credit.
     */
    static PostingRequest.Line mirrorLine(Map<String, Object> l, String baseCurrency) {
        BigDecimal debit = dec(l, "debit");
        BigDecimal credit = dec(l, "credit");
        BigDecimal baseDebit = dec(l, "baseDebit");
        BigDecimal baseCredit = dec(l, "baseCredit");
        if (baseDebit.signum() <= 0 && baseCredit.signum() <= 0) {
            baseDebit = debit;
            baseCredit = credit;
        }
        if (baseDebit.signum() <= 0 && baseCredit.signum() <= 0) {
            return null;
        }
        String desc = ("Reversal: " + (l.get("description") == null ? "" : l.get("description"))).trim();
        // R3: the reversal keeps the line's dimensions (the stock product / location keys among them).
        PostingRequest.Line line = new PostingRequest.Line(str(l, "accountId"),
                baseCredit.signum() > 0 ? JsNumbers.dec(baseCredit) : null,
                baseDebit.signum() > 0 ? JsNumbers.dec(baseDebit) : null, str(l, "partnerId"), desc, null, null,
                dimensions(l.get("dimensions")));
        String currency = str(l, "currencyId");
        BigDecimal foreign = debit.signum() > 0 ? debit : credit;
        if (currency != null && !currency.isBlank() && !currency.equalsIgnoreCase(baseCurrency)
                && foreign.signum() > 0) {
            line = line.inCurrency(currency, JsNumbers.dec(foreign));
        }
        return line;
    }
}
