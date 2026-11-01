package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockPostingService.LandedLine;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.procurement.domain.LandedCost;
import com.inventoryerp.procurement.domain.LandedCostAllocation;
import com.inventoryerp.procurement.domain.LandedCostCharge;
import com.inventoryerp.procurement.application.LandedCostLayers.Applied;
import com.inventoryerp.procurement.application.LandedCostLayers.Group;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Landed cost (port of {@code landed-cost.service.ts}, INV-P2-02): freight, duty, insurance and handling
 * capitalised onto the goods of one posted goods receipt. Posting allocates the charges over the receipt's stock
 * movements (value | quantity | equal); per movement the share still on hand raises the cost layer (lot, serials or
 * the AVCO average) and is debited to Stock Valuation, the consumed share is expensed to COGS; STANDARD items
 * expense everything. Credit: the account the user chose.
 */
@Service
public class LandedCostService {

    /** {@code CreateLandedCostDto}. */
    public record CreateInput(String goodsReceiptId, String creditAccountId, String allocationMethod, String date,
            String notes, List<ChargeInput> charges) {}

    /** {@code LandedCostChargeDto}. */
    public record ChargeInput(String kind, String description, BigDecimal amount) {}

    private static final int SIX = 6;

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final StockPostingService stockPosting;
    private final TransactionTemplate tx;
    private final LandedCostLayers layers;

    public LandedCostService(JdbcClient jdbc, SequenceService seq, AuditService audit, StockPostingService stockPosting,
            PlatformTransactionManager txm, LandedCostLayers layers) {
        this.layers = layers;
        this.jdbc = jdbc;
        this.seq = seq;
        this.audit = audit;
        this.stockPosting = stockPosting;
        this.tx = new TransactionTemplate(txm);
    }

    /** decimal.js {@code a.times(b)} (20 significant digits). */
    private static BigDecimal mul(BigDecimal a, BigDecimal b) {
        return a.multiply(b, Money.DECIMAL_JS);
    }

    private static BigDecimal dp6(BigDecimal v) {
        return v.setScale(SIX, RoundingMode.HALF_UP);
    }

    /** Latest 200 landed costs (optionally of one receipt) with charges and allocations. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String goodsReceiptId) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = " \"organizationId\" = ?";
        if (goodsReceiptId != null && !goodsReceiptId.isEmpty()) {
            where += " AND (\"goodsReceiptId\" = ? OR id IN (SELECT \"landedCostId\" FROM \"LandedCostReceipt\""
                    + " WHERE \"organizationId\" = ? AND \"goodsReceiptId\" = ?))";
            params.add(goodsReceiptId);
            params.add(org());
            params.add(goodsReceiptId);
        }
        List<Map<String, Object>> rows = SqlRows.list(LandedCost.class, jdbc.sql("SELECT * FROM \"LandedCost\" WHERE"
                + where + " ORDER BY \"createdAt\" DESC LIMIT 200").params(params));
        rows.forEach(this::withChildren);
        return rows;
    }

    private Map<String, Object> withChildren(Map<String, Object> lc) {
        lc.put("charges", SqlRows.list(LandedCostCharge.class, jdbc.sql("SELECT * FROM \"LandedCostCharge\" WHERE"
                + " \"landedCostId\" = ?").param(lc.get("id"))));
        lc.put("allocations", SqlRows.list(LandedCostAllocation.class, jdbc.sql("SELECT * FROM \"LandedCostAllocation\""
                + " WHERE \"landedCostId\" = ?").param(lc.get("id"))));
        return lc;
    }

    /** One landed cost with charges and allocations. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> lc = SqlRows.one(LandedCost.class, jdbc.sql("SELECT * FROM \"LandedCost\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (lc == null) {
            throw ApiException.notFound("Landed cost not found");
        }
        return withChildren(lc);
    }

    /** Draft landed cost on a posted receipt; charges must total more than zero. */
    public Map<String, Object> create(CreateInput dto) {
        String orgId = org();
        Map<String, Object> grn = SqlRows.one(jdbc.sql("SELECT id, \"receiptNumber\", status::text AS status FROM"
                + " \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(dto.goodsReceiptId(), orgId));
        if (grn == null) {
            throw ApiException.notFound("Goods receipt not found");
        }
        if (!"posted".equals(grn.get("status"))) {
            throw ApiException.badRequest("Landed cost can only be added to a posted goods receipt ("
                    + grn.get("receiptNumber") + " is " + grn.get("status") + ")");
        }
        assertCreditAccount(dto.creditAccountId());
        BigDecimal total = Money.ZERO;
        for (ChargeInput c : dto.charges()) {
            total = total.add(c.amount());
        }
        if (total.signum() <= 0) {
            throw ApiException.badRequest("Landed cost charges must total more than zero");
        }
        String code = seq.next("landed_cost", "LC-", 5);
        BigDecimal sum = total;
        String id = UUID.randomUUID().toString();
        tx.executeWithoutResult(st -> {
            Instant date = ProcurementSql.date(dto.date());
            List<Object> params = new ArrayList<>(List.of(id, orgId, code, grn.get("id"),
                    dto.allocationMethod() != null ? dto.allocationMethod() : "value", dto.creditAccountId()));
            if (date != null) {
                params.add(SqlRows.ts(date));
            }
            params.add(dto.notes());
            params.add(sum);
            params.add(user());
            jdbc.sql("INSERT INTO \"LandedCost\" (id, \"organizationId\", code, \"goodsReceiptId\", \"allocationMethod\","
                    + " \"creditAccountId\", date, notes, \"totalAmount\", \"createdBy\", \"createdAt\", \"updatedAt\")"
                    + " VALUES (?, ?, ?, ?, ?, ?, " + (date != null ? "?" : NOW) + ", ?, ?, ?, " + NOW + ", " + NOW + ")")
                    .params(params).update();
            for (ChargeInput c : dto.charges()) {
                jdbc.sql("INSERT INTO \"LandedCostCharge\" (id, \"organizationId\", \"landedCostId\", kind, description,"
                                + " amount) VALUES (?, ?, ?, ?, ?, ?)")
                        .params(UUID.randomUUID().toString(), orgId, id, c.kind(), c.description(), c.amount()).update();
            }
        });
        return get(id);
    }

    /** Cancel a draft. */
    public Map<String, Object> cancel(String id) {
        int upd = jdbc.sql("UPDATE \"LandedCost\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ? AND status = 'draft'").params(user(), id, org()).update();
        if (upd == 0) {
            throw ApiException.badRequest("Only a draft landed cost can be cancelled");
        }
        return get(id);
    }

    private void assertCreditAccount(String accountId) {
        Map<String, Object> acc = SqlRows.one(jdbc.sql("SELECT id, \"isActive\", \"isPostable\", \"deprecatedAt\", code,"
                + " name FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(accountId, org()));
        if (acc == null) {
            throw ApiException.badRequest("Credit account not found");
        }
        if (!Boolean.TRUE.equals(acc.get("isActive")) || !Boolean.TRUE.equals(acc.get("isPostable"))
                || acc.get("deprecatedAt") != null) {
            throw ApiException.badRequest("Account " + acc.get("code") + " " + acc.get("name")
                    + " cannot receive postings");
        }
    }

    /** Allocate and post (one transaction): layer re-costing, allocation rows, GL. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String id) {
        String orgId = org();
        String type = jdbc.sql("SELECT \"voucherType\" FROM \"LandedCost\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, orgId).query(String.class).optional().orElse("receipt");
        if ("voucher".equals(type)) {
            throw ApiException.badRequest("This is a landed cost voucher; post it through /procurement/landed-cost-vouchers");
        }
        return tx.execute(st -> {
            int claim = jdbc.sql("UPDATE \"LandedCost\" SET status = 'posted', \"postedAt\" = " + NOW + ", \"postedById\""
                    + " = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status = 'draft'")
                    .params(user(), id, orgId).update();
            if (claim == 0) {
                throw ApiException.conflict("Landed cost is not a draft (already posted or cancelled)");
            }
            Map<String, Object> lc = SqlRows.one(LandedCost.class, jdbc.sql("SELECT * FROM \"LandedCost\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(id, orgId));
            assertCreditAccount((String) lc.get("creditAccountId"));

            Map<String, Object> grn = SqlRows.one(jdbc.sql("SELECT id, \"receiptNumber\", status::text AS status,"
                    + " \"reversedAt\" FROM \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL").params(lc.get("goodsReceiptId"), orgId));
            if (grn == null || !"posted".equals(grn.get("status")) || grn.get("reversedAt") != null) {
                throw ApiException.badRequest("The goods receipt is no longer posted; landed cost cannot be applied");
            }
            List<Map<String, Object>> grnLines = SqlRows.list(jdbc.sql("SELECT * FROM \"GoodsReceiptLine\" WHERE"
                    + " \"goodsReceiptId\" = ?").param(grn.get("id")));
            Instant lcDate = (Instant) lc.get("date");
            assertPeriodOpen(lcDate);

            // What the receipt actually put into stock, per (product, variant, location, lot).
            List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.\"productId\", l.\"variantId\","
                            + " l.\"locationId\", l.\"batchId\", l.\"quantityChange\", l.\"totalValue\", p.id AS \"p_id\","
                            + " p.name AS \"p_name\", p.\"costingMethod\"::text AS \"p_costingMethod\","
                            + " p.\"batchTracking\" AS \"p_batchTracking\", p.\"serialTracking\" AS \"p_serialTracking\""
                            + " FROM \"InventoryLedger\" l JOIN \"Product\" p ON p.id = l.\"productId\""
                            + " WHERE l.\"organizationId\" = ? AND l.\"referenceType\" = 'goods_receipt'"
                            + " AND l.\"referenceId\" = ? AND l.\"quantityChange\" > 0 ORDER BY l.\"createdAt\" ASC")
                    .params(orgId, grn.get("id")));
            if (rows.isEmpty()) {
                throw ApiException.badRequest(grn.get("receiptNumber") + " put no goods into stock; nothing to allocate to");
            }
            Map<String, Group> groups = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                String key = r.get("productId") + ":" + nz(r.get("variantId")) + ":" + r.get("locationId") + ":"
                        + nz(r.get("batchId"));
                Group g = groups.computeIfAbsent(key, k -> new Group(Obj.of("id", r.get("p_id"), "name", r.get("p_name"),
                        "costingMethod", r.get("p_costingMethod"), "batchTracking", r.get("p_batchTracking"),
                        "serialTracking", r.get("p_serialTracking")), (String) r.get("productId"),
                        (String) r.get("variantId"), (String) r.get("locationId"), (String) r.get("batchId"),
                        new BigDecimal[] {Money.ZERO}, new BigDecimal[] {Money.ZERO}));
                g.qty()[0] = g.qty()[0].add(Money.dec(r.get("quantityChange")));
                g.value()[0] = g.value()[0].add(Money.dec(r.get("totalValue")));
            }
            List<Group> list = new ArrayList<>(groups.values());
            String method = (String) lc.get("allocationMethod");
            BigDecimal basisTotal = Money.ZERO;
            for (Group g : list) {
                basisTotal = basisTotal.add(basisOf(method, g));
            }
            if (basisTotal.signum() <= 0) {
                throw ApiException.badRequest(grn.get("receiptNumber") + " has no " + method
                        + " to allocate by (zero-cost receipt?) — use quantity or equal allocation");
            }

            BigDecimal total = Money.dec(lc.get("totalAmount"));
            BigDecimal allocatedSoFar = Money.ZERO;
            BigDecimal capitalizedTotal = Money.ZERO;
            BigDecimal expensedTotal = Money.ZERO;
            List<LandedLine> glLines = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Group g = list.get(i);
                // The last group absorbs rounding so Σ = total exactly.
                BigDecimal allocated = i == list.size() - 1 ? total.subtract(allocatedSoFar)
                        : dp6(Money.div(mul(total, basisOf(method, g)), basisTotal));
                allocatedSoFar = allocatedSoFar.add(allocated);

                Applied applied = layers.apply((String) grn.get("id"), g, allocated, true,
                        new LandedCostLayers.Source("landed_cost", (String) lc.get("code")));
                BigDecimal expensed = allocated.subtract(applied.capitalized()).subtract(applied.wip());
                capitalizedTotal = capitalizedTotal.add(applied.capitalized()).add(applied.wip());
                expensedTotal = expensedTotal.add(expensed);
                glLines.add(new LandedLine(g.productId(), applied.capitalized(), expensed, applied.wip()));

                String batchNumber = g.batchId() == null ? null : jdbc.sql("SELECT \"batchNumber\" FROM \"InventoryBatch\""
                        + " WHERE id = ? AND \"organizationId\" = ?").params(g.batchId(), orgId).query(String.class)
                        .optional().orElse(null);
                Map<String, Object> grnLine = null;
                for (Map<String, Object> l : grnLines) {
                    if (g.productId().equals(l.get("productId")) && batchNumber != null
                            && batchNumber.equals(l.get("batchNumber"))) {
                        grnLine = l;
                        break;
                    }
                }
                if (grnLine == null) {
                    for (Map<String, Object> l : grnLines) {
                        if (g.productId().equals(l.get("productId"))) {
                            grnLine = l;
                            break;
                        }
                    }
                }
                jdbc.sql("INSERT INTO \"LandedCostAllocation\" (id, \"organizationId\", \"landedCostId\","
                                + " \"goodsReceiptLineId\", \"productId\", \"locationId\", \"batchId\", \"receivedQty\","
                                + " \"onHandQty\", \"basisValue\", \"allocatedAmount\", \"capitalizedAmount\","
                                + " \"expensedAmount\", \"unitCostBefore\", \"unitCostAfter\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                                + " ?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(UUID.randomUUID().toString(), orgId, id, grnLine != null ? grnLine.get("id") : "",
                                g.productId(), g.locationId(), g.batchId(), g.qty()[0], applied.onHand(),
                                basisOf(method, g), allocated, applied.capitalized(), expensed, applied.unitBefore(),
                                applied.unitAfter())
                        .update();
            }

            String description = "Landed cost " + lc.get("code") + " · GRN " + grn.get("receiptNumber");
            Map<String, Object> je = stockPosting.postLandedCost(glLines, (String) lc.get("creditAccountId"), lcDate,
                    "landed_cost", (String) lc.get("code"), description);
            jdbc.sql("UPDATE \"LandedCost\" SET \"capitalizedAmount\" = ?, \"expensedAmount\" = ?, \"journalEntryId\" = ?,"
                            + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                    .params(capitalizedTotal, expensedTotal, je != null ? je.get("id") : null, id, orgId).update();
            audit.recordInTx(AuditInput.of("LandedCost", id, "update", null, Obj.of("kind", "landed_cost_posted",
                    "code", lc.get("code"), "goodsReceiptId", grn.get("id"), "total", JsNumbers.dec(total),
                    "capitalized", JsNumbers.dec(capitalizedTotal), "expensed", JsNumbers.dec(expensedTotal))));
            Map<String, Object> out = SqlRows.one(LandedCost.class, jdbc.sql("SELECT * FROM \"LandedCost\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(id, orgId));
            return withChildren(out);
        });
    }

    private static String nz(Object v) {
        return v == null ? "" : v.toString();
    }

    private static BigDecimal basisOf(String method, Group g) {
        return "quantity".equals(method) ? g.qty()[0] : "equal".equals(method) ? BigDecimal.ONE : g.value()[0];
    }

    private void assertPeriodOpen(Instant date) {
        Instant lock = jdbc.sql("SELECT \"booksLockDate\" FROM \"Organization\" WHERE id = ?").param(org())
                .query((rs, n) -> {
                    var t = rs.getTimestamp(1);
                    return t == null ? null : t.toLocalDateTime().toInstant(ZoneOffset.UTC);
                }).optional().orElse(null);
        if (lock != null && date.toEpochMilli() <= lock.toEpochMilli()) {
            throw ApiException.badRequest("Books are locked through "
                    + lock.truncatedTo(ChronoUnit.MILLIS).toString().substring(0, 10));
        }
    }
}
