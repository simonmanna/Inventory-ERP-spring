package com.inventoryerp.inventory.application;

import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockMove.Received;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct stock in / out (port of {@code direct-stock.service.ts}): immediately-posting movements without a
 * document, attributed to a responsible person and (while the approval setting is on) a provable approver.
 */
@Service
public class DirectStockService {

    /** One direct-stock line. */
    public record Line(String productId, String variantId, BigDecimal quantity, BigDecimal unitCost,
            String batchNumber, String expiryDate, String distStrategy, String notes) {}

    /** Request body common to in and out. */
    public record Request(String locationId, String responsibleById, String approvedById, String approverPin,
            List<Line> items, String notes) {}

    private final JdbcClient jdbc;
    private final InventoryLookups lookups;
    private final SequenceService seq;
    private final StockService stock;
    private final SettingResolverService settings;
    private final StaffAttribution staff;
    private final LocationScope scope;

    public DirectStockService(JdbcClient jdbc, InventoryLookups lookups, SequenceService seq, StockService stock,
            SettingResolverService settings, StaffAttribution staff, LocationScope scope) {
        this.scope = scope;
        this.jdbc = jdbc;
        this.lookups = lookups;
        this.seq = seq;
        this.stock = stock;
        this.settings = settings;
        this.staff = staff;
    }

    private void assertAttribution(Request dto, boolean approvalNeeded) {
        String org = TenantContext.organizationId();
        staff.assertActiveStaff(org, StaffAttribution.people(dto.responsibleById(), dto.approvedById()));
        if (!approvalNeeded) {
            return;
        }
        staff.assertDirectStockApproval(org, TenantContext.userId(), TenantContext.permissions(), dto.approvedById(),
                dto.approverPin());
    }

    /** {@link #directIn} from a user's screen (BR-1); offline sync calls {@link #directIn} and is not scoped. */
    @Transactional
    public Map<String, Object> directInChecked(Request dto) {
        scope.assertAllowed(dto.locationId());
        return directIn(dto);
    }

    /** {@link #directOut} from a user's screen (BR-1). */
    @Transactional
    public Map<String, Object> directOutChecked(Request dto) {
        scope.assertAllowed(dto.locationId());
        return directOut(dto);
    }

    /** Direct stock-in: a stock gain (Dr Stock Valuation / Cr Inventory Adjustment). */
    @Transactional
    public Map<String, Object> directIn(Request dto) {
        lookups.requireLocation(dto.locationId());
        boolean approvalNeeded = settings.resolveBool("inventory.stockInApprovalNeeded",
                new SettingContext(null, null, dto.locationId()));
        assertAttribution(dto, approvalNeeded);
        Map<String, Map<String, Object>> products = products(dto.items());
        for (Line item : dto.items()) {
            Map<String, Object> prod = products.get(item.productId());
            if (prod == null) {
                throw ApiException.notFound("Product " + item.productId() + " not found");
            }
            if (Boolean.TRUE.equals(prod.get("batchTracking")) && item.batchNumber() == null) {
                throw ApiException.badRequest("Batch number required for batch-tracked product \"" + prod.get("name")
                        + "\"");
            }
            if (Boolean.TRUE.equals(prod.get("expiryTracking")) && item.expiryDate() == null) {
                throw ApiException.badRequest("Expiry date required for expiry-tracked product \"" + prod.get("name")
                        + "\"");
            }
        }
        String code = seq.next("direct_stock_in", "DSI-", 5);
        BigDecimal total = Money.ZERO;
        // Post in the engine's lock order (product, variant) so concurrent documents cannot deadlock; answer in the
        // entered order.
        List<Line> ordered = new ArrayList<>(dto.items());
        ordered.sort(Comparator.comparing(Line::productId)
                .thenComparing(l -> l.variantId() == null ? "" : l.variantId()));
        Map<Line, Map<String, Object>> byLine = new java.util.IdentityHashMap<>();
        for (Line item : ordered) {
            StockMove m = StockMove.of(item.productId(), dto.locationId(), item.quantity()).variant(item.variantId())
                    .unitCost(item.unitCost()).batch(item.batchNumber()).expiry(item.expiryDate())
                    .source("direct_stock_in", code).notes(item.notes() != null ? item.notes() : dto.notes())
                    .people(dto.responsibleById(), dto.approvedById());
            Received res = stock.receiveForDocument(m,
                    StockService.GlReceiptContext.stockGain("direct_stock_in", code, Instant.now()));
            BigDecimal uc = res.unitCost() == null ? Money.ZERO : res.unitCost();
            total = total.add(uc.multiply(item.quantity()));
            byLine.put(item, Obj.of("productId", item.productId(), "quantity", JsNumbers.js(item.quantity()),
                    "unitCost", JsNumbers.dec(uc)));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        dto.items().forEach(item -> results.add(byLine.get(item)));
        return Obj.of("code", code, "type", "IN", "locationId", dto.locationId(), "totalValue", JsNumbers.js(total),
                "items", results, "notes", dto.notes(), "timestamp", Instant.now());
    }

    /** Direct stock-out: a plain issue, refused when short unless negative stock is allowed. */
    @Transactional
    public Map<String, Object> directOut(Request dto) {
        lookups.requireLocation(dto.locationId());
        SettingContext ctx = new SettingContext(null, null, dto.locationId());
        boolean approvalNeeded = settings.resolveBool("inventory.stockOutApprovalNeeded", ctx);
        assertAttribution(dto, approvalNeeded);
        Map<String, Map<String, Object>> products = products(dto.items());
        for (Line item : dto.items()) {
            if (!products.containsKey(item.productId())) {
                throw ApiException.notFound("Product " + item.productId() + " not found");
            }
        }
        String code = seq.next("direct_stock_out", "DSO-", 5);
        boolean allowNegative = settings.resolveBool("inventory.allowNegativeStock", ctx);
        List<Line> ordered = new ArrayList<>(dto.items());
        ordered.sort(Comparator.comparing(Line::productId)
                .thenComparing(l -> l.variantId() == null ? "" : l.variantId()));
        BigDecimal total = Money.ZERO;
        List<Map<String, Object>> results = new ArrayList<>();
        for (Line item : ordered) {
            StockMove m = StockMove.of(item.productId(), dto.locationId(), item.quantity()).variant(item.variantId())
                    .moveType("issue").strategy(item.distStrategy() != null ? item.distStrategy() : "FEFO")
                    .batch(item.batchNumber()).source("direct_stock_out", code).requireAvailable(!allowNegative)
                    .notes(item.notes() != null ? item.notes() : dto.notes())
                    .people(dto.responsibleById(), dto.approvedById());
            Issued res = stock.issue(m);
            total = total.add(res.totalValue());
            results.add(Obj.of("productId", item.productId(), "quantity", JsNumbers.js(item.quantity()),
                    "unitCost", JsNumbers.dec(res.unitCost())));
        }
        return Obj.of("code", code, "type", "OUT", "locationId", dto.locationId(), "totalValue", JsNumbers.js(total),
                "items", results, "notes", dto.notes(), "timestamp", Instant.now());
    }

    private Map<String, Map<String, Object>> products(List<Line> items) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        items.forEach(i -> ids.add(i.productId()));
        Map<String, Map<String, Object>> out = new java.util.HashMap<>();
        for (Map<String, Object> p : jdbc.sql("SELECT id, name, \"batchTracking\", \"expiryTracking\" FROM \"Product\""
                        + " WHERE id IN (:ids) AND \"organizationId\" = :org AND \"deletedAt\" IS NULL")
                .param("ids", List.copyOf(ids)).param("org", TenantContext.organizationId()).query().listOfRows()) {
            out.put((String) p.get("id"), p);
        }
        return out;
    }
}
