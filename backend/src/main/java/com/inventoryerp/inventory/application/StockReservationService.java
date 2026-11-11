package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.StockService.NOW;

import com.inventoryerp.inventory.domain.StockReservation;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soft stock reservations → available-to-promise (port of {@code stock-reservation.service.ts}). A reservation
 * holds quantity for a source document without touching the ledger; re-reserving a line sets its quantity.
 * Absent filters are dropped like Prisma drops {@code undefined} where keys.
 */
@Service
public class StockReservationService {

    /** {@code ReserveDto}. */
    public record Reserve(String productId, String variantId, String locationId, BigDecimal quantity,
            String sourceType, String sourceId, String reason) {}

    private final JdbcClient jdbc;
    private final SettingResolverService settings;

    public StockReservationService(JdbcClient jdbc, SettingResolverService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** {@code none | order | invoice}. */
    @Transactional(readOnly = true)
    public String mode() {
        return settings.resolveString("inventory.reservationMode", SettingContext.EMPTY);
    }

    /** Reserve (idempotent per source line) under the StockItem row lock. */
    @Transactional
    public Map<String, Object> reserve(Reserve dto) {
        String org = TenantContext.organizationId();
        String variantKey = dto.variantId() == null ? "" : dto.variantId();
        jdbc.sql("SELECT id FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ?"
                        + " AND \"locationId\" = ? FOR UPDATE")
                .params(org, dto.productId(), variantKey, dto.locationId()).query().listOfRows();
        // One active reservation per source line and cell (partial unique index, V460): concurrent re-reserves of a
        // line converge on that row (its quantity is set) instead of inserting a duplicate.
        return SqlRows.one(StockReservation.class, jdbc.sql("INSERT INTO \"StockReservation\" (id, \"organizationId\","
                        + " \"productId\", \"variantId\", \"variantKey\", \"locationId\", quantity, \"sourceType\", \"sourceId\","
                        + " reason, \"createdById\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + NOW + ", " + NOW + ") ON CONFLICT (\"organizationId\", \"productId\", \"variantKey\", \"locationId\","
                        + " \"sourceType\", \"sourceId\") WHERE status = 'active' DO UPDATE SET quantity = EXCLUDED.quantity,"
                        + " reason = COALESCE(EXCLUDED.reason, \"StockReservation\".reason), \"updatedAt\" = " + NOW
                        + " RETURNING *")
                .params(UUID.randomUUID().toString(), org, dto.productId(), dto.variantId(), variantKey,
                        dto.locationId(), dto.quantity(), dto.sourceType(), dto.sourceId(), dto.reason(),
                        TenantContext.userId()));
    }

    /** Release every active reservation of a source. */
    @Transactional
    public Map<String, Object> release(String sourceType, String sourceId) {
        return Obj.of("released", close(sourceType, sourceId, "released", "releasedAt"));
    }

    /** Mark a source's reservations consumed. */
    @Transactional
    public Map<String, Object> consume(String sourceType, String sourceId) {
        return Obj.of("consumed", close(sourceType, sourceId, "consumed", "consumedAt"));
    }

    private int close(String sourceType, String sourceId, String status, String stamp) {
        List<Object> params = new ArrayList<>(List.of(status));
        String where = filters(sourceType, sourceId, params);
        return jdbc.sql("UPDATE \"StockReservation\" SET status = ?::\"StockReservationStatus\", \"" + stamp + "\" = "
                + NOW + ", \"updatedAt\" = " + NOW + " WHERE" + where + " AND status = 'active'").params(params).update();
    }

    private static String filters(String sourceType, String sourceId, List<Object> params) {
        StringBuilder sb = new StringBuilder(" \"organizationId\" = ?");
        params.add(TenantContext.organizationId());
        if (sourceType != null) {
            sb.append(" AND \"sourceType\" = ?");
            params.add(sourceType);
        }
        if (sourceId != null) {
            sb.append(" AND \"sourceId\" = ?");
            params.add(sourceId);
        }
        return sb.toString();
    }

    /** Sum of active reservations for a stock line. */
    @Transactional(readOnly = true)
    public BigDecimal reservedQty(String productId, String locationId, String variantId) {
        List<Object> params = new ArrayList<>();
        String where = cell(productId, locationId, variantId, params);
        return jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockReservation\" WHERE" + where
                + " AND status = 'active'").params(params).query(BigDecimal.class).single();
    }

    /**
     * on-hand − active reservations of one product (and variant): at one location, or summed over all locations when
     * none is given — the same cells on both sides.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> availableToPromise(String productId, String locationId, String variantId) {
        if (productId == null || productId.isBlank()) {
            throw com.inventoryerp.shared.web.ApiException.badRequest("productId is required");
        }
        List<Object> params = new ArrayList<>();
        String where = cell(productId, locationId, variantId, params);
        BigDecimal onHand = jdbc.sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE" + where).params(params)
                .query(BigDecimal.class).single();
        BigDecimal reserved = reservedQty(productId, locationId, variantId);
        return Obj.of("onHand", JsNumbers.dec(onHand), "reserved", JsNumbers.dec(reserved), "available",
                JsNumbers.dec(onHand.subtract(reserved)));
    }

    private static String cell(String productId, String locationId, String variantId, List<Object> params) {
        StringBuilder sb = new StringBuilder(" \"organizationId\" = ? AND \"variantKey\" = ?");
        params.add(TenantContext.organizationId());
        params.add(variantId == null ? "" : variantId);
        if (productId != null) {
            sb.append(" AND \"productId\" = ?");
            params.add(productId);
        }
        if (locationId != null) {
            sb.append(" AND \"locationId\" = ?");
            params.add(locationId);
        }
        return sb.toString();
    }

    /** Reservations of a source, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForSource(String sourceType, String sourceId) {
        List<Object> params = new ArrayList<>();
        String where = filters(sourceType, sourceId, params);
        return SqlRows.list(StockReservation.class, jdbc.sql("SELECT * FROM \"StockReservation\" WHERE" + where
                + " ORDER BY \"createdAt\" DESC").params(params));
    }

    /**
     * The reservations screen (INV-1): reservations with product and location names and the cell's on-hand quantity,
     * filtered by {@code status}, {@code productId}, {@code locationId}, {@code sourceType} and search {@code q}.
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> overview(Map<String, String> q) {
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        if (present(q.get("status"))) {
            where.append(" AND r.status::text = ?");
            params.add(q.get("status"));
        }
        if (present(q.get("productId"))) {
            where.append(" AND r.\"productId\" = ?");
            params.add(q.get("productId"));
        }
        if (present(q.get("locationId"))) {
            where.append(" AND r.\"locationId\" = ?");
            params.add(q.get("locationId"));
        }
        if (present(q.get("sourceType"))) {
            where.append(" AND r.\"sourceType\" = ?");
            params.add(q.get("sourceType"));
        }
        if (present(q.get("q"))) {
            String like = "%" + BaseCrudService.escapeLike(q.get("q").trim()) + "%";
            where.append(" AND (p.code ILIKE ? OR p.name ILIKE ? OR r.\"sourceId\" ILIKE ? OR r.reason ILIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        int page = Math.max(1, intParam(q.get("page"), 1));
        int pageSize = Math.min(200, Math.max(1, intParam(q.get("pageSize"), 25)));
        String from = " FROM \"StockReservation\" r LEFT JOIN \"Product\" p ON p.id = r.\"productId\"";
        long total = jdbc.sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> paged = new ArrayList<>(params);
        paged.add(pageSize);
        paged.add((page - 1) * pageSize);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT r.*, p.code AS \"productCode\", p.name AS"
                + " \"productName\", l.code AS \"locationCode\", l.name AS \"locationName\", COALESCE(s.quantity, 0) AS"
                + " \"onHand\"" + from + " LEFT JOIN \"InventoryLocation\" l ON l.id = r.\"locationId\" LEFT JOIN"
                + " \"StockItem\" s ON s.\"organizationId\" = r.\"organizationId\" AND s.\"productId\" ="
                + " r.\"productId\""
                + " AND s.\"variantKey\" = r.\"variantKey\" AND s.\"locationId\" = r.\"locationId\"" + where
                + " ORDER BY r.\"createdAt\" DESC, r.id LIMIT ? OFFSET ?").params(paged));
        return PageResult.of(rows, page, pageSize, total);
    }

    private static boolean present(String v) {
        return v != null && !v.isBlank();
    }

    private static int intParam(String v, int fallback) {
        try {
            return present(v) ? Integer.parseInt(v.trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
