package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.StockService.NOW;

import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.inventory.domain.StockReservation;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
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
 *
 * <p>The unscoped methods are the engine API other modules (sales, POS, manufacturing) call inside their own flows.
 * The {@code ...ForUser} methods and {@link #overview} serve the {@code /inventory/reservations} routes and apply the
 * caller's branch scope (R2): a restricted user reserves, releases, consumes and sees only their locations' rows.
 */
@Service
public class StockReservationService {

    /** {@code ReserveDto}. */
    public record Reserve(String productId, String variantId, String locationId, BigDecimal quantity,
            String sourceType, String sourceId, String reason) {}

    /** The most rows {@code GET /inventory/reservations} returns when it is not keyed by one source. */
    static final int LIST_MAX = 200;

    private final JdbcClient jdbc;
    private final SettingResolverService settings;
    private final LocationScope scope;
    private final InventoryLookups lookups;

    public StockReservationService(JdbcClient jdbc, SettingResolverService settings, LocationScope scope,
            InventoryLookups lookups) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.scope = scope;
        this.lookups = lookups;
    }

    /** {@code none | order | invoice}. */
    @Transactional(readOnly = true)
    public String mode() {
        return settings.resolveString("inventory.reservationMode", SettingContext.EMPTY);
    }

    /**
     * Reserve (idempotent per source line) under the quant lock: the engine's lock order (costing locks, the quant's
     * advisory lock, then the {@code StockItem} row; hardening R5b), so a reservation never holds the row while a stock
     * move holding the advisory lock waits for it. A caller reserving several lines feeds them in lock order
     * ({@link StockMove#inLockOrder}).
     */
    @Transactional
    public Map<String, Object> reserve(Reserve dto) {
        String org = TenantContext.organizationId();
        String variantKey = dto.variantId() == null ? "" : dto.variantId();
        StockService.lockCellAdvisory(jdbc, org, dto.productId(), dto.variantId(), dto.locationId());
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

    /**
     * {@link #reserve} for a caller that keeps a reservation failure soft (production confirm) and runs it under a
     * savepoint in its own transaction rather than in a new one: the reservation takes the quant's advisory lock, which
     * the caller's transaction may already hold, so a separate transaction would wait for it forever. A failure does
     * not mark the caller's transaction rollback-only.
     */
    @Transactional(noRollbackFor = RuntimeException.class)
    public Map<String, Object> reserveSoft(Reserve dto) {
        return reserve(dto);
    }

    /**
     * Hardening R5b (P2-1): lock one stock cell in the engine's order (costing locks, quant advisory lock, then the
     * {@code StockItem} row) for a caller that measures free stock before it reserves (sales ATP). Locks last until the
     * caller's transaction ends.
     */
    @Transactional
    public void lockCell(String productId, String variantId, String locationId) {
        String org = TenantContext.organizationId();
        StockService.lockCellAdvisory(jdbc, org, productId, variantId, locationId);
        jdbc.sql("SELECT id FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ?"
                        + " AND \"locationId\" = ? FOR UPDATE")
                .params(org, productId, variantId == null ? "" : variantId, locationId).query().listOfRows();
    }

    /** Source type of sales-order line reservations (written by the sales module's ATP service). */
    public static final String SALES_ORDER_LINE = "sales_order_line";

    /**
     * IPR F5: an issue of {@code baseQuantity} (base units) from one stock cell by a document that is not the sales
     * order holding the stock (an invoice, a cash sale, a delivery order that does not come from a sales order) must
     * leave what active sales-order reservations hold there. The cell is locked first, in the engine's order (L3 → L4 →
     * L5), so the measure stays true until the issue in the same transaction; a reservation made meanwhile waits.
     * Refused only when sales-order reservations exist at the cell and the issue would eat into them; without
     * reservations the engine's own negative-stock policy decides, as before.
     */
    @Transactional
    public void assertNotReservedForSalesOrders(String productId, String variantId, String locationId,
            BigDecimal baseQuantity) {
        if (productId == null || locationId == null || baseQuantity == null || baseQuantity.signum() <= 0) {
            return;
        }
        String org = TenantContext.organizationId();
        String variantKey = variantId == null ? "" : variantId;
        StockService.lockCellAdvisory(jdbc, org, productId, variantId, locationId);
        jdbc.sql("SELECT id FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ?"
                        + " AND \"locationId\" = ? FOR UPDATE")
                .params(org, productId, variantKey, locationId).query().listOfRows();
        BigDecimal reserved = jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockReservation\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ? AND status ="
                + " 'active' AND \"sourceType\" = ?").params(org, productId, variantKey, locationId, SALES_ORDER_LINE)
                .query(BigDecimal.class).single();
        if (reserved.signum() <= 0) {
            return;
        }
        BigDecimal onHand = jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?").params(org, productId,
                        variantKey, locationId).query(BigDecimal.class).single();
        BigDecimal free = onHand.subtract(reserved).max(BigDecimal.ZERO);
        if (baseQuantity.compareTo(free) > 0) {
            String name = jdbc.sql("SELECT name FROM \"Product\" WHERE id = ?").param(productId).query(String.class)
                    .optional().orElse(productId);
            String location = jdbc.sql("SELECT name FROM \"InventoryLocation\" WHERE id = ?").param(locationId)
                    .query(String.class).optional().orElse(locationId);
            throw ApiException.badRequest("Only " + JsNumbers.dec(free) + " of " + name + " is free in " + location
                    + "; " + JsNumbers.dec(reserved) + " is reserved for sales orders. Release or deliver those"
                    + " reservations first.");
        }
    }

    /**
     * {@code POST /inventory/reservations}: {@link #reserve} after checking that the product and location exist and
     * that the location is in the caller's scope. A source line's row is keyed by its cell, so the only row a re-reserve
     * can overwrite is one at a location the caller may use.
     */
    @Transactional
    public Map<String, Object> reserveForUser(Reserve dto) {
        if (lookups.product(dto.productId()) == null) {
            throw ApiException.notFound("Product not found");
        }
        lookups.requireLocation(dto.locationId());
        scope.assertAllowed(dto.locationId());
        return reserve(dto);
    }

    /**
     * Release a source's active reservations on every cell other than ({@code productId}, {@code variantId},
     * {@code locationId}): the source line moved to another product, variant or location, so its old cell must not
     * stay reserved (review N2). Returns how many were released.
     */
    @Transactional
    public int releaseOtherCells(String sourceType, String sourceId, String productId, String variantId,
            String locationId) {
        return jdbc.sql("UPDATE \"StockReservation\" SET status = 'released'::\"StockReservationStatus\", \"releasedAt\" = "
                + NOW + ", \"updatedAt\" = " + NOW + " WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ?"
                + " AND status = 'active' AND NOT (\"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" IS NOT DISTINCT"
                + " FROM ?)").params(TenantContext.organizationId(), sourceType, sourceId, productId,
                        variantId == null ? "" : variantId, locationId).update();
    }

    /** Release every active reservation of a source. */
    @Transactional
    public Map<String, Object> release(String sourceType, String sourceId) {
        return Obj.of("released", close(sourceType, sourceId, "released", "releasedAt"));
    }

    /**
     * {@link #release} for a caller that keeps a release failure soft and runs it under a savepoint in its own
     * transaction (production cancel). Integrity fix 2026-11-24 (MR-9): a failure does not mark the caller's
     * transaction rollback-only — through {@link #release} it did, so the caller's commit failed although it had
     * caught the error.
     */
    @Transactional(noRollbackFor = RuntimeException.class)
    public Map<String, Object> releaseSoft(String sourceType, String sourceId) {
        return release(sourceType, sourceId);
    }

    /** Mark a source's reservations consumed. */
    @Transactional
    public Map<String, Object> consume(String sourceType, String sourceId) {
        return Obj.of("consumed", close(sourceType, sourceId, "consumed", "consumedAt"));
    }

    /** {@code POST /inventory/reservations/release}: {@link #release} limited to the caller's locations. */
    @Transactional
    public Map<String, Object> releaseForUser(String sourceType, String sourceId) {
        return Obj.of("released", close(sourceType, sourceId, "released", "releasedAt", scope.current()));
    }

    /** {@code POST /inventory/reservations/consume}: {@link #consume} limited to the caller's locations. */
    @Transactional
    public Map<String, Object> consumeForUser(String sourceType, String sourceId) {
        return Obj.of("consumed", close(sourceType, sourceId, "consumed", "consumedAt", scope.current()));
    }

    private int close(String sourceType, String sourceId, String status, String stamp) {
        return close(sourceType, sourceId, status, stamp, DataScope.OPEN);
    }

    private int close(String sourceType, String sourceId, String status, String stamp, DataScope access) {
        List<Object> params = new ArrayList<>(List.of(status));
        String where = filters(sourceType, sourceId, params) + locationFilter("\"locationId\"", access, params);
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

    /** {@code AND <column> = ANY(<allowed>)} for a restricted user, nothing for an unrestricted one. */
    private static String locationFilter(String column, DataScope access, List<Object> params) {
        if (!access.restricted()) {
            return "";
        }
        params.add(access.locationArray());
        return " AND " + column + " = ANY(?::text[])";
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
     * {@code GET /inventory/reservations}: a source's reservations, newest first, limited to the caller's locations.
     * Keyed by both {@code sourceType} and {@code sourceId} it returns every row of that source; otherwise it is paged
     * ({@code page}, {@code pageSize}, at most {@value #LIST_MAX} rows) so an unkeyed call cannot dump the table.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForUser(String sourceType, String sourceId, String page, String pageSize) {
        List<Object> params = new ArrayList<>();
        String where = filters(sourceType, sourceId, params) + locationFilter("\"locationId\"", scope.current(), params);
        String paging = "";
        if (sourceType == null || sourceId == null) {
            int size = Math.min(LIST_MAX, Math.max(1, intParam(pageSize, LIST_MAX)));
            int number = Math.max(1, intParam(page, 1));
            paging = " LIMIT ? OFFSET ?";
            params.add(size);
            params.add((number - 1) * size);
        }
        return SqlRows.list(StockReservation.class, jdbc.sql("SELECT * FROM \"StockReservation\" WHERE" + where
                + " ORDER BY \"createdAt\" DESC, id" + paging).params(params));
    }

    /**
     * The reservations screen (INV-1): reservations with product and location names and the cell's on-hand quantity,
     * filtered by {@code status}, {@code productId}, {@code locationId}, {@code sourceType} and search {@code q}. A
     * restricted user sees only their locations' rows, and a {@code locationId} filter outside them is refused (R2).
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> overview(Map<String, String> q) {
        StringBuilder where = new StringBuilder(" WHERE r.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        DataScope access = scope.current();
        if (present(q.get("locationId"))) {
            scope.assertAllowed(q.get("locationId"));
        }
        where.append(locationFilter("r.\"locationId\"", access, params));
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
