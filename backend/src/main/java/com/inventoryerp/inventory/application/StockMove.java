package com.inventoryerp.inventory.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Input of a {@link StockService} movement (port of {@code ReceiveStockDto} / {@code IssueStockDto} /
 * {@code AdjustStockDto} / {@code TransferStockDto} plus the internal-only issue flags). Fluent setters keep
 * call sites readable; unset fields are {@code null} like the optional Nest DTO properties.
 */
public final class StockMove {

    /**
     * Lines in the stock engine's lock order (productId, variantId) — audit S-2: every multi-line posting feeds the
     * engine in this order, so two documents over the same items lock their quants in the same sequence and cannot
     * deadlock. Documents keep their own rows and responses in the entered order.
     */
    public static <T> List<T> inLockOrder(List<T> lines, Function<T, String> productId, Function<T, String> variantId) {
        List<T> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing((T l) -> Objects.toString(productId.apply(l), ""))
                .thenComparing(l -> Objects.toString(variantId.apply(l), "")));
        return sorted;
    }

    /** {@link #inLockOrder(List, Function, Function)} over rows keyed {@code productId} / {@code variantId}. */
    public static <T extends Map<String, Object>> List<T> inLockOrder(List<T> lines) {
        return inLockOrder(lines, l -> (String) l.get("productId"), l -> (String) l.get("variantId"));
    }

    public String productId;
    public String variantId;
    public String locationId;
    public String fromLocationId;
    public String toLocationId;
    /** Movement quantity in {@link #uomId} (base unit when null). */
    public BigDecimal quantity;
    /** Adjust only: the counted on-hand. */
    public BigDecimal countedQuantity;
    /**
     * Adjust only, instead of {@link #countedQuantity}: the signed change to apply to the on-hand read under the quant
     * lock (a count posting that must keep movements made after the count, or one lot of a quant).
     */
    public BigDecimal deltaQuantity;
    /** Adjust only (owner decision D5): an opening count — its value is opening stock against opening equity. */
    public boolean openingBalance;
    public BigDecimal unitCost;
    public String uomId;
    /**
     * Snapshot of base units per 1 {@link #uomId}, taken when the source line was written (ADR-S003). When set it
     * is used instead of the product's current unit setup; null keeps the live conversion.
     */
    public BigDecimal uomFactor;
    public String batchNumber;
    public String expiryDate;
    public String mfgDate;
    public String reference;
    public String sourceType;
    public String sourceId;
    public String notes;
    /** StockMoveType value; defaults per operation. */
    public String moveType;
    public List<String> serialNumbers;
    /** FEFO | FIFO | MANUAL | SERIAL. */
    public String distStrategy;
    public String responsibleById;
    public String approvedById;
    /** Issue only: movement date (period check + GL date); now when null. */
    public Instant date;
    /** Issue only: quantity-only (the caller posts the balanced entry itself). */
    public boolean skipGlPosting;
    /** Issue only: refuse when pickable on-hand is short, regardless of the negative-stock setting. */
    public boolean requireAvailable;
    /**
     * Issue / transfer from a transit location: only these lots ({@code InventoryBatch} ids) may be drawn (the lots a
     * transit transfer line dispatched). Null = any lot of the quant.
     */
    public List<String> pinnedBatchIds;
    /** Issue / transfer: only these serial units ({@code InventorySerial} ids) may move. Null = any in-stock serial. */
    public List<String> pinnedSerialIds;
    /**
     * Issue / transfer of an AVCO quant without lots: the unit cost the units entered this quant at (a transit
     * transfer line's dispatch cost). The quant's average is re-derived so its remaining value stays exact.
     */
    public BigDecimal carriedUnitCost;
    /** Phase I2 (audit R-1): the document line that caused the move, recorded on its ledger rows. */
    public String sourceLineId;
    /**
     * Phase I2 (audit R-1): the document unit and factor of a move its caller already converted to base units (a
     * customer return), so the ledger still records the unit it came back in. Internal to {@code StockService}.
     */
    String traceUomId;
    BigDecimal traceFactor;

    /**
     * Phase A A3 / I2 M-6: the exact base-unit quantity of an issue whose document quantity is in another unit, when
     * the caller rounds cumulatively (per delivery-order line, not per load). Null = convert {@code quantity}.
     */
    public BigDecimal baseQuantity;

    /** See {@link #baseQuantity}. */
    public StockMove baseQuantity(BigDecimal v) {
        this.baseQuantity = v;
        return this;
    }

    public static StockMove of(String productId, String locationId, BigDecimal quantity) {
        StockMove m = new StockMove();
        m.productId = productId;
        m.locationId = locationId;
        m.quantity = quantity;
        return m;
    }

    public static StockMove transfer(String productId, String fromLocationId, String toLocationId, BigDecimal quantity) {
        StockMove m = new StockMove();
        m.productId = productId;
        m.fromLocationId = fromLocationId;
        m.toLocationId = toLocationId;
        m.quantity = quantity;
        return m;
    }

    public StockMove variant(String v) {
        this.variantId = v;
        return this;
    }

    public StockMove unitCost(BigDecimal v) {
        this.unitCost = v;
        return this;
    }

    public StockMove uom(String v) {
        this.uomId = v;
        return this;
    }

    /** Line unit plus its snapshot factor (null factor: convert with the live unit setup). */
    public StockMove uom(String v, BigDecimal factor) {
        this.uomId = v;
        this.uomFactor = factor;
        return this;
    }

    public StockMove batch(String v) {
        this.batchNumber = v;
        return this;
    }

    public StockMove expiry(String v) {
        this.expiryDate = v;
        return this;
    }

    public StockMove reference(String v) {
        this.reference = v;
        return this;
    }

    /** The document line that caused this move (Phase I2, audit R-1). */
    public StockMove sourceLine(String id) {
        this.sourceLineId = id;
        return this;
    }

    public StockMove source(String type, String id) {
        this.sourceType = type;
        this.sourceId = id;
        return this;
    }

    public StockMove notes(String v) {
        this.notes = v;
        return this;
    }

    public StockMove moveType(String v) {
        this.moveType = v;
        return this;
    }

    public StockMove serials(List<String> v) {
        this.serialNumbers = v;
        return this;
    }

    public StockMove strategy(String v) {
        this.distStrategy = v;
        return this;
    }

    public StockMove people(String responsible, String approved) {
        this.responsibleById = responsible;
        this.approvedById = approved;
        return this;
    }

    public StockMove date(Instant v) {
        this.date = v;
        return this;
    }

    public StockMove skipGl() {
        this.skipGlPosting = true;
        return this;
    }

    public StockMove requireAvailable(boolean v) {
        this.requireAvailable = v;
        return this;
    }

    /** Restrict the move to the given lots / serial units and carry {@code unitCost} for unlotted AVCO units. */
    public StockMove pin(List<String> batchIds, List<String> serialIds, BigDecimal unitCost) {
        this.pinnedBatchIds = batchIds;
        this.pinnedSerialIds = serialIds;
        this.carriedUnitCost = unitCost;
        return this;
    }

    /**
     * Result of a receipt: {@code quantity} echoes the caller's unit; values are per base unit. {@code totalValue} is
     * the value the stock entered at (the standard for STANDARD-costed products); {@code actualValue} is what the
     * supplier charged (quantity × actual unit cost), which is what GRNI and AP carry (ADR-PROCUREMENT-001, P0-1).
     */
    public record Received(String ledgerCode, BigDecimal quantity, BigDecimal unitCost, BigDecimal totalValue,
            BigDecimal runningAverageCost, BigDecimal actualValue) {}

    /** Result of an issue: {@code baseQuantity}/{@code baseUomId} are what actually left stock. */
    public record Issued(String ledgerCode, BigDecimal quantity, BigDecimal baseQuantity, String baseUomId,
            BigDecimal unitCost, BigDecimal totalValue) {}

    /** Result of an adjustment ({@code ledgerCode} null when nothing changed). */
    public record Adjusted(String ledgerCode, BigDecimal previousQuantity, BigDecimal newQuantity, BigDecimal delta,
            BigDecimal unitCost) {}

    /**
     * Result of a transfer. {@code layers} are what arrived at the destination: one entry per lot created there
     * ({@code batchId}), per serial unit moved ({@code serialId}), and one for units carried without a lot at
     * {@code carriedUnitCost}.
     */
    public record Transferred(String ledgerCode, BigDecimal quantity, String fromLocationId, String toLocationId,
            List<MovedLayer> layers, BigDecimal carriedUnitCost) {

        public Transferred(String ledgerCode, BigDecimal quantity, String fromLocationId, String toLocationId) {
            this(ledgerCode, quantity, fromLocationId, toLocationId, List.of(), null);
        }
    }

    /** One identified layer a transfer delivered: a destination lot or a serial unit. */
    public record MovedLayer(String batchId, String serialId, BigDecimal quantity, BigDecimal unitCost) {}
}
