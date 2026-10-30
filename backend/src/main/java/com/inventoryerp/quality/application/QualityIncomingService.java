package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.quality.application.QualityCommands.OpenLot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Incoming inspection hook (ADR-S013 §4): for a posted goods receipt, one lot per receipt line whose product has an
 * active incoming plan (supplier-specific first), at the receiving warehouse, in base units, with the plan's hold
 * policy (quarantine moves the received batch into QC quarantine). Idempotent per receipt line; a line that cannot
 * be opened (e.g. its stock already left the warehouse) is logged and skipped without blocking the others.
 */
@Service
public class QualityIncomingService {

    private static final Logger log = LoggerFactory.getLogger(QualityIncomingService.class);

    private final QualityDb db;
    private final QualityPlanService plans;
    private final QualityLotService lots;
    private final TransactionTemplate tx;

    public QualityIncomingService(QualityDb db, QualityPlanService plans, QualityLotService lots,
            PlatformTransactionManager txm) {
        this.db = db;
        this.plans = plans;
        this.lots = lots;
        this.tx = new TransactionTemplate(txm);
    }

    /**
     * Lots opened for the receipt (existing ones included); empty when it is not posted or nothing is inspected. Not
     * transactional on purpose: each line opens its lot in its own transaction.
     */
    public List<String> openForGoodsReceipt(String receiptId) {
        Map<String, Object> grn = tx.execute(s -> SqlRows.one(db.jdbc().sql("SELECT id, \"receiptNumber\", \"partnerId\", \"warehouseId\","
                + " status::text AS status FROM \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(receiptId, QualityDb.org())));
        List<String> out = new ArrayList<>();
        if (grn == null || !"posted".equals(grn.get("status"))) {
            return out;
        }
        String supplier = Q.str(grn, "partnerId");
        List<Map<String, Object>> lines = tx.execute(s -> SqlRows.list(db.jdbc().sql("SELECT * FROM"
                + " \"GoodsReceiptLine\" WHERE \"goodsReceiptId\" = ? AND \"organizationId\" = ? AND \"productId\" IS NOT NULL"
                + " ORDER BY \"lineNumber\"").params(receiptId, QualityDb.org())));
        for (Map<String, Object> l : lines) {
            String product = Q.str(l, "productId");
            Map<String, Object> plan = tx.execute(s -> plans.findPlan("incoming", product, supplier, null));
            if (plan == null) {
                continue;
            }
            BigDecimal qty = Q.dec(l, "quantity");
            if (l.get("uomFactor") != null && Q.dec(l, "uomFactor").signum() > 0) {
                qty = qty.multiply(Q.dec(l, "uomFactor")).setScale(6, RoundingMode.HALF_UP);
            }
            if (qty.signum() <= 0) {
                continue;
            }
            BigDecimal baseQty = qty;
            try {
                Map<String, Object> lot = tx.execute(s -> lots.openLot(new OpenLot("incoming", product, null,
                        Q.str(l, "batchNumber"), baseQty, null, Q.str(grn, "warehouseId"), "goods_receipt", receiptId,
                        Q.str(l, "id"), "GRN " + grn.get("receiptNumber"), null, supplier, null, Q.str(plan, "id"),
                        "quality", true, null)));
                out.add(Q.str(lot, "id"));
            } catch (RuntimeException e) {
                log.warn("[quality] incoming lot for GRN {} line {} not opened: {}", grn.get("receiptNumber"),
                        l.get("lineNumber"), e.getMessage());
            }
        }
        return out;
    }

    /** {@code purchase_order.received} carries the receipt number: resolve it to the receipt id. */
    public List<String> openForReceiptNumber(String receiptNumber) {
        String id = tx.execute(s -> db.jdbc().sql("SELECT id FROM \"GoodsReceiptNote\" WHERE \"organizationId\" = ? AND \"receiptNumber\" = ?"
                + " AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" DESC LIMIT 1").params(QualityDb.org(), receiptNumber)
                .query(String.class).optional().orElse(null));
        return id == null ? List.of() : openForGoodsReceipt(id);
    }
}
