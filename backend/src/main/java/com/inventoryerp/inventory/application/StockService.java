package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.bool;
import static com.inventoryerp.inventory.application.InventoryLookups.dec;
import static com.inventoryerp.inventory.application.InventoryLookups.str;

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
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
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
    public void assertStockPeriodOpen(Instant date) {
        String org = TenantContext.organizationId();
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

    /** Receipt from a vendor bill. */
    @Transactional
    public Received receiveFromBill(StockMove dto, String billId, Instant billDate) {
        StockMove m = StockMove.of(dto.productId, dto.locationId, dto.quantity).variant(dto.variantId)
                .unitCost(dto.unitCost).uom(dto.uomId, dto.uomFactor)
                .batch(dto.batchNumber).expiry(dto.expiryDate).notes(dto.notes).serials(dto.serialNumbers);
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
        Map<String, Object> product = lookups.product(dto.productId);
        BigDecimal baseQty = product != null && dto.uomFactor != null
                ? uomConversion.applyFactor(dto.quantity, dto.uomFactor, str(product, "uomId"), true)
                : dto.uomId != null && product != null && product.get("uomId") != null
                && !dto.uomId.equals(product.get("uomId"))
                ? uomConversion.toBase(dto.quantity, dto.uomId, dto.productId, str(product, "uomId"), true)
                : dto.quantity;
        BigDecimal unitCost = dto.unitCost != null ? dto.unitCost
                : resolveCurrentCost(dto.productId, dto.variantId, dto.locationId);
        StockMove m = StockMove.of(dto.productId, dto.locationId, baseQty).variant(dto.variantId).unitCost(unitCost)
                .reference(dto.reference).source(dto.sourceType, dto.sourceId).notes(dto.notes).moveType("return_in")
                .serials(dto.serialNumbers);
        Received res = receiveCore(m, null);
        BigDecimal totalValue = unitCost.multiply(baseQty);
        if (totalValue.signum() > 0) {
            stockPosting.postReturnRestock(dto.productId, totalValue, date,
                    dto.sourceType != null ? dto.sourceType : "return_restock",
                    dto.sourceId != null ? dto.sourceId : dto.reference != null ? dto.reference : dto.productId,
                    "Return restock · " + JsNumbers.dec(dto.quantity));
        }
        return res;
    }

    /** Location running average when positive, else the last positive receipt cost, else the product cost. */
    private BigDecimal resolveCurrentCost(String productId, String variantId, String locationId) {
        Map<String, Object> product = lookups.product(productId);
        Map<String, Object> item = lookups.stockItem(productId, variantId, locationId);
        if (item != null && dec(item, "runningAverageCost").signum() > 0) {
            return dec(item, "runningAverageCost");
        }
        if (item != null) {
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
        Map<String, Object> product = lookups.product(dto.productId);
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        if (lookups.location(dto.locationId) == null) {
            throw ApiException.notFound("Location not found");
        }
        requireVariant(product, dto.variantId, dto.locationId);
        boolean batchTracking = bool(product, "batchTracking");
        String productName = str(product, "name");
        if (batchTracking && dto.batchNumber == null
                && !settings.resolveBool("inventory.batchAutoNumber", SettingContext.EMPTY)) {
            throw ApiException.badRequest("Batch number is required for batch-tracked products");
        }
        if (dto.expiryDate != null && !batchTracking) {
            throw ApiException.badRequest("Expiry date is only valid for batch-tracked products");
        }
        if (bool(product, "expiryTracking") && dto.expiryDate == null) {
            throw ApiException.badRequest("Expiry date is required for expiry-tracked products");
        }
        String costing = str(product, "costingMethod");
        if ("FIFO".equals(costing) && !batchTracking) {
            throw ApiException.badRequest("FIFO costing requires batchTracking=true on the product");
        }
        BigDecimal[] base = toBaseQtyCost(product, dto.uomId, dto.uomFactor, dto.quantity,
                dto.unitCost != null ? dto.unitCost : Money.ZERO);
        BigDecimal qty = base[0];
        BigDecimal actualUnitCost = base[1];
        // ADR-S008: a standard-governed product enters stock at its standard; the difference is PPV (below).
        StandardCostService std = "STANDARD".equals(costing) ? standards.getIfAvailable() : null;
        BigDecimal standardUnit = std == null ? null : std.activeStandard(dto.productId).orElse(null);
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
        String ledgerCode = seq.next("stock_move", "STK/", 6);
        BigDecimal qtyBefore = quant.quantity();
        Resolution cost = costResolver.resolveReceiptCost(
                new ProductCost(costing, (BigDecimal) product.get("costPrice")),
                quant.created() ? null : new Quant(qtyBefore, quant.average()), qty, unitCost);
        // A lot-tracked quant that went short (issued without a lot): the receipt first covers that shortfall, so only
        // the remainder becomes an open lot (open lots always sum to max(0, on-hand)); the covered units are trued up
        // from the cost they were issued at to this receipt's cost.
        BigDecimal netted = batchTracking && qtyBefore.signum() < 0 ? Money.min(qty, qtyBefore.negate()) : Money.ZERO;
        BigDecimal correction = batchTracking
                ? netted.multiply(unitCost.subtract(shortfallCost(quant, product)))
                : cost.costCorrection();

        Map<String, Object> stockItem = addToQuant(quant.id(), qty, cost.newRunningAverage());

        String batchId = null;
        if (batchTracking) {
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
            boolean isReturn = "return_in".equals(dto.moveType) || "reversal_in".equals(dto.moveType);
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
            for (String serial : serials) {
                jdbc.sql("INSERT INTO \"InventorySerial\" (id, \"organizationId\", \"productId\", \"variantId\","
                                + " \"locationId\", \"serialNumber\", \"batchId\", \"unitCost\", status, \"receiptRef\","
                                + " \"receivedAt\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                                + " 'in_stock', ?, " + NOW + ", " + NOW + ", " + NOW + ")"
                                + " ON CONFLICT (\"organizationId\", \"productId\", \"serialNumber\") DO UPDATE SET"
                                + " \"locationId\" = EXCLUDED.\"locationId\", \"variantId\" = EXCLUDED.\"variantId\","
                                + " \"batchId\" = EXCLUDED.\"batchId\", status = 'in_stock', \"issuedAt\" = NULL,"
                                + " \"issueRef\" = NULL, \"receiptRef\" = EXCLUDED.\"receiptRef\", \"updatedAt\" = " + NOW)
                        .params(UUID.randomUUID().toString(), org, dto.productId, variantId, dto.locationId, serial,
                                batchId, unitCost, receiptRef)
                        .update();
            }
        }

        BigDecimal currentQty = dec(stockItem, "quantity");
        insertLedger(org, ledgerCode, dto.productId, variantId, dto.locationId, batchId, null,
                dto.moveType != null ? dto.moveType : "receipt", currentQty.subtract(qty), qty, currentQty, unitCost,
                cost.totalValue(),
                glCtx != null && glCtx.sourceType() != null ? glCtx.sourceType() : dto.sourceType,
                glCtx != null && glCtx.sourceId() != null ? glCtx.sourceId() : dto.sourceId,
                dto.notes, dto.responsibleById, dto.approvedById);

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
        postCostCorrection(org, dto.productId, productName, correction,
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
        if (cost.newRunningAverage() != null) {
            payload.put("newRunningAverage", JsNumbers.dec(cost.newRunningAverage()));
        }
        events.publish("stock.received", payload);
        audit.recordInTx(AuditInput.of("StockItem", str(stockItem, "id"), "receive", null,
                Obj.of("productId", dto.productId, "locationId", dto.locationId, "quantity", JsNumbers.js(dto.quantity),
                        "unitCost", JsNumbers.dec(unitCost), "ledgerCode", ledgerCode)));
        BigDecimal actualValue = standardUnit != null
                ? actualUnitCost.multiply(qty).setScale(6, java.math.RoundingMode.HALF_UP) : cost.totalValue();
        return new Received(ledgerCode, dto.quantity, unitCost, cost.totalValue(),
                cost.newRunningAverage() != null ? cost.newRunningAverage() : dec(stockItem, "runningAverageCost"),
                actualValue);
    }

    /**
     * ADR-S058: a product with variants moves stock per variant. A move without a variant is refused unless the
     * location still holds a quant without one (stock or documents from before the product got variants).
     */
    private void requireVariant(Map<String, Object> product, String variantId, String locationId) {
        if (variantId != null) {
            // The variant must be one of this product's (any organisation's id would otherwise open a foreign quant).
            boolean own = jdbc.sql("SELECT count(*) FROM \"ProductVariant\" WHERE id = ? AND \"productId\" = ?"
                            + " AND \"organizationId\" = ?")
                    .params(variantId, str(product, "id"), TenantContext.organizationId()).query(Long.class).single() > 0;
            if (!own) {
                throw ApiException.notFound("Variant not found");
            }
            return;
        }
        if (!bool(product, "hasVariants")) {
            return;
        }
        if (lookups.stockItem(str(product, "id"), null, locationId) == null) {
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
        Map<String, Object> product = lookups.product(dto.productId);
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        if (lookups.location(dto.locationId) == null) {
            throw ApiException.notFound("Location not found");
        }
        requireVariant(product, dto.variantId, dto.locationId);
        String productName = str(product, "name");
        BigDecimal qty = toBaseQtyCost(product, dto.uomId, dto.uomFactor, dto.quantity, Money.ZERO)[0];
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("Quantity must be positive");
        }
        String variantId = dto.variantId;
        String variantKey = variantId == null ? "" : variantId;
        String moveType = dto.moveType != null ? dto.moveType : "issue";
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
        boolean allowNegative = settings.resolveBool("inventory.allowNegativeStock", ctx);
        String ledgerNotes = joinNotes(dto.notes, dto.reference);

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

        String ledgerCode = seq.next("stock_move", "STK/", 6);
        BigDecimal totalValue = Money.ZERO;
        BigDecimal unitCost;
        boolean carriedCost = !serialTracking && !usesLayers && dto.carriedUnitCost != null
                && "AVCO".equals(str(product, "costingMethod"));
        IssueCtx ic = new IssueCtx(org, ledgerCode, dto, variantId, moveType, stockItemId);

        if (serialTracking) {
            List<String> requested = dto.serialNumbers == null ? List.of() : dto.serialNumbers;
            if (new HashSet<>(requested).size() != requested.size()) {
                throw ApiException.badRequest("Duplicate serial numbers in the issue");
            }
            List<Map<String, Object>> serialRows;
            if (!requested.isEmpty()) {
                serialRows = SqlRows.list(jdbc.sql("SELECT id, \"unitCost\", \"batchId\", \"serialNumber\" FROM"
                                + " \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                                + " AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ? AND status = 'in_stock'"
                                + " AND \"serialNumber\" = ANY(?::text[])")
                        .params(org, dto.productId, variantId, dto.locationId, requested.toArray(String[]::new)));
                if (serialRows.size() != requested.size()) {
                    Set<String> foundSet = new HashSet<>();
                    serialRows.forEach(s -> foundSet.add((String) s.get("serialNumber")));
                    List<String> missing = requested.stream().filter(s -> !foundSet.contains(s)).toList();
                    throw ApiException.badRequest("Serial number(s) not available at this location: "
                            + String.join(", ", missing));
                }
            } else {
                List<Object> sp = new ArrayList<>(List.of(org, dto.productId));
                sp.add(variantId);
                sp.add(dto.locationId);
                if (dto.pinnedSerialIds != null) {
                    sp.add(dto.pinnedSerialIds.toArray(String[]::new));
                }
                sp.add(Math.max(0, qty.intValue()));
                serialRows = SqlRows.list(jdbc.sql("SELECT id, \"unitCost\", \"batchId\", \"serialNumber\" FROM"
                                + " \"InventorySerial\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                                + " AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ? AND status = 'in_stock'"
                                + (dto.pinnedSerialIds != null ? " AND id = ANY(?::text[])" : "")
                                + " ORDER BY \"receivedAt\" ASC LIMIT ?")
                        .params(sp));
            }
            String issueRef = (dto.sourceType != null ? dto.sourceType : "issue") + ":"
                    + (dto.sourceId != null ? dto.sourceId : ledgerCode);
            BigDecimal running = dec(stockItem, "quantity");
            for (Map<String, Object> s : serialRows) {
                BigDecimal sUnit = s.get("unitCost") != null ? (BigDecimal) s.get("unitCost") : dec(product, "costPrice");
                jdbc.sql("UPDATE \"InventorySerial\" SET status = 'issued', \"issuedAt\" = " + NOW + ", \"issueRef\" = ?,"
                                + " \"updatedAt\" = " + NOW + " WHERE id = ?")
                        .params(issueRef, s.get("id")).update();
                BigDecimal after = running.subtract(BigDecimal.ONE);
                decrementStockItem(stockItemId, BigDecimal.ONE);
                ic.row((String) s.get("batchId"), (String) s.get("id"), running, BigDecimal.ONE.negate(), after, sUnit,
                        sUnit, ledgerNotes);
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
                BigDecimal overflowUnit = dec(product, "costPrice");
                BigDecimal overflowValue = overflowUnit.multiply(remaining);
                BigDecimal after = running.subtract(remaining);
                decrementStockItem(stockItemId, remaining);
                ic.row(null, null, running, remaining.negate(), after, overflowUnit, overflowValue, ledgerNotes);
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
        }

        if (totalValue.signum() > 0 && !dto.skipGlPosting) {
            stockPosting.postIssue(new StockPostingService.IssuePosting(dto.productId, dto.locationId, variantId, qty,
                    date, dto.sourceType != null ? dto.sourceType : "stock_issue",
                    dto.sourceId != null ? dto.sourceId : ledgerCode, "inventory:issue:" + org + ":" + ledgerCode,
                    "Stock issue · " + productName + " · " + JsNumbers.dec(dto.quantity),
                    INV_MOVE_TYPES.getOrDefault(moveType, "STOCK_OUT"),
                    usesLayers || serialTracking || carriedCost ? totalValue : null));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org);
        payload.put("productId", dto.productId);
        payload.put("locationId", dto.locationId);
        payload.put("ledgerCode", ledgerCode);
        payload.put("quantity", JsNumbers.dec(dto.quantity));
        payload.put("unitCost", JsNumbers.dec(unitCost));
        payload.put("totalValue", JsNumbers.dec(totalValue));
        events.publish("stock.issued", payload);
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

        IssueCtx(String org, String ledgerCode, StockMove dto, String variantId, String moveType, String stockItemId) {
            this.org = org;
            this.ledgerCode = ledgerCode;
            this.dto = dto;
            this.variantId = variantId;
            this.moveType = moveType;
            this.stockItemId = stockItemId;
        }

        void row(String batchId, String serialId, BigDecimal before, BigDecimal change, BigDecimal after,
                BigDecimal unitCost, BigDecimal totalValue, String notes) {
            insertLedger(org, ledgerCode, dto.productId, variantId, dto.locationId, batchId, serialId, moveType, before,
                    change, after, unitCost, totalValue, dto.sourceType, dto.sourceId, notes, dto.responsibleById,
                    dto.approvedById);
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
        assertStockPeriodOpen(Instant.now());
        Map<String, Object> product = lookups.product(dto.productId);
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        if (lookups.location(dto.locationId) == null) {
            throw ApiException.notFound("Location not found");
        }
        requireVariant(product, dto.variantId, dto.locationId);
        BigDecimal counted = dto.countedQuantity;
        String variantId = dto.variantId;
        if (counted.signum() == 0 && lookups.stockItem(dto.productId, variantId, dto.locationId) == null) {
            return new Adjusted(null, Money.ZERO, null, Money.ZERO, null);
        }
        // Read the counted-against quantity under the quant lock: a concurrent move can no longer slip between the
        // read and the write (the layered path below applies a delta, so a stale read would be a lost update).
        LockedQuant quant = lockQuant(org, dto.productId, variantId, dto.locationId, dec(product, "costPrice"));
        Map<String, Object> stockItem = quant.created() ? null : quant.row();
        BigDecimal currentQty = quant.quantity();
        BigDecimal delta = counted.subtract(currentQty);
        if (delta.signum() == 0) {
            return new Adjusted(null, Money.ZERO, null, Money.ZERO, null);
        }
        if (bool(product, "batchTracking") || bool(product, "serialTracking")
                || "FIFO".equals(str(product, "costingMethod"))) {
            return adjustLayered(dto, product, currentQty, counted, delta, stockItem);
        }
        String ledgerCode = seq.next("stock_move", "STK/", 6);
        String moveType = delta.signum() > 0 ? "adjustment_in" : "adjustment_out";
        BigDecimal unitCost = stockItem != null && dec(stockItem, "runningAverageCost").signum() > 0
                ? dec(stockItem, "runningAverageCost") : dec(product, "costPrice");
        jdbc.sql("UPDATE \"StockItem\" SET quantity = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(counted, quant.id(), org).update();
        String sourceType = dto.sourceType != null ? dto.sourceType : "stock_adjust";
        String sourceId = dto.sourceId != null ? dto.sourceId : ledgerCode;
        insertLedger(org, ledgerCode, dto.productId, variantId, dto.locationId, null, null, moveType, currentQty, delta,
                counted, unitCost, unitCost.multiply(delta.abs()), sourceType, sourceId, dto.notes,
                dto.responsibleById, dto.approvedById);
        stockPosting.postAdjustment(dto.productId, delta, unitCost, Instant.now(), sourceType, sourceId,
                "Stock adjustment · " + str(product, "name"));
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org);
        payload.put("productId", dto.productId);
        payload.put("locationId", dto.locationId);
        payload.put("ledgerCode", ledgerCode);
        payload.put("quantity", JsNumbers.dec(counted));
        payload.put("delta", JsNumbers.dec(delta));
        events.publish("stock.adjusted", payload);
        audit.recordInTx(AuditInput.of("StockItem", stockItem != null ? str(stockItem, "id") : "new", "adjust", null,
                Obj.of("productId", dto.productId, "locationId", dto.locationId, "countedQuantity",
                        JsNumbers.js(dto.countedQuantity), "previousQuantity", JsNumbers.js(currentQty), "ledgerCode",
                        ledgerCode)));
        return new Adjusted(ledgerCode, currentQty, counted, delta, unitCost);
    }

    /** Batch / serial / FIFO variance: moves the identified layers, then posts the variance at their value. */
    private Adjusted adjustLayered(StockMove dto, Map<String, Object> product, BigDecimal currentQty, BigDecimal counted,
            BigDecimal delta, Map<String, Object> stockItem) {
        String sourceType = dto.sourceType != null ? dto.sourceType : "stock_adjust";
        String ledgerCode;
        BigDecimal totalValue;
        if (delta.signum() < 0) {
            StockMove m = StockMove.of(dto.productId, dto.locationId, delta.abs()).variant(dto.variantId)
                    .source(sourceType, dto.sourceId).notes(dto.notes).serials(dto.serialNumbers).batch(dto.batchNumber)
                    .people(dto.responsibleById, dto.approvedById).moveType("adjustment_out")
                    .strategy(dto.batchNumber != null ? "MANUAL" : null).skipGl();
            Issued res = issue(m);
            ledgerCode = res.ledgerCode();
            totalValue = res.totalValue();
        } else {
            String org = TenantContext.organizationId();
            Map<String, Object> lot = null;
            if (bool(product, "batchTracking")) {
                String lotSql = "SELECT * FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                        + " AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ?";
                if (dto.batchNumber != null) {
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
            Received res = receiveCore(m, null);
            ledgerCode = res.ledgerCode();
            totalValue = res.totalValue();
        }
        BigDecimal absQty = delta.abs();
        if (totalValue.signum() > 0) {
            stockPosting.postAdjustment(dto.productId, delta, Money.div(totalValue, absQty), Instant.now(), sourceType,
                    dto.sourceId != null ? dto.sourceId : ledgerCode, "Stock adjustment · " + str(product, "name"));
        }
        audit.recordInTx(AuditInput.of("StockItem", stockItem != null ? str(stockItem, "id") : "new", "adjust", null,
                Obj.of("productId", dto.productId, "locationId", dto.locationId, "countedQuantity",
                        JsNumbers.js(dto.countedQuantity), "previousQuantity", JsNumbers.js(currentQty), "ledgerCode",
                        ledgerCode, "layered", true)));
        return new Adjusted(ledgerCode, currentQty, counted, delta,
                absQty.signum() > 0 ? Money.div(totalValue, absQty) : Money.ZERO);
    }

    // ================================================================== transfer

    /** Move stock between locations carrying its cost (lots / serials move too); GL via transfer rules. */
    @Transactional
    public Transferred transfer(StockMove dto) {
        String org = TenantContext.organizationId();
        assertStockPeriodOpen(Instant.now());
        if (dto.fromLocationId.equals(dto.toLocationId)) {
            throw ApiException.badRequest("Source and destination locations must be different");
        }
        Map<String, Object> product = lookups.product(dto.productId);
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        if (lookups.location(dto.fromLocationId) == null) {
            throw ApiException.notFound("Source location not found");
        }
        if (lookups.location(dto.toLocationId) == null) {
            throw ApiException.notFound("Destination location not found");
        }
        requireVariant(product, dto.variantId, dto.fromLocationId);
        BigDecimal qty = dto.quantity;
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("Quantity must be positive");
        }
        boolean serialTracking = bool(product, "serialTracking");
        if (serialTracking) {
            assertWholeSerialQuantity(str(product, "name"), qty);
        }
        String variantId = dto.variantId;
        if (lookups.stockItem(dto.productId, variantId, dto.fromLocationId) == null) {
            throw ApiException.badRequest("No stock found at source location");
        }
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
        BigDecimal newFromQty = decrementStockItem(from.id(), qty);
        String costing = str(product, "costingMethod");
        boolean batchTracking = bool(product, "batchTracking");
        boolean carried = dto.carriedUnitCost != null && "AVCO".equals(costing) && !batchTracking;
        BigDecimal sourceAvg = from.average();
        BigDecimal carriedAvg = carried ? Money.max(dto.carriedUnitCost, Money.ZERO) : sourceAvg;
        BigDecimal carriedValue = carriedAvg.multiply(qty);
        BigDecimal toBefore = to.quantity();
        // Destination lots that went short: arriving units cover that shortfall first (open lots = max(0, on-hand)).
        BigDecimal toNet = batchTracking && toBefore.signum() < 0 ? Money.min(qty, toBefore.negate()) : Money.ZERO;
        BigDecimal toShortCost = shortfallCost(to, product);
        BigDecimal layerCorrection = Money.ZERO;
        List<StockMove.MovedLayer> layers = new ArrayList<>();
        Map<String, String> lotMap = new HashMap<>();
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
        BigDecimal carriedUnit = Money.div(carriedValue, qty);
        Resolution destCost = costResolver.resolveReceiptCost(
                new ProductCost(costing, (BigDecimal) product.get("costPrice")),
                to.created() ? null : new Quant(toBefore, to.average()), qty, carriedUnit);
        Map<String, Object> toItem = addToQuant(to.id(), qty, destCost.newRunningAverage() != null
                ? destCost.newRunningAverage() : to.created() ? carriedUnit : null);
        if (carried) {
            reAverage(from.id(), fromBefore, sourceAvg, carriedValue, newFromQty);
        }

        if (serialTracking) {
            List<Object> sp = new ArrayList<>(List.of(org, dto.productId));
            sp.add(variantId);
            sp.add(dto.fromLocationId);
            if (dto.pinnedSerialIds != null) {
                sp.add(dto.pinnedSerialIds.toArray(String[]::new));
            }
            sp.add(qty.intValueExact());
            List<Map<String, Object>> moving = SqlRows.list(jdbc.sql("SELECT id, \"batchId\" FROM \"InventorySerial\""
                            + " WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ?"
                            + " AND \"locationId\" = ? AND status = 'in_stock'"
                            + (dto.pinnedSerialIds != null ? " AND id = ANY(?::text[])" : "")
                            + " ORDER BY \"receivedAt\" ASC LIMIT ?")
                    .params(sp));
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
                    layers.add(new StockMove.MovedLayer(null, (String) s.get("id"), BigDecimal.ONE, null));
                }
            }
        }

        BigDecimal newToQty = dec(toItem, "quantity");
        String outRow = insertLedger(org, ledgerCode, dto.productId, variantId, dto.fromLocationId, null, null,
                "transfer_out", fromBefore, qty.negate(), newFromQty, carriedUnit, carriedValue, dto.sourceType,
                dto.sourceId, dto.notes, dto.responsibleById, dto.approvedById);
        String inRow = insertLedger(org, ledgerCode, dto.productId, variantId, dto.toLocationId, null, null,
                "transfer_in", newToQty.subtract(qty), qty, newToQty, carriedUnit, carriedValue, dto.sourceType,
                dto.sourceId, dto.notes, dto.responsibleById, dto.approvedById);
        // LC-1 (ADR-S053): the units of the out row are the units of the in row — landed cost follows them.
        jdbc.sql("INSERT INTO \"InventoryTransferLineage\" (id, \"organizationId\", \"transferId\","
                        + " \"sourceLedgerId\", \"destinationLedgerId\", quantity) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org, dto.sourceId, outRow, inRow, qty).update();
        if (carriedValue.signum() > 0 && !dto.skipGlPosting) {
            stockPosting.postTransfer(dto.productId, carriedValue, dto.fromLocationId, dto.toLocationId, Instant.now(),
                    dto.sourceType != null ? dto.sourceType : "stock_transfer",
                    dto.sourceId != null ? dto.sourceId : ledgerCode,
                    "Stock transfer · " + str(product, "name") + " · " + JsNumbers.dec(dto.quantity));
        }
        postCostCorrection(org, dto.productId, str(product, "name"), batchTracking ? layerCorrection
                        : destCost.costCorrection(), Instant.now(), dto.sourceType != null ? dto.sourceType : "stock_transfer",
                dto.sourceId != null ? dto.sourceId : ledgerCode, ledgerCode);
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", org);
        payload.put("productId", dto.productId);
        payload.put("locationId", dto.toLocationId);
        payload.put("ledgerCode", ledgerCode);
        payload.put("quantity", JsNumbers.dec(dto.quantity));
        events.publish("stock.transferred", payload);
        audit.recordInTx(AuditInput.of("StockItem", from.id(), "transfer", null,
                Obj.of("productId", dto.productId, "fromLocationId", dto.fromLocationId, "toLocationId",
                        dto.toLocationId, "quantity", JsNumbers.js(dto.quantity), "ledgerCode", ledgerCode)));
        return new Transferred(ledgerCode, dto.quantity, dto.fromLocationId, dto.toLocationId, layers, carriedUnit);
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

    /** Transaction-scoped advisory lock serialising one quant's AVCO recompute (first half of the quant lock). */
    private void lockQuantForAvco(String org, String productId, String variantKey, String locationId) {
        String key = "avco:" + org + ":" + productId + ":" + variantKey + ":" + locationId;
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
     */
    private void postCostCorrection(String org, String productId, String productName, BigDecimal amount, Instant date,
            String sourceType, String sourceId, String ledgerCode) {
        if (amount == null) {
            return;
        }
        BigDecimal rounded = amount.setScale(6, java.math.RoundingMode.HALF_UP);
        if (rounded.signum() == 0) {
            return;
        }
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
        if (dto.pinnedBatchIds != null) {
            pin = " AND id = ANY(?::text[])";
            params.add(dto.pinnedBatchIds.toArray(String[]::new));
        }
        if (extraParam != null) {
            params.add(extraParam);
        }
        return jdbc.sql(select + " FROM \"InventoryBatch\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ? AND quantity > 0"
                + " AND \"isActive\" = true" + pin + extra + order).params(params);
    }

    private JdbcClient.StatementSpec lots(String org, StockMove dto, String variantId, String select, String extra,
            String order, Object extraParam) {
        return lots(org, dto, variantId, dto.locationId, select, extra, order, extraParam);
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
            String responsibleById, String approvedById) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"InventoryLedger\" (id, \"organizationId\", \"ledgerCode\", \"productId\", \"variantId\","
                        + " \"locationId\", \"batchId\", \"serialId\", type, \"qtyBefore\", \"quantityChange\","
                        + " \"balanceAfter\", \"unitCost\", \"totalValue\", \"referenceType\", \"referenceId\", notes,"
                        + " \"performedBy\", \"responsibleById\", \"approvedById\", \"createdAt\") VALUES (?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?::\"StockMoveType\", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ")")
                .params(id, org, ledgerCode, productId, variantId, locationId, batchId,
                        serialId, type, qtyBefore, change, after, unitCost, totalValue, referenceType, referenceId,
                        notes, TenantContext.userId(), responsibleById, approvedById)
                .update();
        return id;
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
