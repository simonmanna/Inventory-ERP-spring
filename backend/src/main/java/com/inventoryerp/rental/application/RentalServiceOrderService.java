package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalServiceOrder;
import com.inventoryerp.rental.domain.RentalUnit;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cleaning / repair / alteration orders on a unit (port of {@code rental-service-order.service.ts}): opening one
 * takes the unit out of the pool (cleaning / repair), completing it puts the unit back to available.
 */
@Service
public class RentalServiceOrderService {

    private static final String BY_ID = "id = ? AND \"organizationId\" = ?";
    private static final String LIVE_UNIT = "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL";

    private final RentalRows rows;
    private final PosDb db;
    private final SequenceService sequence;

    public RentalServiceOrderService(RentalRows rows, SequenceService sequence) {
        this.rows = rows;
        this.db = rows.db();
        this.sequence = sequence;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> q) {
        RentalRows.Paging p = RentalRows.paging(q);
        StringBuilder where = new StringBuilder("\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(RentalRows.org()));
        if (RentalUnitService.notEmpty(q.get("unitId"))) {
            where.append(" AND \"unitId\" = ?");
            params.add(q.get("unitId"));
        }
        if (RentalUnitService.notEmpty(q.get("status"))) {
            where.append(" AND status = ?");
            params.add(q.get("status"));
        }
        long total = db.count(RentalServiceOrder.class, where.toString(), params.toArray());
        params.add(p.limit());
        params.add(p.offset());
        List<Map<String, Object>> items = db.list(RentalServiceOrder.class, where
                + " ORDER BY \"createdAt\" DESC LIMIT ? OFFSET ?", params.toArray());
        RentalRows.with(items, "unit", o -> rows.unitBrief(o.get("unitId"), "id", "unitCode"));
        return RentalRows.envelope(total, p, items);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        String orgId = RentalRows.org();
        Object unitId = dto.get("unitId");
        if (db.first(RentalUnit.class, LIVE_UNIT, unitId, orgId) == null) {
            throw ApiException.notFound("Rental unit not found");
        }
        String number = sequence.next("rental_service_order", "SRV-", 5);
        Map<String, Object> order = db.insert(RentalServiceOrder.class, Obj.of("organizationId", orgId,
                "serviceNumber", number, "unitId", unitId, "type", dto.get("type"), "vendor", dto.get("vendor"),
                "cost", RentalRows.dec(RentalRows.numOr(dto, "cost", 0)), "notes", dto.get("notes")));
        db.update(RentalUnit.class, Obj.of("status", "cleaning".equals(dto.get("type")) ? "cleaning" : "repair"),
                LIVE_UNIT, unitId, orgId);
        return order;
    }

    /** Complete: record the final cost / vendor and move the unit back into the pool. */
    @Transactional
    public Map<String, Object> complete(String id, Map<String, Object> dto) {
        String orgId = RentalRows.org();
        Map<String, Object> order = db.first(RentalServiceOrder.class, BY_ID, id, orgId);
        if (order == null) {
            throw ApiException.notFound("Service order not found");
        }
        if ("completed".equals(order.get("status"))) {
            throw ApiException.badRequest("Service order already completed");
        }
        double cost = dto.get("cost") != null ? RentalRows.num(dto, "cost") : RentalRows.num(order.get("cost"));
        Map<String, Object> updated = db.update(RentalServiceOrder.class, Obj.of("cost", RentalRows.dec(cost),
                "vendor", dto.get("vendor") != null ? dto.get("vendor") : order.get("vendor"),
                "notes", dto.get("notes") != null ? dto.get("notes") : order.get("notes"), "status", "completed",
                "completedAt", PosDb.raw(RentalRows.NOW)), BY_ID, id, orgId);
        db.update(RentalUnit.class, Obj.of("status", "available", "lastInspectedAt", PosDb.raw(RentalRows.NOW)),
                LIVE_UNIT, order.get("unitId"), orgId);
        return updated;
    }

    @Transactional
    public Map<String, Object> cancel(String id) {
        String orgId = RentalRows.org();
        Map<String, Object> order = db.first(RentalServiceOrder.class, BY_ID, id, orgId);
        if (order == null) {
            throw ApiException.notFound("Service order not found");
        }
        if ("completed".equals(order.get("status"))) {
            throw ApiException.badRequest("Completed orders cannot be cancelled");
        }
        return db.update(RentalServiceOrder.class, Obj.of("status", "cancelled"), BY_ID, id, orgId);
    }
}
