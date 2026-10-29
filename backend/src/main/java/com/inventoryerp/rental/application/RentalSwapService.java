package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.workflow.LifecycleService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalAgreement;
import com.inventoryerp.rental.domain.RentalAgreementLine;
import com.inventoryerp.rental.domain.RentalBooking;
import com.inventoryerp.rental.domain.RentalSwap;
import com.inventoryerp.rental.domain.RentalUnit;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mid-hire swap of the physical unit on a line (port of {@code rental-swap.service.ts}): old unit RENT-OUT →
 * RENT-CLEAN (status cleaning), new unit RENT-STOCK → RENT-OUT (checked_out), the booking re-points, and a positive
 * {@code priceDelta} is billed at swap time.
 */
@Service
public class RentalSwapService {

    private final RentalRows rows;
    private final PosDb db;
    private final LifecycleService lifecycle;
    private final RentalPostingService posting;

    public RentalSwapService(RentalRows rows, LifecycleService lifecycle, RentalPostingService posting) {
        this.rows = rows;
        this.db = rows.db();
        this.lifecycle = lifecycle;
        this.posting = posting;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> q) {
        RentalRows.Paging p = RentalRows.paging(q);
        StringBuilder where = new StringBuilder("s.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(RentalRows.org()));
        if (RentalUnitService.notEmpty(q.get("agreementId"))) {
            where.append(" AND s.\"agreementLineId\" IN (SELECT id FROM \"RentalAgreementLine\" WHERE \"agreementId\" = ?)");
            params.add(q.get("agreementId"));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"RentalSwap\" s WHERE " + where).params(params)
                .query(Long.class).single();
        params.add(p.limit());
        params.add(p.offset());
        List<Map<String, Object>> items = SqlRows.list(RentalSwap.class, db.jdbc().sql("SELECT s.* FROM \"RentalSwap\" s"
                + " WHERE " + where + " ORDER BY s.\"createdAt\" DESC, s.id LIMIT ? OFFSET ?").params(params));
        RentalRows.with(items, "agreementLine", s -> {
            Map<String, Object> line = db.first(RentalAgreementLine.class, "id = ?", s.get("agreementLineId"));
            if (line != null) {
                line.put("product", rows.productBrief(line.get("productId")));
            }
            return line;
        });
        return RentalRows.envelope(total, p, items);
    }

    /** Swap the unit on a checked-out line; {@code toUnitId} must be an available unit of the same product. */
    @Transactional
    public Map<String, Object> swap(Map<String, Object> dto) {
        String orgId = RentalRows.org();
        String agreementId = RentalRows.str(dto, "agreementId");
        Map<String, Object> line = db.first(RentalAgreementLine.class, "id = ? AND \"organizationId\" = ?",
                dto.get("lineId"), orgId);
        if (line == null) {
            throw ApiException.notFound("Agreement line not found");
        }
        if (!line.get("agreementId").equals(agreementId)) {
            throw ApiException.badRequest("Line does not belong to the agreement");
        }
        String fromUnitId = (String) line.get("unitId");
        if (fromUnitId == null) {
            throw ApiException.badRequest("Line has no unit to swap");
        }
        String productId = (String) line.get("productId");
        Map<String, Object> toUnit = db.first(RentalUnit.class, "id = ? AND \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"deletedAt\" IS NULL", dto.get("toUnitId"), orgId, productId);
        if (toUnit == null) {
            throw ApiException.badRequest("Target unit not found for this product");
        }
        String toUnitId = (String) toUnit.get("id");
        if (toUnitId.equals(fromUnitId)) {
            throw ApiException.badRequest("Target unit is the current unit");
        }
        if (!"available".equals(toUnit.get("status"))) {
            throw ApiException.badRequest("Target unit is '" + toUnit.get("status") + "', not available");
        }
        Map<String, Object> agreement = db.first(RentalAgreement.class, "id = ?", line.get("agreementId"));
        double delta = RentalRows.numOr(dto, "priceDelta", 0);
        Map<String, Object> swap = db.insert(RentalSwap.class, Obj.of("organizationId", orgId,
                "agreementLineId", line.get("id"), "fromUnitId", fromUnitId, "toUnitId", toUnitId,
                "reason", dto.get("reason"), "priceDelta", RentalRows.dec(delta), "byUserId", RentalRows.user()));
        String swapId = (String) swap.get("id");

        posting.returnTransfer(agreementId, productId, 1, "returned");
        posting.checkoutTransfer(agreementId, productId, line.get("quantity"));

        Map<String, Object> booking = db.first(RentalBooking.class, "\"organizationId\" = ? AND \"sourceType\" ="
                + " 'agreement' AND \"sourceId\" = ? AND \"productId\" = ? AND status IN ('confirmed', 'active')",
                orgId, agreementId, productId);
        if (booking != null) {
            db.update(RentalBooking.class, Obj.of("unitId", toUnitId), "id = ?", booking.get("id"));
        }
        String live = "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL";
        db.update(RentalUnit.class, Obj.of("status", "cleaning"), live, fromUnitId, orgId);
        db.update(RentalUnit.class, Obj.of("status", "checked_out", "rentalCount", PosDb.raw("\"rentalCount\" + 1")),
                live, toUnitId, orgId);
        db.update(RentalAgreementLine.class, Obj.of("unitId", toUnitId), "id = ? AND \"organizationId\" = ?",
                line.get("id"), orgId);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("swapId", swapId);
        meta.put("reason", dto.get("reason"));
        lifecycle.transition(RentalLifecycles.UNIT, "rental_unit", fromUnitId, "checked_out", "cleaning", "swap",
                "rental_swap", swapId, meta);
        lifecycle.transition(RentalLifecycles.UNIT, "rental_unit", toUnitId, "available", "checked_out", "swap",
                "rental_swap", swapId, meta);

        if (delta > 0) {
            Map<String, Object> fee = posting.feeProduct("RENT-EXTEND");
            if (fee != null) {
                posting.createFeeInvoice(agreementId, (String) agreement.get("partnerId"),
                        (String) agreement.get("branchId"), "rental",
                        List.of(new RentalPostingService.FeeLine((String) fee.get("id"), "Swap rate delta — "
                                + agreement.get("agreementNumber"), 1, delta, "rental")),
                        RentalRows.str(dto, "paymentMode"));
            }
        }
        return swap;
    }
}
