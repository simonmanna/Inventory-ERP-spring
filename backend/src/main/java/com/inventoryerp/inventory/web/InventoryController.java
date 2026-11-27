package com.inventoryerp.inventory.web;

import com.inventoryerp.shared.validation.ArrayMinSize;
import static com.inventoryerp.inventory.web.InventoryDtos.DOC_STATUSES;
import static com.inventoryerp.inventory.web.InventoryDtos.ISO_DATE;
import static com.inventoryerp.inventory.web.InventoryDtos.LOCATION_TYPES;
import static com.inventoryerp.inventory.web.InventoryDtos.WASTE_CATEGORIES;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.inventoryerp.inventory.application.DirectStockService;
import com.inventoryerp.inventory.application.InventoryIntegrityService;
import com.inventoryerp.inventory.application.InventoryIntegrityService.IntegrityReport;
import com.inventoryerp.inventory.application.InventoryQueryService;
import com.inventoryerp.inventory.application.InventoryRegisterService;
import com.inventoryerp.inventory.application.InventoryReportsService;
import com.inventoryerp.inventory.application.LedgerDetailService;
import com.inventoryerp.inventory.application.LocationService;
import com.inventoryerp.inventory.application.StockDocService;
import com.inventoryerp.inventory.application.StockDocService.DocInput;
import com.inventoryerp.inventory.application.StockDocService.DocLine;
import com.inventoryerp.inventory.application.StockDocService.ReceiveLine;
import com.inventoryerp.inventory.application.StockDocService.WasteQuery;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /inventory} locations, direct stock, queries, reports and stock documents (port of {@code inventory.controller.ts}). */
@RestController
@RequestMapping("/inventory")
public class InventoryController {

    private static final String NUM = "must be a number conforming to the specified constraints";
    private static final String STR = "must be a string";
    private static final String EMPTY = "should not be empty";
    private static final String ARR = "must be an array";
    /** Hardening R5a: caps on document lines and free text (the web forms enter lines by hand). */
    static final int MAX_LINES = 1000;
    private static final String LINES = "must contain no more than " + MAX_LINES + " elements";
    private static final String NOTES = "must be shorter than or equal to 2000 characters";
    private static final String REASON = "must be shorter than or equal to 1000 characters";

    /** {@code CreateLocationDto}. */
    public record CreateLocationRequest(
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String code,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String name,
            @OneOf(value = {"warehouse", "store", "virtual", "main_kitchen", "bar", "storage_room", "walkin_fridge",
                    "freezer", "dry_storage", "front_counter", "branch", "production"},
                    message = InventoryDtos.USER_LOCATION_TYPE_MESSAGE) String type,
            Boolean isActive,
            String branchId) {}

    /** {@code UpdateLocationDto}. */
    public record UpdateLocationRequest(
            String code,
            String name,
            @OneOf(value = {"warehouse", "store", "virtual", "main_kitchen", "bar", "storage_room", "walkin_fridge",
                    "freezer", "dry_storage", "front_counter", "branch", "production"},
                    message = InventoryDtos.USER_LOCATION_TYPE_MESSAGE) String type,
            Boolean isActive,
            String branchId) {}

    /** {@code DirectStockInItemDto} / {@code DirectStockOutItemDto}. */
    public record DirectStockItem(
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String productId,
            String variantId,
            @NotNull(message = NUM) @Positive(message = "must be a positive number") BigDecimal quantity,
            BigDecimal unitCost,
            @OneOf({"FEFO", "FIFO", "MANUAL", "SERIAL"}) String distStrategy,
            String batchNumber,
            @Pattern(regexp = ISO_DATE, message = "must be a valid ISO 8601 date string") String expiryDate,
            @Size(max = 2000, message = NOTES) String notes) {}

    /** {@code DirectStockInDto} / {@code DirectStockOutDto}. */
    public record DirectStockRequest(
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String locationId,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String responsibleById,
            String approvedById,
            @Size(max = 128, message = "must be shorter than or equal to 128 characters") String approverPin,
            @NotNull(message = ARR) @ArrayMinSize(1) @Size(max = MAX_LINES, message = LINES) List<@Valid DirectStockItem> items,
            @Size(max = 2000, message = NOTES) String notes) {}

    /** {@code StockOutItemDto}. */
    public record StockOutLine(
            @NotEmpty(message = EMPTY) String productId,
            String variantId,
            String unit,
            String batchNumber,
            @NotNull(message = NUM) @Positive(message = "must be a positive number") BigDecimal qty,
            @OneOf({"FEFO", "FIFO", "MANUAL", "SERIAL"}) String distStrategy) {}

    /** {@code WasteItemDto}. */
    public record WasteLine(
            @NotEmpty(message = EMPTY) String productId,
            String variantId,
            String unit,
            String batchNumber,
            @NotNull(message = NUM) @Positive(message = "must be a positive number") BigDecimal qty,
            Boolean isExpiry) {}

    /** {@code StockAdjustmentItemDto}. */
    public record AdjustmentLine(
            @NotEmpty(message = EMPTY) String productId,
            String variantId,
            String unit,
            String batchNumber,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal qtyActual) {}

    /** {@code StockTransferItemDto}. */
    public record TransferLine(
            @NotEmpty(message = EMPTY) String productId,
            String variantId,
            String unit,
            String batchNumber,
            @NotNull(message = NUM) BigDecimal qtyRequested,
            @OneOf({"FEFO", "FIFO", "MANUAL", "SERIAL"}) String distStrategy) {}

    /** {@code CreateStockOutDto}. */
    public record CreateStockOutRequest(
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String locationId,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String responsibleById,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String approvedById,
            @OneOf({"general_use", "kitchen_testing", "training", "damaged", "sample", "expired", "complimentary",
                    "other"}) String category,
            @Size(max = 1000, message = REASON) String reason,
            @Size(max = 2000, message = NOTES) String notes,
            @NotNull(message = ARR) @ArrayMinSize(1) @Size(max = MAX_LINES, message = LINES) List<@Valid StockOutLine> items) {}

    /** {@code CreateWasteDto}. */
    public record CreateWasteRequest(
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String locationId,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String responsibleById,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String approvedById,
            @OneOf({"expired", "spoiled", "burnt", "contaminated", "breakage", "other", "overmixed", "packaging_defect",
                    "qc_rejection"}) String category,
            @Size(max = 2000, message = NOTES) String notes,
            @NotNull(message = ARR) @ArrayMinSize(1) @Size(max = MAX_LINES, message = LINES) List<@Valid WasteLine> items) {}

    /** {@code CreateStockAdjustmentDto}. */
    public record CreateAdjustmentRequest(
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String locationId,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String responsibleById,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String approvedById,
            @OneOf({"cycle_count", "damaged", "expired", "theft", "found", "initial_count", "other"}) String reason,
            @Size(max = 2000, message = NOTES) String notes,
            @NotNull(message = ARR) @ArrayMinSize(1) @Size(max = MAX_LINES, message = LINES) List<@Valid AdjustmentLine> items) {}

    /** {@code CreateStockTransferDto}. */
    public record CreateTransferRequest(
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String fromLocationId,
            @OneOf({"immediate", "transit"}) String mode,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String toLocationId,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String responsibleById,
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String approvedById,
            @Size(max = 2000, message = NOTES) String notes,
            @NotNull(message = ARR) @ArrayMinSize(1) @Size(max = MAX_LINES, message = LINES) List<@Valid TransferLine> items) {}

    /** {@code ReceiveStockTransferLineDto}. */
    public record ReceiveLineRequest(
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) String itemId,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal received,
            @DecimalMin(value = "0", message = "must not be less than 0") BigDecimal damaged,
            @JsonProperty("short") @DecimalMin(value = "0", message = "short must not be less than 0") BigDecimal shortQty) {}

    /** {@code ReceiveStockTransferDto}. */
    public record ReceiveTransferRequest(
            @NotNull(message = ARR) @ArrayMinSize(1) @Size(max = MAX_LINES, message = LINES) List<@Valid ReceiveLineRequest> lines,
            @Size(max = 2000, message = NOTES) String notes,
            /* IPR S3: needed when the damaged and short goods are worth more than the approval threshold. */
            @Size(max = 1000, message = REASON) String lossReason,
            String approvedById,
            @Size(max = 128, message = "must be shorter than or equal to 128 characters") String approverPin) {}

    /** {@code ApproveAdjustmentDto}. */
    public record ApproveAdjustmentRequest(Boolean force, @Size(max = 1000, message = REASON) String forceReason) {}

    /** {@code ReverseStockDocDto}. */
    public record ReverseRequest(
            @NotNull(message = "must be a string") @NotEmpty(message = EMPTY) @Size(max = 1000, message = REASON)
            String reason) {}

    private final LocationService locations;
    private final StockDocService stockDocs;
    private final StockReversalService reversals;
    private final InventoryQueryService queries;
    private final DirectStockService directStock;
    private final LedgerDetailService ledgerDetail;
    private final InventoryReportsService reports;
    private final InventoryRegisterService registers;
    private final InventoryIntegrityService integrity;

    public InventoryController(LocationService locations, StockDocService stockDocs, StockReversalService reversals,
            InventoryQueryService queries, DirectStockService directStock, LedgerDetailService ledgerDetail,
            InventoryReportsService reports, InventoryRegisterService registers, InventoryIntegrityService integrity) {
        this.locations = locations;
        this.stockDocs = stockDocs;
        this.reversals = reversals;
        this.queries = queries;
        this.directStock = directStock;
        this.ledgerDetail = ledgerDetail;
        this.reports = reports;
        this.registers = registers;
        this.integrity = integrity;
    }

    // ---- Locations

    @GetMapping("/locations")
    @RequirePermissions(Permissions.INVENTORY_LOCATION__READ)
    public PageResult<Map<String, Object>> listLocations(@RequestParam Map<String, String> query) {
        PageQuery page = QueryParams.page(query, "type");
        String type = query.get("type");
        if (type != null && !List.of(LOCATION_TYPES).contains(type)) {
            throw QueryParams.validation(List.of("type must be one of the following values: "
                    + String.join(", ", LOCATION_TYPES)));
        }
        return locations.list(page, type);
    }

    @GetMapping("/locations/{id}")
    @RequirePermissions(Permissions.INVENTORY_LOCATION__READ)
    public Map<String, Object> findLocation(@PathVariable String id) {
        return locations.findOne(id);
    }

    @PostMapping("/locations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.INVENTORY_LOCATION__CREATE)
    public Map<String, Object> createLocation(@Valid @RequestBody CreateLocationRequest dto) {
        return locations.create(dto);
    }

    @PatchMapping("/locations/{id}")
    @RequirePermissions(Permissions.INVENTORY_LOCATION__UPDATE)
    public Map<String, Object> updateLocation(@PathVariable String id, @Valid @RequestBody UpdateLocationRequest dto) {
        return locations.update(id, dto);
    }

    @DeleteMapping("/locations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.INVENTORY_LOCATION__DELETE)
    public void removeLocation(@PathVariable String id) {
        locations.remove(id);
    }

    // ---- Direct stock in / out

    @PostMapping("/direct-stock/in")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY__MOVE)
    public Map<String, Object> directStockIn(@Valid @RequestBody DirectStockRequest dto) {
        return directStock.directInChecked(direct(dto));
    }

    @PostMapping("/direct-stock/out")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY__MOVE)
    public Map<String, Object> directStockOut(@Valid @RequestBody DirectStockRequest dto) {
        return directStock.directOutChecked(direct(dto));
    }

    private static DirectStockService.Request direct(DirectStockRequest dto) {
        return new DirectStockService.Request(dto.locationId(), dto.responsibleById(), dto.approvedById(),
                dto.approverPin(), dto.items().stream().map(i -> new DirectStockService.Line(i.productId(), i.variantId(),
                        i.quantity(), i.unitCost(), i.batchNumber(), i.expiryDate(), i.distStrategy(), i.notes())).toList(),
                dto.notes());
    }

    // ---- Queries

    @GetMapping("/items")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public PageResult<Map<String, Object>> listItems(@RequestParam Map<String, String> query) {
        QueryParams.page(query, "locationId", "lowStock", "outOfStock");
        return queries.listItems(query);
    }

    @GetMapping("/items/{productId}")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> itemDetail(@PathVariable String productId,
            @RequestParam(required = false) String locationId) {
        return queries.getItemDetail(productId, locationId);
    }

    @GetMapping("/stats")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> stats() {
        return queries.getStockStats();
    }

    @GetMapping("/product-stock-levels")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public PageResult<Map<String, Object>> productStockLevels(@RequestParam Map<String, String> query) {
        QueryParams.page(query, "locationId", "lowStock", "outOfStock");
        return queries.listProductStockLevels(query);
    }

    /** UB1 (post-parity): active staff (id + name) for the stock forms' responsible / approver pickers. */
    @GetMapping("/staff-options")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public List<Map<String, Object>> staffOptions() {
        return queries.staffOptions();
    }

    @GetMapping("/ledger")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public PageResult<Map<String, Object>> ledger(@RequestParam Map<String, String> query) {
        new InventoryDtos.Check(query, "productId", "locationId", "type", "referenceType", "dateFrom", "dateTo", "page",
                "pageSize").date("dateFrom").date("dateTo").number("page").number("pageSize").done();
        return queries.getLedger(query);
    }

    @GetMapping("/ledger/{id}")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> ledgerEntry(@PathVariable String id) {
        return ledgerDetail.getEntry(id);
    }

    // ---- Reports

    @GetMapping("/reports/expiring")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public List<Map<String, Object>> expiring(@RequestParam Map<String, String> query) {
        return reports.expiring(query);
    }

    @GetMapping("/reports/reorder")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public List<Map<String, Object>> reorder(@RequestParam Map<String, String> query) {
        return reports.reorder(query);
    }

    @GetMapping("/reports/movements")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public List<Map<String, Object>> movements(@RequestParam Map<String, String> query) {
        return queries.getMovementSummary(query.get("start"), query.get("end"), query.get("locationId"));
    }

    @GetMapping("/reports/item-movements")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> itemMovements(@RequestParam Map<String, String> query) {
        return reports.itemMovements(query);
    }

    @GetMapping("/reports/valuation")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> valuation(@RequestParam Map<String, String> query) {
        return reports.valuation(query);
    }

    @GetMapping("/reports/movement-analysis")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> movementAnalysis(@RequestParam Map<String, String> query) {
        return reports.movementAnalysis(query);
    }

    @GetMapping("/reports/register/{kind}")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> movementRegister(@PathVariable String kind, @RequestParam Map<String, String> query) {
        return registers.register(kind, query);
    }

    @GetMapping("/reports/reconciliation")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> reconciliation(@RequestParam Map<String, String> query) {
        String tol = query.get("tolerance");
        Double tolerance = null;
        if (tol != null) {
            // Hardening R5a: a non-numeric or negative tolerance is a 400 (it reached new BigDecimal("NaN"), a 500).
            double n = JsNumbers.number(tol);
            if (Double.isNaN(n) || Double.isInfinite(n) || n < 0) {
                throw ApiException.badRequest("tolerance must be a non-negative number");
            }
            tolerance = n;
        }
        return queries.getStockReconciliation(query.get("locationId"), tolerance,
                "true".equals(query.get("includeMatched")));
    }

    /**
     * Hardening R0: the inventory integrity checks (ledger vs quant, lots, serials, serial state, valuation vs stock
     * ledger, valuation vs GL, transit, reservations, offline queue). Read-only; at most 5,000 rows per check.
     */
    @GetMapping("/reports/integrity")
    @RequirePermissions(Permissions.INVENTORY_VALUATION__READ)
    public IntegrityReport integrity(@RequestParam(required = false) String locationId) {
        return integrity.report(locationId);
    }

    @GetMapping("/reports/stock-health")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> stockHealth(@RequestParam Map<String, String> query) {
        return reports.stockHealthReport(query);
    }

    @GetMapping("/reports/negative-stock")
    @RequirePermissions(Permissions.INVENTORY__READ)
    public Map<String, Object> negativeStock(@RequestParam Map<String, String> query) {
        return reports.negativeStock(query);
    }

    // ---- Stock-outs

    @PostMapping("/stock-outs")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__CREATE)
    public Map<String, Object> createStockOut(@Valid @RequestBody CreateStockOutRequest dto) {
        return stockDocs.createStockOut(new DocInput(dto.locationId(), null, null, null, dto.responsibleById(),
                dto.approvedById(), dto.category(), dto.reason(), dto.notes(), dto.items().stream()
                .map(i -> new DocLine(i.productId(), i.variantId(), i.unit(), i.batchNumber(), i.qty(), i.distStrategy(),
                        null, null, null)).toList()));
    }

    @GetMapping("/stock-outs")
    @RequirePermissions(Permissions.INVENTORY_DOC__READ)
    public List<Map<String, Object>> listStockOuts(@RequestParam(required = false) String status,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String limit) {
        return stockDocs.list("out", status, dateFrom, dateTo, limit);
    }

    @PostMapping("/stock-outs/{id}/approve")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> approveStockOut(@PathVariable String id) {
        return stockDocs.approveStockOut(id);
    }

    // ---- Waste

    @PostMapping("/waste")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__CREATE)
    public Map<String, Object> createWaste(@Valid @RequestBody CreateWasteRequest dto) {
        return stockDocs.createWaste(new DocInput(dto.locationId(), null, null, null, dto.responsibleById(),
                dto.approvedById(), dto.category(), null, dto.notes(), dto.items().stream()
                .map(i -> new DocLine(i.productId(), i.variantId(), i.unit(), i.batchNumber(), i.qty(), null,
                        i.isExpiry(), null, null)).toList()));
    }

    @GetMapping("/waste")
    @RequirePermissions(Permissions.INVENTORY_DOC__READ)
    public List<Map<String, Object>> listWaste(@RequestParam Map<String, String> query) {
        return stockDocs.listWaste(wasteQuery(query));
    }

    @GetMapping("/waste/summary")
    @RequirePermissions(Permissions.INVENTORY_DOC__READ)
    public Map<String, Object> wasteSummary(@RequestParam Map<String, String> query) {
        return stockDocs.wasteSummary(wasteQuery(query));
    }

    private static WasteQuery wasteQuery(Map<String, String> q) {
        new InventoryDtos.Check(q, "status", "category", "locationId", "from", "to").oneOf("status", DOC_STATUSES)
                .oneOf("category", WASTE_CATEGORIES).date("from").date("to").done();
        return new WasteQuery(q.get("status"), q.get("category"), q.get("locationId"), q.get("from"), q.get("to"));
    }

    @GetMapping("/waste/{id}")
    @RequirePermissions(Permissions.INVENTORY_DOC__READ)
    public Map<String, Object> getWaste(@PathVariable String id) {
        return stockDocs.getWaste(id);
    }

    @PostMapping("/waste/{id}/approve")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> approveWaste(@PathVariable String id) {
        return stockDocs.approveWaste(id);
    }

    @PostMapping("/waste/{id}/cancel")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> cancelWaste(@PathVariable String id) {
        return stockDocs.cancelWaste(id);
    }

    // ---- Adjustments

    @PostMapping("/adjustments")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__CREATE)
    public Map<String, Object> createAdjustment(@Valid @RequestBody CreateAdjustmentRequest dto) {
        return stockDocs.createAdjustment(new DocInput(dto.locationId(), null, null, null, dto.responsibleById(),
                dto.approvedById(), null, dto.reason(), dto.notes(), dto.items().stream()
                .map(i -> new DocLine(i.productId(), i.variantId(), i.unit(), i.batchNumber(), null, null, null,
                        i.qtyActual(), null)).toList()), false);
    }

    @GetMapping("/adjustments")
    @RequirePermissions(Permissions.INVENTORY_DOC__READ)
    public List<Map<String, Object>> listAdjustments(@RequestParam(required = false) String status,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String limit) {
        return stockDocs.list("adjustment", status, dateFrom, dateTo, limit);
    }

    @PostMapping("/adjustments/{id}/approve")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> approveAdjustment(@PathVariable String id,
            @Valid @RequestBody(required = false) ApproveAdjustmentRequest dto) {
        return stockDocs.approveAdjustment(id, false, dto == null ? null : dto.force(),
                dto == null ? null : dto.forceReason());
    }

    @PostMapping("/adjustments/{id}/cancel")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> cancelAdjustment(@PathVariable String id) {
        return stockDocs.cancelAdjustment(id);
    }

    // ---- Transfers

    @PostMapping("/transfers")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__CREATE)
    public Map<String, Object> createTransfer(@Valid @RequestBody CreateTransferRequest dto) {
        return stockDocs.createTransfer(new DocInput(null, dto.fromLocationId(), dto.toLocationId(), dto.mode(),
                dto.responsibleById(), dto.approvedById(), null, null, dto.notes(), dto.items().stream()
                .map(i -> new DocLine(i.productId(), i.variantId(), i.unit(), i.batchNumber(), null, i.distStrategy(),
                        null, null, i.qtyRequested())).toList()));
    }

    @GetMapping("/transfers")
    @RequirePermissions(Permissions.INVENTORY_DOC__READ)
    public List<Map<String, Object>> listTransfers(@RequestParam(required = false) String status,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String limit) {
        return stockDocs.list("transfer", status, dateFrom, dateTo, limit);
    }

    @PostMapping("/transfers/{id}/approve")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> approveTransfer(@PathVariable String id) {
        return stockDocs.approveTransfer(id);
    }

    @GetMapping("/transfers/{id}")
    @RequirePermissions(Permissions.INVENTORY_DOC__READ)
    public Map<String, Object> getTransfer(@PathVariable String id) {
        return stockDocs.getTransfer(id);
    }

    @PostMapping("/transfers/{id}/dispatch")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_DOC__UPDATE)
    public Map<String, Object> dispatchTransfer(@PathVariable String id) {
        return stockDocs.dispatchTransfer(id);
    }

    /**
     * Gaps h-00 F4: a receipt is not claim-guarded (a transfer takes several), so a resubmitted one booked the goods
     * twice; the key is required and a retry with it replays the first answer.
     */
    @PostMapping("/transfers/{id}/receive")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.INVENTORY_DOC__UPDATE)
    public Map<String, Object> receiveTransfer(@PathVariable String id, @Valid @RequestBody ReceiveTransferRequest dto) {
        return stockDocs.receiveTransfer(id, dto.lines().stream().map(l -> new ReceiveLine(l.itemId(), l.received(),
                l.damaged(), l.shortQty())).toList(), dto.notes(),
                new StockDocService.LossApproval(dto.lossReason(), dto.approvedById(), dto.approverPin()));
    }

    @PostMapping("/transfers/{id}/recall")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> recallTransfer(@PathVariable String id, @Valid @RequestBody ReverseRequest dto) {
        return stockDocs.recallTransfer(id, dto.reason());
    }

    @PostMapping("/transfers/{id}/cancel")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> cancelTransfer(@PathVariable String id) {
        return stockDocs.cancelTransfer(id);
    }

    // ---- Reversals

    @PostMapping("/stock-outs/{id}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> reverseStockOut(@PathVariable String id, @Valid @RequestBody ReverseRequest dto) {
        return reversals.reverseDocument("stock_out", id, dto.reason());
    }

    @PostMapping("/waste/{id}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> reverseWaste(@PathVariable String id, @Valid @RequestBody ReverseRequest dto) {
        return reversals.reverseDocument("waste", id, dto.reason());
    }

    @PostMapping("/adjustments/{id}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> reverseAdjustment(@PathVariable String id, @Valid @RequestBody ReverseRequest dto) {
        return reversals.reverseDocument("stock_adjustment", id, dto.reason());
    }

    @PostMapping("/transfers/{id}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.INVENTORY_DOC__APPROVE)
    public Map<String, Object> reverseTransfer(@PathVariable String id, @Valid @RequestBody ReverseRequest dto) {
        return reversals.reverseDocument("stock_transfer", id, dto.reason());
    }
}
