package com.inventoryerp.repair.application;

import static com.inventoryerp.repair.application.RepairDb.nz;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockReversalService;
import com.inventoryerp.inventory.application.StockReversalService.LedgerSource;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.repair.domain.RepairOrder;
import com.inventoryerp.repair.domain.RepairPart;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Spare-part reservation and issue against a repair order (port of {@code repair-parts.service.ts}). Issuing relieves
 * stock through the inventory {@link StockService#issue} engine (COGS + inventory at issue time); the repair invoice
 * line for the part carries {@code repairOrderLineId} so the POS invoice path never issues it again.
 */
@Service
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairPartsService {

    /** Ledger reference of a part issue; since hardening R5a its reference id is the part (line) id. */
    static final String ISSUE_SOURCE = "repair_part_issue";

    /** Ledger reference of a part return (the reversal of its issue). */
    static final String RETURN_SOURCE = "repair_part_return";

    private final RepairDb db;
    private final StockService stock;
    private final StockReversalService reversals;

    public RepairPartsService(RepairDb db, StockService stock, StockReversalService reversals) {
        this.db = db;
        this.stock = stock;
        this.reversals = reversals;
    }

    public List<Map<String, Object>> listParts(Map<String, String> query) {
        return db.where(RepairPart.class)
                .eqIf("status", query.get("status"))
                .eqIf("repairOrderId", query.get("repairOrderId"))
                .eqIf("jobId", query.get("jobId"))
                .eqIf("productId", query.get("productId"))
                .list("\"createdAt\" DESC", RepairDb.take(query.get("take"), 50, 200),
                        RepairDb.skip(query.get("skip")));
    }

    /** Reserve a part (no inventory movement yet); unit cost defaults to the product cost. */
    public Map<String, Object> reservePart(String repairOrderId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        if (db.byId(RepairOrder.class, repairOrderId) == null) {
            throw ApiException.notFound("Repair order not found");
        }
        // Prisma drops an undefined `id` from the where, so a missing productId matches any product of the org.
        Map<String, Object> product = !dto.containsKey("productId") ? db.where(Product.class).first()
                : dto.get("productId") == null ? null : db.byId(Product.class, dto.get("productId"));
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        BigDecimal quantity = RepairDb.jsNum(nz(dto.get("quantity"), 1));
        if (!RepairDb.positive(quantity)) {
            throw ApiException.badRequest("Quantity must be positive");
        }
        BigDecimal unitCost = RepairDb.jsNumOrZero(nz(nz(dto.get("unitCost"), product.get("costPrice")), 0));
        return db.insert(RepairPart.class, Obj.of("organizationId", RepairDb.org(), "repairOrderId", repairOrderId,
                "jobId", dto.get("jobId"), "productId", dto.get("productId"), "locationId", dto.get("locationId"),
                "variantId", dto.get("variantId"), "quantity", quantity, "unitCost", unitCost,
                "totalCost", RepairDb.round2(quantity.multiply(unitCost)), "status", "reserved",
                "notes", dto.get("notes")));
    }

    /**
     * Issue a reserved part: stock relief through the stock engine (inventory ledger + COGS journal), then the row
     * flips to {@code issued}. Nest runs the two steps without a common transaction; here they commit together and
     * the part row is locked first, so a failed flip never leaves stock relieved and two concurrent issues of the same
     * part cannot both relieve stock (the second answers {@code Part already issued}).
     *
     * <p>Hardening R5a: the part's variant ({@code variantId} of the body or the reservation) is issued; the part's
     * cost becomes the engine's issued cost (what COGS was charged), so job cost equals COGS — a {@code unitCost} in the
     * body no longer overrides it; the ledger rows reference the part itself (reference id and source line = part id),
     * so {@link #returnPart} can reverse exactly this part.
     */
    public Map<String, Object> issuePart(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        String orgId = RepairDb.org();
        Issue issue = db.inTx(() -> {
            Map<String, Object> part = db.lockById(RepairPart.class, id);
            if (part == null) {
                throw ApiException.notFound("Repair part not found");
            }
            if ("issued".equals(part.get("status"))) {
                throw ApiException.badRequest("Part already issued");
            }
            if ("returned".equals(part.get("status"))) {
                throw ApiException.badRequest("Part was returned to stock; reserve it again to issue it");
            }
            Map<String, Object> order = db.byId(RepairOrder.class, part.get("repairOrderId"));
            Object locationId = nz(dto.get("locationId"), part.get("locationId"));
            if (!PosSql.truthy(locationId)) {
                throw ApiException.badRequest("A source location is required to issue a part");
            }
            if (!PosSql.truthy(part.get("productId"))) {
                throw ApiException.badRequest("Part has no linked product — stock cannot be relieved");
            }
            Object ref = order != null && order.get("repairNumber") != null ? order.get("repairNumber")
                    : part.get("repairOrderId");

            BigDecimal quantity = RepairDb.jsNumOrZero(part.get("quantity"));
            Object variantId = nz(dto.get("variantId"), part.get("variantId"));
            StockMove move = StockMove.of((String) part.get("productId"), locationId.toString(), quantity)
                    .variant(PosSql.truthy(variantId) ? variantId.toString() : null).sourceLine(id);
            move.moveType = "issue";
            move.sourceType = ISSUE_SOURCE;
            move.sourceId = id;
            move.reference = "Repair " + ref;
            move.notes = dto.get("notes") == null ? null : dto.get("notes").toString();
            Issued issued = stock.issue(move);

            BigDecimal totalCost = issued.totalValue().abs().setScale(6, java.math.RoundingMode.HALF_UP);
            BigDecimal unitCost = quantity.signum() > 0
                    ? totalCost.divide(quantity, 6, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
            Map<String, Object> updated = db.update(RepairPart.class, id, Obj.of("status", "issued",
                    "issuedAt", Instant.now(), "locationId", locationId, "variantId", move.variantId, "unitCost",
                    unitCost, "totalCost", totalCost));
            return new Issue(part, ref, updated);
        });
        Map<String, Object> part = issue.part();
        db.notify(orgId, "Part issued", RepairDb.s(part.get("quantity")) + " × " + RepairDb.s(part.get("productId"))
                        + " on " + issue.ref(),
                Obj.of("repairOrderId", part.get("repairOrderId"), "partId", part.get("id")));
        return issue.updated();
    }

    /**
     * Hardening R5a: return an issued part to stock. The part's issue rows (reference {@code repair_part_issue} / the
     * part id) are reversed through the stock engine at the cost they left with, their COGS journal is mirrored, and
     * the part becomes {@code returned}. A part issued before R5a referenced its repair order, not itself, so its rows
     * cannot be told apart from the other parts of the order: it is refused with a pointer to a stock adjustment.
     */
    public Map<String, Object> returnPart(String id, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        return db.inTx(() -> {
            Map<String, Object> part = db.lockById(RepairPart.class, id);
            if (part == null) {
                throw ApiException.notFound("Repair part not found");
            }
            if (!"issued".equals(part.get("status"))) {
                throw ApiException.badRequest("Only an issued part can be returned to stock");
            }
            long rows = db.jdbc().sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                    + " AND \"referenceType\" = ? AND \"referenceId\" = ?").params(RepairDb.org(), ISSUE_SOURCE, id)
                    .query(Long.class).single();
            if (rows == 0) {
                throw ApiException.badRequest("This part was issued before part returns were recorded per part;"
                        + " return its stock with a stock adjustment");
            }
            Map<String, Object> order = db.byId(RepairOrder.class, part.get("repairOrderId"));
            Object ref = order != null && order.get("repairNumber") != null ? order.get("repairNumber")
                    : part.get("repairOrderId");
            Object reason = nz(dto.get("reason"), dto.get("notes"));
            String notes = "Repair part return · Repair " + ref + (PosSql.truthy(reason) ? " · " + reason : "");
            reversals.reverseLedgerSource(new LedgerSource(ISSUE_SOURCE, id, RETURN_SOURCE, id,
                    List.of(ISSUE_SOURCE), notes, null));
            return db.update(RepairPart.class, id, Obj.of("status", "returned"));
        });
    }

    /** What an issue transaction hands back for the notification. */
    private record Issue(Map<String, Object> part, Object ref, Map<String, Object> updated) {}

    public Map<String, Object> deletePart(String id) {
        Map<String, Object> part = db.byId(RepairPart.class, id);
        if (part == null) {
            throw ApiException.notFound("Repair part not found");
        }
        if ("issued".equals(part.get("status"))) {
            throw ApiException.badRequest("Issued parts cannot be deleted — reverse via stock adjustment");
        }
        return db.where(RepairPart.class).eq("id", id).deleteMany();
    }
}
