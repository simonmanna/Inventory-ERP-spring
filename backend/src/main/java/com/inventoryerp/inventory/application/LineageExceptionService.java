package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.LineageFollower.Followed;
import com.inventoryerp.inventory.application.LineageFollower.Mode;
import com.inventoryerp.inventory.application.LineageFollower.Placement;
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
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public LineageExceptionService(JdbcClient jdbc, LineageFollower follower, CostLineageService lineage,
            PostingService posting, AccountDeterminationService determination, AccountResolverService accounts,
            StockPostingService stockPosting, AuditService audit) {
        this.jdbc = jdbc;
        this.follower = follower;
        this.lineage = lineage;
        this.posting = posting;
        this.determination = determination;
        this.accounts = accounts;
        this.stockPosting = stockPosting;
        this.audit = audit;
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
            BigDecimal before = SqlRows.dec(in, "qtyBefore").add(SqlRows.dec(in, "quantityChange").subtract(qty)
                    .max(BigDecimal.ZERO));
            boolean standard = "STANDARD".equals(jdbc.sql("SELECT \"costingMethod\"::text FROM \"Product\" WHERE id = ?")
                    .param(in.get("productId")).query(String.class).optional().orElse(null));
            Followed f = follower.follow(in, qty, before, open.abs());
            if (open.signum() < 0) {
                f = f.negate();
            }
            if (!standard) {
                f = follower.retargetEmptyPools(f);
            }
            entryId = release(f, open, adj, standard, "Lineage exception resolved · goods assigned");
            follower.record(f, (String) adj.get("adjustmentType"), (String) adj.get("sourceType"),
                    (String) adj.get("sourceId"), entryId, standard ? Mode.ADJUNCT : Mode.AVERAGE,
                    "Parked landed cost released to its goods");
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
        Map<String, Object> adj = parked(e);
        BigDecimal open = adj == null ? BigDecimal.ZERO : SqlRows.dec(adj, "openAmount");
        String entryId = null;
        if (open.signum() != 0) {
            String text = "Lineage exception written off · " + reason;
            entryId = post(List.of(signed(accountId, open, text), signed(lineage.priceDifferenceAccount(),
                    open.negate(), text)), text, id);
            closeParked(adj, open, text);
        }
        return close(e, "written_off", Obj.of("action", "write_off", "accountId", accountId, "reason", reason,
                "amount", JsNumbers.dec(open), "journalEntryId", entryId));
    }

    /** Dr each target / Cr 1396 for the parked amount, and take it out of the parked adjunct. */
    private String release(Followed f, BigDecimal open, Map<String, Object> adj, boolean standard, String text) {
        Map<String, BigDecimal> debits = new LinkedHashMap<>();
        for (Placement p : f.placements()) {
            String account = switch (p.target()) {
                case "on_hand" -> standard ? lineage.priceDifferenceAccount()
                        : stockPosting.landedAccount(p.productId(), p.amount().abs());
                case "wip" -> determination.mapped("wip");
                case "in_transit" -> lineage.priceDifferenceAccount();
                case "return" -> accounts.ensureByCode("5976", "Landed Cost on Returned Goods", "cost_of_goods_sold",
                        "landed_cost_returns");
                default -> determination.mapped("cogs");
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
