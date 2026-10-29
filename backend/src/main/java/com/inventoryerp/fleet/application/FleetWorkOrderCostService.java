package com.inventoryerp.fleet.application;

import static com.inventoryerp.fleet.application.MaintainableUnits.notBlank;
import static com.inventoryerp.fleet.application.MaintainableUnits.trim;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.fleet.application.MaintenanceCommands.ExternalBillInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.LabourInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.PartIssueInput;
import com.inventoryerp.fleet.application.MaintenanceCommands.PartReturnInput;
import com.inventoryerp.fleet.domain.FleetWorkOrderLine;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.VendorBillService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cost lines of fleet work orders (1.6, ADR-S009 §5–6), each posted exactly once through the posting engine with
 * posting key {@code fleet:wo_<kind>:<lineId>} — the maintenance expense always lands on the unit's cost centre with
 * the {@code vehicleId} / {@code workOrderId} dimensions:
 *
 * <ul>
 *   <li><b>part</b>: {@code StockService.issue} (quantity-only, {@code internal_use}, never below on-hand), then
 *       Dr fleet maintenance expense / Cr the part's stock account at the issue cost;</li>
 *   <li><b>part return</b>: unused quantity received back at the issue unit cost (bare receipt), Dr stock /
 *       Cr maintenance expense;</li>
 *   <li><b>labour</b>: hr employee × hours × rate; a memo cost (payroll already expenses wages) unless the org setting
 *       {@code fleet.maintenanceLabourAbsorption} is on: Dr maintenance expense / Cr workshop labour absorbed;</li>
 *   <li><b>external</b> workshop / supplier bill: a draft vendor bill is raised in AP through
 *       {@link VendorBillService} (its line debits the clearing account "fleet workshop bills to receive"; AP approves
 *       and posts it, crediting the supplier), and the cost accrues on the unit now: Dr maintenance expense /
 *       Cr the clearing account;</li>
 *   <li><b>reverse</b>: the line's journal is reversed with {@code PostingService.reverse} (a part goes back to stock
 *       in full); returns are reversed by reversing their own line.</li>
 * </ul>
 * Money routes are {@code @Idempotent(required = true)}; the work-order row is locked for every line so concurrent
 * issues on one work order serialise, and the stock engine's row lock keeps two work orders from issuing the same
 * last units.
 */
@Service
public class FleetWorkOrderCostService {

    static final String SETTING_LABOUR_ABSORPTION = "fleet.maintenanceLabourAbsorption";
    static final String SOURCE = "fleet_work_order";

    private final FleetDb db;
    private final FleetWorkOrderService workOrders;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final VendorBillService vendorBills;
    private final SettingResolverService settings;
    private final AuditService audit;

    public FleetWorkOrderCostService(FleetDb db, FleetWorkOrderService workOrders, StockService stock,
            StockPostingService stockPosting, PostingService posting, AccountResolverService accounts,
            VendorBillService vendorBills, SettingResolverService settings, AuditService audit) {
        this.db = db;
        this.workOrders = workOrders;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.posting = posting;
        this.accounts = accounts;
        this.vendorBills = vendorBills;
        this.settings = settings;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ accounts (ADR-S009 §6)

    /** Fleet repairs &amp; maintenance (6420, mapping {@code fleet_maintenance_expense}). */
    public String maintenanceExpenseAccount() {
        return accounts.ensureByCode("6420", "Fleet Repairs & Maintenance", "operating_expense",
                "fleet_maintenance_expense");
    }

    /** Clearing between the accrued workshop cost and the AP vendor bill (2168, {@code fleet_maintenance_accrual}). */
    public String billClearingAccount() {
        return accounts.ensureByCode("2168", "Fleet Workshop Bills to Receive", "current_liability",
                "fleet_maintenance_accrual");
    }

    /** Workshop labour absorbed (6429, {@code fleet_maintenance_labour_absorbed}). */
    public String labourAbsorbedAccount() {
        return accounts.ensureByCode("6429", "Fleet Workshop Labour Absorbed", "operating_expense",
                "fleet_maintenance_labour_absorbed");
    }

    // ------------------------------------------------------------------ parts

    /** Issue a spare part from stock onto the work order (open / in progress). */
    @Transactional
    public Map<String, Object> issuePart(String workOrderId, PartIssueInput in) {
        Map<String, Object> w = workOrders.require(workOrderId, true);
        String code = FleetWorkOrderService.code(w);
        if (!MaintenancePolicies.acceptsParts((String) w.get("status"))) {
            throw ApiException.badRequest("Parts can only be issued to an open or in-progress work order (" + code
                    + " is " + MaintenancePolicies.label((String) w.get("status")) + ")");
        }
        String lineId = java.util.UUID.randomUUID().toString();
        StockMove.Issued issued = stock.issue(StockMove.of(in.productId(), in.locationId(), in.quantity())
                .variant(trim(in.variantId())).skipGl().requireAvailable(true).moveType("internal_use")
                .source(SOURCE, code).reference(code).notes("Work order " + code + " · " + w.get("title")));
        BigDecimal value = issued.totalValue().setScale(6, RoundingMode.HALF_UP);
        String key = "fleet:wo_part:" + lineId;
        String label = "Work order " + code + " · part " + productName(in.productId());
        String entry = null;
        if (value.signum() > 0) {
            entry = post("INV", w, label, key, value, maintenanceExpenseAccount(),
                    stockPosting.stockAccount(in.productId(), value), true);
        }
        Map<String, Object> data = base(w, lineId, "part", trim(in.description()) == null ? label : in.description());
        data.put("productId", in.productId());
        data.put("variantId", trim(in.variantId()));
        data.put("locationId", in.locationId());
        data.put("quantity", issued.baseQuantity());
        data.put("stockLedgerCode", issued.ledgerCode());
        data.put("unitCost", issued.unitCost());
        data.put("amount", value);
        data.put("journalEntryId", entry);
        data.put("postingKey", key);
        return saved(w, db.insert(FleetWorkOrderLine.class, data));
    }

    /** Return unused quantity of an issued part to the location it came from, at the issue cost. */
    @Transactional
    public Map<String, Object> returnPart(String workOrderId, String lineId, PartReturnInput in) {
        Map<String, Object> w = workOrders.require(workOrderId, true);
        String code = FleetWorkOrderService.code(w);
        assertCosts(w);
        Map<String, Object> part = line(workOrderId, lineId, true);
        if (!"part".equals(part.get("lineType")) || !"posted".equals(part.get("status"))) {
            throw ApiException.badRequest("Only a posted part line can be returned");
        }
        BigDecimal remaining = SqlRows.dec(part, "quantity").subtract(SqlRows.dec(part, "returnedQuantity"));
        if (in.quantity().compareTo(remaining) > 0) {
            throw ApiException.badRequest("Only " + JsNumbers.dec(remaining) + " of this part can still be returned");
        }
        BigDecimal unitCost = SqlRows.dec(part, "unitCost");
        BigDecimal value = in.quantity().multiply(unitCost).setScale(6, RoundingMode.HALF_UP);
        String productId = (String) part.get("productId");
        StockMove receipt = StockMove.of(productId, (String) part.get("locationId"), in.quantity())
                .variant((String) part.get("variantId")).unitCost(unitCost).moveType("return_in")
                .source(SOURCE, code).reference(code).notes("Unused part returned from work order " + code);
        stock.receive(receipt);
        String returnId = java.util.UUID.randomUUID().toString();
        String key = "fleet:wo_part_return:" + returnId;
        String label = "Work order " + code + " · part returned " + productName(productId);
        String entry = null;
        if (value.signum() > 0) {
            entry = post("INV", w, label, key, value, stockPosting.stockAccount(productId, value),
                    maintenanceExpenseAccount(), false);
        }
        db.update(FleetWorkOrderLine.class, lineId, Obj.of("returnedQuantity",
                SqlRows.dec(part, "returnedQuantity").add(in.quantity())));
        Map<String, Object> data = base(w, returnId, "part_return", trim(in.description()) == null ? label
                : in.description());
        data.put("productId", productId);
        data.put("variantId", part.get("variantId"));
        data.put("locationId", part.get("locationId"));
        data.put("quantity", in.quantity());
        data.put("returnOfLineId", lineId);
        data.put("unitCost", unitCost);
        data.put("amount", value.negate());
        data.put("journalEntryId", entry);
        data.put("postingKey", key);
        return saved(w, db.insert(FleetWorkOrderLine.class, data));
    }

    // ------------------------------------------------------------------ labour

    /** Book labour: hours × rate (default the employee's hourly rate); posted only with labour absorption on. */
    @Transactional
    public Map<String, Object> addLabour(String workOrderId, LabourInput in) {
        Map<String, Object> w = workOrders.require(workOrderId, true);
        String code = FleetWorkOrderService.code(w);
        assertCosts(w);
        Map<String, Object> employee = workOrders.employee(in.employeeId());
        if (employee == null) {
            throw ApiException.badRequest("Employee not found");
        }
        BigDecimal rate = in.rate() != null ? in.rate()
                : employee.get("hourlyRate") == null ? null : SqlRows.dec(employee, "hourlyRate");
        if (rate == null) {
            throw ApiException.badRequest("Employee " + employee.get("employeeCode")
                    + " has no hourly rate; enter the rate");
        }
        BigDecimal amount = in.hours().multiply(rate).setScale(2, RoundingMode.HALF_UP);
        String lineId = java.util.UUID.randomUUID().toString();
        String name = (employee.get("firstName") + (employee.get("lastName") == null ? "" : " "
                + employee.get("lastName"))).trim();
        String label = "Work order " + code + " · labour " + name + " " + JsNumbers.dec(in.hours()) + " h";
        String key = null;
        String entry = null;
        if (labourAbsorption() && amount.signum() > 0) {
            key = "fleet:wo_labour:" + lineId;
            entry = post("GEN", w, label, key, amount, maintenanceExpenseAccount(), labourAbsorbedAccount(), true);
        }
        Map<String, Object> data = base(w, lineId, "labour", trim(in.description()) == null ? label
                : in.description());
        data.put("employeeId", in.employeeId());
        data.put("hours", in.hours());
        data.put("workDate", in.workDate() == null ? MaintainableUnits.today() : in.workDate());
        data.put("unitCost", rate);
        data.put("amount", amount);
        data.put("journalEntryId", entry);
        data.put("postingKey", key);
        return saved(w, db.insert(FleetWorkOrderLine.class, data));
    }

    private boolean labourAbsorption() {
        return settings.resolveBool(SETTING_LABOUR_ABSORPTION, SettingContext.EMPTY);
    }

    // ------------------------------------------------------------------ external bills

    /** External workshop / supplier bill: draft vendor bill in AP + accrued cost on the unit. */
    @Transactional
    public Map<String, Object> addBill(String workOrderId, ExternalBillInput in) {
        Map<String, Object> w = workOrders.require(workOrderId, true);
        String code = FleetWorkOrderService.code(w);
        assertCosts(w);
        Map<String, Object> partner = SqlRows.one(db.jdbc().sql("SELECT id, name, \"isSupplier\" FROM \"Partner\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(in.partnerId(),
                FleetDb.org()));
        if (partner == null) {
            throw ApiException.badRequest("Supplier not found");
        }
        LocalDate billDate = in.billDate() == null ? MaintainableUnits.today() : in.billDate();
        if (billDate.isAfter(MaintainableUnits.today())) {
            throw ApiException.badRequest("The bill date cannot be in the future");
        }
        BigDecimal amount = in.amount().setScale(2, RoundingMode.HALF_UP);
        String clearing = billClearingAccount();
        String description = in.description().trim();
        Map<String, Object> bill = vendorBills.create(new VendorBillService.BillInput(in.partnerId(),
                billDate.toString(), null, null, in.supplierReference().trim(), "Fleet work order " + code
                        + " · " + w.get("title"), (String) w.get("branchId"), null,
                List.of(new LineInput(null, null, null, null, clearing, "Work order " + code + " · " + description,
                        BigDecimal.ONE, amount, null, null, null, null, null, null, null, null))));
        String lineId = java.util.UUID.randomUUID().toString();
        String key = "fleet:wo_bill:" + lineId;
        String label = "Work order " + code + " · " + partner.get("name") + " " + in.supplierReference().trim();
        String entry = post("GEN", w, label, key, amount, maintenanceExpenseAccount(), clearing, true);
        Map<String, Object> data = base(w, lineId, "external", description);
        data.put("partnerId", in.partnerId());
        data.put("supplierReference", in.supplierReference().trim());
        data.put("billDate", billDate);
        data.put("vendorBillId", bill.get("id"));
        data.put("unitCost", amount);
        data.put("amount", amount);
        data.put("journalEntryId", entry);
        data.put("postingKey", key);
        return saved(w, db.insert(FleetWorkOrderLine.class, data));
    }

    // ------------------------------------------------------------------ reversal

    /**
     * Reverse a posted line (correction): its journal is reversed; a part comes back to stock in full (refused once
     * part of it was returned — reverse the returns first); a returned quantity is re-issued when a return is
     * reversed; an external line is refused while its vendor bill is posted in AP (cancel the bill there first).
     */
    @Transactional
    public Map<String, Object> reverse(String workOrderId, String lineId, String reason) {
        Map<String, Object> w = workOrders.require(workOrderId, true);
        String code = FleetWorkOrderService.code(w);
        assertCosts(w);
        Map<String, Object> l = line(workOrderId, lineId, true);
        if (!"posted".equals(l.get("status"))) {
            throw ApiException.badRequest("This line is already reversed");
        }
        String type = (String) l.get("lineType");
        switch (type) {
            case "part" -> {
                if (SqlRows.dec(l, "returnedQuantity").signum() > 0) {
                    throw ApiException.badRequest("Part of this line was returned; reverse the returns first");
                }
                stock.receive(StockMove.of((String) l.get("productId"), (String) l.get("locationId"),
                        SqlRows.dec(l, "quantity")).variant((String) l.get("variantId"))
                        .unitCost(SqlRows.dec(l, "unitCost")).moveType("reversal_in").source(SOURCE, code)
                        .reference(code).notes("Reversal of a part issued to work order " + code));
            }
            case "part_return" -> {
                Map<String, Object> part = line(workOrderId, (String) l.get("returnOfLineId"), true);
                if (!"posted".equals(part.get("status"))) {
                    throw ApiException.badRequest("The returned part line is no longer posted");
                }
                stock.issue(StockMove.of((String) l.get("productId"), (String) l.get("locationId"),
                        SqlRows.dec(l, "quantity")).variant((String) l.get("variantId")).skipGl()
                        .requireAvailable(true).moveType("internal_use").source(SOURCE, code).reference(code)
                        .notes("Reversal of a part return on work order " + code));
                db.update(FleetWorkOrderLine.class, (String) part.get("id"), Obj.of("returnedQuantity",
                        SqlRows.dec(part, "returnedQuantity").subtract(SqlRows.dec(l, "quantity"))));
            }
            case "external" -> {
                if (l.get("vendorBillId") != null) {
                    Map<String, Object> bill = SqlRows.one(db.jdbc().sql("SELECT \"documentNumber\", status::text"
                            + " AS status FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?")
                            .params(l.get("vendorBillId"), FleetDb.org()));
                    if (bill != null && "posted".equals(bill.get("status"))) {
                        throw ApiException.badRequest("Vendor bill " + bill.get("documentNumber") + " is posted in"
                                + " Accounts Payable; cancel it there first, then reverse this line");
                    }
                }
            }
            default -> { }
        }
        String reversal = null;
        if (l.get("journalEntryId") != null) {
            Map<String, Object> rev = posting.reverse((String) l.get("journalEntryId"), Instant.now(),
                    "Reversal · work order " + code + " · " + reason.trim());
            reversal = (String) rev.get("id");
        }
        db.update(FleetWorkOrderLine.class, lineId, Obj.of("status", "reversed", "reversalEntryId", reversal,
                "reversedAt", Instant.now(), "reversedBy", TenantContext.userId(), "reverseReason", reason.trim()));
        workOrders.refreshTotals(workOrderId);
        audit.recordInTx(AuditInput.of("FleetWorkOrderLine", lineId, "update", Obj.of("status", "posted"),
                Obj.of("status", "reversed", "reason", reason.trim())));
        return workOrders.detailOf(workOrderId);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Balanced two-line entry: {@code debit} / {@code credit}; the expense side ({@code debitIsExpense} picks it)
     * carries the unit's cost centre; both carry the vehicle / work-order dimensions.
     */
    private String post(String journal, Map<String, Object> w, String label, String key, BigDecimal amount,
            String debit, String credit, boolean debitIsExpense) {
        String cc = (String) w.get("costCenterId");
        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("vehicleId", w.get("vehicleId"));
        dims.put("workOrderId", w.get("id"));
        String v = JsNumbers.dec(amount);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(debit, v, null, null, label, null, debitIsExpense ? cc : null, dims));
        lines.add(new Line(credit, null, v, null, label, null, debitIsExpense ? null : cc, dims));
        Map<String, Object> je = posting.post(new PostingRequest(journal, Instant.now(), label, null, null, SOURCE,
                (String) w.get("id"), null, key, (String) w.get("branchId"), null, dims, lines));
        return (String) je.get("id");
    }

    private Map<String, Object> base(Map<String, Object> w, String lineId, String type, String description) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", lineId);
        data.put("workOrderId", w.get("id"));
        data.put("vehicleId", w.get("vehicleId"));
        data.put("lineType", type);
        data.put("status", "posted");
        data.put("description", description);
        data.put("createdBy", TenantContext.userId());
        return data;
    }

    private Map<String, Object> saved(Map<String, Object> w, Map<String, Object> line) {
        workOrders.refreshTotals((String) w.get("id"));
        audit.recordInTx(AuditInput.of("FleetWorkOrderLine", (String) line.get("id"), "create", null, line));
        return workOrders.detailOf((String) w.get("id"));
    }

    private Map<String, Object> line(String workOrderId, String lineId, boolean lock) {
        Map<String, Object> l = db.byId(FleetWorkOrderLine.class, lineId, lock);
        if (l == null || !workOrderId.equals(l.get("workOrderId"))) {
            throw ApiException.notFound("Work order line not found");
        }
        return l;
    }

    private static void assertCosts(Map<String, Object> w) {
        if (!MaintenancePolicies.acceptsCosts((String) w.get("status"))) {
            throw ApiException.badRequest("Work order " + w.get("code") + " is "
                    + MaintenancePolicies.label((String) w.get("status")) + "; its costs can no longer change");
        }
    }

    private String productName(String productId) {
        return notBlank(productId) ? db.jdbc().sql("SELECT name FROM \"Product\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(productId, FleetDb.org()).query(String.class).optional()
                .orElse("") : "";
    }
}
