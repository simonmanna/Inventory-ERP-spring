package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.workflow.LifecycleService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalAgreement;
import com.inventoryerp.rental.domain.RentalAgreementLine;
import com.inventoryerp.rental.domain.RentalBooking;
import com.inventoryerp.rental.domain.RentalExtension;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-line due-date extensions (port of {@code rental-extension.service.ts}): the new window must be free of every
 * OTHER booking, the line's booking and due date shift, and the fee (RENT-EXTEND) is billed at extend time.
 */
@Service
public class RentalExtensionService {

    private final RentalRows rows;
    private final PosDb db;
    private final LifecycleService lifecycle;
    private final RentalAvailabilityService availability;
    private final RentalPostingService posting;

    public RentalExtensionService(RentalRows rows, LifecycleService lifecycle, RentalAvailabilityService availability,
            RentalPostingService posting) {
        this.rows = rows;
        this.db = rows.db();
        this.lifecycle = lifecycle;
        this.availability = availability;
        this.posting = posting;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> q) {
        RentalRows.Paging p = RentalRows.paging(q);
        StringBuilder where = new StringBuilder("e.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(RentalRows.org()));
        if (RentalUnitService.notEmpty(q.get("agreementId"))) {
            where.append(" AND e.\"agreementLineId\" IN (SELECT id FROM \"RentalAgreementLine\" WHERE \"agreementId\" = ?)");
            params.add(q.get("agreementId"));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"RentalExtension\" e WHERE " + where).params(params)
                .query(Long.class).single();
        params.add(p.limit());
        params.add(p.offset());
        List<Map<String, Object>> items = SqlRows.list(RentalExtension.class, db.jdbc().sql("SELECT e.* FROM"
                + " \"RentalExtension\" e WHERE " + where + " ORDER BY e.\"createdAt\" DESC, e.id LIMIT ? OFFSET ?")
                .params(params));
        RentalRows.with(items, "agreementLine", e -> lineWithBriefs(e.get("agreementLineId")));
        return RentalRows.envelope(total, p, items);
    }

    /** {@code agreementLine: {include: {product: {select}, unit: {select: {id, unitCode}}}}}. */
    Map<String, Object> lineWithBriefs(Object lineId) {
        Map<String, Object> line = db.first(RentalAgreementLine.class, "id = ?", lineId);
        if (line != null) {
            line.put("product", rows.productBrief(line.get("productId")));
            line.put("unit", rows.unitBrief(line.get("unitId"), "id", "unitCode"));
        }
        return line;
    }

    /** Extend a checked-out line to the absolute {@code newDueAt}, billed at the line's rate. */
    @Transactional
    public Map<String, Object> extend(Map<String, Object> dto) {
        String orgId = RentalRows.org();
        Instant newDueAt = RentalRows.date(dto.get("newDueAt"));
        String agreementId = RentalRows.str(dto, "agreementId");
        Map<String, Object> line = db.first(RentalAgreementLine.class, "id = ? AND \"organizationId\" = ?",
                dto.get("lineId"), orgId);
        if (line == null) {
            throw ApiException.notFound("Agreement line not found");
        }
        if (!line.get("agreementId").equals(agreementId)) {
            throw ApiException.badRequest("Line does not belong to the agreement");
        }
        Instant dueAt = (Instant) line.get("dueAt");
        if (newDueAt == null || !newDueAt.isAfter(dueAt)) {
            // An Invalid Date compares false both ways: Nest falls through to Prisma (500).
            if (newDueAt == null) {
                throw new IllegalStateException("Invalid value for argument `endAt`: Provided Date object is invalid.");
            }
            throw ApiException.badRequest("newDueAt must be after the current due date");
        }
        String productId = (String) line.get("productId");
        double quantity = RentalRows.num(line.get("quantity"));
        if (availability.countAvailable(productId, dueAt, newDueAt, agreementId) < quantity) {
            throw ApiException.badRequest("Requested extension overlaps an existing booking");
        }
        Map<String, Object> agreement = db.first(RentalAgreement.class, "id = ?", line.get("agreementId"));
        String period = (String) line.get("ratePeriod");
        int units = RentalMath.calcUnits(dueAt, newDueAt, period);
        double amount = RentalRows.num(line.get("unitRate")) * units * quantity;

        Map<String, Object> ext = db.insert(RentalExtension.class, Obj.of("organizationId", orgId,
                "agreementLineId", line.get("id"), "previousDueAt", dueAt, "newDueAt", newDueAt,
                "additionalUnits", units, "additionalCharge", RentalRows.dec(amount),
                "approvedById", RentalRows.user()));
        Map<String, Object> booking = db.first(RentalBooking.class, "\"organizationId\" = ? AND \"sourceType\" ="
                + " 'agreement' AND \"sourceId\" = ? AND \"productId\" = ? AND status IN ('confirmed', 'active')",
                orgId, agreementId, productId);
        if (booking != null) {
            db.update(RentalBooking.class, Obj.of("endAt", newDueAt), "id = ?", booking.get("id"));
        }
        db.update(RentalAgreementLine.class, Obj.of("dueAt", newDueAt), "id = ? AND \"organizationId\" = ?",
                line.get("id"), orgId);

        Map<String, Object> fee = posting.feeProduct("RENT-EXTEND");
        if (fee != null && amount > 0) {
            posting.createFeeInvoice(agreementId, (String) agreement.get("partnerId"),
                    (String) agreement.get("branchId"), "extension",
                    List.of(new RentalPostingService.FeeLine((String) fee.get("id"), "Extension (" + units + " " + period
                            + ") — " + agreement.get("agreementNumber"), 1, amount, "extension")),
                    RentalRows.str(dto, "paymentMode"));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("extensionId", ext.get("id"));
        meta.put("previousDueAt", dueAt);
        meta.put("newDueAt", newDueAt);
        Object unitId = line.get("unitId");
        lifecycle.transition(RentalLifecycles.UNIT, "rental_unit", (String) (unitId != null ? unitId : line.get("id")),
                "checked_out", "checked_out", "extend", "rental_extension", (String) ext.get("id"), meta);
        return ext;
    }
}
