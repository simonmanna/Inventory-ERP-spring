package com.inventoryerp.quarry.application;

import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quarry.application.QuarryCommands.ConsumeRequest;
import com.inventoryerp.quarry.domain.QuarryStockpile;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ROM (run-of-mine) stockpiles as inventory: the stockpile view of the quarry screens and the <b>public API for
 * crushing &amp; screening (2.3)</b>. A stockpile is an ordinary inventory location, so 2.3 may also consume it like
 * any other location through {@link StockService} / the manufacturing stage engine; this service gives it the
 * stockpile → location / material / on-hand mapping and a quantity-only {@link #consume} that refuses to drive a
 * stockpile negative.
 */
@Service
public class QuarryStockpileService {

    /** On-hand of one product in one stockpile, at the location's running average cost. */
    public record RomStock(String stockpileId, String stockpileCode, String stockpileName, String kind, String siteId,
            String locationId, String productId, String productCode, String productName, String materialId,
            BigDecimal onHandTonnes, BigDecimal averageCost, BigDecimal value) {}

    private final QuarryDb db;
    private final QuarrySiteService sites;
    private final StockService stock;

    public QuarryStockpileService(QuarryDb db, QuarrySiteService sites, StockService stock) {
        this.db = db;
        this.sites = sites;
        this.stock = stock;
    }

    /** The stockpile row (404 when unknown) — id, siteId, kind, locationId, ... */
    @Transactional(readOnly = true)
    public Map<String, Object> stockpile(String stockpileId) {
        return sites.requireStockpile(stockpileId, false);
    }

    /** The inventory location backing a stockpile. */
    @Transactional(readOnly = true)
    public String locationOf(String stockpileId) {
        return (String) sites.requireStockpile(stockpileId, false).get("locationId");
    }

    /** On-hand per stockpile × product of a site ({@code siteId} null = every site); {@code kind} rom / product / null. */
    @Transactional(readOnly = true)
    public List<RomStock> romStock(String siteId, String kind) {
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org()));
        StringBuilder where = new StringBuilder();
        if (siteId != null) {
            where.append(" AND k.\"siteId\" = ?");
            params.add(siteId);
        }
        if (kind != null) {
            where.append(" AND k.kind = ?");
            params.add(kind);
        }
        List<RomStock> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                SELECT k.id, k.code, k.name, k.kind, k."siteId", k."locationId", p.id AS "productId",
                       p.code AS "productCode", p.name AS "productName",
                       (SELECT m.id FROM "QuarryMaterial" m WHERE m."organizationId" = k."organizationId"
                          AND m."productId" = p.id AND m."deletedAt" IS NULL ORDER BY m.code LIMIT 1) AS "materialId",
                       SUM(si.quantity) AS quantity, SUM(si.quantity * si."runningAverageCost") AS value
                FROM "QuarryStockpile" k
                JOIN "StockItem" si ON si."locationId" = k."locationId" AND si."organizationId" = k."organizationId"
                JOIN "Product" p ON p.id = si."productId"
                WHERE k."organizationId" = ? AND k."deletedAt" IS NULL""" + where
                + " GROUP BY k.id, p.id HAVING SUM(si.quantity) <> 0 ORDER BY k.code, p.name").params(params))) {
            BigDecimal qty = SqlRows.dec(r, "quantity");
            BigDecimal value = SqlRows.dec(r, "value").setScale(2, RoundingMode.HALF_UP);
            out.add(new RomStock((String) r.get("id"), (String) r.get("code"), (String) r.get("name"),
                    (String) r.get("kind"), (String) r.get("siteId"), (String) r.get("locationId"),
                    (String) r.get("productId"), (String) r.get("productCode"), (String) r.get("productName"),
                    (String) r.get("materialId"), qty, qty.signum() == 0 ? BigDecimal.ZERO
                            : value.divide(qty, 6, RoundingMode.HALF_UP), value));
        }
        return out;
    }

    /** On-hand tonnes of {@code productId} in a stockpile. */
    @Transactional(readOnly = true)
    public BigDecimal onHand(String stockpileId, String productId) {
        String loc = locationOf(stockpileId);
        return db.jdbc().sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ? AND"
                + " \"locationId\" = ? AND \"productId\" = ?").params(QuarryDb.org(), loc, productId)
                .query(BigDecimal.class).single();
    }

    /**
     * Take stone out of a stockpile into a process — quantity only, costed AVCO / FIFO by inventory, refused when
     * short. The caller posts the GL (e.g. {@code StockPostingService#postProductionConsume} for Dr WIP / Cr stock)
     * with the returned {@link Issued#totalValue()}. Runs in the caller's transaction.
     */
    @Transactional
    public Issued consume(ConsumeRequest r) {
        if (r.tonnes() == null || r.tonnes().signum() <= 0) {
            throw ApiException.badRequest("The tonnes to consume must be positive");
        }
        Map<String, Object> k = db.require(QuarryStockpile.class, r.stockpileId(), false, "Stockpile not found");
        if (!Boolean.TRUE.equals(k.get("isActive"))) {
            throw ApiException.badRequest("Stockpile " + k.get("code") + " is inactive");
        }
        StockMove m = StockMove.of(r.productId(), (String) k.get("locationId"), r.tonnes())
                .source(r.sourceType(), r.sourceId()).reference(r.reference()).notes(r.notes())
                .moveType("production_consume").date(r.date()).skipGl().requireAvailable(true);
        m.responsibleById = TenantContext.userId();
        return stock.issue(m);
    }

    /** Stockpile view: the stockpile with its stock per product and paged inventory movements. */
    @Transactional(readOnly = true)
    public Map<String, Object> view(String stockpileId, Integer page, Integer pageSize) {
        Map<String, Object> k = sites.stockpileRows(" AND k.id = ?", stockpileId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Stockpile not found"));
        Map<String, Object> out = new LinkedHashMap<>(k);
        out.put("stock", romStock((String) k.get("siteId"), null).stream()
                .filter(s -> stockpileId.equals(s.stockpileId())).toList());
        int p = QuarryDb.page(page);
        int size = QuarryDb.size(pageSize);
        String org = QuarryDb.org();
        long total = db.jdbc().sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND"
                + " \"locationId\" = ?").params(org, k.get("locationId")).query(Long.class).single();
        out.put("movements", PageResult.of(SqlRows.list(db.jdbc().sql("""
                SELECT l.id, l."ledgerCode", l.type::text AS type, l."quantityChange", l."balanceAfter",
                       l."unitCost", l."totalValue", l."referenceType", l."referenceId", l.notes, l."createdAt",
                       p.code AS "productCode", p.name AS "productName",
                       (SELECT g.code FROM "QuarryExtractionLog" g WHERE g.id = l."referenceId") AS "extractionLogCode"
                FROM "InventoryLedger" l JOIN "Product" p ON p.id = l."productId"
                WHERE l."organizationId" = ? AND l."locationId" = ?
                ORDER BY l."createdAt" DESC LIMIT ? OFFSET ?""").params(org, k.get("locationId"), size,
                (p - 1) * size)), p, size, total));
        return out;
    }
}
