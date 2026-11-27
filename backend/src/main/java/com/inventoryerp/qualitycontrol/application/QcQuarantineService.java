package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.inventory.application.VirtualLocationService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.qualitycontrol.domain.QcCheck;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock moves of quality control (ADR-S087 §7). Every move is a {@link StockService#transfer} at carried cost; there is
 * no second ledger. The ledger reference {@code qc_check / <checkId>:<revision>:<leg>} makes each leg idempotent: a leg
 * that is already on the ledger is never moved again.
 *
 * <ul>
 *   <li>Receipt into quarantine (post-receipt check) and release on the decision are <b>strict</b>: a failure throws
 *       and rolls back the receipt or the decision with it (§7, atomic units 1–3).</li>
 *   <li>A pre-completion failure is <b>lenient</b>: the quantity is checked first (on hand, unreserved, in the batch);
 *       when it cannot move, the completion still commits and the check records {@code quarantineStatus = failed}
 *       for a manager to retry. A quantity is never shown as quarantined unless it moved.</li>
 * </ul>
 */
@Service
public class QcQuarantineService {

    static final String REF_TYPE = "qc_check";

    private final QcDb db;
    private final StockService stock;
    private final VirtualLocationService locations;
    private final AuditService audit;

    public QcQuarantineService(QcDb db, StockService stock, VirtualLocationService locations, AuditService audit) {
        this.db = db;
        this.stock = stock;
        this.locations = locations;
        this.audit = audit;
    }

    /** The quarantine location of a check: its point's failure location, else {@code QC-QUARANTINE}. */
    String quarantineLocation(Map<String, Object> check) {
        String pointId = QcUtil.str(check, "controlPointId");
        if (pointId != null) {
            String loc = db.jdbc().sql("SELECT \"failureLocationId\" FROM \"QcControlPoint\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(pointId, QcDb.org()).query(String.class).optional().orElse(null);
            if (loc != null) {
                return loc;
            }
        }
        return locations.ensure(VirtualLocationService.QC_QUARANTINE, "QC Quarantine");
    }

    // ------------------------------------------------------------------ post-receipt (strict)

    /** Moves a post-receipt check's covered quantity from the receive location into quarantine. Throws on failure. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void holdOnReceipt(Map<String, Object> check) {
        BigDecimal qty = QcUtil.dec(check, "coveredQty");
        String from = QcUtil.str(check, "locationId");
        if (qty.signum() <= 0 || from == null || check.get("productId") == null) {
            return;
        }
        String to = quarantineLocation(check);
        String ref = QcUtil.str(check, "id") + ":receipt";
        if (!moved(ref)) {
            BigDecimal onHand = onHand(check, from, false);
            if (onHand.compareTo(qty) < 0) {
                throw ApiException.badRequest("Quality check " + check.get("checkNumber") + ": only " + onHand.stripTrailingZeros()
                        .toPlainString() + " of " + qty.stripTrailingZeros().toPlainString()
                        + " can be moved into quarantine, so the receipt is not posted.");
            }
            transfer(check, from, to, qty, ref);
        }
        db.update(QcCheck.class, QcUtil.str(check, "id"), Obj.of("quarantinedQty", qty, "quarantineStatus", "pending",
                "quarantineLocationId", to, "quarantineMoveRef", ref, "quarantineError", null));
        QcCheckService.event(db, QcUtil.str(check, "id"), "quarantined", null, null, 1, null, Obj.of("qty", qty, "locationId", to));
    }

    /**
     * The decision of a post-receipt check releases stock back to the receive location, in the deciding transaction:
     * passed → everything; failed → all but {@code failedQty} (all is kept when no quantity is given); waived → all but
     * {@code excludedQty}. Throws (rolling the decision back) when the release cannot move.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseOnDecision(Map<String, Object> check, String decision, BigDecimal retained, int revision) {
        if (!"post_receipt".equals(QcUtil.str(check, "phase")) || !"pending".equals(QcUtil.str(check, "quarantineStatus"))) {
            return;
        }
        BigDecimal held = QcUtil.dec(check, "quarantinedQty");
        BigDecimal keep = switch (decision) {
            case "passed" -> BigDecimal.ZERO;
            case "failed" -> retained == null ? held : retained.min(held);
            default -> retained == null ? BigDecimal.ZERO : retained.min(held);
        };
        BigDecimal release = held.subtract(keep);
        String id = QcUtil.str(check, "id");
        String ref = id + ":" + revision + ":release";
        if (release.signum() > 0 && !moved(ref)) {
            String from = QcUtil.str(check, "quarantineLocationId");
            BigDecimal onHand = onHand(check, from, false);
            if (onHand.compareTo(release) < 0) {
                throw ApiException.badRequest("Quality check " + check.get("checkNumber") + ": only "
                        + onHand.stripTrailingZeros().toPlainString() + " is left in quarantine; "
                        + release.stripTrailingZeros().toPlainString() + " should be released. The decision is not saved.");
            }
            transfer(check, from, QcUtil.str(check, "locationId"), release, ref);
        }
        db.update(QcCheck.class, id, Obj.of("quarantinedQty", keep, "quarantineStatus", "completed",
                "quarantineMoveRef", release.signum() > 0 ? ref : check.get("quarantineMoveRef")));
        QcCheckService.event(db, id, "released", null, null, revision, null, Obj.of("released", release, "kept", keep));
    }

    /**
     * Purchase readiness H9b: the receipt of a still-pending post-receipt check is reversed. Its quarantined quantity
     * goes back to the receive location (strict: refused when it is no longer all in quarantine), so the reversal can
     * take it out of stock there. Idempotent per revision.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void returnOnReversal(Map<String, Object> check, int revision) {
        BigDecimal held = QcUtil.dec(check, "quarantinedQty");
        String id = QcUtil.str(check, "id");
        String ref = id + ":" + revision + ":reversal";
        String from = QcUtil.str(check, "quarantineLocationId");
        String to = QcUtil.str(check, "locationId");
        if (held.signum() > 0 && from != null && to != null && !moved(ref)) {
            BigDecimal onHand = onHand(check, from, false);
            if (onHand.compareTo(held) < 0) {
                throw ApiException.badRequest("Quality check " + check.get("checkNumber") + ": only "
                        + onHand.stripTrailingZeros().toPlainString() + " of " + held.stripTrailingZeros().toPlainString()
                        + " is left in quarantine, so the receipt cannot be reversed.");
            }
            transfer(check, from, to, held, ref);
        }
        db.update(QcCheck.class, id, Obj.of("quarantinedQty", BigDecimal.ZERO, "quarantineStatus", "completed",
                "quarantineMoveRef", held.signum() > 0 ? ref : check.get("quarantineMoveRef")));
        QcCheckService.event(db, id, "released", null, null, revision, "source document reversed",
                Obj.of("released", held, "kept", BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------ pre-completion failure (lenient)

    /**
     * Moves a failed pre-completion check's {@code failedQty} from the document's stock location into quarantine, when
     * it is all there (on hand, unreserved, in the batch); otherwise records the exception. Never throws for stock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void quarantineFailure(Map<String, Object> check, String fromLocation) {
        String id = QcUtil.str(check, "id");
        BigDecimal qty = check.get("failedQty") == null ? BigDecimal.ZERO : QcUtil.dec(check, "failedQty");
        if (qty.signum() <= 0 || fromLocation == null || check.get("productId") == null) {
            return;
        }
        String to = quarantineLocation(check);
        int revision = QcUtil.integer(check, "currentRevision");
        String ref = id + ":" + revision + ":failed";
        if (to.equals(fromLocation)) {
            // Already received into quarantine (e.g. a production order with its own QC hold): nothing to move.
            db.update(QcCheck.class, id, Obj.of("quarantinedQty", qty, "quarantineStatus", "completed",
                    "quarantineLocationId", to, "quarantineError", null));
            return;
        }
        if (!moved(ref)) {
            BigDecimal available = onHand(check, fromLocation, true);
            if (available.compareTo(qty) < 0) {
                String error = "Only " + available.stripTrailingZeros().toPlainString() + " of "
                        + qty.stripTrailingZeros().toPlainString() + " is available to move into quarantine.";
                db.update(QcCheck.class, id, Obj.of("quarantineStatus", "failed", "quarantineError", error,
                        "quarantineLocationId", to, "locationId", fromLocation));
                QcCheckService.event(db, id, "quarantine_failed", null, null, revision, error, Obj.of("qty", qty));
                return;
            }
            transfer(check, fromLocation, to, qty, ref);
        }
        db.update(QcCheck.class, id, Obj.of("quarantinedQty", qty, "quarantineStatus", "completed",
                "quarantineLocationId", to, "quarantineMoveRef", ref, "quarantineError", null, "locationId",
                fromLocation));
        QcCheckService.event(db, id, "quarantined", null, null, revision, null, Obj.of("qty", qty, "locationId", to));
    }

    /** {@code POST /checks/:id/quarantine/retry} ({@code manage}): retries a failed quarantine move. */
    @Transactional
    public void retry(String id) {
        Map<String, Object> c = db.require(QcCheck.class, id, true, "Quality check not found");
        if (!"failed".equals(QcUtil.str(c, "quarantineStatus"))) {
            throw ApiException.badRequest("Quality check " + c.get("checkNumber") + " has no failed quarantine move.");
        }
        quarantineFailure(c, QcUtil.str(c, "locationId"));
        Map<String, Object> after = db.require(QcCheck.class, id, false, "Quality check not found");
        if ("failed".equals(QcUtil.str(after, "quarantineStatus"))) {
            throw ApiException.badRequest(QcUtil.str(after, "quarantineError"));
        }
        audit.recordInTx(AuditInput.of(QcCheckService.AUDIT_ENTITY, id, "transfer", Obj.of("quarantineStatus", "failed"),
                Obj.of("op", "quarantine_retry", "quarantineStatus", after.get("quarantineStatus"), "quarantinedQty",
                        after.get("quarantinedQty"))));
    }

    // ------------------------------------------------------------------ helpers

    private void transfer(Map<String, Object> check, String from, String to, BigDecimal qty, String ref) {
        StockMove move = StockMove.transfer(QcUtil.str(check, "productId"), from, to, qty)
                .variant(QcUtil.str(check, "variantId")).batch(QcUtil.str(check, "batchNumber"))
                .source(REF_TYPE, ref).notes("Quality check " + check.get("checkNumber"));
        stock.transfer(move);
    }

    /** Is a leg already on the ledger (idempotency key = the reference)? */
    boolean moved(String ref) {
        return db.jdbc().sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"referenceType\" = ? AND \"referenceId\" = ?").params(QcDb.org(), REF_TYPE, ref)
                .query(Long.class).single() > 0;
    }

    /** On hand at a location for the check's product / variant (and batch, for a batch-tracked product). */
    BigDecimal onHand(Map<String, Object> check, String location, boolean unreserved) {
        String org = QcDb.org();
        String product = QcUtil.str(check, "productId");
        String variant = QcUtil.str(check, "variantId");
        BigDecimal qty = db.jdbc().sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"variantKey\" = COALESCE(?, '') AND \"locationId\" = ?")
                .params(org, product, variant, location).query(BigDecimal.class).single();
        if (unreserved) {
            qty = qty.subtract(db.jdbc().sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = COALESCE(?, '') AND"
                    + " \"locationId\" = ? AND status = 'active'").params(org, product, variant, location)
                    .query(BigDecimal.class).single());
        }
        String batch = QcUtil.str(check, "batchNumber");
        boolean tracked = Boolean.TRUE.equals(db.jdbc().sql("SELECT \"batchTracking\" FROM \"Product\" WHERE id = ?")
                .param(product).query(Boolean.class).optional().orElse(false));
        if (tracked && batch != null) {
            qty = qty.min(db.jdbc().sql("SELECT COALESCE(SUM(quantity), 0) FROM \"InventoryBatch\" WHERE"
                    + " \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND"
                    + " \"locationId\" = ? AND \"batchNumber\" = ? AND \"isActive\" = true AND quantity > 0")
                    .params(org, product, variant, location, batch).query(BigDecimal.class).single());
        }
        return qty.max(BigDecimal.ZERO);
    }
}
