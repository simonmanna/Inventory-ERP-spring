package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.bool;
import static com.inventoryerp.inventory.application.InventoryLookups.dec;
import static com.inventoryerp.inventory.application.InventoryLookups.str;

import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.inventory.application.CostResolver.ProductCost;
import com.inventoryerp.inventory.application.CostResolver.Quant;
import com.inventoryerp.inventory.application.CostResolver.Resolution;
import com.inventoryerp.inventory.application.StockMove.Adjusted;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.inventory.application.StockMove.Transferred;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.EffectiveTime;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.OccurredAt;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stock engine (port of {@code stock.service.ts}): every quantity change goes through receive / issue /
 * adjust / transfer and writes append-only {@code InventoryLedger} rows chained {@code qtyBefore → balanceAfter}.
 * Every move locks its quants in one documented order ({@link #lockQuant}: advisory lock, then the {@code StockItem}
 * row, several quants in key order) before reading quantity or average; FIFO / batch
 * products consume {@code InventoryBatch} layers; serial products move identified {@code InventorySerial} units.
 * Each method joins the caller's transaction (POS checkout, procurement, manufacturing) or opens its own.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    /** StockMoveType → InventoryMovementType for the issue GL leg. */
    private static final Map<String, String> INV_MOVE_TYPES = Map.of(
            "issue", "STOCK_OUT",
            "waste", "WASTE",
            "expiry_write_off", "EXPIRY_WRITE_OFF",
            "return_to_supplier", "RETURN_TO_SUPPLIER",
            "internal_use", "INTERNAL_CONSUMPTION",
            "promo_sample", "PROMO_SAMPLE",
            "production_consume", "PRODUCTION_CONSUME");

    /** Who owns a receipt's accounting effect (Nest {@code GlReceiptContext}). */
    public record GlReceiptContext(String sourceType, String sourceId, Instant date, String kind) {

        public static GlReceiptContext grni(String sourceType, String sourceId, Instant date) {
            return new GlReceiptContext(sourceType, sourceId, date, "receipt_grni");
        }

        public static GlReceiptContext stockGain(String sourceType, String sourceId, Instant date) {
            return new GlReceiptContext(sourceType, sourceId, date, "stock_gain");
        }
    }

    private final JdbcClient jdbc;
    private final InventoryLookups lookups;
    private final EventBus events;
    private final SequenceService seq;
    private final AuditService audit;
    private final StockPostingService stockPosting;
    private final CostResolver costResolver;
    private final SettingResolverService settings;
    private final UomConversionService uomConversion;

    private final org.springframework.beans.factory.ObjectProvider<StandardCostService> standards;

    public StockService(JdbcClient jdbc, InventoryLookups lookups, EventBus events, SequenceService seq,
            AuditService audit, StockPostingService stockPosting, CostResolver costResolver,
            SettingResolverService settings, UomConversionService uomConversion,
            org.springframework.beans.factory.ObjectProvider<StandardCostService> standards) {
        this.standards = standards;
        this.jdbc = jdbc;
        this.lookups = lookups;
        this.events = events;
        this.seq = seq;
        this.audit = audit;
        this.stockPosting = stockPosting;
        this.costResolver = costResolver;
        this.settings = settings;
        this.uomConversion = uomConversion;
    }

    // ================================================================== period lock

    /** Movements dated in a locked period (books lock date or closed/locked fiscal period) are refused. */
    @Transactional(readOnly = true)
    public void assertStockPeriodOpen(Instant at) {
        String org = TenantContext.organizationId();
        // Lock date and period bounds are org-local wall clock (ADR-S070): compare the movement's local wall clock.
        Instant date = AccountingDay.toWallClock(at, AccountingDay.zone(jdbc, org));
        Instant lock = jdbc.sql("SELECT \"booksLockDate\" FROM \"Organization\" WHERE id = ?").param(org)
                .query((rs, n) -> {
                    var t = rs.getTimestamp(1);
                    return t == null ? null : t.toLocalDateTime().toInstant(ZoneOffset.UTC);
                }).optional().orElse(null);
        if (lock != null && !date.isAfter(lock)) {
            throw ApiException.badRequest("Books are locked through " + day(lock) + "; stock cannot move on " + day(date));
        }
        var period = jdbc.sql("SELECT name, status::text AS status FROM \"FiscalPeriod\" WHERE \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND \"startDate\" <= ? AND \"endDate\" >= ?"
                        + " AND status IN ('closed', 'locked') LIMIT 1")
                .params(org, SqlRows.ts(date), SqlRows.ts(date)).query().listOfRows();
        if (!period.isEmpty()) {
            throw ApiException.badRequest("Fiscal period '" + period.getFirst().get("name") + "' is "
                    + period.getFirst().get("status") + " (locked); stock cannot move in it");
        }
    }

    private static String day(Instant i) {
        return i.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    // ================================================================== receive

    /** Bare receipt: quantity + AVCO, no GL (value owned elsewhere). */
    @Transactional
    public Received receive(StockMove dto) {
        return receiveCore(dto, null);
    }

    /** Receipt owned by a source document: also posts Dr Stock Valuation / Cr GRNI (or stock gain). */
    @Transactional
    public Received receiveForDocument(StockMove dto, GlReceiptContext glCtx) {
        return receiveCore(dto, glCtx);
    }

    /**
     * Receipt from a vendor bill. IPR SW-5: the bill line ({@link StockMove#sourceLineId}) and the business time
     * ({@link StockMove#effectiveAt}) the caller names are recorded on the ledger row (they were dropped).
     */
    @Transactional
    public Received receiveFromBill(StockMove dto, String billId, Instant billDate) {
        StockMove m = StockMove.of(dto.productId, dto.locationId, dto.quantity).variant(dto.variantId)
                .unitCost(dto.unitCost).uom(dto.uomId, dto.uomFactor)
                .batch(dto.batchNumber).expiry(dto.expiryDate).notes(dto.notes).serials(dto.serialNumbers)
                .sourceLine(dto.sourceLineId).effectiveAt(dto.effectiveAt);
        m.mfgDate = dto.mfgDate;
        m.manualMove = dto.manualMove;
        return receiveCore(m, GlReceiptContext.grni("vendor_bill", billId, billDate));
    }

    /**
     * Restock a customer return: receive as {@code return_in} at the current cost basis (unless given) and post
     * the COGS reversal (Dr Stock Valuation / Cr COGS) in the same transaction.
     */
    @Transactional
    public Received receiveReturn(StockMove dto) {
        Instant date = dto.date != null ? dto.date : Instant.now();
        assertStockPeriodOpen(date);
        holdCostingShared(TenantContext.organizationId(), dto.productId);
        Map<String, Object> product = lookups.product(dto.productId);
        BigDecimal baseQty = returnBase(product, dto.productId, dto.quantity, dto.uomId, dto.uomFactor);
        BigDecimal unitCost = dto.unitCost != null ? dto.unitCost
                : resolveCurrentCost(dto.productId, dto.variantId, dto.locationId);
        // R1 (2026-11-23): a return keeps the identity its caller names — lot, expiry, serials, the original lot row
        // and the movement it brings back.
        StockMove m = StockMove.of(dto.productId, dto.locationId, baseQty).variant(dto.variantId).unitCost(unitCost)
                .reference(dto.reference).source(dto.sourceType, dto.sourceId).notes(dto.notes)
                .moveType(dto.moveType != null ? dto.moveType : "return_in").serials(dto.serialNumbers)
                .sourceLine(dto.sourceLineId).batch(dto.batchNumber).expiry(dto.expiryDate)
                .reverses(dto.reversesLedgerId);
        m.mfgDate = dto.mfgDate;
        m.returnBatchId = dto.returnBatchId;
        Trace t = trace(dto, product, baseQty, dto.quantity); // R-1: the unit it came back in
        m.traceUomId = t.docUomId();
        m.traceFactor = t.factor();
        Received res = receiveCore(m, null);
        BigDecimal totalValue = unitCost.multiply(baseQty);
        String sourceType = dto.sourceType != null ? dto.sourceType : "return_restock";
        String sourceId = dto.sourceId != null ? dto.sourceId : dto.reference != null ? dto.reference : dto.productId;
        if (totalValue.signum() > 0) {
            stockPosting.postReturnRestock(dto.productId, totalValue, date, sourceType, sourceId,
                    "Return restock · " + JsNumbers.dec(dto.quantity));
        }
        postStandardReturnVariance(dto.productId, baseQty, totalValue, res,
                stockPosting.returnStockAccount(dto.productId, totalValue), date, sourceType, sourceId);
        return res;
    }

    /**
     * Hardening R3 (P1-9): a STANDARD product comes back at its governing standard (quant, lot, ledger row), while the
     * GL restock — the caller's or {@link #receiveReturn}'s — reverses the original issue cost ({@code originalValue}).
     * The difference goes to purchase price variance against {@code stockAccountId} (see
     * {@link StandardCostService#postReturnVariance}), so the stock account ends at the standard and the COGS reversal
     * stays at the original cost. Nothing for other costing methods (they come back at the original cost).
     */
    void postStandardReturnVariance(String productId, BigDecimal quantity, BigDecimal originalValue, Received res,
            String stockAccountId, Instant date, String sourceType, String sourceId) {
        StandardCostService std = standards.getIfAvailable();
        Map<String, Object> product = lookups.product(productId);
        if (std == null || product == null || !"STANDARD".equals(str(product, "costingMethod"))) {
            return;
        }
        std.postReturnVariance(productId, stockAccountId, quantity, originalValue, res.totalValue(),
                date != null ? date : Instant.now(), sourceType, sourceId,
                "Return at standard · " + str(product, "name") + " · " + JsNumbers.dec(quantity));
    }

    /**
     * Base units of a returned {@code quantity} in {@code uomId}: the snapshot {@code uomFactor} first (ADR-S003), else
     * the live conversion when {@code uomId} is not the product's base unit, else {@code quantity}. IPR E-2a: public,
     * the one rule every caller converting a document quantity back to stock units uses.
     */
    public BigDecimal returnBaseQuantity(String productId, BigDecimal quantity, String uomId, BigDecimal uomFactor) {
        return returnBase(lookups.product(productId), productId, quantity, uomId, uomFactor);
    }

    private BigDecimal returnBase(Map<String, Object> product, String productId, BigDecimal quantity, String uomId,
            BigDecimal uomFactor) {
        return product != null && uomFactor != null
                ? uomConversion.applyFactor(quantity, uomFactor, str(product, "uomId"), true)
                : uomId != null && product != null && product.get("uomId") != null
                        && !uomId.equals(product.get("uomId"))
                        ? uomConversion.toBase(quantity, uomId, productId, str(product, "uomId"), true)
                        : quantity;
    }

    /** Location running average when positive, else the last positive receipt cost, else the (variant's) cost. */
    private BigDecimal resolveCurrentCost(String productId, String variantId, String locationId) {
        Map<String, Object> product = withVariantCost(lookups.product(productId), variantId);
        Map<String, Object> item = lookups.stockItem(productId, variantId, locationId);
        if (item != null && dec(item, "runningAverageCost").signum() > 0) {
            return dec(item, "runningAverageCost");
        }
        return lastReceiptOrCostPrice(product, item != null, productId, variantId, locationId);
    }

    /**
     * IPR C-3 / GL-6 (D3): {@link #resolveCurrentCost} for a quant already locked (its average read under the lock);
     * {@code product} carries the variant's cost.
     */
    private BigDecimal currentCost(LockedQuant quant, Map<String, Object> product, String productId, String variantId,
            String locationId) {
        if (!quant.created() && quant.average() != null && quant.average().signum() > 0) {
            return quant.average();
        }
        return lastReceiptOrCostPrice(product, !quant.created(), productId, variantId, locationId);
    }

    private BigDecimal lastReceiptOrCostPrice(Map<String, Object> product, boolean quantExists, String productId,
            String variantId, String locationId) {
        if (quantExists) {
            List<BigDecimal> last = jdbc.sql("SELECT \"unitCost\" FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                            + " AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ?"
                            + " AND type = 'receipt' AND \"quantityChange\" > 0 ORDER BY \"createdAt\" DESC LIMIT 1")
                    .params(TenantContext.organizationId(), productId, variantId, locationId)
                    .query(BigDecimal.class).list();
            if (!last.isEmpty() && last.getFirst().signum() > 0) {
                return last.getFirst();
            }
        }
        return dec(product, "costPrice");
    }

    /** Batch number from {@code inventory.batchNumberFormat} (YYYY MM DD + a #### sequence run). */
    private String generateBatchNumber() {
        String format = settings.resolveString("inventory.batchNumberFormat", SettingContext.EMPTY);
        String n = seq.next("batch_no", "", 4);
        LocalDate d = LocalDate.now(BusinessZone.get());
        return format.replace("YYYY", String.valueOf(d.getYear()))
                .replace("MM", String.format("%02d", d.getMonthValue()))
                .replace("DD", String.format("%02d", d.getDayOfMonth()))
                .replaceAll("#+", n);
    }

    private Received receiveCore(StockMove dto, GlReceiptContext glCtx) {
        String org = TenantContext.organizationId();
        assertStockPeriodOpen(glCtx != null && glCtx.date() != null ? glCtx.date() : Instant.now());
        holdCostingShared(org, dto.productId);
        Map<String, Object> product = lookups.product(dto.productId);
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        Map<String, Object> location = lookups.location(dto.locationId);
        if (location == null) {
            throw ApiException.notFound("Location not found");
        }
        assertGuardedLocation(dto, location, true, false);
        assertNotFrozen(org, dto, dto.locationId);
        Eff eff = effective(dto);
        requireVariant(product, dto.variantId, dto.locationId,
                "return_in".equals(dto.moveType) ? VariantRule.EXISTING : VariantRule.INBOUND);
        product = withVariantCost(product, dto.variantId);
        boolean batchTracking = bool(product, "batchTracking");
        String productName = str(product, "name");
        // R1: a return into the lot it left from (same quant) restores that lot; its number and expiry stand. The lot
        // row is only read here; it is updated after the quant lock below (engine lock order).
        Map<String, Object> returnLot = batchTracking && dto.returnBatchId != null ? SqlRows.one(jdbc.sql("SELECT id,"
                        + " \"batchNumber\" FROM \"InventoryBatch\" WHERE id = ? AND \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ?")
                .params(dto.returnBatchId, org, dto.productId, dto.variantId, dto.locationId)) : null;
        if (returnLot == null && batchTracking && dto.batchNumber == null
                && !settings.resolveBool("inventory.batchAutoNumber", SettingContext.EMPTY)) {
            throw ApiException.badRequest("Batch number is required for batch-tracked products");
        }
        if (dto.expiryDate != null && !batchTracking) {
            throw ApiException.badRequest("Expiry date is only valid for batch-tracked products");
        }
        if (returnLot == null && bool(product, "expiryTracking") && dto.expiryDate == null) {
            throw ApiException.badRequest("Expiry date is required for expiry-tracked products");
        }
        String costing = str(product, "costingMethod");
        if ("FIFO".equals(costing) && !batchTracking) {
            throw ApiException.badRequest("FIFO costing requires batchTracking=true on the product");
        }
        // IPR C-3 / GL-6 (owner decision D3): a receipt without a unit cost enters at the current cost (resolved under
        // the quant lock below), never at 0. An explicit 0 is the caller's (gated at the manual entry points).
        boolean blankCost = dto.unitCost == null;
        BigDecimal[] base = toBaseQtyCost(product, dto.uomId, dto.uomFactor, dto.quantity,
                blankCost ? Money.ZERO : dto.unitCost);
        BigDecimal qty = base[0];
        BigDecimal actualUnitCost = base[1];
        // ADR-S008: a standard-governed product enters stock at its standard; the difference is PPV (below). Hardening
        // R3 (P1-8): a STANDARD product without an active standard cost is governed by its cost price (what issues and
        // the valuation use), so it enters at that price too; with neither, a valued receipt is refused.
        StandardCostService std = "STANDARD".equals(costing) ? standards.getIfAvailable() : null;
        BigDecimal standardUnit = std == null ? null
                : std.activeStandard(dto.productId).orElse((BigDecimal) product.get("costPrice"));
        if (std != null && standardUnit == null && glCtx != null) {
            throw ApiException.badRequest("Set a cost price or an active standard cost for STANDARD product "
                    + productName + " before receiving it");
        }
        BigDecimal unitCost = standardUnit != null ? standardUnit : actualUnitCost;
        List<String> serials = dto.serialNumbers == null ? List.of() : dto.serialNumbers;
        if (bool(product, "serialTracking")) {
            assertWholeSerialQuantity(productName, qty);
            String policy = settings.resolveString("inventory.serialPolicy",
                    new SettingContext(dto.productId, str(product, "categoryId"), dto.locationId));
            if ("required".equals(policy) && qty.compareTo(BigDecimal.valueOf(serials.size())) != 0) {
                throw ApiException.badRequest("Serial policy is \"required\": " + productName + " needs exactly "
                        + JsNumbers.dec(qty) + " serial number(s) (base units), got " + serials.size());
            }
            if (!serials.isEmpty()) {
                if (new HashSet<>(serials).size() != serials.size()) {
                    throw ApiException.badRequest("Duplicate serial numbers in the receipt");
                }
                if (qty.compareTo(BigDecimal.valueOf(serials.size())) < 0) {
                    throw ApiException.badRequest("Received " + JsNumbers.dec(qty) + " base unit(s) but "
                            + serials.size() + " serial number(s) provided");
                }
            }
        }
        String variantId = dto.variantId;

        // Average and quantity are read under the quant lock (advisory + row), so a concurrent issue cannot change
        // the quantity this average is computed from.
        LockedQuant quant = lockQuant(org, dto.productId, variantId, dto.locationId, Money.ZERO);
        if (blankCost) {
            // IPR review 3 (#4): the current cost of a standard-governed product is its governing standard (not the
            // quant average, last receipt or cost price), so a blank cost books no purchase price variance.
            actualUnitCost = standardUnit != null ? standardUnit
                    : currentCost(quant, product, dto.productId, variantId, dto.locationId);
            if (standardUnit == null) {
                unitCost = actualUnitCost;
            }
        }
        String ledgerCode = seq.next("stock_move", "STK/", 6);
        BigDecimal qtyBefore = quant.quantity();
        Resolution cost = costResolver.resolveReceiptCost(
                new ProductCost(costing, (BigDecimal) product.get("costPrice")),
                quant.created() ? null : new Quant(qtyBefore, quant.average()), qty, unitCost);
        // Gaps h-00 F1: a serial-tracked quant averages only its units without a serial (see serialAverage).
        SerialAverage serialAvg = bool(product, "serialTracking")
                ? serialAverage(org, dto.productId, variantId, dto.locationId, quant, product,
                        qty.subtract(BigDecimal.valueOf(serials.size())), unitCost, cost.newRunningAverage())
                : null;
        // A lot-tracked quant that went short (issued without a lot): the receipt first covers that shortfall, so only
        // the remainder becomes an open lot (open lots always sum to max(0, on-hand)); the covered units are trued up
        // from the cost they were issued at to this receipt's cost.
        BigDecimal netted = batchTracking && qtyBefore.signum() < 0 ? Money.min(qty, qtyBefore.negate()) : Money.ZERO;
        BigDecimal correction = serialAvg != null ? serialAvg.correction()
                : batchTracking ? netted.multiply(unitCost.subtract(shortfallCost(quant, product)))
                : cost.costCorrection();
        // Hardening R5b (P2-3): a STANDARD quant carries its governing standard as its average (it stayed 0 when the
        // receipt created it, so transfers out of it carried no value).
        BigDecimal newAverage = serialAvg != null ? serialAvg.average()
                : standardUnit != null ? standardUnit : cost.newRunningAverage();

        Map<String, Object> stockItem = addToQuant(quant.id(), qty, newAverage);

        String batchId = null;
        if (returnLot != null) {
            batchId = str(returnLot, "id");
            BigDecimal lotQty = qty.subtract(netted);
            jdbc.sql("UPDATE \"InventoryBatch\" SET quantity = quantity + ?, \"isActive\" = (quantity + ? > 0),"
                            + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                    .params(lotQty, lotQty, batchId, org).update();
        } else if (batchTracking) {
            String batchNumber = dto.batchNumber != null ? dto.batchNumber : generateBatchNumber();
            batchId = UUID.randomUUID().toString();
            BigDecimal lotQty = qty.subtract(netted);
            jdbc.sql("INSERT INTO \"InventoryBatch\" (id, \"organizationId\", \"productId\", \"variantId\", \"locationId\","
                            + " \"batchNumber\", quantity, \"unitCost\", \"expiryDate\", \"mfgDate\", \"isActive\","
                            + " \"receivedAt\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW
                            + ", " + NOW + ", " + NOW + ")")
                    .params(batchId, org, dto.productId, variantId, dto.locationId, batchNumber, lotQty, unitCost,
                            SqlRows.ts(toInstant(dto.expiryDate)), SqlRows.ts(toInstant(dto.mfgDate)), lotQty.signum() > 0)
                    .update();
        }

        if (bool(product, "serialTracking") && !serials.isEmpty()) {
            String receiptRef = (glCtx != null && glCtx.sourceType() != null ? glCtx.sourceType()
                    : dto.sourceType != null ? dto.sourceType : "receipt") + ":"
                    + (glCtx != null && glCtx.sourceId() != null ? glCtx.sourceId()
                    : dto.sourceId != null ? dto.sourceId : ledgerCode);
            List<Map<String, Object>> found = jdbc.sql("SELECT \"serialNumber\", status::text AS status FROM"
                            + " \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                            + " AND \"serialNumber\" = ANY(?::text[]) FOR UPDATE")
                    .params(org, dto.productId, serials.toArray(String[]::new)).query().listOfRows();
            boolean isReturn = "return_in".equals(dto.moveType) || "reversal_in".equals(dto.moveType)
                    || dto.reversesLedgerId != null;
            List<String> conflicts = new ArrayList<>();
            for (Map<String, Object> row : found) {
                if (!isReturn || !"issued".equals(row.get("status"))) {
                    conflicts.add(row.get("serialNumber") + " (" + row.get("status") + ")");
                }
            }
            if (!conflicts.isEmpty()) {
                throw ApiException.badRequest(isReturn
                        ? "Serial number(s) cannot be returned because they are not currently issued: "
                                + String.join(", ", conflicts)
                        : "Serial number(s) already exist for this product: " + String.join(", ", conflicts));
            }
            Set<String> reinstated = new HashSet<>();
            found.forEach(row -> reinstated.add((String) row.get("serialNumber")));
            for (String serial : serials) {
                if (!reinstated.contains(serial)) {
                    // Hardening R5b (P2-2): a new serial number is inserted only if no concurrent receipt created it
                    // meanwhile (it would otherwise be moved here silently by the upsert below).
                    insertNewSerial(org, dto.productId, variantId, dto.locationId, serial, batchId, unitCost, receiptRef);
                    continue;
                }
                jdbc.sql("INSERT INTO \"InventorySerial\" (id, \"organizationId\", \"productId\", \"variantId\","
                                + " \"locationId\", \"serialNumber\", \"batchId\", \"unitCost\", status, \"receiptRef\","
                                + " \"receivedAt\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                                + " 'in_stock', ?, " + NOW + ", " + NOW + ", " + NOW + ")"
                                + " ON CONFLICT (\"organizationId\", \"productId\", \"serialNumber\") DO UPDATE SET"
                                + " \"locationId\" = EXCLUDED.\"locationId\", \"variantId\" = EXCLUDED.\"variantId\","
                                + " \"batchId\" = EXCLUDED.\"batchId\", status = 'in_stock', \"issuedAt\" = NULL,"
                                // Gaps h-00 F1: a returned unit is carried at the cost it came back at (what the GL
                                // debited), not the cost of its earlier stay.
                                + " \"unitCost\" = EXCLUDED.\"unitCost\","
                                + " \"issueRef\" = NULL, \"receiptRef\" = EXCLUDED.\"receiptRef\", \"updatedAt\" = " + NOW)
                        .params(UUID.randomUUID().toString(), org, dto.productId, variantId, dto.locationId, serial,
                                batchId, unitCost, receiptRef)
                        .update();
            }
        }

        BigDecimal currentQty = dec(stockItem, "quantity");
        // R1: a return of one named serial records that unit on its row (as an issue row does).
        String serialId = dto.reversesLedgerId != null && serials.size() == 1 && bool(product, "serialTracking")
                ? jdbc.sql("SELECT id FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND"
                        + " \"serialNumber\" = ?").params(org, dto.productId, serials.getFirst()).query(String.class)
                        .optional().orElse(null)
                : null;
        insertLedger(org, ledgerCode, dto.productId, variantId, dto.locationId, batchId, serialId,
                dto.moveType != null ? dto.moveType : "receipt", currentQty.subtract(qty), qty, currentQty, unitCost,
                cost.totalValue(),
                glCtx != null && glCtx.sourceType() != null ? glCtx.sourceType() : dto.sourceType,
                glCtx != null && glCtx.sourceId() != null ? glCtx.sourceId() : dto.sourceId,
                joinNotes(dto.notes, eff.note(glCtx != null && glCtx.date() != null ? glCtx.date() : dto.date)),
                dto.responsibleById, dto.approvedById, trace(dto, product, qty, dto.quantity), eff.at());

        if (glCtx != null) {
            if ("stock_gain".equals(glCtx.kind())) {
                stockPosting.postAdjustment(dto.productId, qty, unitCost, glCtx.date(), glCtx.sourceType(),
                        glCtx.sourceId(), "Direct stock-in · " + productName + " · " + JsNumbers.dec(dto.quantity));
            } else if (standardUnit != null) {
                std.postReceipt(dto.productId, qty, actualUnitCost, standardUnit,
                        stockPosting.receiptLines(dto.productId, actualUnitCost.multiply(qty)), glCtx.date(),
                        glCtx.sourceType(), glCtx.sourceId(), "Stock receipt · " + productName + " · "
                                + JsNumbers.dec(dto.quantity));
            } else {
                stockPosting.postReceipt(dto.productId, qty, unitCost, glCtx.date(), glCtx.sourceType(),
                        glCtx.sourceId(), "Stock receipt · " + productName + " · " + JsNumbers.dec(dto.quantity));
            }
        }
        // Every receipt into a negative quant squares the value of the units issued short (not only purchase receipts):
        // production output, returns, reversals, quality moves and adjustments-in all leave stock and GL apart otherwise.
        postCostCorrection(org, dto.productId, variantId, dto.locationId, currentQty, productName, correction,
                glCtx != null && glCtx.date() != null ? glCtx.date() : dto.date != null ? dto.date : Instant.now(),
                glCtx != null && glCtx.sourceType() != null ? glCtx.sourceType()
                        : dto.sourceType != null ? dto.sourceType : "stock_receipt",
                glCtx != null && glCtx.sourceId() != null ? glCtx.sourceId()
                        : dto.sourceId != null ? dto.sourceId : ledgerCode,
                ledgerCode);

        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org);
        payload.put("productId", dto.productId);
        payload.put("locationId", dto.locationId);
        payload.put("ledgerCode", ledgerCode);
        payload.put("quantity", JsNumbers.dec(dto.quantity));
        payload.put("unitCost", JsNumbers.dec(unitCost));
        if (newAverage != null) {
            payload.put("newRunningAverage", JsNumbers.dec(newAverage));
        }
        events.publishOnCommit("stock.received", payload);
        correctLateCountRows(org, ledgerCode, dto, eff);
        audit.recordInTx(AuditInput.of("StockItem", str(stockItem, "id"), "receive", null,
                Obj.of("productId", dto.productId, "locationId", dto.locationId, "quantity", JsNumbers.js(dto.quantity),
                        "unitCost", JsNumbers.dec(unitCost), "ledgerCode", ledgerCode)));
        BigDecimal actualValue = standardUnit != null
                ? actualUnitCost.multiply(qty).setScale(6, java.math.RoundingMode.HALF_UP) : cost.totalValue();
        return new Received(ledgerCode, dto.quantity, unitCost, cost.totalValue(),
                newAverage != null ? newAverage : dec(stockItem, "runningAverageCost"), actualValue);
    }

    /**
     * Hardening R5b (P2-2): insert a serial unit that did not exist when the receipt read its serials. When a concurrent
     * receipt committed the same number in between, the insert does nothing and the receipt is refused (400) instead of
     * moving that unit to this location.
     */
    private void insertNewSerial(String org, String productId, String variantId, String locationId, String serial,
            String batchId, BigDecimal unitCost, String receiptRef) {
        int inserted = jdbc.sql("INSERT INTO \"InventorySerial\" (id, \"organizationId\", \"productId\", \"variantId\","
                        + " \"locationId\", \"serialNumber\", \"batchId\", \"unitCost\", status, \"receiptRef\","
                        + " \"receivedAt\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'in_stock', ?, "
                        + NOW + ", " + NOW + ", " + NOW + ") ON CONFLICT (\"organizationId\", \"productId\","
                        + " \"serialNumber\") DO NOTHING")
                .params(UUID.randomUUID().toString(), org, productId, variantId, locationId, serial, batchId, unitCost,
                        receiptRef)
                .update();
        if (inserted == 1) {
            return;
        }
        Map<String, Object> existing = SqlRows.one(jdbc.sql("SELECT s.status::text AS status, l.name AS location FROM"
                        + " \"InventorySerial\" s LEFT JOIN \"InventoryLocation\" l ON l.id = s.\"locationId\" WHERE"
                        + " s.\"organizationId\" = ? AND s.\"productId\" = ? AND s.\"serialNumber\" = ?")
                .params(org, productId, serial));
        if (existing != null && "in_stock".equals(existing.get("status"))) {
            throw ApiException.badRequest("Serial " + serial + " is already in stock at " + existing.get("location"));
        }
        throw ApiException.badRequest("Serial number(s) already exist for this product: " + serial + " ("
                + (existing == null ? "unknown" : existing.get("status")) + ")");
    }

    /** New average (null = unchanged) and negative-stock correction of a serial-tracked receipt. */
    private record SerialAverage(BigDecimal average, BigDecimal correction) {}

    /**
     * Gaps h-00 F1: the running average of a serial-tracked quant is the cost of its units that carry no serial. The
     * valuation carries those units at it (the cost price while it is 0) and {@link #issue} issues them at it, so the
     * stock account and the sub-ledger stay equal when such items move without serials. Units received without a
     * serial blend into it; when the quant is short of unserialised units the receipt first covers that shortfall and
     * the covered units are trued up from the cost they left at, as for lot-tracked quants. While the quant holds no
     * unit without a serial the average values nothing, so it keeps following every unit ({@code blended}, as before).
     */
    private SerialAverage serialAverage(String org, String productId, String variantId, String locationId,
            LockedQuant quant, Map<String, Object> product, BigDecimal unserialised, BigDecimal unitCost,
            BigDecimal blended) {
        long serialised = jdbc.sql("SELECT count(*) FROM \"InventorySerial\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ?"
                        + " AND status = 'in_stock'")
                .params(org, productId, variantId, locationId).query(Long.class).single();
        BigDecimal before = quant.quantity().subtract(BigDecimal.valueOf(serialised));
        if (unserialised.signum() <= 0) {
            return new SerialAverage(before.signum() == 0 ? blended : null, Money.ZERO);
        }
        BigDecimal avg = quant.average().signum() > 0 ? quant.average() : dec(product, "costPrice");
        if (before.signum() >= 0) {
            return new SerialAverage(Money.div(before.multiply(avg).add(unserialised.multiply(unitCost)),
                    before.add(unserialised)), Money.ZERO);
        }
        BigDecimal netted = Money.min(unserialised, before.negate());
        return new SerialAverage(before.add(unserialised).signum() > 0 ? unitCost : null,
                netted.multiply(unitCost.subtract(avg)));
    }

    /**
     * Phase I2 (audit C-1, owner decision D3): wherever the engine falls back to "the product cost" (first issue of an
     * empty quant, overflow past the layers, counts, return restocks, shortfalls), a variant uses its own cost
     * ({@code COALESCE(variant.costPrice, product.costPrice)}, as {@code ProductVariantService.COST_SQL}). STANDARD
     * stays per product by design (D3).
     */
    private Map<String, Object> withVariantCost(Map<String, Object> product, String variantId) {
        if (product == null || variantId == null || "STANDARD".equals(str(product, "costingMethod"))) {
            return product;
        }
        BigDecimal cost = jdbc.sql("SELECT \"costPrice\" FROM \"ProductVariant\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"costPrice\" IS NOT NULL")
                .params(variantId, TenantContext.organizationId()).query(BigDecimal.class).optional().orElse(null);
        if (cost == null) {
            return product;
        }
        Map<String, Object> copy = new java.util.LinkedHashMap<>(product);
        copy.put("costPrice", cost);
        return copy;
    }

    /** How strictly {@link #requireVariant} treats a move (Phase I2, owner decision D2). */
    private enum VariantRule {
        /** Adds new stock (receipt, positive count): an active variant of the product is required. */
        INBOUND,
        /** Moves or restores existing stock (issue, transfer, negative count, return restock): legacy rule. */
        EXISTING
    }

    /**
     * ADR-S058 + Phase I2 (owner decision D2): a product with variants moves stock per variant.
     * <ul>
     *   <li>New stock ({@link VariantRule#INBOUND}) must name an active variant of the product: a receipt or a
     *       positive count never grows the legacy quant without a variant, and an inactive variant receives nothing.</li>
     *   <li>Existing stock ({@link VariantRule#EXISTING}) may move without a variant only while the location still
     *       holds a quant without one (stock from before the product got variants), until it runs out; an inactive
     *       variant's remaining stock can still be issued, moved, counted down or restored by a return.</li>
     * </ul>
     */
    private void requireVariant(Map<String, Object> product, String variantId, String locationId, VariantRule rule) {
        if (variantId != null) {
            // The variant must be one of this product's (any organisation's id would otherwise open a foreign quant).
            Boolean active = jdbc.sql("SELECT \"isActive\" AND \"deletedAt\" IS NULL FROM \"ProductVariant\" WHERE id = ?"
                            + " AND \"productId\" = ? AND \"organizationId\" = ?")
                    .params(variantId, str(product, "id"), TenantContext.organizationId()).query(Boolean.class)
                    .optional().orElse(null);
            if (active == null) {
                throw ApiException.notFound("Variant not found");
            }
            if (rule == VariantRule.INBOUND && !active) {
                throw ApiException.badRequest("Variant is inactive: it cannot receive stock");
            }
            return;
        }
        if (!bool(product, "hasVariants")) {
            return;
        }
        if (rule == VariantRule.INBOUND || lookups.stockItem(str(product, "id"), null, locationId) == null) {
            throw ApiException.badRequest("Choose a variant for " + str(product, "name"));
        }
    }

    // ================================================================== issue

    /** Issue stock (sale, consumption, waste, ...) with layer-aware costing and the GL leg. */
    @Transactional
    public Issued issue(StockMove dto) {
        String org = TenantContext.organizationId();
        Instant date = dto.date != null ? dto.date : Instant.now();
        assertStockPeriodOpen(date);
        holdCostingShared(org, dto.productId);
        Map<String, Object> product = lookups.product(dto.productId);
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        Map<String, Object> location = lookups.location(dto.locationId);
        if (location == null) {
            throw ApiException.notFound("Location not found");
        }
        String moveType = dto.moveType != null ? dto.moveType : "issue";
        if (!dto.skipGlPosting && !INV_MOVE_TYPES.containsKey(moveType)) {
            // IPR (stream C engine request, GL-4 style): an issue that posts its own GL must map to a posting rule; an
            // unknown move type used to fall back to cost of sales.
            throw ApiException.badRequest("Stock move type \"" + moveType + "\" has no stock posting rule; use one of "
                    + String.join(", ", new java.util.TreeSet<>(INV_MOVE_TYPES.keySet()))
                    + ", or post the journal in the calling document");
        }
        assertGuardedLocation(dto, location, false, false);
        assertNotFrozen(org, dto, dto.locationId);
        Eff eff = effective(dto);
        requireVariant(product, dto.variantId, dto.locationId, VariantRule.EXISTING);
        product = withVariantCost(product, dto.variantId);
        String productName = str(product, "name");
        BigDecimal qty = dto.baseQuantity != null ? dto.baseQuantity
                : toBaseQtyCost(product, dto.uomId, dto.uomFactor, dto.quantity, Money.ZERO)[0];
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("Quantity must be positive");
        }
        String variantId = dto.variantId;
        String variantKey = variantId == null ? "" : variantId;
        SettingContext ctx = new SettingContext(dto.productId, str(product, "categoryId"), dto.locationId);
        var override = settings.describe("inventory.defaultPickingStrategy", ctx);
        String strategy = dto.distStrategy != null ? dto.distStrategy
                : !"default".equals(override.source()) && override.value() != null ? String.valueOf(override.value())
                : product.get("pickingStrategy") != null ? str(product, "pickingStrategy") : "FEFO";
        String serialPolicy = settings.resolveString("inventory.serialPolicy", ctx);
        String expiredPolicy = settings.resolveString("inventory.expiredStockPolicy", ctx);
        boolean skipExpired = "skip_expired".equals(expiredPolicy) && !"expiry_write_off".equals(dto.moveType)
                && !"waste".equals(dto.moveType);
        String pickable = skipExpired ? " AND (\"expiryDate\" IS NULL OR \"expiryDate\" > " + NOW + ")" : "";
        boolean serialTracking = bool(product, "serialTracking");
        boolean serialRequired = serialTracking && "required".equals(serialPolicy);
        int requestedSerials = dto.serialNumbers == null ? 0 : dto.serialNumbers.size();
        if (serialTracking) {
            assertWholeSerialQuantity(productName, qty);
        }
        if (serialRequired && qty.compareTo(BigDecimal.valueOf(requestedSerials)) != 0) {
            throw ApiException.badRequest("Serial policy is \"required\": issuing " + JsNumbers.dec(qty)
                    + " base unit(s) of " + productName + " needs " + JsNumbers.dec(qty) + " serial number(s), got "
                    + requestedSerials);
        }
        if (serialTracking && BigDecimal.valueOf(requestedSerials).compareTo(qty) > 0) {
            // IPR C-2: every caller gets this check (fleet dispatch included): one serial number per unit at most.
            throw ApiException.badRequest("Issuing " + JsNumbers.dec(qty) + " base unit(s) of " + productName + " but "
                    + requestedSerials + " serial number(s) were given; give at most one serial number per unit");
        }
        boolean allowNegative = settings.resolveBool("inventory.allowNegativeStock", ctx);
        String ledgerNotes = joinNotes(dto.notes, dto.reference, eff.note(dto.date));

        // A-025 + the engine lock order: advisory then row lock; every quantity below is read under it.
        Map<String, Object> stockItem = lockQuant(org, dto.productId, variantId, dto.locationId,
                dec(product, "costPrice")).row();
        String stockItemId = str(stockItem, "id");
        boolean usesLayers = "FIFO".equals(str(product, "costingMethod")) || bool(product, "batchTracking");
        assertNotReservedForProcessing(dto, org, variantKey, qty, dec(stockItem, "quantity"), productName);
        if (dto.requireAvailable && usesLayers && "MANUAL".equals(strategy) && dto.batchNumber != null) {
            // A named lot must cover the issue itself: other lots never make up for it (ADR-S060).
            BigDecimal inLot = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE \"organizationId\" = ?"
                            + " AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ?"
                            + " AND \"batchNumber\" = ? AND quantity > 0 AND \"isActive\" = true" + pickable)
                    .params(org, dto.productId, variantId, dto.locationId, dto.batchNumber).query(BigDecimal.class)
                    .single();
            if (qty.compareTo(inLot) > 0) {
                throw ApiException.badRequest("Insufficient stock in lot " + dto.batchNumber + " of " + productName
                        + ": on hand " + JsNumbers.dec(inLot) + ", requested " + JsNumbers.dec(qty) + ".");
            }
        }

        // A lot-tracked item set to block never goes short, whatever the negative-stock setting: a shortfall would have
        // no lot and no cost (ADR-S060).
        boolean lotBlock = bool(product, "batchTracking") && "block".equals(str(product, "stockPolicy"));
        // inventory.allowNegativeStock=false blocks every issue except a POS / offline sale being drained (that sale
        // already happened; it keeps following the item stock policy).
        boolean strict = !allowNegative && !isSaleDrain(dto);
        if (!allowNegative || dto.requireAvailable || lotBlock) {
            BigDecimal available = usesLayers
                    ? lots(org, dto, variantId, "SELECT COALESCE(SUM(quantity), 0)", pickable, "", null)
                            .query(BigDecimal.class).single()
                    : dec(stockItem, "quantity");
            if (qty.compareTo(available) > 0) {
                String policy = str(product, "stockPolicy");
                if (dto.requireAvailable || "block".equals(policy) || strict) {
                    throw ApiException.badRequest("Insufficient stock for " + productName + ": on hand "
                            + JsNumbers.dec(available) + ", requested " + JsNumbers.dec(qty) + ".");
                }
                if ("warn".equals(policy)) {
                    log.warn("[stock] oversell {} @ {}: on hand {}, issuing {}",
                            product.get("sku") != null ? product.get("sku") : dto.productId, dto.locationId,
                            JsNumbers.dec(available), JsNumbers.dec(qty));
                }
            }
        }
        if (dto.honourSalesReservations) {
            // IPR E-1a: a sale drain is refused only where the availability rules above would refuse it.
            boolean saleRefusable = dto.requireAvailable || "block".equals(str(product, "stockPolicy")) || lotBlock;
            assertSalesReservationsLeft(org, dto, productName, variantKey, dto.locationId, qty,
                    dec(stockItem, "quantity"), !isSaleDrain(dto) || saleRefusable);
        }

        String ledgerCode = seq.next("stock_move", "STK/", 6);
        BigDecimal totalValue = Money.ZERO;
        BigDecimal unitCost;
        BigDecimal residueAt = null;
        boolean carriedCost = !serialTracking && !usesLayers && dto.carriedUnitCost != null
                && "AVCO".equals(str(product, "costingMethod"));
        IssueCtx ic = new IssueCtx(org, ledgerCode, dto, variantId, moveType, stockItemId,
                trace(dto, product, qty, dto.quantity), eff.at());

        if (serialTracking) {
            List<String> requested = dto.serialNumbers == null ? List.of() : dto.serialNumbers;
            if (new HashSet<>(requested).size() != requested.size()) {
                throw ApiException.badRequest("Duplicate serial numbers in the issue");
            }
            List<Map<String, Object>> serialRows = new ArrayList<>();
            if (!requested.isEmpty()) {
                serialRows.addAll(SqlRows.list(jdbc.sql("SELECT id, \"unitCost\", \"batchId\", \"serialNumber\" FROM"
                                + " \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                                + " AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ? AND status = 'in_stock'"
                                + " AND \"serialNumber\" = ANY(?::text[]) ORDER BY \"serialNumber\"")
                        .params(org, dto.productId, variantId, dto.locationId, requested.toArray(String[]::new))));
                if (serialRows.size() != requested.size()) {
                    Set<String> foundSet = new HashSet<>();
                    serialRows.forEach(s -> foundSet.add((String) s.get("serialNumber")));
                    List<String> missing = requested.stream().filter(s -> !foundSet.contains(s)).toList();
                    throw ApiException.badRequest("Serial number(s) not available at this location: "
                            + String.join(", ", missing));
                }
            }
            // IPR C-2: fewer serial numbers than units (capture_optional) — the rest are picked from the units in stock
            // (oldest first), so no serial unit stays "in stock" for a unit that left.
            boolean serialLots = bool(product, "batchTracking");
            // IPR review 3 (#3): a named lot (MANUAL) of a serial+lot product gives its own serial units, then its own
            // units without a serial; other lots' serial units only for what the lot lacks (as a lot-only product).
            String namedLot = serialLots && "MANUAL".equals(strategy) && dto.batchNumber != null
                    && !dto.batchNumber.isBlank() ? dto.batchNumber : null;
            Set<String> fromOtherLots = new HashSet<>();
            int toPick = qty.intValue() - serialRows.size();
            if (toPick > 0) {
                List<String> chosen = new ArrayList<>(serialRows.stream().map(r -> (String) r.get("id")).toList());
                if (dto.unserialisedRestFirst) {
                    // IPR review 3 (#2): a take-back leaves with units without a serial first (the receipt row's
                    // unserialised part); serial units of other receipts only for what those do not cover.
                    toPick -= Math.min(toPick, unserialisedFree(org, dto, variantId, stockItem, serialLots, namedLot,
                            pickable));
                }
                if (namedLot != null && toPick > 0) {
                    List<Map<String, Object>> inLot = pickSerials(org, dto, variantId, dto.locationId, toPick, chosen,
                            namedLot, true);
                    serialRows.addAll(inLot);
                    inLot.forEach(r -> chosen.add((String) r.get("id")));
                    toPick -= inLot.size();
                    if (toPick > 0 && !dto.unserialisedRestFirst) {
                        toPick -= Math.min(toPick, unserialisedFree(org, dto, variantId, stockItem, true, namedLot,
                                pickable));
                    }
                }
                if (toPick > 0) {
                    List<Map<String, Object>> rest = pickSerials(org, dto, variantId, dto.locationId, toPick, chosen,
                            null, serialLots);
                    if (namedLot != null) {
                        rest.forEach(r -> fromOtherLots.add((String) r.get("id")));
                    }
                    serialRows.addAll(rest);
                }
            }
            String shortNote = namedLot == null ? ledgerNotes
                    : joinNotes(ledgerNotes, "LOT " + namedLot + " SHORT: drawn from other lots");
            String issueRef = (dto.sourceType != null ? dto.sourceType : "issue") + ":"
                    + (dto.sourceId != null ? dto.sourceId : ledgerCode);
            BigDecimal running = dec(stockItem, "quantity");
            for (Map<String, Object> s : serialRows) {
                BigDecimal sUnit = s.get("unitCost") != null ? (BigDecimal) s.get("unitCost") : dec(product, "costPrice");
                int flipped = jdbc.sql("UPDATE \"InventorySerial\" SET status = 'issued', \"issuedAt\" = " + NOW
                                + ", \"issueRef\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND status = 'in_stock'")
                        .params(issueRef, s.get("id")).update();
                if (flipped != 1) {
                    throw ApiException.conflict("Serial number " + s.get("serialNumber") + " was issued concurrently");
                }
                // IPR C-1: a serial unit of a lot-tracked product leaves its lot too (open lots = on-hand).
                decrementSerialLot(org, (String) s.get("batchId"), (String) s.get("serialNumber"));
                BigDecimal after = running.subtract(BigDecimal.ONE);
                decrementStockItem(stockItemId, BigDecimal.ONE);
                ic.row((String) s.get("batchId"), (String) s.get("id"), running, BigDecimal.ONE.negate(), after, sUnit,
                        sUnit, fromOtherLots.contains((String) s.get("id")) ? shortNote : ledgerNotes);
                totalValue = totalValue.add(sUnit);
                running = after;
            }
            BigDecimal covered = BigDecimal.valueOf(serialRows.size());
            if (qty.compareTo(covered) > 0 && serialRequired) {
                throw ApiException.badRequest("Serial policy is \"required\": only " + JsNumbers.dec(covered) + " of "
                        + JsNumbers.dec(qty) + " serialised unit(s) of " + productName + " are available");
            }
            if (qty.compareTo(covered) > 0) {
                BigDecimal remaining = qty.subtract(covered);
                // Gaps h-00 F1: units without a serial leave at the quant's average, which is what they entered at
                // (serialAverage); the cost price only while the quant has none.
                BigDecimal overflowUnit = dec(stockItem, "runningAverageCost").signum() > 0
                        ? dec(stockItem, "runningAverageCost") : dec(product, "costPrice");
                BigDecimal overflowValue = overflowUnit.multiply(remaining);
                if (serialLots) {
                    // IPR C-1: units without a serial of a serial+lot product leave the lots' unserialised part (FEFO),
                    // valued at the average as before; only what no lot covers is a lot-less shortfall row. IPR review
                    // 3 (#3): a named lot's unserialised part first, the other lots only for what it lacks.
                    if (namedLot != null) {
                        BigDecimal drawn = issueUnserialisedFromLots(ic, variantId, remaining, running, overflowUnit,
                                pickable + " AND \"batchNumber\" = ?", namedLot, ledgerNotes);
                        running = running.subtract(drawn);
                        remaining = remaining.subtract(drawn);
                    }
                    if (remaining.signum() > 0) {
                        BigDecimal drawn = issueUnserialisedFromLots(ic, variantId, remaining, running, overflowUnit,
                                pickable, null, shortNote);
                        running = running.subtract(drawn);
                        remaining = remaining.subtract(drawn);
                    }
                }
                if (remaining.signum() > 0) {
                    BigDecimal after = running.subtract(remaining);
                    decrementStockItem(stockItemId, remaining);
                    ic.row(null, null, running, remaining.negate(), after, overflowUnit,
                            overflowUnit.multiply(remaining), ledgerNotes);
                }
                totalValue = totalValue.add(overflowValue);
            }
            unitCost = totalValue.signum() > 0 ? Money.div(totalValue, qty) : Money.ZERO;
        } else if (usesLayers) {
            String order = "FIFO".equals(strategy) ? " ORDER BY \"receivedAt\" ASC"
                    : " ORDER BY \"expiryDate\" ASC NULLS LAST, \"receivedAt\" ASC";
            boolean manualLot = "MANUAL".equals(strategy) && dto.batchNumber != null;
            LayerState st = new LayerState(qty, dec(stockItem, "quantity"));
            consumeBatches(ic, SqlRows.list(lots(org, dto, variantId, "SELECT *",
                    pickable + (manualLot ? " AND \"batchNumber\" = ?" : ""), order,
                    manualLot ? dto.batchNumber : null)), st, ledgerNotes);
            if (st.remaining.signum() > 0 && manualLot) {
                // The named lot is short: the other valid lots cover the rest before the quant goes short, so open lots
                // keep summing to on-hand (a lot-less shortfall next to open lots would be issued twice).
                consumeBatches(ic, SqlRows.list(lots(org, dto, variantId, "SELECT *", pickable,
                        " ORDER BY \"expiryDate\" ASC NULLS LAST, \"receivedAt\" ASC", null)), st,
                        joinNotes(ledgerNotes, "LOT " + dto.batchNumber + " SHORT: drawn from other lots"));
            }
            if (st.remaining.signum() > 0 && skipExpired) {
                List<Map<String, Object>> expiredLots = SqlRows.list(lots(org, dto, variantId, "SELECT *",
                        " AND \"expiryDate\" <= " + NOW, " ORDER BY \"expiryDate\" ASC, \"receivedAt\" ASC",
                        null));
                if (!expiredLots.isEmpty()) {
                    BigDecimal before = st.remaining;
                    String expiredNote = joinNotes(ledgerNotes,
                            "EXPIRED LOT CONSUMED (skip_expired: no valid lot available)");
                    consumeBatches(ic, expiredLots, st, expiredNote);
                    BigDecimal expiredQty = before.subtract(st.remaining);
                    if (expiredQty.signum() > 0) {
                        jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", \"locationId\", \"productId\","
                                        + " description, quantity, kind, reason, payload, status, \"createdAt\")"
                                        + " VALUES (?, ?, ?, ?, ?, ?, 'expired_lot_consumed', ?, ?::jsonb, 'open', " + NOW + ")")
                                .params(UUID.randomUUID().toString(), org, dto.locationId, dto.productId, productName,
                                        expiredQty, "Issued " + JsNumbers.dec(expiredQty)
                                                + " from expired lot(s) because no unexpired stock was available ("
                                                + ledgerCode + ")",
                                        com.inventoryerp.shared.web.EntityJson.toJson(Obj.of("ledgerCode", ledgerCode,
                                                "moveType", moveType, "sourceType", dto.sourceType, "sourceId",
                                                dto.sourceId, "reference", dto.reference)))
                                .update();
                    }
                }
            }
            if (st.remaining.signum() > 0) {
                BigDecimal consumedQty = qty.subtract(st.remaining);
                BigDecimal overflowUnit = consumedQty.signum() > 0 ? Money.div(st.totalValue, consumedQty)
                        : dec(product, "costPrice");
                BigDecimal overflowValue = overflowUnit.multiply(st.remaining);
                BigDecimal before = st.running;
                BigDecimal after = decrementStockItem(stockItemId, st.remaining);
                ic.row(null, null, before, st.remaining.negate(), after, overflowUnit, overflowValue, ledgerNotes);
                recordShortfallCost(stockItemId, dec(stockItem, "runningAverageCost"), before, after, overflowUnit);
                st.totalValue = st.totalValue.add(overflowValue);
                st.remaining = Money.ZERO;
            }
            totalValue = st.totalValue;
            unitCost = totalValue.signum() > 0 ? Money.div(totalValue, qty) : Money.ZERO;
        } else {
            if (carriedCost) {
                // Units leaving at the cost they entered this quant at (a transit line's dispatch cost).
                unitCost = Money.max(dto.carriedUnitCost, Money.ZERO);
                totalValue = unitCost.multiply(qty);
            } else if ("AVCO".equals(str(product, "costingMethod"))) {
                unitCost = Money.max(dec(stockItem, "runningAverageCost"), Money.ZERO);
                totalValue = unitCost.multiply(qty);
            } else {
                Resolution r = costResolver.resolveIssueCost(
                        new ProductCost(str(product, "costingMethod"), (BigDecimal) product.get("costPrice")),
                        new Quant(dec(stockItem, "quantity"), dec(stockItem, "runningAverageCost")), qty, null, null);
                unitCost = r.unitCost();
                totalValue = r.totalValue();
            }
            BigDecimal before = dec(stockItem, "quantity");
            BigDecimal after = decrementStockItem(stockItemId, qty);
            ic.row(null, null, before, qty.negate(), after, unitCost, totalValue, ledgerNotes);
            if (carriedCost) {
                reAverage(stockItemId, before, dec(stockItem, "runningAverageCost"), totalValue, after);
            }
            residueAt = after;
        }

        if (totalValue.signum() > 0 && !dto.skipGlPosting) {
            stockPosting.postIssue(new StockPostingService.IssuePosting(dto.productId, dto.locationId, variantId, qty,
                    date, dto.sourceType != null ? dto.sourceType : "stock_issue",
                    dto.sourceId != null ? dto.sourceId : ledgerCode, "inventory:issue:" + org + ":" + ledgerCode,
                    "Stock issue · " + productName + " · " + JsNumbers.dec(dto.quantity),
                    INV_MOVE_TYPES.getOrDefault(moveType, "STOCK_OUT"),
                    usesLayers || serialTracking || carriedCost ? totalValue : null));
        }
        // IPR GL-7: swept also when the caller posts the issue journal itself (skipGl). The caller's journal carries the
        // issue row's value (Issued.totalValue); the residue is what Σ ledger value keeps beyond it, so the sweep's own
        // COGS_CORRECTION entry (its own posting key) is never part of the caller's journal: no double posting.
        if (residueAt != null) {
            sweepAvcoResidue(org, product, dto.productId, variantId, dto.locationId, residueAt,
                    dto.sourceType != null ? dto.sourceType : "stock_issue",
                    dto.sourceId != null ? dto.sourceId : ledgerCode, ledgerCode, date);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org);
        payload.put("productId", dto.productId);
        payload.put("locationId", dto.locationId);
        payload.put("ledgerCode", ledgerCode);
        payload.put("quantity", JsNumbers.dec(dto.quantity));
        payload.put("unitCost", JsNumbers.dec(unitCost));
        payload.put("totalValue", JsNumbers.dec(totalValue));
        // Phase I2 (audit R-1): additive keys; "quantity" stays in the caller's (document) unit as in Nest.
        payload.put("baseQuantity", JsNumbers.dec(qty));
        payload.put("variantId", variantId);
        payload.put("uomId", ic.trace.docUomId());
        payload.put("uomFactor", ic.trace.factor() == null ? null : JsNumbers.dec(ic.trace.factor()));
        events.publishOnCommit("stock.issued", payload);
        correctLateCountRows(org, ledgerCode, dto, eff);
        audit.recordInTx(AuditInput.of("StockItem", stockItemId, "issue", null,
                Obj.of("productId", dto.productId, "locationId", dto.locationId, "quantity", JsNumbers.js(dto.quantity),
                        "unitCost", JsNumbers.dec(unitCost), "totalValue", JsNumbers.dec(totalValue), "ledgerCode",
                        ledgerCode)));
        return new Issued(ledgerCode, dto.quantity, qty, str(product, "uomId"), unitCost, totalValue);
    }

    /**
     * Stock reserved for a draft processing job (ADR-S060) cannot be taken by another movement. Only processing
     * reservations count, so an organisation not using processing sees no change; reversals are never blocked and POS
     * is out of scope. Runs under the StockItem row lock.
     */
    private void assertNotReservedForProcessing(StockMove dto, String org, String variantKey, BigDecimal qty,
            BigDecimal onHand, String productName) {
        String source = dto.sourceType == null ? "" : dto.sourceType;
        if (source.contains("reversal") || source.startsWith("pos")) {
            return;
        }
        BigDecimal reserved = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ? AND status = 'active' AND"
                        + " \"sourceType\" = 'processing_job_input'")
                .params(org, dto.productId, variantKey, dto.locationId).query(BigDecimal.class).single();
        if (reserved.signum() > 0 && qty.compareTo(onHand.subtract(reserved)) > 0) {
            throw ApiException.badRequest("Insufficient unreserved stock for " + productName + ": on hand "
                    + JsNumbers.dec(onHand) + ", reserved for processing jobs " + JsNumbers.dec(reserved)
                    + ", requested " + JsNumbers.dec(qty) + ".");
        }
    }

    /** Per-issue constants for writing ledger rows. */
    private final class IssueCtx {
        final String org;
        final String ledgerCode;
        final StockMove dto;
        final String variantId;
        final String moveType;
        final String stockItemId;
        final Trace trace;
        final Instant effectiveAt;

        IssueCtx(String org, String ledgerCode, StockMove dto, String variantId, String moveType, String stockItemId,
                Trace trace, Instant effectiveAt) {
            this.org = org;
            this.ledgerCode = ledgerCode;
            this.dto = dto;
            this.variantId = variantId;
            this.moveType = moveType;
            this.stockItemId = stockItemId;
            this.trace = trace;
            this.effectiveAt = effectiveAt;
        }

        void row(String batchId, String serialId, BigDecimal before, BigDecimal change, BigDecimal after,
                BigDecimal unitCost, BigDecimal totalValue, String notes) {
            insertLedger(org, ledgerCode, dto.productId, variantId, dto.locationId, batchId, serialId, moveType, before,
                    change, after, unitCost, totalValue, dto.sourceType, dto.sourceId, notes, dto.responsibleById,
                    dto.approvedById, trace, effectiveAt);
        }
    }

    /** Mutable FIFO walk state. */
    private static final class LayerState {
        BigDecimal remaining;
        BigDecimal running;
        BigDecimal totalValue = Money.ZERO;

        LayerState(BigDecimal remaining, BigDecimal running) {
            this.remaining = remaining;
            this.running = running;
        }
    }

    private void consumeBatches(IssueCtx ic, List<Map<String, Object>> batches, LayerState st, String rowNotes) {
        for (Map<String, Object> batch : batches) {
            if (st.remaining.signum() <= 0) {
                break;
            }
            BigDecimal batchQty = dec(batch, "quantity");
            BigDecimal consumed = Money.min(st.remaining, batchQty);
            int updated = jdbc.sql("UPDATE \"InventoryBatch\" SET quantity = quantity - ?, \"updatedAt\" = " + NOW
                            + " WHERE id = ? AND \"organizationId\" = ? AND quantity >= ?")
                    .params(consumed, batch.get("id"), ic.org, consumed).update();
            if (updated == 0) {
                continue;
            }
            if (batchQty.subtract(consumed).signum() <= 0) {
                jdbc.sql("UPDATE \"InventoryBatch\" SET \"isActive\" = false, \"updatedAt\" = " + NOW
                                + " WHERE id = ? AND \"organizationId\" = ?")
                        .params(batch.get("id"), ic.org).update();
            }
            decrementStockItem(ic.stockItemId, consumed);
            BigDecimal after = st.running.subtract(consumed);
            BigDecimal batchUnit = batch.get("unitCost") != null ? (BigDecimal) batch.get("unitCost") : Money.ZERO;
            BigDecimal value = batchUnit.multiply(consumed);
            ic.row((String) batch.get("id"), null, st.running, consumed.negate(), after, batchUnit, value, rowNotes);
            st.totalValue = st.totalValue.add(value);
            st.remaining = st.remaining.subtract(consumed);
            st.running = after;
        }
    }

    // ================================================================== adjust

    /** Count a quant to {@code countedQuantity}; the variance posts ADJUSTMENT_GAIN / ADJUSTMENT_LOSS. */
    @Transactional
    public Adjusted adjust(StockMove dto) {
        String org = TenantContext.organizationId();
        // IPR SW-6: the period check and every journal use the move's date (now when it has none).
        Instant date = dto.date != null ? dto.date : Instant.now();
        assertStockPeriodOpen(date);
        holdCostingShared(org, dto.productId);
        Map<String, Object> product = lookups.product(dto.productId);
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        Map<String, Object> location = lookups.location(dto.locationId);
        if (location == null) {
            throw ApiException.notFound("Location not found");
        }
        assertGuardedLocation(dto, location, true, false);
        assertNotFrozen(org, dto, dto.locationId);
        Eff eff = effective(dto);
        requireVariant(product, dto.variantId, dto.locationId, VariantRule.EXISTING);
        product = withVariantCost(product, dto.variantId);
        // Phase I1b (audit U-3): stock documents count in the base unit (ADR-S003); a caller that names a unit gets its
        // count / delta converted instead of being read as base units.
        BigDecimal counted = dto.countedQuantity == null ? null : toBaseQty(product, dto, dto.countedQuantity);
        BigDecimal deltaIn = dto.deltaQuantity == null ? null : toBaseQty(product, dto, dto.deltaQuantity);
        String variantId = dto.variantId;
        boolean deltaMode = deltaIn != null;
        if (deltaMode ? deltaIn.signum() == 0
                : counted.signum() == 0 && lookups.stockItem(dto.productId, variantId, dto.locationId) == null) {
            return new Adjusted(null, Money.ZERO, null, Money.ZERO, null);
        }
        // Read the counted-against quantity under the quant lock: a concurrent move can no longer slip between the
        // read and the write (the layered path below applies a delta, so a stale read would be a lost update).
        LockedQuant quant = lockQuant(org, dto.productId, variantId, dto.locationId, dec(product, "costPrice"));
        Map<String, Object> stockItem = quant.created() ? null : quant.row();
        BigDecimal currentQty = quant.quantity();
        if (deltaMode) {
            counted = currentQty.add(deltaIn);
        }
        BigDecimal delta = counted.subtract(currentQty);
        if (delta.signum() == 0) {
            return new Adjusted(null, Money.ZERO, null, Money.ZERO, null);
        }
        if (delta.signum() > 0) {
            // D2: a count that adds stock is a receipt — never onto the legacy no-variant quant or an inactive variant.
            requireVariant(product, variantId, dto.locationId, VariantRule.INBOUND);
        }
        if (bool(product, "batchTracking") || bool(product, "serialTracking")
                || "FIFO".equals(str(product, "costingMethod"))) {
            return adjustLayered(dto, product, currentQty, counted, delta, stockItem, date);
        }
        String ledgerCode = seq.next("stock_move", "STK/", 6);
        String moveType = delta.signum() > 0 ? "adjustment_in" : "adjustment_out";
        BigDecimal avg = stockItem != null ? dec(stockItem, "runningAverageCost") : Money.ZERO;
        BigDecimal unitCost = avg.signum() > 0 ? avg : dec(product, "costPrice");
        BigDecimal newAverage = null;
        BigDecimal shortCorrection = null;
        if ("AVCO".equals(str(product, "costingMethod"))) {
            // IPR C-3 / GL-6 (D3): an AVCO quant is valued at its own average (no cost-price fallback), so a count
            // loss leaves at that average (as an issue does) and a gain onto a quant without an average enters at
            // the current cost and gives the quant its average; valuation = ledger = GL.
            if (delta.signum() < 0) {
                unitCost = avg.max(Money.ZERO);
            } else if (avg.signum() <= 0) {
                unitCost = currentCost(quant, product, dto.productId, variantId, dto.locationId);
                if (currentQty.signum() >= 0 && counted.signum() > 0) {
                    newAverage = Money.div(currentQty.multiply(avg.max(Money.ZERO)).add(delta.multiply(unitCost)),
                            counted);
                } else if (currentQty.signum() < 0) {
                    // IPR review 3 (#5): a gain onto a negative quant without an average, as a receipt would (receiveCore
                    // via CostResolver): the quant takes the gain's cost and the short units issued at the old
                    // average are squared by a negative-stock cost correction, so no value is stranded.
                    Resolution r = costResolver.resolveReceiptCost(
                            new ProductCost("AVCO", (BigDecimal) product.get("costPrice")),
                            new Quant(currentQty, avg.max(Money.ZERO)), delta, unitCost);
                    newAverage = r.newRunningAverage();
                    shortCorrection = r.costCorrection();
                }
            }
        }
        if (newAverage != null) {
            jdbc.sql("UPDATE \"StockItem\" SET quantity = ?, \"runningAverageCost\" = ?, \"updatedAt\" = " + NOW
                    + " WHERE id = ? AND \"organizationId\" = ?").params(counted, newAverage, quant.id(), org).update();
        } else {
            jdbc.sql("UPDATE \"StockItem\" SET quantity = ?, \"updatedAt\" = " + NOW
                    + " WHERE id = ? AND \"organizationId\" = ?").params(counted, quant.id(), org).update();
        }
        String sourceType = dto.sourceType != null ? dto.sourceType : "stock_adjust";
        String sourceId = dto.sourceId != null ? dto.sourceId : ledgerCode;
        insertLedger(org, ledgerCode, dto.productId, variantId, dto.locationId, null, null, moveType, currentQty, delta,
                counted, unitCost, unitCost.multiply(delta.abs()), sourceType, sourceId,
                joinNotes(dto.notes, eff.note(dto.date)), dto.responsibleById, dto.approvedById,
                deltaMode ? trace(dto, product, deltaIn, dto.deltaQuantity)
                        : trace(dto, product, counted, dto.countedQuantity), eff.at());
        // ADR-S088 closed-period traceability: the journal memo names the ledger code.
        stockPosting.postAdjustmentAt(dto.productId, dto.locationId, delta, unitCost, date, sourceType, sourceId,
                "Stock adjustment · " + str(product, "name") + " · " + ledgerCode, dto.openingBalance);
        postCostCorrection(org, dto.productId, variantId, dto.locationId, counted, str(product, "name"), shortCorrection,
                date, sourceType, sourceId, ledgerCode);
        sweepAvcoResidue(org, product, dto.productId, variantId, dto.locationId, counted, sourceType, sourceId,
                ledgerCode, date);
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org);
        payload.put("productId", dto.productId);
        payload.put("locationId", dto.locationId);
        payload.put("ledgerCode", ledgerCode);
        payload.put("quantity", JsNumbers.dec(counted));
        payload.put("delta", JsNumbers.dec(delta));
        events.publishOnCommit("stock.adjusted", payload);
        correctLateCountRows(org, ledgerCode, dto, eff);
        audit.recordInTx(AuditInput.of("StockItem", stockItem != null ? str(stockItem, "id") : "new", "adjust", null,
                Obj.of("productId", dto.productId, "locationId", dto.locationId, "countedQuantity",
                        JsNumbers.js(counted), "previousQuantity", JsNumbers.js(currentQty), "ledgerCode",
                        ledgerCode)));
        return new Adjusted(ledgerCode, currentQty, counted, delta, unitCost);
    }

    /** Batch / serial / FIFO variance: moves the identified layers, then posts the variance at their value. */
    private Adjusted adjustLayered(StockMove dto, Map<String, Object> product, BigDecimal currentQty, BigDecimal counted,
            BigDecimal delta, Map<String, Object> stockItem, Instant date) {
        String sourceType = dto.sourceType != null ? dto.sourceType : "stock_adjust";
        String ledgerCode;
        BigDecimal totalValue;
        if (delta.signum() < 0) {
            StockMove m = StockMove.of(dto.productId, dto.locationId, delta.abs()).variant(dto.variantId)
                    .source(sourceType, dto.sourceId).notes(dto.notes).serials(dto.serialNumbers).batch(dto.batchNumber)
                    .people(dto.responsibleById, dto.approvedById).moveType("adjustment_out")
                    .strategy(dto.batchNumber != null ? "MANUAL" : null).skipGl();
            carryCountFields(dto, m, date);
            if (dto.lotId != null) {
                // Hardening R5b (P2-2): the counted lot itself goes first (a same-number lot never absorbs it).
                m.prefer(List.of(dto.lotId), null);
            }
            Issued res = issue(m);
            ledgerCode = res.ledgerCode();
            totalValue = res.totalValue();
        } else {
            String org = TenantContext.organizationId();
            Map<String, Object> lot = null;
            if (bool(product, "batchTracking")) {
                String lotSql = "SELECT * FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                        + " AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ?";
                if (dto.lotId != null) {
                    lot = SqlRows.one(jdbc.sql(lotSql + " AND id = ?")
                            .params(org, dto.productId, dto.variantId, dto.locationId, dto.lotId));
                }
                if (lot == null && dto.batchNumber != null) {
                    lot = SqlRows.one(jdbc.sql(lotSql + " AND \"batchNumber\" = ? ORDER BY \"receivedAt\" DESC LIMIT 1")
                            .params(org, dto.productId, dto.variantId, dto.locationId, dto.batchNumber));
                }
                if (lot == null) {
                    lot = SqlRows.one(jdbc.sql(lotSql + " ORDER BY \"receivedAt\" DESC LIMIT 1")
                            .params(org, dto.productId, dto.variantId, dto.locationId));
                }
            }
            BigDecimal unitCost = lot != null && lot.get("unitCost") != null && dec(lot, "unitCost").signum() > 0
                    ? dec(lot, "unitCost")
                    : stockItem != null && dec(stockItem, "runningAverageCost").signum() > 0
                            ? dec(stockItem, "runningAverageCost") : dec(product, "costPrice");
            String expiry = dto.expiryDate != null ? dto.expiryDate
                    : lot != null && lot.get("expiryDate") != null
                            ? com.inventoryerp.kernel.web.JsonConfig.toJsIso((Instant) lot.get("expiryDate")) : null;
            if (bool(product, "expiryTracking") && expiry == null) {
                throw ApiException.badRequest(str(product, "name")
                        + " is expiry-tracked and has no existing lot here — give the adjustment a batch number and"
                        + " expiry date");
            }
            boolean batchTracking = bool(product, "batchTracking");
            StockMove m = StockMove.of(dto.productId, dto.locationId, delta.abs()).variant(dto.variantId)
                    .source(sourceType, dto.sourceId).notes(dto.notes).serials(dto.serialNumbers)
                    .people(dto.responsibleById, dto.approvedById).moveType("adjustment_in").unitCost(unitCost)
                    .batch(batchTracking ? (dto.batchNumber != null ? dto.batchNumber
                            : lot != null ? str(lot, "batchNumber")
                            : "ADJ-" + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")) : null)
                    .expiry(batchTracking ? expiry : null);
            carryCountFields(dto, m, date);
            if (dto.lotId != null && lot != null && dto.lotId.equals(str(lot, "id"))) {
                // Hardening R5b (P2-2): the gain goes back into the counted lot row.
                m.returnBatchId = dto.lotId;
            }
            Received res = receiveCore(m, null);
            ledgerCode = res.ledgerCode();
            totalValue = res.totalValue();
        }
        BigDecimal absQty = delta.abs();
        if (totalValue.signum() > 0) {
            stockPosting.postAdjustmentAt(dto.productId, dto.locationId, delta, Money.div(totalValue, absQty), date,
                    sourceType, dto.sourceId != null ? dto.sourceId : ledgerCode, "Stock adjustment · "
                            + str(product, "name") + " · " + ledgerCode, dto.openingBalance);
        }
        audit.recordInTx(AuditInput.of("StockItem", stockItem != null ? str(stockItem, "id") : "new", "adjust", null,
                Obj.of("productId", dto.productId, "locationId", dto.locationId, "countedQuantity",
                        JsNumbers.js(counted), "previousQuantity", JsNumbers.js(currentQty), "ledgerCode",
                        ledgerCode, "layered", true)));
        return new Adjusted(ledgerCode, currentQty, counted, delta,
                absQty.signum() > 0 ? Money.div(totalValue, absQty) : Money.ZERO);
    }

    // ================================================================== transfer

    /** Move stock between locations carrying its cost (lots / serials move too); GL via transfer rules. */
    @Transactional
    public Transferred transfer(StockMove dto) {
        String org = TenantContext.organizationId();
        // IPR SW-6: the period check and every journal use the move's date (now when it has none).
        Instant date = dto.date != null ? dto.date : Instant.now();
        assertStockPeriodOpen(date);
        if (dto.fromLocationId.equals(dto.toLocationId)) {
            throw ApiException.badRequest("Source and destination locations must be different");
        }
        holdCostingShared(org, dto.productId);
        Map<String, Object> product = lookups.product(dto.productId);
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        Map<String, Object> fromLocation = lookups.location(dto.fromLocationId);
        if (fromLocation == null) {
            throw ApiException.notFound("Source location not found");
        }
        Map<String, Object> toLocation = lookups.location(dto.toLocationId);
        if (toLocation == null) {
            throw ApiException.notFound("Destination location not found");
        }
        assertGuardedLocation(dto, fromLocation, false, true);
        assertGuardedLocation(dto, toLocation, true, true);
        requireVariant(product, dto.variantId, dto.fromLocationId, VariantRule.EXISTING);
        product = withVariantCost(product, dto.variantId);
        if (dto.quantity.signum() <= 0) {
            throw ApiException.badRequest("Quantity must be positive");
        }
        // Phase I1b (audit U-3): a transfer that names a unit (e.g. a sales return moving 2 boxes to quarantine) moves
        // its base quantity; stock documents keep sending base units (ADR-S003).
        BigDecimal qty = toBaseQty(product, dto, dto.quantity);
        boolean serialTracking = bool(product, "serialTracking");
        if (serialTracking) {
            assertWholeSerialQuantity(str(product, "name"), qty);
        }
        String variantId = dto.variantId;
        if (lookups.stockItem(dto.productId, variantId, dto.fromLocationId) == null) {
            throw ApiException.badRequest("No stock found at source location");
        }
        assertNotFrozen(org, dto, dto.fromLocationId, dto.toLocationId);
        Eff eff = effective(dto);
        String rowNotes = joinNotes(dto.notes, eff.note(dto.date));
        // Both quants up front in location order (the engine lock order), so A→B and B→A cannot deadlock.
        LockedQuant from;
        LockedQuant to;
        if (dto.fromLocationId.compareTo(dto.toLocationId) < 0) {
            from = lockQuant(org, dto.productId, variantId, dto.fromLocationId, Money.ZERO);
            to = lockQuant(org, dto.productId, variantId, dto.toLocationId, Money.ZERO);
        } else {
            to = lockQuant(org, dto.productId, variantId, dto.toLocationId, Money.ZERO);
            from = lockQuant(org, dto.productId, variantId, dto.fromLocationId, Money.ZERO);
        }
        String ledgerCode = seq.next("stock_move", "STK/", 6);
        BigDecimal fromBefore = from.quantity();
        if (fromBefore.compareTo(qty) < 0) {
            throw ApiException.badRequest("Insufficient stock at source: available " + JsNumbers.dec(fromBefore)
                    + ", requested " + JsNumbers.dec(qty));
        }
        if (dto.honourSalesReservations) {
            assertSalesReservationsLeft(org, dto, str(product, "name"), variantId == null ? "" : variantId,
                    dto.fromLocationId, qty, fromBefore, true);
        }
        BigDecimal newFromQty = decrementStockItem(from.id(), qty);
        String costing = str(product, "costingMethod");
        boolean batchTracking = bool(product, "batchTracking");
        boolean carried = dto.carriedUnitCost != null && "AVCO".equals(costing) && !batchTracking;
        BigDecimal sourceAvg = from.average();
        // Hardening R5b (P2-3): a STANDARD product moves at its governing standard (its quants could carry 0).
        StandardCostService std = "STANDARD".equals(costing) ? standards.getIfAvailable() : null;
        BigDecimal standardUnit = std == null ? null : std.governingStandard(dto.productId).orElse(null);
        BigDecimal carriedAvg = carried ? Money.max(dto.carriedUnitCost, Money.ZERO)
                : standardUnit != null && !batchTracking ? standardUnit : sourceAvg;
        BigDecimal carriedValue = carriedAvg.multiply(qty);
        // The value the source quant's average loses (re-derived below for a carried move): units at its average.
        BigDecimal averageValue = carriedValue;
        BigDecimal toBefore = to.quantity();
        // Destination lots that went short: arriving units cover that shortfall first (open lots = max(0, on-hand)).
        BigDecimal toNet = batchTracking && toBefore.signum() < 0 ? Money.min(qty, toBefore.negate()) : Money.ZERO;
        BigDecimal toShortCost = shortfallCost(to, product);
        BigDecimal layerCorrection = Money.ZERO;
        List<StockMove.MovedLayer> layers = new ArrayList<>();
        Map<String, String> lotMap = new HashMap<>();
        // IPR C-7 / C-1: the serial units are chosen first (the named ones, then in-stock units for the rest), so the
        // lots of a serial+lot product follow the units that move.
        List<Map<String, Object>> moving = serialTracking
                ? transferSerials(org, dto, variantId, qty, batchTracking) : List.of();
        Map<String, Integer> serialsByLot = new HashMap<>();
        Map<String, Long> inStockByLot = new HashMap<>();
        if (serialTracking && batchTracking) {
            moving.forEach(m -> {
                if (m.get("batchId") != null) {
                    serialsByLot.merge((String) m.get("batchId"), 1, Integer::sum);
                }
            });
            jdbc.sql("SELECT \"batchId\", count(*) AS n FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND"
                            + " \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ? AND"
                            + " status = 'in_stock' AND \"batchId\" IS NOT NULL GROUP BY 1")
                    .params(org, dto.productId, variantId, dto.fromLocationId)
                    .query((rs, n) -> inStockByLot.put(rs.getString(1), rs.getLong(2))).list();
        }
        BigDecimal unserialisedLeft = qty.subtract(BigDecimal.valueOf(moving.size()));
        if (batchTracking) {
            // ADR-S013 (additive): a transfer that names a batch moves that lot only (quality quarantine / release);
            // transfers without a batch number keep the oldest-first behaviour; a transit line moves its own lots.
            boolean oneLot = dto.batchNumber != null && !dto.batchNumber.isBlank();
            if (oneLot) {
                BigDecimal inLot = lots(org, dto, variantId, dto.fromLocationId, "SELECT COALESCE(SUM(quantity), 0)",
                        " AND \"batchNumber\" = ?", "", dto.batchNumber).query(BigDecimal.class).single();
                if (inLot.compareTo(qty) < 0) {
                    throw ApiException.badRequest("Insufficient stock in batch " + dto.batchNumber + " at source: available "
                            + JsNumbers.dec(inLot) + ", requested " + JsNumbers.dec(qty));
                }
            }
            List<Map<String, Object>> batches = SqlRows.list(lots(org, dto, variantId, dto.fromLocationId, "SELECT *",
                    oneLot ? " AND \"batchNumber\" = ?" : "", " ORDER BY \"receivedAt\" ASC",
                    oneLot ? dto.batchNumber : null));
            BigDecimal remaining = qty;
            BigDecimal layerValue = Money.ZERO;
            for (Map<String, Object> batch : batches) {
                if (remaining.signum() <= 0) {
                    break;
                }
                BigDecimal batchQty = dec(batch, "quantity");
                BigDecimal moved = Money.min(remaining, batchQty);
                if (serialTracking) {
                    // IPR C-1: the lot's moving serial units, plus units without a serial from its unserialised part.
                    String lotId = (String) batch.get("id");
                    BigDecimal free = batchQty.subtract(BigDecimal.valueOf(inStockByLot.getOrDefault(lotId, 0L)))
                            .max(Money.ZERO);
                    BigDecimal takeFree = Money.min(unserialisedLeft, free);
                    moved = Money.min(batchQty, BigDecimal.valueOf(serialsByLot.getOrDefault(lotId, 0)).add(takeFree));
                    unserialisedLeft = unserialisedLeft.subtract(takeFree);
                    if (moved.signum() <= 0) {
                        continue;
                    }
                }
                int ok = jdbc.sql("UPDATE \"InventoryBatch\" SET quantity = quantity - ?, \"updatedAt\" = " + NOW
                                + " WHERE id = ? AND \"organizationId\" = ? AND quantity >= ?")
                        .params(moved, batch.get("id"), org, moved).update();
                if (ok == 0) {
                    continue;
                }
                if (batchQty.subtract(moved).signum() <= 0) {
                    jdbc.sql("UPDATE \"InventoryBatch\" SET \"isActive\" = false, \"updatedAt\" = " + NOW
                                    + " WHERE id = ? AND \"organizationId\" = ? AND quantity <= 0")
                            .params(batch.get("id"), org).update();
                }
                BigDecimal lotCost = batch.get("unitCost") != null ? dec(batch, "unitCost") : carriedAvg;
                BigDecimal netHere = Money.min(moved, toNet);
                toNet = toNet.subtract(netHere);
                BigDecimal destQty = moved.subtract(netHere);
                String destId = UUID.randomUUID().toString();
                jdbc.sql("INSERT INTO \"InventoryBatch\" (id, \"organizationId\", \"productId\", \"variantId\","
                                + " \"locationId\", \"batchNumber\", quantity, \"unitCost\", \"expiryDate\", \"mfgDate\","
                                + " \"receivedAt\", \"isActive\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, " + NOW + ", " + NOW + ")")
                        .params(destId, org, dto.productId, variantId, dto.toLocationId,
                                batch.get("batchNumber"), destQty, batch.get("unitCost"),
                                SqlRows.ts((Instant) batch.get("expiryDate")), SqlRows.ts((Instant) batch.get("mfgDate")),
                                SqlRows.ts((Instant) batch.get("receivedAt")), destQty.signum() > 0)
                        .update();
                lotMap.put((String) batch.get("id"), destId);
                if (destQty.signum() > 0) {
                    layers.add(new StockMove.MovedLayer(destId, null, destQty, lotCost));
                }
                layerCorrection = layerCorrection.add(netHere.multiply(lotCost.subtract(toShortCost)));
                layerValue = layerValue.add(lotCost.multiply(moved));
                remaining = remaining.subtract(moved);
            }
            carriedValue = layerValue.add(carriedAvg.multiply(remaining));
            if (remaining.signum() > 0 && toNet.signum() > 0) {
                BigDecimal netHere = Money.min(remaining, toNet);
                layerCorrection = layerCorrection.add(netHere.multiply(carriedAvg.subtract(toShortCost)));
            }
        }
        if (serialTracking) {
            // Hardening R5b (P2-3): serial units move at their own cost (what the valuation carries them at); units
            // without a serial at the source's average (the cost price while it has none), as an issue values them.
            BigDecimal unserialisedUnit = carried ? carriedAvg
                    : sourceAvg.signum() > 0 ? sourceAvg : dec(product, "costPrice");
            BigDecimal value = unserialisedUnit.multiply(qty.subtract(BigDecimal.valueOf(moving.size())));
            for (Map<String, Object> s : moving) {
                value = value.add(s.get("unitCost") != null ? (BigDecimal) s.get("unitCost") : unserialisedUnit);
            }
            carriedValue = value;
        }
        BigDecimal carriedUnit = Money.div(carriedValue, qty);
        Resolution destCost = costResolver.resolveReceiptCost(
                new ProductCost(costing, (BigDecimal) product.get("costPrice")),
                to.created() ? null : new Quant(toBefore, to.average()), qty, carriedUnit);
        // Gaps h-00 F1: units arriving without a serial blend into the destination's average of such units at the
        // cost they leave the source at (its average, the cost price while it has none), as on a receipt.
        BigDecimal toAverage = standardUnit != null ? standardUnit
                : destCost.newRunningAverage() != null ? destCost.newRunningAverage()
                : to.created() ? carriedUnit : null;
        SerialAverage serialAvg = serialTracking ? serialAverage(org, dto.productId, variantId, dto.toLocationId, to,
                product, qty.subtract(BigDecimal.valueOf(moving.size())),
                carried ? carriedAvg : sourceAvg.signum() > 0 ? sourceAvg : dec(product, "costPrice"), toAverage) : null;
        Map<String, Object> toItem = addToQuant(to.id(), qty, serialAvg != null ? serialAvg.average() : toAverage);
        if (carried) {
            reAverage(from.id(), fromBefore, sourceAvg, serialTracking ? averageValue : carriedValue, newFromQty);
        }

        if (serialTracking) {
            if (!moving.isEmpty()) {
                Set<String> oldBatchIds = new LinkedHashSet<>();
                moving.forEach(s -> {
                    if (s.get("batchId") != null && !lotMap.containsKey((String) s.get("batchId"))) {
                        oldBatchIds.add((String) s.get("batchId"));
                    }
                });
                Map<String, String> batchIdMap = new HashMap<>(lotMap);
                if (!oldBatchIds.isEmpty()) {
                    List<Map<String, Object>> oldBatches = jdbc.sql("SELECT id, \"batchNumber\" FROM \"InventoryBatch\""
                                    + " WHERE id IN (:ids) AND \"organizationId\" = :org")
                            .param("ids", List.copyOf(oldBatchIds)).param("org", org).query().listOfRows();
                    List<String> numbers = oldBatches.stream().map(b -> (String) b.get("batchNumber")).toList();
                    List<Map<String, Object>> destBatches = numbers.isEmpty() ? List.of()
                            : jdbc.sql("SELECT id, \"batchNumber\" FROM \"InventoryBatch\""
                                    + " WHERE \"organizationId\" = :org AND \"productId\" = :p"
                                    + " AND \"variantId\" IS NOT DISTINCT FROM :v AND \"locationId\" = :l"
                                    + " AND \"batchNumber\" IN (:n) ORDER BY \"receivedAt\" DESC")
                            .param("org", org).param("p", dto.productId).param("v", variantId)
                            .param("l", dto.toLocationId).param("n", numbers).query().listOfRows();
                    Map<String, String> destByNumber = new HashMap<>();
                    for (Map<String, Object> b : destBatches) {
                        destByNumber.putIfAbsent((String) b.get("batchNumber"), (String) b.get("id"));
                    }
                    for (Map<String, Object> b : oldBatches) {
                        String destId = destByNumber.get((String) b.get("batchNumber"));
                        if (destId != null) {
                            batchIdMap.put((String) b.get("id"), destId);
                        }
                    }
                }
                for (Map<String, Object> s : moving) {
                    String newBatch = s.get("batchId") != null ? batchIdMap.get((String) s.get("batchId")) : null;
                    jdbc.sql("UPDATE \"InventorySerial\" SET \"locationId\" = ?, \"batchId\" = COALESCE(?, \"batchId\"),"
                                    + " \"updatedAt\" = " + NOW + " WHERE id = ?")
                            .params(dto.toLocationId, newBatch, s.get("id")).update();
                    layers.add(new StockMove.MovedLayer(null, (String) s.get("id"), BigDecimal.ONE,
                            (BigDecimal) s.get("unitCost")));
                }
            }
        }

        BigDecimal newToQty = dec(toItem, "quantity");
        String outRow = insertLedger(org, ledgerCode, dto.productId, variantId, dto.fromLocationId, null, null,
                "transfer_out", fromBefore, qty.negate(), newFromQty, carriedUnit, carriedValue, dto.sourceType,
                dto.sourceId, rowNotes, dto.responsibleById, dto.approvedById, trace(dto, product, qty, dto.quantity),
                eff.at());
        String inRow = insertLedger(org, ledgerCode, dto.productId, variantId, dto.toLocationId, null, null,
                "transfer_in", newToQty.subtract(qty), qty, newToQty, carriedUnit, carriedValue, dto.sourceType,
                dto.sourceId, rowNotes, dto.responsibleById, dto.approvedById, trace(dto, product, qty, dto.quantity),
                eff.at());
        // LC-1 (ADR-S053): the units of the out row are the units of the in row — landed cost follows them.
        jdbc.sql("INSERT INTO \"InventoryTransferLineage\" (id, \"organizationId\", \"transferId\","
                        + " \"sourceLedgerId\", \"destinationLedgerId\", quantity) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org, dto.sourceId, outRow, inRow, qty).update();
        if (carriedValue.signum() > 0 && !dto.skipGlPosting) {
            stockPosting.postTransfer(dto.productId, carriedValue, dto.fromLocationId, dto.toLocationId, date,
                    dto.sourceType != null ? dto.sourceType : "stock_transfer",
                    dto.sourceId != null ? dto.sourceId : ledgerCode,
                    "Stock transfer · " + str(product, "name") + " · " + JsNumbers.dec(qty) + " · " + ledgerCode);
        }
        postCostCorrection(org, dto.productId, variantId, dto.toLocationId, newToQty, str(product, "name"),
                serialAvg != null ? serialAvg.correction()
                        : batchTracking ? layerCorrection
                        : destCost.costCorrection(), date, dto.sourceType != null ? dto.sourceType : "stock_transfer",
                dto.sourceId != null ? dto.sourceId : ledgerCode, ledgerCode);
        // IPR GL-7: the source quant's residue is swept whoever posts the transfer journal (see issue).
        sweepAvcoResidue(org, product, dto.productId, variantId, dto.fromLocationId, newFromQty,
                dto.sourceType != null ? dto.sourceType : "stock_transfer",
                dto.sourceId != null ? dto.sourceId : ledgerCode, ledgerCode, date);
        correctLateCountRows(org, ledgerCode, dto, eff);
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org);
        payload.put("productId", dto.productId);
        payload.put("locationId", dto.toLocationId);
        payload.put("ledgerCode", ledgerCode);
        payload.put("quantity", JsNumbers.dec(qty));
        events.publishOnCommit("stock.transferred", payload);
        audit.recordInTx(AuditInput.of("StockItem", from.id(), "transfer", null,
                Obj.of("productId", dto.productId, "fromLocationId", dto.fromLocationId, "toLocationId",
                        dto.toLocationId, "quantity", JsNumbers.js(qty), "ledgerCode", ledgerCode)));
        return new Transferred(ledgerCode, qty, dto.fromLocationId, dto.toLocationId, layers, carriedUnit);
    }

    /**
     * IPR C-7 / C-2: the serial units a transfer moves. Named serial numbers ({@link StockMove#serialNumbers}) must all
     * be in stock at the source (400 otherwise) and may not outnumber the units; the remaining units are picked from
     * the source's in-stock units ({@link #pickSerials}: pins, a named lot's units only, then oldest first). Read under
     * both quant locks.
     */
    private List<Map<String, Object>> transferSerials(String org, StockMove dto, String variantId, BigDecimal qty,
            boolean batchTracking) {
        List<String> requested = dto.serialNumbers == null ? List.of() : dto.serialNumbers;
        if (new HashSet<>(requested).size() != requested.size()) {
            throw ApiException.badRequest("Duplicate serial numbers in the transfer");
        }
        if (BigDecimal.valueOf(requested.size()).compareTo(qty) > 0) {
            throw ApiException.badRequest("Transferring " + JsNumbers.dec(qty) + " base unit(s) but "
                    + requested.size() + " serial number(s) were given; give at most one serial number per unit");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!requested.isEmpty()) {
            rows.addAll(SqlRows.list(jdbc.sql("SELECT id, \"batchId\", \"unitCost\", \"serialNumber\" FROM"
                            + " \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS"
                            + " NOT DISTINCT FROM ? AND \"locationId\" = ? AND status = 'in_stock' AND \"serialNumber\" ="
                            + " ANY(?::text[]) ORDER BY \"serialNumber\"")
                    .params(org, dto.productId, variantId, dto.fromLocationId, requested.toArray(String[]::new))));
            if (rows.size() != requested.size()) {
                Set<String> found = new HashSet<>();
                rows.forEach(r -> found.add((String) r.get("serialNumber")));
                throw ApiException.badRequest("Serial number(s) not available at the source location: "
                        + String.join(", ", requested.stream().filter(n -> !found.contains(n)).toList()));
            }
        }
        // IPR review 3 (#1): a transfer naming a lot moves that lot only (its lot rows are the ones decremented): its
        // serial units, then its units without a serial — never another lot's serial unit.
        String lot = batchTracking && dto.batchNumber != null && !dto.batchNumber.isBlank() ? dto.batchNumber : null;
        if (lot != null && !rows.isEmpty()) {
            List<String> outside = jdbc.sql("SELECT s.\"serialNumber\" FROM \"InventorySerial\" s LEFT JOIN"
                            + " \"InventoryBatch\" b ON b.id = s.\"batchId\" AND b.\"organizationId\" = s.\"organizationId\""
                            + " WHERE s.\"organizationId\" = ? AND s.id = ANY(?::text[]) AND b.\"batchNumber\" IS DISTINCT"
                            + " FROM ? ORDER BY s.\"serialNumber\"")
                    .params(org, rows.stream().map(r -> (String) r.get("id")).toArray(String[]::new), lot)
                    .query(String.class).list();
            if (!outside.isEmpty()) {
                throw ApiException.badRequest("Serial number(s) " + String.join(", ", outside) + " are not in batch "
                        + lot + " at the source location");
            }
        }
        rows.addAll(pickSerials(org, dto, variantId, dto.fromLocationId, qty.intValueExact() - rows.size(),
                rows.stream().map(r -> (String) r.get("id")).toList(), lot, batchTracking));
        return rows;
    }

    // ================================================================== effective time, freeze, late counts (IPR 1b)

    /** Reference type of a late count correction (count spec section 4). */
    public static final String LATE_COUNT_CORRECTION = "inventory_count_late_correction";

    /**
     * ADR-S088: the effective time a move writes ({@code at}; null = server NOW) and the clock-skew note when the
     * requested time was too far ahead.
     */
    record Eff(Instant at, String skew) {

        /**
         * The ledger note of the move: the clock-skew flag, and "occurred &lt;effective&gt;, posted &lt;date&gt;" when
         * the posting date is another day than the effective time (closed period moved, or a late sync).
         */
        String note(Instant postingDate) {
            String shifted = at != null && postingDate != null && !day(at).equals(day(postingDate))
                    ? "occurred " + com.inventoryerp.kernel.web.JsonConfig.toJsIso(at) + ", posted " + day(postingDate)
                    : null;
            return joinNotes(skew, shifted);
        }
    }

    /**
     * The business time of {@code dto}: its own {@link StockMove#effectiveAt}, else the offline op time bound in
     * {@link EffectiveTime}, else none (server NOW). A time later than now + {@code inventory.maxFutureEffectiveMinutes}
     * is recorded at the receive time and flagged {@code clockSkew} (never refused).
     */
    Eff effective(StockMove dto) {
        Instant raw = dto.effectiveAt;
        if (raw == null) {
            EffectiveTime.Context c = EffectiveTime.current();
            raw = c == null ? null : c.at();
        }
        if (raw == null) {
            return new Eff(null, null);
        }
        if (raw.isAfter(Instant.now().plus(OccurredAt.futureLimit()))) {
            return new Eff(null, "clockSkew: movement time " + com.inventoryerp.kernel.web.JsonConfig.toJsIso(raw)
                    + " was ahead of the server; recorded at the receive time");
        }
        return new Eff(raw, null);
    }

    /** Copy the count / effective-time fields of a layered adjustment onto the issue or receipt it delegates to. */
    private static void carryCountFields(StockMove from, StockMove to, Instant date) {
        to.manualMove = from.manualMove;
        to.quarantineFlow = from.quarantineFlow;
        to.effectiveAt = from.effectiveAt;
        to.countSessionId = from.countSessionId;
        to.skipLateCountCheck = from.skipLateCountCheck;
        to.sourceLineId = from.sourceLineId;
        to.reversesLedgerId = from.reversesLedgerId;
        to.date = date;
    }

    /** Lock key of a location freeze (lock level L3b). */
    static String freezeLockKey(String org, String locationId) {
        return "freeze:" + org + ":" + locationId;
    }

    /**
     * IPR 1b (count spec section 6): take the location freeze lock exclusively (L3b), before a count session with a
     * freeze starts. Waits for every move in flight at the location; the holder takes no lower lock.
     */
    @Transactional
    public void lockFreezeExclusive(String locationId) {
        // Polled with the try form: a waiting exclusive request would queue every new shared request behind it, and a
        // move that already holds a quant lock (L4) and then asks for the shared freeze key (a count or beverage
        // submit) could close a cycle. The try form never queues; the count start gives up after FREEZE_WAIT.
        String key = freezeLockKey(TenantContext.organizationId(), locationId);
        long deadline = System.nanoTime() + FREEZE_WAIT.toNanos();
        while (!Boolean.TRUE.equals(jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtext(?)::bigint)").param(key)
                .query(Boolean.class).single())) {
            if (System.nanoTime() > deadline) {
                throw ApiException.conflict("Stock is moving at this location right now; start the frozen count again"
                        + " in a moment");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("Stock is moving at this location right now; start the frozen count again"
                        + " in a moment");
            }
        }
    }

    /** How long a frozen count start waits for the moves in flight at its location. */
    static final java.time.Duration FREEZE_WAIT = java.time.Duration.ofSeconds(10);

    /**
     * IPR 1b (count spec section 6): a server-side manual move at a location frozen by a draft count is refused (409),
     * except the session's own posting, sales (POS jobs, sync sale ops, refunds), late count corrections and offline
     * device work (a device cannot see the freeze; the count reconciliation handles it). Lock level L3b: the shared
     * freeze key of each location (sorted), after the costing locks (L3) and before the quant locks (L4).
     */
    private void assertNotFrozen(String org, StockMove dto, String... locationIds) {
        if (freezeExempt(dto)) {
            return;
        }
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>();
        for (String l : locationIds) {
            if (l != null) {
                sorted.add(l);
            }
        }
        for (String loc : sorted) {
            jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtext(?)::bigint)").param(freezeLockKey(org, loc))
                    .query().listOfRows();
            Map<String, Object> frozen = SqlRows.one(jdbc.sql("SELECT s.id, s.\"countCode\", l.name FROM"
                            + " \"InventoryCountSession\" s JOIN \"InventoryLocation\" l ON l.id = s.\"locationId\""
                            + " WHERE s.\"organizationId\" = ? AND s.\"locationId\" = ? AND s.status = 'draft'"
                            + " AND s.\"freezeLocation\" = true AND s.\"deletedAt\" IS NULL LIMIT 1")
                    .params(org, loc));
            if (frozen != null && !str(frozen, "id").equals(dto.countSessionId)) {
                throw ApiException.conflict("Location " + frozen.get("name") + " is frozen for count "
                        + frozen.get("countCode") + "; submit or cancel the count before moving stock there");
            }
        }
    }

    /**
     * Sale-side sources a count freeze never blocks (P15-2): back-office sales, deliveries and returns, besides the POS
     * sources of {@link #isSaleDrain}. The count reconciles them like any movement after its cut-off.
     */
    private static final java.util.Set<String> SALE_SOURCES = java.util.Set.of("cash_sale", "sales_invoice",
            "sales_order", "sales_order_line", "invoice", "delivery_order", "sales_delivery", "sales_return",
            "credit_note", "credit_note_cancel");

    private static boolean freezeExempt(StockMove dto) {
        // A count's own posting (countSessionId) is exempt: with one draft per location (S4) the only freeze at its
        // location is its own, and it already holds the quant locks (L4), so it must not ask for L3b after them.
        if (isSaleDrain(dto) || LATE_COUNT_CORRECTION.equals(dto.sourceType) || dto.countSessionId != null
                || (dto.sourceType != null && SALE_SOURCES.contains(dto.sourceType))) {
            return true;
        }
        EffectiveTime.Context c = EffectiveTime.current();
        return c != null && c.offline();
    }

    /**
     * IPR 1b (count spec section 4, case C): a movement that happened before a count's cut-off but reached the ledger
     * after that count was submitted (an offline sale synced late) keeps its own effect (issue and COGS); the engine
     * then posts a linked count correction of the opposite quantity against the first submitted count of the cell
     * whose cut-off is after the movement ({@code reversesLedgerId} = the count's variance row, {@code sourceLineId} =
     * the count line, reference {@code inventory_count_late_correction} / the late row). Net effect: stock equals the
     * counted figure, the count loss is reclassified, both pieces of evidence stay. Runs in the late row's
     * transaction, under the locks the move already holds (L3, L4, L5): no new lock level. Exactly once per (count
     * line, late row): checked here and by the V701 unique index.
     *
     * <p>Not corrected (listed on the count's late transactions for a person): serial-tracked cells (P15-2), rows
     * without an explicit past effective time (clock-skew flagged rows are recorded at receive time), reversals and
     * corrections themselves, counts submitted before V700, and a late receipt of a lot-tracked or FIFO cell (its
     * correction could span several lots). A correction the engine refuses (for example an expiry-tracked cell with
     * no lot) is rolled back to a savepoint and left listed: the late movement itself always posts.
     */
    private void correctLateCountRows(String org, String ledgerCode, StockMove dto, Eff eff) {
        if (eff.at() == null || dto.skipLateCountCheck || dto.countSessionId != null || dto.reversesLedgerId != null
                || LATE_COUNT_CORRECTION.equals(dto.sourceType)) {
            return;
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.id, l.\"productId\", l.\"variantId\","
                        + " l.\"locationId\", l.\"batchId\", l.\"quantityChange\", l.seq, l.\"ledgerCode\","
                        + " l.\"referenceType\", COALESCE(l.\"effectiveAt\", l.\"createdAt\") AS eff, p.\"serialTracking\","
                        + " p.\"batchTracking\", p.\"costingMethod\"::text AS costing FROM \"InventoryLedger\" l"
                        + " JOIN \"Product\" p ON p.id = l.\"productId\" WHERE l.\"organizationId\" = ?"
                        + " AND l.\"ledgerCode\" = ? AND l.\"quantityChange\" <> 0 ORDER BY l.seq")
                .params(org, ledgerCode));
        for (Map<String, Object> r : rows) {
            if (bool(r, "serialTracking")) {
                continue;
            }
            BigDecimal change = dec(r, "quantityChange");
            boolean layered = bool(r, "batchTracking") || "FIFO".equals(r.get("costing"));
            if (layered && change.signum() > 0) {
                continue;
            }
            Map<String, Object> line = SqlRows.one(jdbc.sql("SELECT l.id, COALESCE(l.\"countedAt\", s.\"startedAt\") AS cutoff,"
                            + " s.id AS \"sessionId\", s.\"countCode\", s.\"countType\"::text AS \"countType\""
                            + " FROM \"InventoryCountLine\" l JOIN \"InventoryCountSession\" s ON s.id = l.\"sessionId\""
                            + " WHERE s.\"organizationId\" = ? AND s.status = 'submitted' AND s.\"deletedAt\" IS NULL"
                            + " AND s.\"locationId\" = ? AND l.\"productId\" = ? AND l.\"variantId\" IS NOT DISTINCT FROM ?"
                            + " AND l.\"countedQty\" IS NOT NULL AND l.\"measuredSeq\" IS NOT NULL AND l.\"measuredSeq\" < ?"
                            + " AND COALESCE(l.\"countedAt\", s.\"startedAt\") > ?"
                            + " AND (l.\"lotLine\" = false OR l.\"batchId\" IS NOT DISTINCT FROM ?)"
                            + " ORDER BY s.\"submittedAt\", s.id LIMIT 1")
                    .params(org, r.get("locationId"), r.get("productId"), r.get("variantId"),
                            ((Number) r.get("seq")).longValue(), SqlRows.ts((Instant) r.get("eff")), r.get("batchId")));
            if (line == null) {
                continue;
            }
            boolean done = !jdbc.sql("SELECT 1 FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"referenceType\""
                            + " = ? AND \"sourceLineId\" = ? AND \"referenceId\" = ? LIMIT 1")
                    .params(org, LATE_COUNT_CORRECTION, line.get("id"), r.get("id")).query().listOfRows().isEmpty();
            if (done) {
                continue;
            }
            String varianceRow = jdbc.sql("SELECT id FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                            + " \"sourceLineId\" = ? AND \"referenceType\" = 'stock_adjustment' AND \"quantityChange\" <> 0"
                            + " ORDER BY seq LIMIT 1")
                    .params(org, line.get("id")).query(String.class).optional().orElse(null);
            Instant cutoff = (Instant) line.get("cutoff");
            StockMove k = StockMove.of(str(r, "productId"), str(r, "locationId"), null).variant(str(r, "variantId"))
                    .source(LATE_COUNT_CORRECTION, str(r, "id")).sourceLine(str(line, "id")).reverses(varianceRow)
                    .people(dto.responsibleById, dto.approvedById)
                    .notes("Late " + (r.get("referenceType") != null ? r.get("referenceType") + " " : "")
                            + r.get("ledgerCode") + " of " + com.inventoryerp.kernel.web.JsonConfig.toJsIso((Instant) r.get("eff"))
                            + " was before count " + line.get("countCode") + " ("
                            + com.inventoryerp.kernel.web.JsonConfig.toJsIso(cutoff) + ")");
            k.deltaQuantity = change.negate();
            k.effectiveAt = cutoff;
            k.skipLateCountCheck = true;
            k.lotId = str(r, "batchId");
            k.openingBalance = "opening".equals(line.get("countType"));
            // IPR E1: a refusal thrown out of a proxied @Transactional bean (the posting) marks this whole
            // transaction rollback-only, which the savepoint below cannot undo — the late movement itself would then
            // fail at commit. The posting is checked first, without throwing; a correction that cannot post is left
            // listed on the count. The savepoint still catches the engine's own refusals (thrown in this class).
            String problem = stockPosting.adjustmentPostingProblem(str(r, "productId"), change.negate(),
                    k.openingBalance);
            if (problem != null) {
                log.warn("[stock] late count correction of {} against count {} not posted: {}", r.get("ledgerCode"),
                        line.get("countCode"), problem);
                continue;
            }
            jdbc.sql("SAVEPOINT late_count_correction").update();
            try {
                adjust(k);
                jdbc.sql("RELEASE SAVEPOINT late_count_correction").update();
            } catch (RuntimeException e) {
                jdbc.sql("ROLLBACK TO SAVEPOINT late_count_correction").update();
                log.warn("[stock] late count correction of {} against count {} not posted: {}", r.get("ledgerCode"),
                        line.get("countCode"), e.getMessage());
            }
        }
    }

    /**
     * ADR-S088: the effective time of a back-office document date. A timestamp with a time of day is used as given
     * (later rows of today: now). A date without a time (midnight UTC) means "some time that day": the end of that day
     * when it is a past day, now when it is today or later. Null = now.
     */
    public static Instant documentEffectiveAt(Instant docDate) {
        if (docDate == null) {
            return null;
        }
        Instant now = Instant.now();
        if (docDate.isAfter(now)) {
            return null;
        }
        java.time.LocalDateTime wall = docDate.atOffset(ZoneOffset.UTC).toLocalDateTime();
        if (!wall.toLocalTime().equals(java.time.LocalTime.MIDNIGHT)) {
            return docDate;
        }
        LocalDate d = wall.toLocalDate();
        return d.isBefore(now.atOffset(ZoneOffset.UTC).toLocalDate())
                ? d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1) : null;
    }

    /**
     * ADR-S088 limit 2: a back-office document dated more than {@code inventory.maxBackdateDays} days before today is
     * refused (400). Offline device work is exempt (it may have been offline for longer; it is flagged lateSync
     * instead). 0 = no limit.
     */
    @Transactional(readOnly = true)
    public void assertWithinBackdateLimit(Instant docDate, String documentLabel) {
        if (docDate == null) {
            return;
        }
        EffectiveTime.Context c = EffectiveTime.current();
        if (c != null && c.offline()) {
            return;
        }
        Number configured = settings.resolveNumber("inventory.maxBackdateDays", SettingContext.EMPTY);
        long days = configured == null ? 7 : configured.longValue();
        if (days <= 0) {
            return;
        }
        LocalDate oldest = LocalDate.now(ZoneOffset.UTC).minusDays(days);
        if (docDate.atOffset(ZoneOffset.UTC).toLocalDate().isBefore(oldest)) {
            throw ApiException.badRequest(documentLabel + " date is more than " + days + " days in the past");
        }
    }

    // ================================================================== helpers

    /** Millisecond wall clock (Prisma stamps timestamps client-side, so rows of one tx get distinct times). */
    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private static void assertWholeSerialQuantity(String productName, BigDecimal baseQty) {
        if (baseQty.stripTrailingZeros().scale() > 0) {
            throw ApiException.badRequest(productName + " is serial-tracked: quantity must be a whole number of base units,"
                    + " got " + JsNumbers.dec(baseQty));
        }
    }

    /**
     * Line-unit quantity/cost to base unit, total value preserved. A snapshot factor (ADR-S003) wins over the
     * product's current unit setup so a document always posts the conversion it was written with.
     */
    private BigDecimal[] toBaseQtyCost(Map<String, Object> product, String sourceUomId, BigDecimal snapshotFactor,
            BigDecimal quantity, BigDecimal unitCost) {
        String baseUom = str(product, "uomId");
        if (snapshotFactor != null && snapshotFactor.signum() > 0 && quantity.signum() > 0) {
            if (snapshotFactor.compareTo(BigDecimal.ONE) == 0) {
                return new BigDecimal[] {quantity, unitCost};
            }
            BigDecimal baseQty = uomConversion.applyFactor(quantity, snapshotFactor, baseUom, true);
            BigDecimal factor = Money.div(baseQty, quantity);
            return new BigDecimal[] {baseQty, factor.signum() > 0 ? Money.div(unitCost, factor) : unitCost};
        }
        if (sourceUomId == null || baseUom == null || sourceUomId.equals(baseUom) || quantity.signum() <= 0) {
            return new BigDecimal[] {quantity, unitCost};
        }
        BigDecimal baseQty = uomConversion.toBase(quantity, sourceUomId, str(product, "id"), baseUom, true);
        BigDecimal factor = Money.div(baseQty, quantity);
        return new BigDecimal[] {baseQty, factor.signum() > 0 ? Money.div(unitCost, factor) : unitCost};
    }

    /**
     * {@code quantity} of {@code dto}'s unit (snapshot factor first, else the live conversion) in base units, sign kept;
     * unchanged when the move names no unit.
     */
    private BigDecimal toBaseQty(Map<String, Object> product, StockMove dto, BigDecimal quantity) {
        if ((dto.uomId == null && dto.uomFactor == null) || quantity.signum() == 0) {
            return quantity;
        }
        BigDecimal base = toBaseQtyCost(product, dto.uomId, dto.uomFactor, quantity.abs(), Money.ZERO)[0];
        return quantity.signum() < 0 ? base.negate() : base;
    }

    /** The advisory-lock key of one quant (shared with {@link LayerCostWriter}). */
    static String quantLockKey(String org, String productId, String variantKey, String locationId) {
        return "avco:" + org + ":" + productId + ":" + variantKey + ":" + locationId;
    }

    /**
     * Gaps h-00 F7: hold the quant locks of these lines' cells ({@code productId} / {@code variantId}) at one location
     * until the caller's transaction ends, taken in the engine lock order. The advisory half is enough: every
     * movement takes it before it reads or writes the quant, so a caller that computes a variance from on-hand and
     * ledger before posting it (count submit) sees no movement commit in between. Creates no quant.
     */
    @Transactional
    public void lockQuants(String locationId, List<? extends Map<String, Object>> lines) {
        String org = TenantContext.organizationId();
        // Hardening R5b (P2-1): the costing locks of every product first (sorted), as each move takes them before its
        // quant lock; otherwise a standard activation (costing key, then quants) and this (quants, then each move's
        // costing key) could deadlock.
        new java.util.TreeSet<>(lines.stream().map(l -> str(l, "productId")).filter(java.util.Objects::nonNull)
                .toList()).forEach(productId -> holdCostingShared(org, productId));
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> l : StockMove.inLockOrder(new ArrayList<Map<String, Object>>(lines))) {
            String variantKey = l.get("variantId") == null ? "" : str(l, "variantId");
            if (seen.add(str(l, "productId") + ":" + variantKey)) {
                lockQuantForAvco(org, str(l, "productId"), variantKey, locationId);
            }
        }
    }

    /** One quant a caller pre-locks with {@link #lockCells}. */
    public record Cell(String productId, String variantId, String locationId) {}

    /**
     * Hardening R5b (P2-1): take, before a multi-move operation, every lock its moves will take — the costing locks of
     * each product (sorted), then each quant's advisory lock in the engine order (product, variant, location) — so the
     * moves that follow (which re-take them) never acquire a lock out of order, whatever order they run in. Creates no
     * quant; the locks last until the caller's transaction ends.
     */
    @Transactional
    public void lockCells(java.util.Collection<Cell> cells) {
        String org = TenantContext.organizationId();
        new java.util.TreeSet<>(cells.stream().map(Cell::productId).toList())
                .forEach(productId -> holdCostingShared(org, productId));
        cells.stream().distinct().sorted(java.util.Comparator.comparing(Cell::productId)
                        .thenComparing(c -> c.variantId() == null ? "" : c.variantId())
                        .thenComparing(Cell::locationId))
                .forEach(c -> lockQuantForAvco(org, c.productId(), c.variantId() == null ? "" : c.variantId(),
                        c.locationId()));
    }

    /**
     * Hardening R5b: the costing locks (shared) and the advisory lock of one quant, in the engine order — for a writer
     * outside the engine that then locks the quant's {@code StockItem} row (stock reservations).
     */
    static void lockCellAdvisory(JdbcClient jdbc, String org, String productId, String variantId, String locationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtext(?)::bigint),"
                        + " pg_advisory_xact_lock_shared(hashtext(?)::bigint)")
                .params(costingLockKey(org, null), costingLockKey(org, productId)).query().listOfRows();
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)")
                .param(quantLockKey(org, productId, variantId == null ? "" : variantId, locationId)).query().listOfRows();
    }

    /** Costing-change lock key (readiness gaps h-02 F2): the organisation's ({@code productId} null) or a product's. */
    static String costingLockKey(String org, String productId) {
        return productId == null ? "costing:" + org : "costing:" + org + ":" + productId;
    }

    /**
     * Readiness gaps h-02 F2: the shared side of the costing-change locks, taken (organisation key first) before a move
     * reads the product's costing method, lot tracking or active standard. A cost policy activation holds the
     * organisation key and a standard cost activation the product key exclusively, so a move runs wholly before a
     * re-costing (which waits for it) or wholly after it, reading the new method.
     */
    private void holdCostingShared(String org, String productId) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtext(?)::bigint),"
                        + " pg_advisory_xact_lock_shared(hashtext(?)::bigint)")
                .params(costingLockKey(org, null), costingLockKey(org, productId)).query().listOfRows();
    }

    /** Transaction-scoped advisory lock serialising one quant's AVCO recompute (first half of the quant lock). */
    private void lockQuantForAvco(String org, String productId, String variantKey, String locationId) {
        String key = quantLockKey(org, productId, variantKey, locationId);
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param(key).query().listOfRows();
    }

    /** A quant held by {@link #lockQuant}; {@code created} when that call inserted it (quantity 0). */
    private record LockedQuant(Map<String, Object> row, boolean created) {

        String id() {
            return (String) row.get("id");
        }

        BigDecimal quantity() {
            return dec(row, "quantity");
        }

        BigDecimal average() {
            return dec(row, "runningAverageCost");
        }
    }

    /**
     * THE lock order of the stock engine. Every path that reads a quant to compute from it (receipt average, issue
     * chain, count, transfer) takes, per quant, first the transaction-scoped advisory lock
     * {@code avco:<org>:<product>:<variantKey>:<location>} and then the {@code StockItem} row {@code FOR UPDATE}, and
     * reads quantity / average only after both. A move touching two quants (transfer) locks them in ascending
     * locationId order; multi-line documents feed their lines in ascending (productId, variantKey, locationId) order.
     * The external average writers (landed cost, lineage follower, revaluation) take the same advisory lock first.
     * The row is created (quantity 0, {@code createAvg}) when missing so the very first receipt has a row to lock.
     */
    private LockedQuant lockQuant(String org, String productId, String variantId, String locationId,
            BigDecimal createAvg) {
        String variantKey = variantId == null ? "" : variantId;
        lockQuantForAvco(org, productId, variantKey, locationId);
        int inserted = jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"variantId\","
                        + " \"variantKey\", \"locationId\", quantity, \"runningAverageCost\", \"createdAt\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0, ?, " + NOW + ", " + NOW + ")"
                        + " ON CONFLICT (\"organizationId\", \"productId\", \"variantKey\", \"locationId\") DO NOTHING")
                .params(UUID.randomUUID().toString(), org, productId, variantId, variantKey, locationId,
                        createAvg == null ? Money.ZERO : createAvg)
                .update();
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ? FOR UPDATE")
                .params(org, productId, variantKey, locationId));
        return new LockedQuant(row, inserted == 1);
    }

    /** Add {@code qty} to a locked quant and set the new average when given; returns the row. */
    private Map<String, Object> addToQuant(String id, BigDecimal qty, BigDecimal newAvg) {
        return SqlRows.one(jdbc.sql("UPDATE \"StockItem\" SET quantity = quantity + ?,"
                        + " \"runningAverageCost\" = COALESCE(?, \"runningAverageCost\"), \"updatedAt\" = " + NOW
                        + " WHERE id = ? RETURNING *")
                .params(qty, newAvg, id));
    }

    /**
     * Unit cost the short (negative) units of a lot-tracked quant were issued at: kept in its running average while
     * it is negative ({@link #recordShortfallCost}); the product cost when that is unknown (stock from before).
     */
    private static BigDecimal shortfallCost(LockedQuant quant, Map<String, Object> product) {
        return quant.average().signum() > 0 ? quant.average() : dec(product, "costPrice");
    }

    /** A lot-tracked quant went (further) short at {@code unit}: its average becomes the cost of its short units. */
    private void recordShortfallCost(String stockItemId, BigDecimal avg, BigDecimal before, BigDecimal after,
            BigDecimal unit) {
        BigDecimal oldShort = Money.max(before.negate(), Money.ZERO);
        BigDecimal newShort = Money.max(after.negate(), Money.ZERO);
        BigDecimal added = newShort.subtract(oldShort);
        if (added.signum() <= 0) {
            return;
        }
        BigDecimal shortAvg = oldShort.signum() > 0 && avg.signum() > 0
                ? Money.div(oldShort.multiply(avg).add(added.multiply(unit)), newShort) : unit;
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(shortAvg, stockItemId).update();
    }

    /** Units left an AVCO quant at a specific value: re-derive the average so the remaining value stays exact. */
    private void reAverage(String stockItemId, BigDecimal before, BigDecimal avg, BigDecimal removedValue,
            BigDecimal after) {
        if (after.signum() <= 0) {
            return;
        }
        BigDecimal remainingValue = before.multiply(avg).subtract(removedValue);
        BigDecimal newAvg = Money.max(Money.div(remainingValue, after), Money.ZERO);
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(newAvg, stockItemId).update();
    }

    /**
     * Post the value difference of units received into a negative quant (Dr COGS / Cr stock when the receipt costs
     * more than they were issued at). One entry per stock move: the posting key is the ledger code.
     *
     * <p>The correction changes the quant's value without moving quantity, so it is also recorded as a value-only
     * {@code cost_adjustment} ledger row (as the AVCO residue sweep is): Σ ledger value then stays equal to the
     * valuation and to the stock-account GL. Reversals skip zero-quantity rows and do not mirror the journal.
     */
    private void postCostCorrection(String org, String productId, String variantId, String locationId,
            BigDecimal balance, String productName, BigDecimal amount, Instant date, String sourceType, String sourceId,
            String ledgerCode) {
        if (amount == null) {
            return;
        }
        BigDecimal rounded = amount.setScale(6, java.math.RoundingMode.HALF_UP);
        if (rounded.signum() == 0) {
            return;
        }
        insertLedger(org, seq.next("stock_move", "STK/", 6), productId, variantId, locationId, null, null,
                "cost_adjustment", balance, Money.ZERO, balance, Money.ZERO, rounded.negate(), sourceType,
                sourceId, "Negative-stock cost correction (" + ledgerCode + ")", null, null, null, null);
        stockPosting.postNegativeStockCostCorrection(productId, rounded, date, sourceType, sourceId,
                "Negative-stock cost correction · " + productName, COST_CORRECTION_KEY + org + ":" + ledgerCode);
    }

    /** Posting-key prefix of {@link #postCostCorrection} entries (reversals do not mirror them). */
    public static final String COST_CORRECTION_KEY = "inventory:cost_correction:";

    /** A POS / offline sale being drained into stock: it already happened, so the negative-stock setting never blocks. */
    private static boolean isSaleDrain(StockMove dto) {
        String source = dto.sourceType == null ? "" : dto.sourceType;
        return source.startsWith("pos") || "menu_recipe".equals(source);
    }

    /**
     * Statement over the open lots ({@code quantity > 0}, active) of the move's quant at {@code locationId}, restricted
     * to the move's pinned lots when it has them, plus {@code extra} (binding {@code extraParam} when not null).
     */
    private JdbcClient.StatementSpec lots(String org, StockMove dto, String variantId, String locationId, String select,
            String extra, String order, Object extraParam) {
        List<Object> params = new ArrayList<>(List.of(org, dto.productId));
        params.add(variantId);
        params.add(locationId);
        String pin = "";
        if (dto.pinnedBatchIds != null && !dto.pinFallback) {
            pin = " AND id = ANY(?::text[])";
            params.add(dto.pinnedBatchIds.toArray(String[]::new));
        }
        if (extraParam != null) {
            params.add(extraParam);
        }
        String ordered = order;
        if (dto.pinnedBatchIds != null && dto.pinFallback && order.startsWith(" ORDER BY ")) {
            // Hardening R5b: preferred lots first, then the others in the usual order.
            ordered = " ORDER BY (id = ANY(?::text[])) DESC, " + order.substring(" ORDER BY ".length());
            params.add(dto.pinnedBatchIds.toArray(String[]::new));
        }
        return jdbc.sql(select + " FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ? AND quantity > 0"
                + " AND \"isActive\" = true" + pin + extra + ordered).params(params);
    }

    private JdbcClient.StatementSpec lots(String org, StockMove dto, String variantId, String select, String extra,
            String order, Object extraParam) {
        return lots(org, dto, variantId, dto.locationId, select, extra, order, extraParam);
    }

    /**
     * IPR C-2 / C-7 / C-9: up to {@code limit} in-stock serial units of the move's quant at {@code locationId}, other
     * than {@code excludeIds}: the move's pinned units only (or first, with {@link StockMove#pinFallback}), then oldest
     * receipt first (C-9: kept, FIFO by receipt is the documented default; ties by serial number so the pick is
     * deterministic). IPR review 3 (#1 / #3): with {@code onlyLotNumber} only units of that lot at the location; for a
     * lot-tracked product ({@code batchTracking}) whose move pins lots ({@link StockMove#pinnedBatchIds}, no serial pins)
     * only units of those lots (or theirs first, with {@link StockMove#pinFallback}) — the lot rows the move decrements
     * are the lots of the units it picks. Read under the quant lock (L4/L5), so a concurrent move of the same cell
     * cannot pick the same units.
     */
    private List<Map<String, Object>> pickSerials(String org, StockMove dto, String variantId, String locationId,
            int limit, java.util.Collection<String> excludeIds, String onlyLotNumber, boolean batchTracking) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        List<Object> params = new ArrayList<>(List.of(org, dto.productId));
        params.add(variantId);
        params.add(locationId);
        StringBuilder sql = new StringBuilder("SELECT id, \"unitCost\", \"batchId\", \"serialNumber\" FROM"
                + " \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT"
                + " DISTINCT FROM ? AND \"locationId\" = ? AND status = 'in_stock'");
        if (!excludeIds.isEmpty()) {
            sql.append(" AND id <> ALL(?::text[])");
            params.add(excludeIds.toArray(String[]::new));
        }
        if (dto.pinnedSerialIds != null && !dto.pinFallback) {
            sql.append(" AND id = ANY(?::text[])");
            params.add(dto.pinnedSerialIds.toArray(String[]::new));
        }
        boolean lotPins = batchTracking && dto.pinnedBatchIds != null && dto.pinnedSerialIds == null;
        if (lotPins && !dto.pinFallback) {
            sql.append(" AND \"batchId\" = ANY(?::text[])");
            params.add(dto.pinnedBatchIds.toArray(String[]::new));
        }
        if (onlyLotNumber != null && !onlyLotNumber.isBlank()) {
            sql.append(" AND \"batchId\" IN (SELECT b.id FROM \"InventoryBatch\" b WHERE b.\"organizationId\" = ? AND"
                    + " b.\"productId\" = ? AND b.\"variantId\" IS NOT DISTINCT FROM ? AND b.\"locationId\" = ? AND"
                    + " b.\"batchNumber\" = ?)");
            params.add(org);
            params.add(dto.productId);
            params.add(variantId);
            params.add(locationId);
            params.add(onlyLotNumber);
        }
        sql.append(" ORDER BY ");
        if (dto.pinnedSerialIds != null && dto.pinFallback) {
            sql.append("(id = ANY(?::text[])) DESC, ");
            params.add(dto.pinnedSerialIds.toArray(String[]::new));
        }
        if (lotPins && dto.pinFallback) {
            sql.append("COALESCE(\"batchId\" = ANY(?::text[]), false) DESC, ");
            params.add(dto.pinnedBatchIds.toArray(String[]::new));
        }
        sql.append("\"receivedAt\" ASC, \"serialNumber\" ASC LIMIT ?");
        params.add(limit);
        return new ArrayList<>(SqlRows.list(jdbc.sql(sql.toString()).params(params)));
    }

    /**
     * IPR review 3 (#2 / #3): the units without a serial an issue can draw (whole units, never negative). Without lots:
     * the quant's on-hand less its in-stock serial units. With lots: the unserialised part of the open lots the move may
     * draw (its pinned lots, lot {@code namedLot} when given; {@code pickable} applied). Read under the quant lock.
     */
    private int unserialisedFree(String org, StockMove dto, String variantId, Map<String, Object> stockItem,
            boolean serialLots, String namedLot, String pickable) {
        BigDecimal free;
        if (serialLots) {
            free = lots(org, dto, variantId, "SELECT COALESCE(SUM(GREATEST(quantity - (SELECT count(*) FROM"
                            + " \"InventorySerial\" s WHERE s.\"organizationId\" = \"InventoryBatch\".\"organizationId\""
                            + " AND s.\"batchId\" = \"InventoryBatch\".id AND s.status = 'in_stock'), 0)), 0)",
                    pickable + (namedLot != null ? " AND \"batchNumber\" = ?" : ""), "", namedLot)
                    .query(BigDecimal.class).single();
        } else {
            long serials = jdbc.sql("SELECT count(*) FROM \"InventorySerial\" WHERE \"organizationId\" = ? AND"
                            + " \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ? AND"
                            + " status = 'in_stock'")
                    .params(org, dto.productId, variantId, dto.locationId).query(Long.class).single();
            free = dec(stockItem, "quantity").subtract(BigDecimal.valueOf(serials));
        }
        return free.signum() <= 0 ? 0 : free.setScale(0, RoundingMode.FLOOR).intValueExact();
    }

    /**
     * IPR C-1: take one unit off the lot a serial unit belongs to (lock level L6, under the quant lock). A lot already at
     * zero (historical drift from before C-1, when serial issues never touched their lot) is left as it is and logged:
     * the integrity report (quant_vs_lots) shows that cell; the issue itself is not refused.
     */
    private void decrementSerialLot(String org, String batchId, String serialNumber) {
        if (batchId == null) {
            return;
        }
        int updated = jdbc.sql("UPDATE \"InventoryBatch\" SET quantity = quantity - 1, \"isActive\" = (quantity - 1 > 0),"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND quantity >= 1")
                .params(batchId, org).update();
        if (updated == 0) {
            log.warn("[stock] serial {} left lot {} which holds no quantity (drift from before IPR C-1)", serialNumber,
                    batchId);
        }
    }

    /**
     * IPR C-1: issue {@code remaining} units without a serial of a serial+lot product from its lots, FEFO, each lot only
     * down to the units its in-stock serials still hold (the unserialised part). Rows carry the lot and are valued at
     * {@code unit} (the quant's average, as units without a serial always were). Returns the quantity drawn.
     */
    private BigDecimal issueUnserialisedFromLots(IssueCtx ic, String variantId, BigDecimal remaining,
            BigDecimal running, BigDecimal unit, String extra, Object extraParam, String notes) {
        List<Map<String, Object>> lots = SqlRows.list(lots(ic.org, ic.dto, variantId, "SELECT id, quantity,"
                        + " quantity - (SELECT count(*) FROM \"InventorySerial\" s WHERE s.\"organizationId\" ="
                        + " \"InventoryBatch\".\"organizationId\" AND s.\"batchId\" = \"InventoryBatch\".id AND s.status ="
                        + " 'in_stock') AS free", extra, " ORDER BY \"expiryDate\" ASC NULLS LAST, \"receivedAt\" ASC",
                extraParam));
        BigDecimal drawn = Money.ZERO;
        for (Map<String, Object> lot : lots) {
            BigDecimal left = remaining.subtract(drawn);
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = Money.min(left, Money.min(dec(lot, "free"), dec(lot, "quantity")));
            if (take.signum() <= 0) {
                continue;
            }
            int updated = jdbc.sql("UPDATE \"InventoryBatch\" SET quantity = quantity - ?, \"isActive\" = (quantity - ? > 0),"
                            + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND quantity >= ?")
                    .params(take, take, lot.get("id"), ic.org, take).update();
            if (updated == 0) {
                continue;
            }
            BigDecimal before = running.subtract(drawn);
            decrementStockItem(ic.stockItemId, take);
            ic.row((String) lot.get("id"), null, before, take.negate(), before.subtract(take), unit, unit.multiply(take),
                    notes);
            drawn = drawn.add(take);
        }
        return drawn;
    }

    /**
     * IPR E-1a (F5): refuse a move that would eat into stock reserved for sales orders at the cell (active
     * {@code sales_order_line} reservations other than the caller's {@link StockMove#reservationHolderIds}). Runs under
     * the quant lock (L4/L5), which a reservation also takes first, so the measure holds until commit. With
     * {@code refusable} false (a POS / offline sale drain the availability rules let through) nothing is refused.
     */
    private void assertSalesReservationsLeft(String org, StockMove dto, String productName, String variantKey,
            String locationId, BigDecimal qty, BigDecimal onHand, boolean refusable) {
        if (!refusable) {
            return;
        }
        requireFreeOfSalesReservations(jdbc, org, dto.productId, productName, variantKey, locationId, qty, onHand,
                dto.reservationHolderIds);
    }

    /**
     * IPR E-1a / F5, the one implementation (engine issues and transfers, and
     * {@link StockReservationService#assertNotReservedForSalesOrders}): refuse taking {@code qty} base units from a cell
     * holding {@code onHand} when it would eat into active {@code sales_order_line} reservations other than those of
     * {@code holderIds}. The caller holds the cell lock (L4/L5). {@code productName} null = read on refusal.
     */
    static void requireFreeOfSalesReservations(JdbcClient jdbc, String org, String productId, String productName,
            String variantKey, String locationId, BigDecimal qty, BigDecimal onHand, List<String> holderIds) {
        List<Object> params = new ArrayList<>(List.of(org, productId, variantKey, locationId));
        String holders = "";
        if (holderIds != null && !holderIds.isEmpty()) {
            holders = " AND \"sourceId\" <> ALL(?::text[])";
            params.add(holderIds.toArray(String[]::new));
        }
        BigDecimal reserved = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE"
                        + " \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ? AND"
                        + " status = 'active' AND \"sourceType\" = '" + SALES_ORDER_RESERVATION + "'" + holders)
                .params(params).query(BigDecimal.class).single();
        if (reserved.signum() <= 0) {
            return;
        }
        BigDecimal free = onHand.subtract(reserved).max(BigDecimal.ZERO);
        if (qty.compareTo(free) > 0) {
            String name = productName != null ? productName : jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?")
                    .param(productId).query(String.class).optional().orElse(productId);
            String location = jdbc.sql("SELECT name FROM \"InventoryLocation\" WHERE id = ?").param(locationId)
                    .query(String.class).optional().orElse(locationId);
            throw ApiException.badRequest("Only " + JsNumbers.dec(free) + " of " + name + " is free in " + location
                    + "; " + JsNumbers.dec(reserved) + " is reserved for sales orders. Release or deliver those"
                    + " reservations first.");
        }
    }

    /** Source type of sales-order line reservations (the sales module's ATP service). */
    static final String SALES_ORDER_RESERVATION = "sales_order_line";

    /** IPR X-3: codes of the quarantine virtual locations (quality / QC and sales-return inspection). */
    public static final Set<String> QUARANTINE_CODES = Set.of(VirtualLocationService.QC_QUARANTINE, "SALES-QUARANTINE");

    /**
     * IPR X-3: codes of the rental virtual locations that hold units in a rental's custody (with the customer, being
     * cleaned or repaired, damaged). RENT-STOCK is not one: rental stock is put there by an ordinary transfer.
     */
    public static final Set<String> RENTAL_HELD_CODES = Set.of("RENT-OUT", "RENT-CLEAN", "RENT-REPAIR", "RENT-DAMAGED");

    /**
     * IPR X-3: source types of the flows that own a quarantine location and may issue from it, for callers that do not
     * pass {@link StockMove#quarantineFlow} yet: quality lots and downgrades, QC checks, manufacturing QC and stage QC,
     * and the sales-return inspection.
     */
    public static final Set<String> QUARANTINE_OWNER_SOURCES = Set.of("quality_lot", "quality_downgrade", "qc_check",
            "production_qc", "production_stage", "sales_return");

    /**
     * IPR X-3 (stream D request; stream C request for QC quarantine). For a quarantine or rental virtual location
     * (type {@code virtual}, code in {@link #QUARANTINE_CODES} / {@link #RENTAL_HELD_CODES}):
     * <ul>
     *   <li>a manual stock document move ({@link StockMove#manualMove}) into or out of it is refused, except a transfer
     *       into a quarantine location (putting stock on hold is always safe; PLM rework holds use it);</li>
     *   <li>an issue out of a quarantine location (a sale, POS drain, consumption, write-off) is refused unless it
     *       belongs to the owning flow ({@link StockMove#quarantineFlow} or {@link #QUARANTINE_OWNER_SOURCES}), or it is
     *       a reversal, a count posting or a late count correction.</li>
     * </ul>
     * Transfers and receipts of the owning flows (QC quarantine / release, rental checkout / return / clean) pass: they
     * are not manual. No lock is taken (reads the location row only).
     */
    private void assertGuardedLocation(StockMove dto, Map<String, Object> location, boolean inbound, boolean transfer) {
        if (location == null || !"virtual".equals(String.valueOf(location.get("type")))) {
            return;
        }
        String code = str(location, "code");
        boolean quarantine = QUARANTINE_CODES.contains(code);
        if (!quarantine && !RENTAL_HELD_CODES.contains(code)) {
            return;
        }
        String where = location.get("name") + " (" + code + ")";
        if (dto.manualMove && !(quarantine && transfer && inbound)) {
            throw ApiException.badRequest(where + " is managed by the " + (quarantine ? "quality and returns" : "rental")
                    + " flows: a manual stock document cannot move stock into or out of it");
        }
        if (quarantine && !inbound && !transfer && !quarantineOwner(dto)) {
            throw ApiException.badRequest("Stock in " + where + " is on hold: it leaves only through a quality release,"
                    + " rejection or downgrade, or the returns inspection, never through a sale, issue or consumption");
        }
    }

    private static boolean quarantineOwner(StockMove dto) {
        String source = dto.sourceType == null ? "" : dto.sourceType;
        return dto.quarantineFlow || dto.countSessionId != null || dto.reversesLedgerId != null
                || "reversal_out".equals(dto.moveType) || source.contains("reversal")
                || LATE_COUNT_CORRECTION.equals(source) || QUARANTINE_OWNER_SOURCES.contains(source);
    }

    /** Below this absolute value a zero quant's leftover ledger value is AVCO rounding and is swept (R5b). */
    static final BigDecimal RESIDUE_SWEEP_LIMIT = BigDecimal.ONE;

    /**
     * Hardening R5b (P2-3): an AVCO quant (no lots, no serials) that a move just took to exactly zero carries no value,
     * but its ledger can keep a residue: the average is stored at 6 decimals and every issue posts average × quantity.
     * A residue below {@link #RESIDUE_SWEEP_LIMIT} is swept in the move's transaction — a value-only
     * {@code cost_adjustment} ledger row for the quant and the matching journal (Dr cost of sales / Cr stock for a
     * positive residue, the reverse for a negative one, posting rule {@code COGS_CORRECTION}, keyed like a negative-stock
     * cost correction so a reversal of the move does not mirror it). A larger difference is not rounding: it is left for
     * the integrity check ({@code valuation_vs_subledger}). Value-adjunct rows ({@code valuationComponent}) are not part
     * of the residue.
     */
    private void sweepAvcoResidue(String org, Map<String, Object> product, String productId, String variantId,
            String locationId, BigDecimal after, String sourceType, String sourceId, String ledgerCode, Instant date) {
        if (after == null || after.signum() != 0 || product == null || !"AVCO".equals(str(product, "costingMethod"))
                || bool(product, "batchTracking") || bool(product, "serialTracking")) {
            return;
        }
        BigDecimal residue = jdbc.sql("SELECT COALESCE(SUM(CASE WHEN \"quantityChange\" >= 0 THEN"
                        + " COALESCE(\"totalValue\", 0) ELSE -COALESCE(\"totalValue\", 0) END), 0) FROM \"InventoryLedger\""
                        + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ?"
                        + " AND \"locationId\" = ? AND \"valuationComponent\" IS NULL")
                .params(org, productId, variantId, locationId).query(BigDecimal.class).single()
                .setScale(6, RoundingMode.HALF_UP);
        if (residue.signum() == 0 || residue.abs().compareTo(RESIDUE_SWEEP_LIMIT) >= 0) {
            return;
        }
        String code = seq.next("stock_move", "STK/", 6);
        insertLedger(org, code, productId, variantId, locationId, null, null, "cost_adjustment", Money.ZERO, Money.ZERO,
                Money.ZERO, Money.ZERO, residue.negate(), sourceType, sourceId,
                "AVCO rounding residue at zero stock (" + ledgerCode + ")", null, null, null, null);
        stockPosting.postNegativeStockCostCorrection(productId, residue, date, sourceType, sourceId,
                "AVCO rounding residue · " + str(product, "name"), COST_CORRECTION_KEY + org + ":residue:" + code);
    }

    /** Atomic decrement; returns the new quantity. */
    private BigDecimal decrementStockItem(String id, BigDecimal by) {
        return jdbc.sql("UPDATE \"StockItem\" SET quantity = quantity - ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? RETURNING quantity")
                .params(by, id).query(BigDecimal.class).single();
    }

    private String insertLedger(String org, String ledgerCode, String productId, String variantId, String locationId,
            String batchId, String serialId, String type, BigDecimal qtyBefore, BigDecimal change, BigDecimal after,
            BigDecimal unitCost, BigDecimal totalValue, String referenceType, String referenceId, String notes,
            String responsibleById, String approvedById, Trace trace, Instant effectiveAt) {
        String id = UUID.randomUUID().toString();
        BigDecimal factor = trace == null ? null : trace.factor();
        BigDecimal docQty = factor == null || factor.signum() == 0 ? null
                : change.divide(factor, 6, RoundingMode.HALF_UP);
        jdbc.sql("INSERT INTO \"InventoryLedger\" (id, \"organizationId\", \"ledgerCode\", \"productId\", \"variantId\","
                        + " \"locationId\", \"batchId\", \"serialId\", type, \"qtyBefore\", \"quantityChange\","
                        + " \"balanceAfter\", \"unitCost\", \"totalValue\", \"referenceType\", \"referenceId\", notes,"
                        + " \"performedBy\", \"responsibleById\", \"approvedById\", \"docUomId\", \"uomFactor\","
                        + " \"docQuantity\", \"sourceLineId\", \"reversesLedgerId\", \"createdAt\", \"effectiveAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::\"StockMoveType\", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, " + NOW + ", COALESCE(?::timestamp(3), " + NOW + "))")
                .params(id, org, ledgerCode, productId, variantId, locationId, batchId,
                        serialId, type, qtyBefore, change, after, unitCost, totalValue, referenceType, referenceId,
                        notes, TenantContext.userId(), responsibleById, approvedById,
                        trace == null ? null : trace.docUomId(), factor, docQty,
                        trace == null ? null : trace.sourceLineId(), trace == null ? null : trace.reversesLedgerId(),
                        SqlRows.ts(effectiveAt))
                .update();
        return id;
    }

    /** Phase I2 (audit R-1): the document unit, factor and line a ledger row records. */
    private record Trace(String docUomId, BigDecimal factor, String sourceLineId, String reversesLedgerId) {}

    /**
     * The trace of a move of {@code docQty} document units that became {@code baseQty} stock units: the caller's unit
     * and frozen factor when given, the stock unit at factor 1 for a base-unit move, else the factor actually applied.
     */
    private static Trace trace(StockMove dto, Map<String, Object> product, BigDecimal baseQty, BigDecimal docQty) {
        if (dto.traceFactor != null) {
            return new Trace(dto.traceUomId, dto.traceFactor, dto.sourceLineId, dto.reversesLedgerId);
        }
        String base = product == null ? null : str(product, "uomId");
        if ((dto.uomId == null || dto.uomId.equals(base)) && dto.uomFactor == null) {
            return new Trace(base, BigDecimal.ONE, dto.sourceLineId, dto.reversesLedgerId);
        }
        BigDecimal factor = dto.uomFactor != null ? dto.uomFactor
                : baseQty != null && docQty != null && docQty.signum() != 0
                        ? baseQty.abs().divide(docQty.abs(), 10, RoundingMode.HALF_UP) : null;
        return new Trace(dto.uomId != null ? dto.uomId : base, factor, dto.sourceLineId, dto.reversesLedgerId);
    }

    private static String joinNotes(String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isEmpty()) {
                kept.add(p);
            }
        }
        return kept.isEmpty() ? null : String.join(" · ", kept);
    }

    private static Instant toInstant(String iso) {
        return iso == null ? null : EntityPatch.toInstant(iso);
    }
}
