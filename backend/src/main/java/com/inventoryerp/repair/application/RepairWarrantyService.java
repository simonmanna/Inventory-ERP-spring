package com.inventoryerp.repair.application;

import static com.inventoryerp.repair.application.RepairDb.dateOrNull;
import static com.inventoryerp.repair.application.RepairDb.nz;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.repair.domain.RepairOrder;
import com.inventoryerp.repair.domain.RepairWarranty;
import com.inventoryerp.repair.domain.RepairWarrantyClaim;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Warranties issued with repairs (or tracked for an asset) and their claim workflow (port of
 * {@code repair-warranty.service.ts}). Settlement of a claim is a manual service-desk decision.
 */
@Service
@ConditionalOnProperty(name = "app.modules.repair", havingValue = "true")
public class RepairWarrantyService {

    private static final List<String> CLAIM_STATUSES = List.of("pending", "approved", "rejected", "settled");

    private final RepairDb db;

    public RepairWarrantyService(RepairDb db) {
        this.db = db;
    }

    public List<Map<String, Object>> listWarranties(Map<String, String> query) {
        List<Map<String, Object>> rows = db.where(RepairWarranty.class)
                .eqIf("status", query.get("status"))
                .eqIf("repairOrderId", query.get("repairOrderId"))
                .eqIf("warrantyType", query.get("warrantyType"))
                .list("\"createdAt\" DESC", RepairDb.take(query.get("take"), 50, 200),
                        RepairDb.skip(query.get("skip")));
        for (Map<String, Object> w : rows) {
            w.put("claims", db.many(RepairWarrantyClaim.class, "warrantyId", w.get("id"), "\"claimDate\" DESC"));
        }
        return rows;
    }

    /**
     * Create a warranty; {@code warrantyPeriodMonths} without an explicit end derives {@code coverageEnd} from the
     * start (default now). A linked repair order records the period and expiry. Both writes commit together: in
     * Nest an unknown / foreign {@code repairOrderId} failed the order update (500) after the warranty was created;
     * here the warranty rolls back with it.
     */
    public Map<String, Object> createWarranty(Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Instant coverageStart = dateOrNull(dto.get("coverageStart"));
        Instant coverageEnd = dateOrNull(dto.get("coverageEnd"));
        if (PosSql.truthy(dto.get("warrantyPeriodMonths")) && coverageEnd == null) {
            Instant start = coverageStart != null ? coverageStart : Instant.now();
            coverageStart = start;
            coverageEnd = addMonths(start, (long) PosSql.num(dto.get("warrantyPeriodMonths")));
        }
        Object repairOrderId = dto.get("repairOrderId");
        Instant start = coverageStart;
        Instant end = coverageEnd;
        return db.inTx(() -> {
            Map<String, Object> warranty = db.insert(RepairWarranty.class, Obj.of("organizationId", RepairDb.org(),
                    "repairOrderId", repairOrderId, "assetId", dto.get("assetId"),
                    "warrantyType", nz(dto.get("warrantyType"), "repair_warranty"), "coverageStart", start,
                    "coverageEnd", end, "coveredParts", nz(dto.get("coveredParts"), List.of()),
                    "coveredLabour", nz(dto.get("coveredLabour"), true), "terms", dto.get("terms")));
            if (PosSql.truthy(repairOrderId)) {
                db.update(RepairOrder.class, repairOrderId.toString(), Obj.of(
                        "warrantyPeriodMonths", nz(dto.get("warrantyPeriodMonths"), 0), "warrantyExpiresAt", end));
            }
            return warranty;
        });
    }

    public Map<String, Object> updateWarranty(String id, Map<String, Object> dto) {
        if (db.byId(RepairWarranty.class, id) == null) {
            throw ApiException.notFound("Record not found");
        }
        return db.update(RepairWarranty.class, id, db.spread(RepairWarranty.class, dto));
    }

    // ─── claims ───

    public Map<String, Object> createClaim(String warrantyId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        String orgId = RepairDb.org();
        Map<String, Object> warranty = db.byId(RepairWarranty.class, warrantyId);
        if (warranty == null) {
            throw ApiException.notFound("Warranty not found");
        }
        if ("expired".equals(warranty.get("status"))) {
            throw ApiException.badRequest("Warranty is expired — claim not accepted");
        }
        Instant claimDate = PosSql.truthy(dto.get("claimDate")) ? RepairDb.jsDate(dto.get("claimDate")) : Instant.now();
        Map<String, Object> claim = db.inTx(() -> {
            Map<String, Object> created = db.insert(RepairWarrantyClaim.class, Obj.of("organizationId", orgId,
                    "warrantyId", warrantyId, "claimDate", claimDate, "description", dto.get("description"),
                    "amount", nz(dto.get("amount"), 0), "status", "pending"));
            db.update(RepairWarranty.class, warrantyId, Obj.of("status", "claimed"));
            return created;
        });
        db.notify(orgId, "Warranty claim filed", RepairDb.s(warranty.get("warrantyType")) + " — "
                        + RepairDb.s(nz(dto.get("description"), "no details")),
                Obj.of("warrantyId", warrantyId, "claimId", claim.get("id")));
        return claim;
    }

    /** Resolve a claim; a rejected claim puts the warranty back to {@code active}. */
    public Map<String, Object> resolveClaim(String claimId, Map<String, Object> dtoIn) {
        Map<String, Object> dto = RepairDb.body(dtoIn);
        Map<String, Object> claim = db.byId(RepairWarrantyClaim.class, claimId);
        if (claim == null) {
            throw ApiException.notFound("Warranty claim not found");
        }
        Object status = dto.get("status");
        if (!(status instanceof String s) || !CLAIM_STATUSES.contains(s)) {
            throw ApiException.badRequest("Invalid claim status: " + (dto.containsKey("status") ? RepairDb.s(status)
                    : "undefined"));
        }
        return db.inTx(() -> {
            Map<String, Object> updated = db.update(RepairWarrantyClaim.class, claimId, Obj.of("status", status,
                    "resolution", dto.get("resolution")));
            if ("rejected".equals(status)) {
                db.update(RepairWarranty.class, (String) claim.get("warrantyId"), Obj.of("status", "active"));
            }
            return updated;
        });
    }

    /**
     * JS {@code d.setMonth(d.getMonth() + months)} on a UTC clock: the day of month is kept and overflows into the
     * following month when the target month is shorter (Jan 31 + 1 → Mar 3).
     */
    static Instant addMonths(Instant start, long months) {
        LocalDateTime t = LocalDateTime.ofInstant(start, ZoneOffset.UTC);
        YearMonth target = YearMonth.from(t).plusMonths(months);
        int day = t.getDayOfMonth();
        LocalDateTime base = t.withDayOfMonth(1).withYear(target.getYear()).withMonth(target.getMonthValue());
        return base.plusDays(day - 1L).toInstant(ZoneOffset.UTC);
    }
}
