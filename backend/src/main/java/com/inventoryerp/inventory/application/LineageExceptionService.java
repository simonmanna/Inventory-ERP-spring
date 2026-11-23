package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.LineageFollower.Followed;
import com.inventoryerp.inventory.application.LineageFollower.Mode;
import com.inventoryerp.inventory.application.LineageFollower.Placement;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Landed cost LC-1 (ADR-S053): the lineage exception workspace. A share that could not follow its goods (transfer with
 * no or only partial lineage, a cycle, too many hops) was parked in the 1396 adjunct. An authorised user
 * ({@code landed_cost:resolve_lineage}) either names the transfer_in row that received the goods — the link is recorded
 * and the parked amount is released through the normal lineage distribution from there — or writes it off to an
 * account with a reason. Every action is audited and recorded on the exception.
 */
@Service
public class LineageExceptionService {

    private final JdbcClient jdbc;
    private final LineageFollower follower;
    private final CostLineageService lineage;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AccountResolverService accounts;
    private final StockPostingService stockPosting;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final SegregationOfDutiesService sod;
    private final TransactionTemplate newTx;

    /** Adjustment types of a landed-cost voucher (their own disposition; every other source capitalises as adjunct). */
    private static final Set<String> VOUCHER_TYPES = Set.of("landed_cost", "std_price_difference");

    public LineageExceptionService(JdbcClient jdbc, LineageFollower follower, CostLineageService lineage,
            PostingService posting, AccountDeterminationService determination, AccountResolverService accounts,
            StockPostingService stockPosting, AuditService audit, ApprovalsService approvals,
            SegregationOfDutiesService sod, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.follower = follower;
        this.lineage = lineage;
        this.posting = posting;
        this.determination = determination;
        this.accounts = accounts;
        this.stockPosting = stockPosting;
        this.audit = audit;
        this.approvals = approvals;
        this.sod = sod;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Exceptions, newest first; {@code status} filters (open / resolved / written_off). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String where = "";
        if (status != null && !status.isBlank()) {
            where = " AND e.status = ?";
            params.add(status);
        }
        return SqlRows.list(jdbc.sql("SELECT e.*, p.name AS \"productName\", l.name AS \"locationName\","
                + " a.\"openAmount\" AS \"parkedOpen\" FROM \"LineageException\" e LEFT JOIN \"Product\" p ON"
                + " p.id = e.\"productId\" LEFT JOIN \"InventoryLocation\" l ON l.id = e.\"locationId\" LEFT JOIN"
                + " \"CostValueAdjustment\" a ON a.id = e.\"adjustmentId\" WHERE e.\"organizationId\" = ?" + where
                + " ORDER BY e.\"createdAt\" DESC LIMIT 500").params(params));
    }

    private Map<String, Object> lockOpen(String id) {
        Map<String, Object> e = SqlRows.one(jdbc.sql("SELECT * FROM \"LineageException\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (e == null) {
            throw ApiException.notFound("Lineage exception not found");
        }
        if (!"open".equals(e.get("status"))) {
            throw ApiException.badRequest("Only an open lineage exception can be resolved");
        }
        return e;
    }

    private Map<String, Object> parked(Map<String, Object> e) {
        if (e.get("adjustmentId") == null) {
            return null;
        }
        return SqlRows.one(jdbc.sql("SELECT * FROM \"CostValueAdjustment\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"reversedAt\" IS NULL FOR UPDATE").params(e.get("adjustmentId"), org()));
    }

    /**
     * Name the transfer_in row that received the goods of the exception's transfer_out row: the link is recorded (for
     * the quantity still unlinked on both sides) and the parked amount is distributed from the destination.
     */
    @Transactional
    public Map<String, Object> assign(String id, String destinationLedgerId, String reason) {
        Map<String, Object> e = lockOpen(id);
        requireReason(reason);
        String sourceId = (String) e.get("ledgerId");
        Map<String, Object> out = ledger(sourceId);
        Map<String, Object> in = ledger(destinationLedgerId);
        if (out == null || !"transfer_out".equals(out.get("type"))) {
            throw ApiException.badRequest("The exception's stock movement is not a transfer out");
        }
        if (in == null || !"transfer_in".equals(in.get("type"))
                || !in.get("productId").equals(out.get("productId"))
                || !key(in.get("variantId")).equals(key(out.get("variantId")))) {
            throw ApiException.badRequest("The destination must be a transfer in of the same product and variant");
        }
        BigDecimal outFree = SqlRows.dec(out, "quantityChange").negate().subtract(linked("sourceLedgerId", sourceId));
        BigDecimal inFree = SqlRows.dec(in, "quantityChange").subtract(linked("destinationLedgerId",
                destinationLedgerId));
        BigDecimal qty = outFree.min(inFree);
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("Nothing left to link between these two movements");
        }
        jdbc.sql("INSERT INTO \"InventoryTransferLineage\" (id, \"organizationId\", \"sourceLedgerId\","
                        + " \"destinationLedgerId\", quantity) VALUES (?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), org(), sourceId, destinationLedgerId, qty).update();
        Map<String, Object> adj = parked(e);
        String entryId = null;
        BigDecimal open = adj == null ? BigDecimal.ZERO : SqlRows.dec(adj, "openAmount");
        if (open.signum() != 0) {
            String productId = (String) in.get("productId");
            // Readiness gaps h-02 F2: the destination's method is read under the costing locks, as a stock move does.
            CostingLocks.shareProduct(jdbc, org(), productId);
            BigDecimal before = SqlRows.dec(in, "qtyBefore").add(SqlRows.dec(in, "quantityChange").subtract(qty)
                    .max(BigDecimal.ZERO));
            boolean standard = "STANDARD".equals(jdbc.sql("SELECT \"costingMethod\"::text FROM \"Product\" WHERE id = ?"
                    + " AND \"organizationId\" = ?").params(productId, org()).query(String.class).optional()
                    .orElse(null));
            // Readiness gaps h-02 F10 / ACR-14: the share follows its goods the way its source capitalised it. A voucher
            // raises the average of AVCO / FIFO goods (adjunct for STANDARD); every other source (an accrual, a cost
            // adjustment, freight on a transfer, ...) always capitalises into the 1396 adjunct.
            String type = (String) adj.get("adjustmentType");
            boolean voucher = VOUCHER_TYPES.contains(type);
            Mode mode = voucher && !standard ? Mode.AVERAGE : Mode.ADJUNCT;
            Followed f = follower.follow(in, qty, before, open.abs());
            if (open.signum() < 0) {
                f = f.negate();
            }
            if (mode == Mode.AVERAGE) {
                f = follower.retargetEmptyPools(f);
            }
            String text = "Lineage exception resolved · goods assigned";
            entryId = release(f, open, adj, voucher, standard, text);
            // The parked amount is handed on to the destination's adjustments (children of the parked one, same root):
            // it leaves the parked adjustment's carried amount, and the hand-over is recorded as a release.
            jdbc.sql("UPDATE \"CostValueAdjustment\" SET \"onHandAmount\" = \"onHandAmount\" - ? WHERE id = ?")
                    .params(open, adj.get("id")).update();
            lineage.recordRelease((String) adj.get("id"), "assigned", destinationLedgerId,
                    lineage.priceDifferenceAccount(), open, "lineage_exception", id);
            follower.record(f, type, (String) adj.get("sourceType"), (String) adj.get("sourceId"), entryId, mode,
                    "Parked landed cost released to its goods", (String) adj.get("id"),
                    adj.get("rootAdjustmentId") != null ? (String) adj.get("rootAdjustmentId") : (String) adj.get("id"),
                    (String) adj.get("sourceReceiptLedgerId"));
        }
        return close(e, "resolved", Obj.of("action", "assign", "destinationLedgerId", destinationLedgerId,
                "quantity", JsNumbers.dec(qty), "reason", reason, "journalEntryId", entryId));
    }

    /** Write the parked amount off to an account, with a reason. */
    @Transactional
    public Map<String, Object> writeOff(String id, String accountId, String reason) {
        Map<String, Object> e = lockOpen(id);
        requireReason(reason);
        if (accountId == null || accountId.isBlank()) {
            throw ApiException.badRequest("accountId should not be empty");
        }
        if (jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?")
                .params(accountId, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Account not found");
        }
        // Readiness gaps h-02 F5: the chosen account follows the manual-journal control-account policy.
        lineage.assertOffsetAccount(accountId);
        Map<String, Object> adj = parked(e);
        BigDecimal open = adj == null ? BigDecimal.ZERO : SqlRows.dec(adj, "openAmount");
        String entryId = null;
        if (open.signum() != 0) {
            // Readiness gaps h-02 F5: maker-checker — the approval engine (when the organisation runs a workflow for
            // inventory valuation changes) and the separation-of-duties pair (who posted the parked cost cannot write
            // it off).
            Map<String, Object> gate = newTx.execute(s -> approvals.checkOrRequestApproval(
                    SegregationOfDutiesService.INVENTORY_VALUATION, id, Obj.of("amount", JsNumbers.js(open.abs()),
                            "kind", "lineage_write_off", "accountId", accountId, "makerIds",
                            adj.get("createdBy") == null ? "" : adj.get("createdBy"))));
            if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
                throw ApiException.badRequest("Approval required before writing off this lineage exception. Pending"
                        + " approval request " + gate.get("requestId") + ".");
            }
            List<String> makers = new ArrayList<>();
            makers.add((String) adj.get("createdBy"));
            sod.assertSeparated(SegregationOfDutiesService.INVENTORY_VALUATION, "LineageException", id, makers);
            String text = "Lineage exception written off · " + reason;
            entryId = post(List.of(signed(accountId, open, text), signed(lineage.priceDifferenceAccount(),
                    open.negate(), text)), text, id);
            // Review 2026-11-17 (ACR-14): an accrual clearing takes this part of its estimate back out of that account.
            lineage.recordRelease((String) adj.get("id"), "write_off", null, accountId, open, "lineage_exception", id);
            closeParked(adj, open, text);
        }
        return close(e, "written_off", Obj.of("action", "write_off", "accountId", accountId, "reason", reason,
                "amount", JsNumbers.dec(open), "journalEntryId", entryId));
    }

    /**
     * Dr each target / Cr 1396 for the parked amount, and take it out of the parked adjunct. A voucher's share posts as
     * the voucher does (on hand → stock, or the 1396 adjunct for STANDARD; returns → 5976; every other consumption →
     * cost of sales); any other source's share as its capitalisation does (readiness gaps h-02 F10): on hand / in transit
     * stays in the 1396 adjunct, consumption goes where the automatic release sends it (WIP, scrap → stock adjustment
     * expense, the rest → cost of sales), so an accrual clearing later takes it back out of the same accounts.
     */
    private String release(Followed f, BigDecimal open, Map<String, Object> adj, boolean voucher, boolean standard,
            String text) {
        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        // Hardening R3: a voucher's on-hand share posts to the product's stock account, one line per product (and
        // location) carrying the product dimension.
        Map<List<String>, BigDecimal> stockDebits = new LinkedHashMap<>();
        for (Placement p : f.placements()) {
            if (voucher && !standard && "on_hand".equals(p.target())) {
                stockDebits.merge(java.util.Arrays.asList(stockPosting.landedAccount(p.productId(), p.amount().abs()),
                        p.productId(), p.locationId()), p.amount(), BigDecimal::add);
                continue;
            }
            String account = voucher ? switch (p.target()) {
                case "on_hand" -> standard ? lineage.priceDifferenceAccount()
                        : stockPosting.landedAccount(p.productId(), p.amount().abs());
                case "wip" -> determination.mapped("wip");
                case "in_transit" -> lineage.priceDifferenceAccount();
                case "return" -> accounts.ensureByCode("5976", "Landed Cost on Returned Goods", "cost_of_goods_sold",
                        "landed_cost_returns");
                default -> determination.mapped("cogs");
            } : switch (p.target()) {
                case "on_hand", "transfer", "in_transit" -> lineage.priceDifferenceAccount();
                default -> lineage.consumptionAccount(p.target());
            };
            debits.merge(account, p.amount(), BigDecimal::add);
        }
        debits.merge(lineage.priceDifferenceAccount(), open.negate(), BigDecimal::add);
        List<Line> lines = new ArrayList<>();
        debits.forEach((acc, v) -> {
            if (v.signum() != 0) {
                lines.add(signed(acc, v, text));
            }
        });
        stockDebits.forEach((k, v) -> {
            if (v.signum() != 0) {
                lines.add(StockPostingService.stampLine(signed(k.get(0), v, text), k.get(1), k.get(2)));
            }
        });
        String entryId = lines.size() >= 2 ? post(lines, text, (String) adj.get("id")) : null;
        closeParked(adj, open, text);
        return entryId;
    }

    private void closeParked(Map<String, Object> adj, BigDecimal open, String text) {
        lineage.recordValueOnly((String) adj.get("productId"), (String) adj.get("variantId"),
                (String) adj.get("locationId"), null, open.negate(), "lineage_exception", (String) adj.get("id"),
                "price_difference", text);
        jdbc.sql("UPDATE \"CostValueAdjustment\" SET \"openAmount\" = 0 WHERE id = ?").param(adj.get("id")).update();
    }

    private Map<String, Object> close(Map<String, Object> e, String status, Map<String, Object> resolution) {
        jdbc.sql("UPDATE \"LineageException\" SET status = ?, resolution = ?, \"resolvedBy\" = ?, \"resolvedAt\" = "
                        + StockService.NOW + " WHERE id = ?")
                .params(status, com.inventoryerp.shared.web.EntityJson.toJson(resolution), TenantContext.userId(),
                        e.get("id")).update();
        audit.recordInTx(AuditInput.of("LineageException", (String) e.get("id"), "update", null, resolution));
        return SqlRows.one(jdbc.sql("SELECT * FROM \"LineageException\" WHERE id = ?").param(e.get("id")));
    }

    private String post(List<Line> lines, String text, String key) {
        Map<String, Object> je = posting.post(PostingRequest.of("INV", Instant.now(), text, "lineage_exception", key,
                "inventory:lineage_exception:" + org() + ":" + key + ":" + UUID.randomUUID(), lines));
        return je == null ? null : (String) je.get("id");
    }

    private static Line signed(String account, BigDecimal amount, String text) {
        return amount.signum() > 0 ? Line.debit(account, JsNumbers.dec(amount), text)
                : Line.credit(account, JsNumbers.dec(amount.negate()), text);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason should not be empty");
        }
    }

    private Map<String, Object> ledger(String id) {
        return id == null ? null : SqlRows.one(jdbc.sql("SELECT *, type::text AS type FROM \"InventoryLedger\" WHERE"
                + " id = ? AND \"organizationId\" = ?").params(id, org()));
    }

    private BigDecimal linked(String column, String ledgerId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"InventoryTransferLineage\" WHERE \"organizationId\" = ?"
                + " AND \"" + column + "\" = ?").params(org(), ledgerId).query(BigDecimal.class).single();
    }

    private static String key(Object v) {
        return v == null ? "" : v.toString();
    }
}
