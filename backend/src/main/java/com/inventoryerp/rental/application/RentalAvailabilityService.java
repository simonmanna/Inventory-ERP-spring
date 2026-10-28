package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalBooking;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The calendar engine (port of {@code rental-availability.service.ts}): availability = candidate units − overlapping
 * bookings (held / confirmed / active). The window end is extended by the product's {@code rentalBufferDays};
 * products that require cleaning keep their {@code cleaning} units out of the candidate pool.
 */
@Service
public class RentalAvailabilityService {

    private static final String ACTIVE = "status::text IN ('held', 'confirmed', 'active')";

    private final RentalRows rows;
    private final PosDb db;

    public RentalAvailabilityService(RentalRows rows) {
        this.rows = rows;
        this.db = rows.db();
    }

    /** How many units of a product are available in {@code [startAt, endAt)}. */
    @Transactional(readOnly = true)
    public long countAvailable(String productId, Instant startAt, Instant endAt, String excludeSourceId) {
        String orgId = RentalRows.org();
        Map<String, Object> product = rows.product(productId);
        if (product == null) {
            return 0;
        }
        Instant effEnd = effectiveEnd(product, endAt);
        long candidates = candidates(product, orgId);
        StringBuilder where = new StringBuilder("\"organizationId\" = ? AND \"productId\" = ? AND " + ACTIVE
                + " AND \"startAt\" < ? AND \"endAt\" > ?");
        List<Object> params = new java.util.ArrayList<>(List.of(orgId, product.get("id"), SqlRows.ts(effEnd),
                SqlRows.ts(RentalRows.valid(startAt))));
        if (excludeSourceId != null && !excludeSourceId.isEmpty()) {
            // Prisma NOT {sourceId, sourceType}: NOT (a AND b).
            where.append(" AND NOT (\"sourceId\" = ? AND \"sourceType\" = 'agreement')");
            params.add(excludeSourceId);
        }
        long overlap = db.count(RentalBooking.class, where.toString(), params.toArray());
        return Math.max(0, candidates - overlap);
    }

    /** Candidate unit ids for a window — unit-level (serialized) products only. */
    @Transactional(readOnly = true)
    public List<String> candidateUnits(String productId, Instant startAt, Instant endAt) {
        String orgId = RentalRows.org();
        Map<String, Object> product = rows.product(productId);
        if (product == null || Boolean.TRUE.equals(product.get("rentalIsPooled"))) {
            return List.of();
        }
        Instant effEnd = effectiveEnd(product, endAt);
        String statuses = Boolean.TRUE.equals(product.get("rentalRequiresCleaning")) ? "('available', 'repair')"
                : "('available', 'cleaning', 'repair')";
        List<String> unitIds = db.jdbc().sql("SELECT id FROM \"RentalUnit\" WHERE \"organizationId\" = ? AND"
                        + " \"productId\" = ? AND \"deletedAt\" IS NULL AND status::text IN " + statuses)
                .params(orgId, product.get("id")).query(String.class).list();
        Set<String> busy = new HashSet<>(db.jdbc().sql("SELECT \"unitId\" FROM \"RentalBooking\" WHERE"
                        + " \"organizationId\" = ? AND \"productId\" = ? AND " + ACTIVE + " AND \"startAt\" < ? AND"
                        + " \"endAt\" > ? AND \"unitId\" IS NOT NULL")
                .params(orgId, product.get("id"), SqlRows.ts(effEnd), SqlRows.ts(RentalRows.valid(startAt)))
                .query(String.class).list());
        return unitIds.stream().filter(id -> !busy.contains(id)).toList();
    }

    private static Instant effectiveEnd(Map<String, Object> product, Instant endAt) {
        Object buffer = product.get("rentalBufferDays");
        long days = buffer == null ? 0 : ((Number) buffer).longValue();
        return RentalRows.valid(endAt).plus(Duration.ofDays(days));
    }

    /** Unit-status side of the candidates; pooled products have no units (sized by booking qty). */
    private long candidates(Map<String, Object> product, String orgId) {
        if (Boolean.TRUE.equals(product.get("rentalIsPooled"))) {
            return 0;
        }
        String statuses = Boolean.TRUE.equals(product.get("rentalRequiresCleaning")) ? "('available', 'repair')"
                : "('available', 'cleaning', 'repair')";
        return db.jdbc().sql("SELECT count(*) FROM \"RentalUnit\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND status::text IN " + statuses)
                .params(orgId, product.get("id")).query(Long.class).single();
    }
}
