package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountingPolicyService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.CostLineageService;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.invoicing.application.DocumentBuilderService;
import com.inventoryerp.invoicing.application.VendorBillService;
import com.inventoryerp.inventory.application.StockPostingService.LandedLine;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.procurement.application.LandedCostLayers.Applied;
import com.inventoryerp.procurement.application.LandedCostLayers.Group;
import com.inventoryerp.procurement.domain.LandedCost;
import com.inventoryerp.procurement.domain.LandedCostAllocation;
import com.inventoryerp.procurement.domain.LandedCostCharge;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Landed cost voucher (ADR-S007 §2–3). One voucher covers one or more posted goods receipts — typically the partial
 * deliveries of an import {@code Shipment} — and any number of charge lines, each with its own cost component,
 * supplier and invoice reference, currency, recoverable tax, IAS 2 treatment, allocation basis
 * ({@code value | quantity | equal | weight | volume | manual}) and optional subset of receipt lines.
 *
 * <p>Workflow draft → submitted → approved → posted → reversed (or cancelled before posting); prepare, approve, post and
 * reverse are separate permissions. Posting, in one transaction: per capitalised charge the allocation over the
 * eligible receipt movements (exact, remainder on the last), the on-hand share raising the cost layer under the
 * product's costing method (AVCO average, FIFO / lot, serials) and the consumed share going to COGS; expensed charges to
 * their expense account with the cost centre; recoverable tax to input tax; each charge credited to its credit account
 * — by default the landed-cost clearing account, which the freight / customs vendor bill later clears, so nothing is
 * expensed twice. Reversal takes the capitalisation back out of whatever is still on hand and reverses the entry; the
 * part sold since moves from stock back to COGS.
 */
@Service
public class LandedCostVoucherService {

    /** One charge line. Amount and tax are in the charge currency; base = × exchangeRate. */
    public record ChargeInput(String costComponentId, String kind, String description, String supplierId,
            String supplierInvoiceRef, String invoiceDate, String currencyCode, BigDecimal exchangeRate,
            BigDecimal amount, BigDecimal taxAmount, String treatment, String allocationMethod, String expenseAccountId,
            String creditAccountId, String costCenterId, List<String> appliesToLineIds,
            Map<String, BigDecimal> manualAllocations, String billMode, String vendorBillId, String dueDate,
            String taxId, Boolean taxRecoverable) {

        /** Without the tax recoverability fields (resolved from the tax, component or organisation default). */
        public ChargeInput(String costComponentId, String kind, String description, String supplierId,
                String supplierInvoiceRef, String invoiceDate, String currencyCode, BigDecimal exchangeRate,
                BigDecimal amount, BigDecimal taxAmount, String treatment, String allocationMethod,
                String expenseAccountId, String creditAccountId, String costCenterId, List<String> appliesToLineIds,
                Map<String, BigDecimal> manualAllocations, String billMode, String vendorBillId, String dueDate) {
            this(costComponentId, kind, description, supplierId, supplierInvoiceRef, invoiceDate, currencyCode,
                    exchangeRate, amount, taxAmount, treatment, allocationMethod, expenseAccountId, creditAccountId,
                    costCenterId, appliesToLineIds, manualAllocations, billMode, vendorBillId, dueDate, null, null);
        }

        /** Without the supplier-bill fields (bill mode resolved from supplier / credit account). */
        public ChargeInput(String costComponentId, String kind, String description, String supplierId,
                String supplierInvoiceRef, String invoiceDate, String currencyCode, BigDecimal exchangeRate,
                BigDecimal amount, BigDecimal taxAmount, String treatment, String allocationMethod,
                String expenseAccountId, String creditAccountId, String costCenterId, List<String> appliesToLineIds,
                Map<String, BigDecimal> manualAllocations) {
            this(costComponentId, kind, description, supplierId, supplierInvoiceRef, invoiceDate, currencyCode,
                    exchangeRate, amount, taxAmount, treatment, allocationMethod, expenseAccountId, creditAccountId,
                    costCenterId, appliesToLineIds, manualAllocations, null, null, null);
        }
    }

    /** Voucher body; receipts default to the shipment's. */
    public record VoucherInput(String shipmentId, List<String> goodsReceiptIds, String date, String notes,
            String costCenterId, List<ChargeInput> charges) {}

    /** A receipt movement with its receipt line and basis drivers. */
    private record Unit(String grnId, String receiptNumber, String grnLineId, Group group, BigDecimal weight,
            BigDecimal volume) {}

    /** One computed allocation row. */
    private record Alloc(String chargeId, Unit unit, String method, BigDecimal basis, BigDecimal share,
            BigDecimal allocated, BigDecimal rounding, Applied applied) {}

    private record Computation(List<Alloc> allocs, List<LandedLine> landed, List<Line> other, BigDecimal total,
            BigDecimal capitalized, BigDecimal expensed, BigDecimal tax) {}

    private static final int SIX = 6;

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final StockPostingService stockPosting;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AccountResolverService accounts;
    private final LandedCostLayers layers;
    private final CostComponentService components;
    private final ShipmentService shipments;
    private final VendorBillService bills;
    private final TransactionTemplate tx;
    private final CostLineageService lineage;
    private final AccountingPolicyService policies;
    private final SettingResolverService settings;

    public LandedCostVoucherService(JdbcClient jdbc, SequenceService seq, AuditService audit,
            StockPostingService stockPosting, PostingService posting, AccountDeterminationService determination,
            AccountResolverService accounts, LandedCostLayers layers, CostComponentService components,
            ShipmentService shipments, VendorBillService bills, PlatformTransactionManager txm,
            CostLineageService lineage, AccountingPolicyService policies, SettingResolverService settings) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.audit = audit;
        this.stockPosting = stockPosting;
        this.posting = posting;
        this.determination = determination;
        this.accounts = accounts;
        this.layers = layers;
        this.components = components;
        this.shipments = shipments;
        this.bills = bills;
        this.tx = new TransactionTemplate(txm);
        this.lineage = lineage;
        this.policies = policies;
        this.settings = settings;
    }

    private static BigDecimal dp6(BigDecimal v) {
        return v.setScale(SIX, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status, String shipmentId) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ? AND l.\"voucherType\" = 'voucher'");
        if (status != null && !status.isEmpty()) {
            where.append(" AND l.status = ?");
            params.add(status);
        }
        if (shipmentId != null && !shipmentId.isEmpty()) {
            where.append(" AND l.\"shipmentId\" = ?");
            params.add(shipmentId);
        }
        return SqlRows.list(jdbc.sql("SELECT l.id, l.code, l.status, l.date, l.\"shipmentId\", s.code AS \"shipmentCode\","
                + " l.\"totalAmount\", l.\"capitalizedAmount\", l.\"expensedAmount\", l.\"taxAmount\", l.notes,"
                + " l.\"createdAt\", (SELECT count(*) FROM \"LandedCostReceipt\" r WHERE r.\"landedCostId\" = l.id)"
                + " AS \"receiptCount\", (SELECT count(*) FROM \"LandedCostCharge\" c WHERE c.\"landedCostId\" = l.id)"
                + " AS \"chargeCount\" FROM \"LandedCost\" l LEFT JOIN \"Shipment\" s ON s.id = l.\"shipmentId\""
                + where + " ORDER BY l.\"createdAt\" DESC LIMIT 500").params(params));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> lc = SqlRows.one(LandedCost.class, jdbc.sql("SELECT * FROM \"LandedCost\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"voucherType\" = 'voucher'").params(id, org()));
        if (lc == null) {
            throw ApiException.notFound("Landed cost voucher not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(lc);
        if (lc.get("shipmentId") != null) {
            out.put("shipment", SqlRows.one(jdbc.sql("SELECT id, code, reference, status FROM \"Shipment\" WHERE id = ?")
                    .param(lc.get("shipmentId"))));
        }
        out.put("receipts", SqlRows.list(jdbc.sql("SELECT g.id, g.\"receiptNumber\", g.status::text AS status,"
                + " g.\"receivedAt\", p.name AS \"partnerName\" FROM \"LandedCostReceipt\" r"
                + " JOIN \"GoodsReceiptNote\" g ON g.id = r.\"goodsReceiptId\" LEFT JOIN \"Partner\" p ON p.id = g.\"partnerId\""
                + " WHERE r.\"landedCostId\" = ? ORDER BY g.\"receiptNumber\"").param(id)));
        List<Map<String, Object>> charges = SqlRows.list(LandedCostCharge.class, jdbc.sql("SELECT * FROM"
                + " \"LandedCostCharge\" WHERE \"landedCostId\" = ? ORDER BY \"lineNumber\", id").param(id));
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT c.id, cc.code AS component, p.name AS supplier FROM"
                + " \"LandedCostCharge\" c LEFT JOIN \"CostComponent\" cc ON cc.id = c.\"costComponentId\""
                + " LEFT JOIN \"Partner\" p ON p.id = c.\"supplierId\" WHERE c.\"landedCostId\" = ?").param(id))) {
            names.put(r.get("id") + ":c", (String) r.get("component"));
            names.put(r.get("id") + ":s", (String) r.get("supplier"));
        }
        Map<String, Map<String, Object>> billsById = new HashMap<>();
        for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT DISTINCT d.id, d.\"documentNumber\","
                + " d.status::text AS status, d.\"totalAmount\", d.\"amountPaid\", d.\"amountResidual\","
                + " d.\"paymentStatus\"::text AS \"paymentStatus\", d.\"dueDate\" FROM \"LandedCostCharge\" c"
                + " JOIN \"Document\" d ON d.id = c.\"vendorBillId\" WHERE c.\"landedCostId\" = ?").param(id))) {
            billsById.put((String) b.get("id"), b);
        }
        Map<String, Map<String, Object>> suppliers = new LinkedHashMap<>();
        for (Map<String, Object> c : charges) {
            c.put("costComponentCode", names.get(c.get("id") + ":c"));
            c.put("supplierName", names.get(c.get("id") + ":s"));
            Map<String, Object> bill = billsById.get(c.get("vendorBillId"));
            c.put("vendorBill", bill);
            String key = c.get("supplierId") == null ? "" : (String) c.get("supplierId");
            Map<String, Object> sup = suppliers.computeIfAbsent(key, k -> Obj.of("supplierId", c.get("supplierId"),
                    "supplierName", c.get("supplierName"), "chargeCount", 0, "baseAmount", Money.ZERO, "taxAmount",
                    Money.ZERO, "invoices", new ArrayList<Map<String, Object>>()));
            BigDecimal rate = c.get("exchangeRate") == null ? BigDecimal.ONE : Money.dec(c.get("exchangeRate"));
            sup.put("chargeCount", (Integer) sup.get("chargeCount") + 1);
            sup.put("baseAmount", ((BigDecimal) sup.get("baseAmount")).add(Money.dec(c.get("baseAmount"))));
            sup.put("taxAmount", ((BigDecimal) sup.get("taxAmount")).add(dp6(Money.dec(c.get("taxAmount")).multiply(rate))));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> invoices = (List<Map<String, Object>>) sup.get("invoices");
            Object ref = c.get("supplierInvoiceRef");
            if (ref != null && invoices.stream().noneMatch(i -> ref.equals(i.get("supplierInvoiceRef")))) {
                Map<String, Object> inv = new LinkedHashMap<>();
                inv.put("supplierInvoiceRef", ref);
                inv.put("billMode", c.get("billMode"));
                inv.put("vendorBill", bill);
                invoices.add(inv);
            }
        }
        suppliers.values().forEach(sup -> {
            sup.put("baseAmount", JsNumbers.dec((BigDecimal) sup.get("baseAmount")));
            sup.put("taxAmount", JsNumbers.dec((BigDecimal) sup.get("taxAmount")));
        });
        out.put("charges", charges);
        out.put("suppliers", new ArrayList<>(suppliers.values()));
        List<Map<String, Object>> allocs = SqlRows.list(LandedCostAllocation.class, jdbc.sql("SELECT * FROM"
                + " \"LandedCostAllocation\" WHERE \"landedCostId\" = ? ORDER BY \"chargeId\", id").param(id));
        Map<String, String> productNames = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT DISTINCT p.id, p.name FROM \"LandedCostAllocation\" a"
                + " JOIN \"Product\" p ON p.id = a.\"productId\" WHERE a.\"landedCostId\" = ?").param(id))) {
            productNames.put((String) r.get("id"), (String) r.get("name"));
        }
        allocs.forEach(a -> a.put("productName", productNames.get(a.get("productId"))));
        out.put("allocations", allocs);
        return out;
    }

    /** Allocation as posting would compute it now, without writing anything. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String id) {
        Map<String, Object> lc = get(id);
        Computation c = compute(lc, false);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Alloc a : c.allocs()) {
            Unit u = a.unit();
            BigDecimal qty = u.group().qty()[0];
            BigDecimal unitCost = qty.signum() == 0 ? Money.ZERO : Money.div(u.group().value()[0], qty);
            rows.add(Obj.of("chargeId", a.chargeId(), "goodsReceiptId", u.grnId(), "receiptNumber", u.receiptNumber(),
                    "goodsReceiptLineId", u.grnLineId(), "productId", u.group().productId(), "productName",
                    u.group().product().get("name"), "locationId", u.group().locationId(), "batchId",
                    u.group().batchId(), "receivedQty", JsNumbers.dec(qty), "originalValue",
                    JsNumbers.dec(u.group().value()[0]), "originalUnitCost", JsNumbers.dec(dp6(unitCost)),
                    "basisMethod", a.method(), "basisValue", JsNumbers.dec(a.basis()), "sharePct",
                    JsNumbers.dec(a.share().multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP)),
                    "allocatedAmount", JsNumbers.dec(a.allocated()), "roundingAmount", JsNumbers.dec(a.rounding()),
                    "onHandQty", JsNumbers.dec(a.applied().onHand()), "capitalizedAmount",
                    JsNumbers.dec(a.applied().capitalized()), "expensedAmount",
                    JsNumbers.dec(a.allocated().subtract(a.applied().capitalized())), "landedUnitCost",
                    JsNumbers.dec(dp6(qty.signum() == 0 ? Money.ZERO
                            : Money.div(u.group().value()[0].add(a.allocated()), qty))),
                    "unitCostBefore", JsNumbers.dec(a.applied().unitBefore()), "unitCostAfter",
                    JsNumbers.dec(a.applied().unitAfter())));
        }
        return Obj.of("voucher", lc, "allocations", rows, "totalAmount", JsNumbers.dec(c.total()),
                "capitalizedAmount", JsNumbers.dec(c.capitalized()), "expensedAmount", JsNumbers.dec(c.expensed()),
                "taxAmount", JsNumbers.dec(c.tax()));
    }

    // ------------------------------------------------------------------ commands

    /** Create a draft voucher. */
    public Map<String, Object> create(VoucherInput in) {
        if (!settings.resolveBool("costing.landedCost.enabled", SettingContext.EMPTY)) {
            throw ApiException.badRequest("Landed costs are switched off in Costing & Valuation settings");
        }
        String id = UUID.randomUUID().toString();
        tx.executeWithoutResult(st -> {
            List<String> receipts = receipts(in);
            String code = seq.next("landed_cost", "LC-", 5);
            jdbc.sql("INSERT INTO \"LandedCost\" (id, \"organizationId\", code, \"goodsReceiptId\", \"voucherType\","
                            + " \"shipmentId\", \"costCenterId\", date, notes, \"createdBy\", \"createdAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?, ?, 'voucher', ?, ?, " + (in.date() != null ? "?" : NOW) + ", ?, ?, "
                            + NOW + ", " + NOW + ")")
                    .params(params(id, org(), code, receipts.getFirst(), blank(in.shipmentId()), blank(in.costCenterId()),
                            in.date() != null ? SqlRows.ts(ProcurementSql.date(in.date())) : SKIP, in.notes(), user()))
                    .update();
            writeReceipts(id, receipts);
            writeCharges(id, in.charges(), receipts);
        });
        return get(id);
    }

    /** Replace a draft's header, receipts and charges. */
    public Map<String, Object> update(String id, VoucherInput in) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> lc = lock(id);
            if (!"draft".equals(lc.get("status"))) {
                throw ApiException.badRequest("Only a draft landed cost voucher can be edited");
            }
            List<String> receipts = receipts(in);
            jdbc.sql("UPDATE \"LandedCost\" SET \"goodsReceiptId\" = ?, \"shipmentId\" = ?, \"costCenterId\" = ?,"
                            + " date = COALESCE(?, date), notes = ?, \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                            + " WHERE id = ?")
                    .params(receipts.getFirst(), blank(in.shipmentId()), blank(in.costCenterId()),
                            in.date() != null ? SqlRows.ts(ProcurementSql.date(in.date())) : null, in.notes(), user(), id)
                    .update();
            jdbc.sql("DELETE FROM \"LandedCostReceipt\" WHERE \"landedCostId\" = ?").param(id).update();
            jdbc.sql("DELETE FROM \"LandedCostCharge\" WHERE \"landedCostId\" = ?").param(id).update();
            writeReceipts(id, receipts);
            writeCharges(id, in.charges(), receipts);
        });
        return get(id);
    }

    public Map<String, Object> submit(String id) {
        return transition(id, List.of("draft"), "submitted", "\"submittedAt\" = " + NOW + ", \"submittedBy\" = ?",
                "Only a draft landed cost voucher can be submitted");
    }

    public Map<String, Object> approve(String id) {
        return transition(id, List.of("draft", "submitted"), "approved", "\"approvedAt\" = " + NOW
                + ", \"approvedBy\" = ?", "Only a draft or submitted landed cost voucher can be approved");
    }

    public Map<String, Object> cancel(String id) {
        return transition(id, List.of("draft", "submitted", "approved"), "cancelled", "\"updatedBy\" = ?",
                "Only an unposted landed cost voucher can be cancelled");
    }

    private Map<String, Object> transition(String id, List<String> from, String to, String set, String error) {
        int n = jdbc.sql("UPDATE \"LandedCost\" SET status = ?, " + set + ", \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ? AND \"voucherType\" = 'voucher' AND status = ANY(?::text[])")
                .params(to, user(), id, org(), from.toArray(String[]::new)).update();
        if (n == 0) {
            get(id);
            throw ApiException.badRequest(error);
        }
        audit.record(AuditInput.of("LandedCost", id, "update", null, Obj.of("kind", "landed_cost_voucher_" + to)));
        return get(id);
    }

    /** Allocate and post an approved voucher (one transaction). */
    public Map<String, Object> post(String id) {
        tx.executeWithoutResult(st -> {
            int claim = jdbc.sql("UPDATE \"LandedCost\" SET status = 'posted', \"postedAt\" = " + NOW + ","
                    + " \"postedById\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"voucherType\" = 'voucher' AND status = 'approved'").params(user(), id, org()).update();
            if (claim == 0) {
                Map<String, Object> lc = get(id);
                throw "posted".equals(lc.get("status"))
                        ? ApiException.conflict("Landed cost voucher is already posted")
                        : ApiException.badRequest("Only an approved landed cost voucher can be posted (status "
                                + lc.get("status") + ")");
            }
            Map<String, Object> lc = get(id);
            assertNoDuplicateInvoices(id, chargesOf(lc));
            Computation c = compute(lc, true);
            for (Alloc a : c.allocs()) {
                Unit u = a.unit();
                jdbc.sql("INSERT INTO \"LandedCostAllocation\" (id, \"organizationId\", \"landedCostId\","
                                + " \"goodsReceiptLineId\", \"productId\", \"locationId\", \"batchId\", \"receivedQty\","
                                + " \"onHandQty\", \"basisValue\", \"allocatedAmount\", \"capitalizedAmount\","
                                + " \"expensedAmount\", \"unitCostBefore\", \"unitCostAfter\", \"chargeId\", \"goodsReceiptId\","
                                + " \"basisMethod\", \"sharePct\", \"roundingAmount\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(UUID.randomUUID().toString(), org(), id, u.grnLineId() == null ? "" : u.grnLineId(),
                                u.group().productId(), u.group().locationId(), u.group().batchId(), u.group().qty()[0],
                                a.applied().onHand(), a.basis(), a.allocated(), a.applied().capitalized(),
                                a.allocated().subtract(a.applied().capitalized()), a.applied().unitBefore(),
                                a.applied().unitAfter(), a.chargeId(), u.grnId(), a.method(),
                                a.share().setScale(10, RoundingMode.HALF_UP), a.rounding())
                        .update();
            }
            Instant date = (Instant) lc.get("date");
            String description = "Landed cost " + lc.get("code") + (lc.get("shipment") instanceof Map<?, ?> s
                    ? " · shipment " + s.get("code") : "");
            Map<String, Object> je = stockPosting.postLandedVoucher(c.landed(), (String) lc.get("costCenterId"),
                    c.other(), date, (String) lc.get("code"), description);
            jdbc.sql("UPDATE \"LandedCost\" SET \"totalAmount\" = ?, \"capitalizedAmount\" = ?, \"expensedAmount\" = ?,"
                            + " \"taxAmount\" = ?, \"journalEntryId\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(c.total(), c.capitalized(), c.expensed(), c.tax(), je == null ? null : je.get("id"), id)
                    .update();
            raiseSupplierBills(lc, date);
            audit.recordInTx(AuditInput.of("LandedCost", id, "update", null, Obj.of("kind", "landed_cost_voucher_posted",
                    "code", lc.get("code"), "total", JsNumbers.dec(c.total()), "capitalized",
                    JsNumbers.dec(c.capitalized()), "expensed", JsNumbers.dec(c.expensed()), "tax",
                    JsNumbers.dec(c.tax()))));
        });
        return get(id);
    }

    /** Reverse a posted voucher: take the capitalisation back out of stock, reverse the entry, reclass the rest. */
    public Map<String, Object> reverse(String id) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> lc = lock(id);
            if (!"posted".equals(lc.get("status"))) {
                throw ApiException.badRequest("Only a posted landed cost voucher can be reversed");
            }
            reverseLocked(lc, "landed_cost_voucher");
        });
        return get(id);
    }

    /**
     * Reverse a posted legacy (per-receipt) landed cost through the same engine as a voucher (Cost Management CM-6,
     * ADR-S036): capitalisation taken back out of stock, entry reversed, the share sold since reclassed to COGS.
     */
    public void reverseReceipt(String id) {
        tx.executeWithoutResult(st -> {
            Map<String, Object> lc = SqlRows.one(jdbc.sql("SELECT id, code, status, \"journalEntryId\","
                    + " \"costCenterId\", \"goodsReceiptId\" FROM \"LandedCost\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND coalesce(\"voucherType\", 'receipt') = 'receipt' FOR UPDATE").params(id, org()));
            if (lc == null) {
                throw ApiException.notFound("Landed cost not found");
            }
            if (!"posted".equals(lc.get("status"))) {
                throw ApiException.badRequest("Only a posted landed cost can be reversed");
            }
            reverseLocked(lc, "landed_cost");
        });
    }

    private void reverseLocked(Map<String, Object> lc, String sourceType) {
            String id = (String) lc.get("id");
            List<Map<String, Object>> generated = SqlRows.list(jdbc.sql("SELECT DISTINCT d.id, d.\"documentNumber\","
                    + " d.status::text AS status, d.\"amountPaid\" FROM \"LandedCostCharge\" c JOIN \"Document\" d"
                    + " ON d.id = c.\"vendorBillId\" WHERE c.\"landedCostId\" = ? AND c.\"billMode\" = 'generate'")
                    .param(id));
            for (Map<String, Object> b : generated) {
                // Any non-cancelled bill with money against it (a fully paid bill is no longer 'posted').
                if (!"cancelled".equals(b.get("status")) && Money.dec(b.get("amountPaid")).signum() > 0) {
                    throw ApiException.badRequest("Supplier bill " + b.get("documentNumber") + " has payments; reverse"
                            + " them before reversing landed cost " + lc.get("code"));
                }
            }
            for (Map<String, Object> b : generated) {
                if ("posted".equals(b.get("status"))) {
                    bills.cancel((String) b.get("id"));
                }
            }
            Map<String, BigDecimal> unabsorbed = new LinkedHashMap<>();
            List<Object[]> valueRows = new ArrayList<>();
            for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT * FROM \"LandedCostAllocation\" WHERE"
                    + " \"landedCostId\" = ? ORDER BY id").param(id))) {
                Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT id, \"costingMethod\"::text AS"
                        + " \"costingMethod\", \"serialTracking\" FROM \"Product\" WHERE id = ?").param(a.get("productId")));
                BigDecimal cap = Money.dec(a.get("capitalizedAmount"));
                String grnId = a.get("goodsReceiptId") != null ? (String) a.get("goodsReceiptId")
                        : (String) lc.get("goodsReceiptId");
                BigDecimal removed = layers.remove(a, product, grnId,
                        new LandedCostLayers.Source(sourceType, (String) lc.get("code")));
                jdbc.sql("UPDATE \"LandedCostAllocation\" SET \"reversedAmount\" = ? WHERE id = ?")
                        .params(removed, a.get("id")).update();
                if (cap.subtract(removed).signum() > 0) {
                    unabsorbed.merge((String) a.get("productId"), cap.subtract(removed), BigDecimal::add);
                }
                valueRows.add(new Object[] {a, product, cap, removed});
            }
            Instant now = Instant.now();
            String reversalId = null;
            if (lc.get("journalEntryId") != null) {
                Map<String, Object> rev = posting.reverse((String) lc.get("journalEntryId"), now,
                        "Reversal of landed cost " + lc.get("code"));
                reversalId = (String) rev.get("id");
            }
            // CM-1: the stock ledger follows the GL — the reversal takes the capitalisation out (source "reversal"),
            // the reclass puts back the part sold since (it stays in cost of sales).
            for (Object[] v : valueRows) {
                @SuppressWarnings("unchecked")
                Map<String, Object> a = (Map<String, Object>) v[0];
                @SuppressWarnings("unchecked")
                Map<String, Object> product = (Map<String, Object>) v[1];
                BigDecimal cap = (BigDecimal) v[2];
                BigDecimal removed = (BigDecimal) v[3];
                String component = "STANDARD".equals(product.get("costingMethod")) ? "price_difference" : null;
                if (reversalId != null) {
                    lineage.recordValueOnly((String) a.get("productId"), (String) a.get("variantId"),
                            (String) a.get("locationId"), (String) a.get("batchId"), cap.negate(), "reversal",
                            (String) lc.get("journalEntryId"), component, "Landed cost " + lc.get("code") + " reversed");
                }
                lineage.recordValueOnly((String) a.get("productId"), (String) a.get("variantId"),
                        (String) a.get("locationId"), (String) a.get("batchId"), cap.subtract(removed),
                        "landed_cost_voucher_reversal", (String) lc.get("code"), component,
                        "Landed cost " + lc.get("code") + " reversed · share sold since back to COGS");
            }
            stockPosting.postLandedReclass(unabsorbed, (String) lc.get("costCenterId"), now, (String) lc.get("code"),
                    "Landed cost " + lc.get("code") + " reversed · share sold since back to COGS");
            jdbc.sql("UPDATE \"LandedCost\" SET status = 'reversed', \"reversedAt\" = " + NOW + ", \"reversedBy\" = ?,"
                    + " \"reversalJournalEntryId\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                    .params(user(), reversalId, id).update();
            audit.recordInTx(AuditInput.of("LandedCost", id, "update", null, Obj.of("kind",
                    sourceType + "_reversed", "code", lc.get("code"))));
    }

    // ------------------------------------------------------------------ computation

    @SuppressWarnings("unchecked")
    private Computation compute(Map<String, Object> lc, boolean write) {
        List<Map<String, Object>> receipts = (List<Map<String, Object>>) lc.get("receipts");
        List<Unit> units = new ArrayList<>();
        for (Map<String, Object> r : receipts) {
            Map<String, Object> grn = SqlRows.one(jdbc.sql("SELECT id, \"receiptNumber\", status::text AS status,"
                    + " \"reversedAt\" FROM \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL").params(r.get("id"), org()));
            if (grn == null || !"posted".equals(grn.get("status")) || grn.get("reversedAt") != null) {
                throw ApiException.badRequest("Goods receipt " + r.get("receiptNumber")
                        + " is no longer posted; landed cost cannot be applied");
            }
            units.addAll(units(grn));
        }
        List<Map<String, Object>> charges = chargesOf(lc);
        List<Alloc> allocs = new ArrayList<>();
        List<LandedLine> landed = new ArrayList<>();
        List<Line> other = new ArrayList<>();
        BigDecimal total = Money.ZERO;
        BigDecimal capitalized = Money.ZERO;
        BigDecimal expensed = Money.ZERO;
        BigDecimal tax = Money.ZERO;
        String voucherCc = (String) lc.get("costCenterId");
        String description = "Landed cost " + lc.get("code");
        // Same receipt movement re-costed by several charges: later charges see the earlier ones' effect.
        for (Map<String, Object> ch : charges) {
            BigDecimal rate = ch.get("exchangeRate") == null ? BigDecimal.ONE : Money.dec(ch.get("exchangeRate"));
            BigDecimal base = dp6(Money.dec(ch.get("amount")).multiply(rate));
            BigDecimal baseTax = dp6(Money.dec(ch.get("taxAmount")).multiply(rate));
            Object recoverable = ch.get("taxRecoverable");
            if (write && baseTax.signum() > 0 && recoverable == null) {
                throw ApiException.badRequest("Charge " + label(ch) + ": tax recoverability is unreviewed");
            }
            // ADR-S053: non-recoverable tax is part of the charge (capitalised or expensed with it), never input tax.
            boolean taxToCost = baseTax.signum() > 0 && Boolean.FALSE.equals(recoverable);
            BigDecimal cost = taxToCost ? base.add(baseTax) : base;
            String treatment = String.valueOf(ch.get("treatment"));
            String cc = ch.get("costCenterId") != null ? (String) ch.get("costCenterId") : voucherCc;
            String lineDesc = description + " · " + (ch.get("description") != null ? ch.get("description") : ch.get("kind"))
                    + (ch.get("supplierInvoiceRef") != null ? " · inv " + ch.get("supplierInvoiceRef") : "");
            total = total.add(cost);
            if (write) {
                String credit = ch.get("creditAccountId") != null ? (String) ch.get("creditAccountId") : clearingAccount();
                other.add(new Line(credit, null, JsNumbers.dec(base.add(baseTax)), (String) ch.get("supplierId"),
                        lineDesc, null, null, null));
                if (baseTax.signum() > 0 && !taxToCost) {
                    other.add(Line.debit(determination.mapped("tax_receivable"), JsNumbers.dec(baseTax),
                            lineDesc + " · input tax"));
                }
            }
            if (!taxToCost) {
                tax = tax.add(baseTax);
            }
            switch (treatment) {
                case "recoverable_tax" -> {
                    tax = tax.add(base);
                    if (write) {
                        other.add(Line.debit(determination.mapped("tax_receivable"), JsNumbers.dec(base), lineDesc));
                    }
                }
                case "expense" -> {
                    expensed = expensed.add(cost);
                    if (write) {
                        String account = ch.get("expenseAccountId") != null ? (String) ch.get("expenseAccountId")
                                : determination.mapped("default_expense");
                        other.add(new Line(account, JsNumbers.dec(cost), null, null, lineDesc, null, cc, null));
                    }
                }
                default -> {
                    ch.put("_voucherCode", lc.get("code"));
                    List<Alloc> parts = allocate(ch, cost, units, write);
                    for (Alloc a : parts) {
                        allocs.add(a);
                        BigDecimal wip = a.applied().wip();
                        BigDecimal exp = a.allocated().subtract(a.applied().capitalized()).subtract(wip);
                        capitalized = capitalized.add(a.applied().capitalized()).add(wip);
                        expensed = expensed.add(exp);
                        landed.add(new LandedLine(a.unit().group().productId(), a.applied().capitalized(), exp, wip));
                    }
                }
            }
        }
        return new Computation(allocs, landed, other, total, capitalized, expensed, tax);
    }

    /** Split one capitalised charge over its eligible units by its basis; last unit absorbs rounding. */
    @SuppressWarnings("unchecked")
    private List<Alloc> allocate(Map<String, Object> ch, BigDecimal base, List<Unit> all, boolean write) {
        String method = String.valueOf(ch.get("allocationMethod"));
        List<String> only = (List<String>) ch.get("appliesToLineIds");
        List<Unit> units = new ArrayList<>();
        for (Unit u : all) {
            if (only == null || only.isEmpty() || only.contains(u.grnLineId())) {
                units.add(u);
            }
        }
        if (units.isEmpty()) {
            throw ApiException.badRequest("Charge " + label(ch) + " applies to no goods of the voucher's receipts");
        }
        Map<String, BigDecimal> manual = new HashMap<>();
        if ("manual".equals(method)) {
            Object raw = ch.get("manualAllocations");
            if (raw instanceof tools.jackson.databind.JsonNode node) {
                for (Map.Entry<String, tools.jackson.databind.JsonNode> e : node.properties()) {
                    manual.put(e.getKey(), new BigDecimal(e.getValue().asString()));
                }
            } else if (raw instanceof Map<?, ?> mm) {
                mm.forEach((k, v) -> manual.put(String.valueOf(k), Money.dec(v)));
            }
        }
        Map<String, BigDecimal> lineQty = new HashMap<>();
        for (Unit u : units) {
            lineQty.merge(String.valueOf(u.grnLineId()), u.group().qty()[0], BigDecimal::add);
        }
        BigDecimal[] basis = new BigDecimal[units.size()];
        BigDecimal basisTotal = Money.ZERO;
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            BigDecimal qty = u.group().qty()[0];
            basis[i] = switch (method) {
                case "quantity" -> qty;
                case "equal" -> BigDecimal.ONE;
                case "weight" -> qty.multiply(u.weight());
                case "volume" -> qty.multiply(u.volume());
                case "manual" -> {
                    BigDecimal lineAmount = manual.getOrDefault(String.valueOf(u.grnLineId()), Money.ZERO);
                    BigDecimal lq = lineQty.get(String.valueOf(u.grnLineId()));
                    yield lq == null || lq.signum() == 0 ? Money.ZERO : Money.div(lineAmount.multiply(qty), lq);
                }
                default -> u.group().value()[0];
            };
            basisTotal = basisTotal.add(basis[i]);
        }
        if (basisTotal.signum() <= 0) {
            throw ApiException.badRequest("Charge " + label(ch) + " has no " + method + " to allocate by"
                    + ("weight".equals(method) || "volume".equals(method) ? " — set the products' " + method
                    : " — use another allocation method"));
        }
        List<Alloc> out = new ArrayList<>();
        BigDecimal so = Money.ZERO;
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            boolean last = i == units.size() - 1;
            BigDecimal exact = Money.div(base.multiply(basis[i]), basisTotal);
            BigDecimal allocated = last ? base.subtract(so) : dp6(exact);
            so = so.add(allocated);
            BigDecimal rounding = last ? dp6(allocated.subtract(exact)) : Money.ZERO;
            Applied applied = layers.apply(u.grnId(), u.group(), allocated, write,
                    new LandedCostLayers.Source("landed_cost_voucher", (String) ch.get("_voucherCode")));
            out.add(new Alloc((String) ch.get("id"), u, method, basis[i], Money.div(basis[i], basisTotal), allocated,
                    rounding, applied));
        }
        return out;
    }

    /** What one receipt put into stock, per (product, variant, location, lot), with its receipt line and drivers. */
    private List<Unit> units(Map<String, Object> grn) {
        List<Map<String, Object>> grnLines = SqlRows.list(jdbc.sql("SELECT * FROM \"GoodsReceiptLine\" WHERE"
                + " \"goodsReceiptId\" = ? ORDER BY \"lineNumber\", id").param(grn.get("id")));
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.\"productId\", l.\"variantId\","
                        + " l.\"locationId\", l.\"batchId\", l.\"quantityChange\", l.\"totalValue\", p.name AS \"p_name\","
                        + " p.\"costingMethod\"::text AS \"p_costingMethod\", p.\"batchTracking\" AS \"p_batchTracking\","
                        + " p.\"serialTracking\" AS \"p_serialTracking\", COALESCE(p.weight, 0) AS \"p_weight\","
                        + " COALESCE(p.volume, 0) AS \"p_volume\", b.\"batchNumber\" AS \"b_number\""
                        + " FROM \"InventoryLedger\" l JOIN \"Product\" p ON p.id = l.\"productId\""
                        + " LEFT JOIN \"InventoryBatch\" b ON b.id = l.\"batchId\""
                        + " WHERE l.\"organizationId\" = ? AND l.\"referenceType\" = 'goods_receipt'"
                        + " AND l.\"referenceId\" = ? AND l.\"quantityChange\" > 0 ORDER BY l.\"createdAt\" ASC")
                .params(org(), grn.get("id")));
        if (rows.isEmpty()) {
            throw ApiException.badRequest(grn.get("receiptNumber") + " put no goods into stock; nothing to allocate to");
        }
        Map<String, Object[]> groups = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String key = r.get("productId") + ":" + nz(r.get("variantId")) + ":" + r.get("locationId") + ":"
                    + nz(r.get("batchId"));
            Object[] g = groups.computeIfAbsent(key, k -> new Object[] {new Group(Obj.of("id", r.get("productId"), "name",
                    r.get("p_name"), "costingMethod", r.get("p_costingMethod"), "batchTracking", r.get("p_batchTracking"),
                    "serialTracking", r.get("p_serialTracking")), (String) r.get("productId"), (String) r.get("variantId"),
                    (String) r.get("locationId"), (String) r.get("batchId"), new BigDecimal[] {Money.ZERO},
                    new BigDecimal[] {Money.ZERO}), r});
            Group grp = (Group) g[0];
            grp.qty()[0] = grp.qty()[0].add(Money.dec(r.get("quantityChange")));
            grp.value()[0] = grp.value()[0].add(Money.dec(r.get("totalValue")));
        }
        List<Unit> out = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Object[] g : groups.values()) {
            Group grp = (Group) g[0];
            Map<String, Object> r = (Map<String, Object>) g[1];
            String lineId = null;
            for (Map<String, Object> l : grnLines) {
                if (grp.productId().equals(l.get("productId")) && r.get("b_number") != null
                        && r.get("b_number").equals(l.get("batchNumber"))) {
                    lineId = (String) l.get("id");
                    break;
                }
            }
            if (lineId == null) {
                for (Map<String, Object> l : grnLines) {
                    if (grp.productId().equals(l.get("productId")) && !used.contains((String) l.get("id"))) {
                        lineId = (String) l.get("id");
                        break;
                    }
                }
            }
            if (lineId == null) {
                for (Map<String, Object> l : grnLines) {
                    if (grp.productId().equals(l.get("productId"))) {
                        lineId = (String) l.get("id");
                        break;
                    }
                }
            }
            used.add(lineId);
            out.add(new Unit((String) grn.get("id"), (String) grn.get("receiptNumber"), lineId, grp,
                    Money.dec(r.get("p_weight")), Money.dec(r.get("p_volume"))));
        }
        return out;
    }

    // ------------------------------------------------------------------ writes / validation

    private static final Object SKIP = new Object();

    /** Positional parameters without the {@link #SKIP} placeholder (a NOW default replaced it). */
    private static List<Object> params(Object... values) {
        List<Object> out = new ArrayList<>();
        for (Object v : values) {
            if (v != SKIP) {
                out.add(v);
            }
        }
        return out;
    }

    private List<String> receipts(VoucherInput in) {
        List<String> ids = new ArrayList<>();
        if (in.goodsReceiptIds() != null) {
            in.goodsReceiptIds().forEach(i -> {
                if (!ids.contains(i)) {
                    ids.add(i);
                }
            });
        }
        if (in.shipmentId() != null && !in.shipmentId().isBlank()) {
            shipments.get(in.shipmentId());
            if (ids.isEmpty()) {
                ids.addAll(shipments.receiptIds(in.shipmentId()));
            }
        }
        if (ids.isEmpty()) {
            throw ApiException.badRequest("A landed cost voucher needs at least one goods receipt");
        }
        for (String grnId : ids) {
            Map<String, Object> grn = SqlRows.one(jdbc.sql("SELECT id, \"receiptNumber\", status::text AS status FROM"
                    + " \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(grnId, org()));
            if (grn == null) {
                throw ApiException.notFound("Goods receipt not found");
            }
            if (!"posted".equals(grn.get("status"))) {
                throw ApiException.badRequest("Landed cost can only be added to a posted goods receipt ("
                        + grn.get("receiptNumber") + " is " + grn.get("status") + ")");
            }
        }
        return ids;
    }

    private void writeReceipts(String id, List<String> receipts) {
        for (String grnId : receipts) {
            jdbc.sql("INSERT INTO \"LandedCostReceipt\" (id, \"organizationId\", \"landedCostId\", \"goodsReceiptId\")"
                    + " VALUES (?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id, grnId).update();
        }
    }

    private void writeCharges(String id, List<ChargeInput> charges, List<String> receipts) {
        if (charges == null || charges.isEmpty()) {
            throw ApiException.badRequest("A landed cost voucher needs at least one charge");
        }
        Set<String> lineIds = new LinkedHashSet<>(jdbc.sql("SELECT id FROM \"GoodsReceiptLine\" WHERE"
                + " \"goodsReceiptId\" = ANY(?::text[])").param(receipts.toArray(String[]::new)).query(String.class).list());
        BigDecimal total = Money.ZERO;
        BigDecimal taxTotal = Money.ZERO;
        int n = 1;
        List<Map<String, Object>> written = new ArrayList<>();
        for (ChargeInput c : charges) {
            Map<String, Object> comp = null;
            if (c.costComponentId() != null) {
                comp = components.find(c.costComponentId());
                if (comp == null) {
                    throw ApiException.badRequest("Cost component not found");
                }
            }
            String kind = c.kind() != null ? c.kind() : comp != null ? (String) comp.get("kind") : "other";
            String treatment = c.treatment() != null ? c.treatment() : comp != null ? (String) comp.get("treatment")
                    : "capitalize";
            String method = c.allocationMethod() != null ? c.allocationMethod() : comp != null
                    ? (String) comp.get("defaultAllocationMethod") : "value";
            String expense = c.expenseAccountId() != null ? c.expenseAccountId() : comp != null
                    ? (String) comp.get("expenseAccountId") : null;
            if (!CostComponentService.TREATMENTS.contains(treatment)) {
                throw ApiException.badRequest("treatment must be one of the following values: capitalize, expense,"
                        + " recoverable_tax");
            }
            if ("capitalize".equals(treatment) && comp != null) {
                policies.assertCapitalisable("cost_component", (String) comp.get("id"), "Cost component "
                        + comp.get("code"), false);
            }
            if (!CostComponentService.METHODS.contains(method)) {
                throw ApiException.badRequest("allocationMethod must be one of the following values: value, quantity,"
                        + " equal, weight, volume, manual");
            }
            if (c.amount() == null || c.amount().signum() <= 0) {
                throw ApiException.badRequest("Charge amounts must be greater than zero");
            }
            BigDecimal rate = c.exchangeRate() == null ? BigDecimal.ONE : c.exchangeRate();
            if (rate.signum() <= 0) {
                throw ApiException.badRequest("exchangeRate must be greater than zero");
            }
            if (c.supplierId() != null) {
                long s = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(c.supplierId(), org()).query(Long.class).single();
                if (s == 0) {
                    throw ApiException.badRequest("Supplier not found");
                }
            }
            String supplierId = c.supplierId();
            String supplierRef = blank(c.supplierInvoiceRef());
            String billMode = billMode(c);
            String vendorBillId = null;
            switch (billMode) {
                case "generate" -> {
                    if (supplierId == null || supplierRef == null) {
                        throw ApiException.badRequest("Charge " + label(c, kind) + ": a supplier bill needs a supplier"
                                + " and the supplier invoice number");
                    }
                    if (c.creditAccountId() != null) {
                        throw ApiException.badRequest("Charge " + label(c, kind) + ": a generated supplier bill credits"
                                + " the supplier's payable; leave the credit account empty");
                    }
                    if (c.dueDate() != null && c.invoiceDate() != null && c.dueDate().compareTo(c.invoiceDate()) < 0) {
                        throw ApiException.badRequest("Due date cannot be earlier than issue date");
                    }
                }
                case "link" -> {
                    Map<String, Object> bill = SqlRows.one(jdbc.sql("SELECT id, \"documentNumber\", \"partnerId\","
                            + " status::text AS status, reference FROM \"Document\" WHERE id = ? AND \"organizationId\" = ?"
                            + " AND \"documentType\" = 'vendor_bill'").params(c.vendorBillId(), org()));
                    if (bill == null) {
                        throw ApiException.badRequest("Vendor bill not found");
                    }
                    if ("cancelled".equals(bill.get("status"))) {
                        throw ApiException.badRequest("Vendor bill " + bill.get("documentNumber") + " is cancelled");
                    }
                    if (supplierId != null && !supplierId.equals(bill.get("partnerId"))) {
                        throw ApiException.badRequest("Vendor bill " + bill.get("documentNumber")
                                + " belongs to another supplier than charge " + label(c, kind));
                    }
                    supplierId = (String) bill.get("partnerId");
                    if (supplierRef == null) {
                        supplierRef = bill.get("reference") != null ? (String) bill.get("reference")
                                : (String) bill.get("documentNumber");
                    }
                    vendorBillId = c.vendorBillId();
                }
                default -> { }
            }
            for (String acc : new String[] {c.creditAccountId(), expense}) {
                if (acc != null) {
                    assertPostable(acc);
                }
            }
            if (c.appliesToLineIds() != null) {
                for (String l : c.appliesToLineIds()) {
                    if (!lineIds.contains(l)) {
                        throw ApiException.badRequest("appliesToLineIds must reference lines of the voucher's receipts");
                    }
                }
            }
            if ("manual".equals(method)) {
                if (c.manualAllocations() == null || c.manualAllocations().isEmpty()) {
                    throw ApiException.badRequest("A manual allocation needs manualAllocations per receipt line");
                }
                BigDecimal sum = Money.ZERO;
                for (Map.Entry<String, BigDecimal> e : c.manualAllocations().entrySet()) {
                    if (!lineIds.contains(e.getKey())) {
                        throw ApiException.badRequest("manualAllocations must reference lines of the voucher's receipts");
                    }
                    sum = sum.add(e.getValue());
                }
                if (sum.compareTo(c.amount()) != 0) {
                    throw ApiException.badRequest("Manual allocation of " + label(c, kind) + " totals "
                            + JsNumbers.dec(sum) + ", not the charge amount " + JsNumbers.dec(c.amount()));
                }
            }
            BigDecimal base = dp6(c.amount().multiply(rate));
            BigDecimal tax = c.taxAmount() == null ? Money.ZERO : c.taxAmount();
            if (tax.signum() < 0) {
                throw ApiException.badRequest("taxAmount must not be negative");
            }
            if ("recoverable_tax".equals(treatment) && tax.signum() > 0) {
                throw ApiException.badRequest("Charge " + label(c, kind) + ": a recoverable tax charge cannot carry tax"
                        + " of its own");
            }
            String taxId = blank(c.taxId()) != null ? c.taxId() : comp != null ? (String) comp.get("taxId") : null;
            Boolean recoverable = taxRecoverable(id, c, kind, tax, taxId);
            BigDecimal baseTax = dp6(tax.multiply(rate));
            if (Boolean.TRUE.equals(recoverable)) {
                total = total.add(base);
                taxTotal = taxTotal.add(baseTax);
            } else {
                total = total.add(base).add(baseTax);
            }
            String chargeId = UUID.randomUUID().toString();
            Map<String, BigDecimal> manualBase = null;
            if ("manual".equals(method)) {
                manualBase = new LinkedHashMap<>();
                for (Map.Entry<String, BigDecimal> e : c.manualAllocations().entrySet()) {
                    manualBase.put(e.getKey(), dp6(e.getValue().multiply(rate)));
                }
            }
            jdbc.sql("INSERT INTO \"LandedCostCharge\" (id, \"organizationId\", \"landedCostId\", kind, description, amount,"
                            + " \"lineNumber\", \"costComponentId\", \"supplierId\", \"supplierInvoiceRef\", \"invoiceDate\","
                            + " \"currencyCode\", \"exchangeRate\", \"baseAmount\", \"taxAmount\", treatment, \"allocationMethod\","
                            + " \"expenseAccountId\", \"creditAccountId\", \"costCenterId\", \"appliesToLineIds\","
                            + " \"manualAllocations\", \"billMode\", \"vendorBillId\", \"dueDate\", \"taxId\", \"taxRecoverable\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?::jsonb, ?, ?,"
                            + " ?::date, ?, ?)")
                    .params(chargeId, org(), id, kind, c.description(), c.amount(), n++, c.costComponentId(),
                            supplierId, supplierRef, blank(c.invoiceDate()), blank(c.currencyCode()), rate,
                            base, tax, treatment, method, expense, c.creditAccountId(), blank(c.costCenterId()),
                            c.appliesToLineIds() == null || c.appliesToLineIds().isEmpty() ? null
                                    : c.appliesToLineIds().toArray(String[]::new),
                            manualBase == null ? null : EntityJson.toJson(manualBase), billMode, vendorBillId,
                            blank(c.dueDate()), taxId, recoverable)
                    .update();
            written.add(Obj.of("id", chargeId, "supplierId", supplierId, "supplierInvoiceRef", supplierRef,
                    "billMode", billMode, "currencyCode", blank(c.currencyCode()), "exchangeRate", rate,
                    "vendorBillId", vendorBillId));
        }
        assertNoDuplicateInvoices(id, written);
        assertBillGroups(written);
        jdbc.sql("UPDATE \"LandedCost\" SET \"totalAmount\" = ?, \"taxAmount\" = ? WHERE id = ?")
                .params(total, taxTotal, id).update();
    }

    /** A supplier invoice can be landed once (across live vouchers and within one). */
    private void assertNoDuplicateInvoices(String voucherId, List<Map<String, Object>> charges) {
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> c : charges) {
            Object supplier = c.get("supplierId");
            Object ref = c.get("supplierInvoiceRef");
            if (supplier == null || ref == null || c.get("vendorBillId") != null) {
                continue;
            }
            if (!seen.add(supplier + "|" + ref)) {
                continue;
            }
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("lc-inv:" + org() + ":" + supplier + ":"
                    + ref).query().listOfRows();
            String other = jdbc.sql("SELECT l.code FROM \"LandedCostCharge\" c JOIN \"LandedCost\" l ON l.id ="
                            + " c.\"landedCostId\" WHERE c.\"organizationId\" = ? AND c.\"supplierId\" = ?"
                            + " AND c.\"supplierInvoiceRef\" = ? AND l.id <> ? AND l.status NOT IN ('cancelled', 'reversed')"
                            + " LIMIT 1")
                    .params(org(), supplier, ref, voucherId).query(String.class).optional().orElse(null);
            if (other != null) {
                throw ApiException.conflict("Supplier invoice " + ref + " is already on landed cost " + other);
            }
        }
    }

    /**
     * One posted vendor bill per (supplier, invoice no.) of the {@code generate} charges: each line debits the
     * landed-cost clearing account with the charge plus its tax (what the voucher credited there), the bill credits the
     * supplier's own payable. Clearing nets to zero per invoice; every supplier keeps its own document and balance.
     */
    private void raiseSupplierBills(Map<String, Object> lc, Instant voucherDate) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> ch : chargesOf(lc)) {
            if ("generate".equals(ch.get("billMode")) && ch.get("vendorBillId") == null) {
                groups.computeIfAbsent(ch.get("supplierId") + "|" + ch.get("supplierInvoiceRef"), k -> new ArrayList<>())
                        .add(ch);
            }
        }
        String clearing = clearingAccount();
        String fallbackDate = voucherDate.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        for (List<Map<String, Object>> group : groups.values()) {
            Map<String, Object> first = group.getFirst();
            List<DocumentBuilderService.LineInput> billLines = new ArrayList<>();
            String due = null;
            for (Map<String, Object> ch : group) {
                BigDecimal gross = Money.dec(ch.get("amount")).add(Money.dec(ch.get("taxAmount")));
                String desc = (ch.get("description") != null ? ch.get("description") : ch.get("kind")) + " · landed cost "
                        + lc.get("code");
                billLines.add(new DocumentBuilderService.LineInput(null, null, null, null, clearing, (String) desc,
                        BigDecimal.ONE, gross, null, null, null, null, null, null, null, false));
                if (ch.get("dueDate") != null) {
                    due = ch.get("dueDate").toString();
                }
            }
            String issue = first.get("invoiceDate") != null ? first.get("invoiceDate").toString() : fallbackDate;
            Map<String, Object> bill = bills.createPostedForLandedCost((String) first.get("supplierId"), issue,
                    due != null ? due : issue, (String) first.get("currencyCode"), first.get("exchangeRate") == null
                            ? BigDecimal.ONE : Money.dec(first.get("exchangeRate")),
                    (String) first.get("supplierInvoiceRef"), (String) lc.get("code"),
                    "Landed cost " + lc.get("code") + (lc.get("shipment") instanceof Map<?, ?> s
                            ? " · shipment " + s.get("code") : ""), billLines);
            for (Map<String, Object> ch : group) {
                jdbc.sql("UPDATE \"LandedCostCharge\" SET \"vendorBillId\" = ? WHERE id = ?")
                        .params(bill.get("id"), ch.get("id")).update();
            }
        }
    }

    /**
     * Bill mode of a charge: explicit, else {@code link} when it references a bill, {@code generate} when it names a
     * supplier and invoice number and keeps the default (clearing) credit account, otherwise {@code none}.
     */
    private static String billMode(ChargeInput c) {
        if (c.billMode() != null) {
            if ("link".equals(c.billMode()) && blank(c.vendorBillId()) == null) {
                throw ApiException.badRequest("vendorBillId is required when billMode is link");
            }
            return c.billMode();
        }
        if (blank(c.vendorBillId()) != null) {
            return "link";
        }
        return c.supplierId() != null && blank(c.supplierInvoiceRef()) != null && c.creditAccountId() == null
                ? "generate" : "none";
    }

    /**
     * Tax recoverability of a charge (ADR-S053, LC-0): irrelevant without tax; otherwise the charge's tax, else the
     * organisation default. A stated value that contradicts a derived one needs {@code landed_cost:override_tax}; with
     * nothing to derive from, the charge must state it.
     */
    private Boolean taxRecoverable(String voucherId, ChargeInput c, String kind, BigDecimal tax, String taxId) {
        if (tax.signum() == 0) {
            return Boolean.TRUE;
        }
        Boolean derived = null;
        if (taxId != null) {
            List<String> found = jdbc.sql("SELECT coalesce(recoverability, '') FROM \"Tax\" WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(taxId, org()).query(String.class).list();
            if (found.isEmpty()) {
                throw ApiException.badRequest("Tax not found");
            }
            derived = "full".equals(found.getFirst()) ? Boolean.TRUE : "none".equals(found.getFirst()) ? Boolean.FALSE
                    : null;
        }
        if (derived == null) {
            String def = settings.resolveString("costing.landedCost.taxRecoverableDefault", SettingContext.EMPTY);
            derived = "recoverable".equals(def) ? Boolean.TRUE : "non_recoverable".equals(def) ? Boolean.FALSE : null;
        }
        if (c.taxRecoverable() == null) {
            if (derived == null) {
                throw ApiException.badRequest("Charge " + label(c, kind) + ": state whether its tax is recoverable"
                        + " (no tax or default in Costing & Valuation settings says so)");
            }
            return derived;
        }
        if (derived != null && !derived.equals(c.taxRecoverable())) {
            if (!TenantContext.permissions().contains(Permissions.LANDED_COST__OVERRIDE_TAX)) {
                throw ApiException.forbidden("Charge " + label(c, kind) + ": overriding the tax recoverability needs"
                        + " landed_cost:override_tax");
            }
            audit.recordInTx(AuditInput.of("LandedCost", voucherId, "update", null, Obj.of("kind",
                    "landed_cost_tax_override", "charge", label(c, kind), "derived", derived, "stated",
                    c.taxRecoverable())));
        }
        return c.taxRecoverable();
    }

    /** Charges that become one supplier bill must share its currency and rate. */
    private static void assertBillGroups(List<Map<String, Object>> charges) {
        Map<String, Map<String, Object>> first = new HashMap<>();
        for (Map<String, Object> c : charges) {
            if (!"generate".equals(c.get("billMode"))) {
                continue;
            }
            Map<String, Object> f = first.putIfAbsent(c.get("supplierId") + "|" + c.get("supplierInvoiceRef"), c);
            if (f != null && (!Objects.equals(f.get("currencyCode"), c.get("currencyCode"))
                    || Money.dec(f.get("exchangeRate")).compareTo(Money.dec(c.get("exchangeRate"))) != 0)) {
                throw ApiException.badRequest("Charges on supplier invoice " + c.get("supplierInvoiceRef")
                        + " must share one currency and exchange rate");
            }
        }
    }

    private void assertPostable(String accountId) {
        Map<String, Object> acc = SqlRows.one(jdbc.sql("SELECT id, \"isActive\", \"isPostable\", \"deprecatedAt\", code,"
                + " name FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(accountId, org()));
        if (acc == null) {
            throw ApiException.badRequest("Account not found");
        }
        if (!Boolean.TRUE.equals(acc.get("isActive")) || !Boolean.TRUE.equals(acc.get("isPostable"))
                || acc.get("deprecatedAt") != null) {
            throw ApiException.badRequest("Account " + acc.get("code") + " " + acc.get("name") + " cannot receive postings");
        }
    }

    /** Landed-cost clearing (2155): the freight / customs vendor bill debits it, the voucher credits it. */
    String clearingAccount() {
        return accounts.ensureByCode("2155", "Landed Cost Clearing", "current_liability", "landed_cost_clearing");
    }

    private List<Map<String, Object>> chargesOf(Map<String, Object> lc) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> charges = (List<Map<String, Object>>) lc.get("charges");
        return charges;
    }

    private Map<String, Object> lock(String id) {
        Map<String, Object> lc = SqlRows.one(jdbc.sql("SELECT id, code, status, \"journalEntryId\", \"costCenterId\" FROM"
                + " \"LandedCost\" WHERE id = ? AND \"organizationId\" = ? AND \"voucherType\" = 'voucher' FOR UPDATE")
                .params(id, org()));
        if (lc == null) {
            throw ApiException.notFound("Landed cost voucher not found");
        }
        return lc;
    }

    private static String label(Map<String, Object> ch) {
        return ch.get("lineNumber") + " (" + ch.get("kind") + ")";
    }

    private static String label(ChargeInput c, String kind) {
        return c.description() != null ? c.description() : kind;
    }

    private static String nz(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
