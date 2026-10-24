package com.inventoryerp.inventory.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.TenderAccount;
import com.inventoryerp.inventory.application.CostResolver.Layer;
import com.inventoryerp.inventory.application.CostResolver.ProductCost;
import com.inventoryerp.inventory.application.CostResolver.Quant;
import com.inventoryerp.inventory.application.CostResolver.Resolution;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridges inventory movements and the General Ledger (port of {@code stock-posting.service.ts}). Every valued
 * movement posts a balanced entry whose accounts come from the configurable {@link InventoryPostingRuleService}
 * engine, falling back to the AccountMapping defaults when no rule is configured. Runs inside the caller's
 * transaction.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class StockPostingService {

    private static final Logger log = LoggerFactory.getLogger(StockPostingService.class);

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final CostResolver costResolver;
    private final InventoryPostingRuleService rules;

    public StockPostingService(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination,
            CostResolver costResolver, InventoryPostingRuleService rules) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.determination = determination;
        this.costResolver = costResolver;
        this.rules = rules;
    }

    /** Parameters of {@link #postIssue}. */
    public record IssuePosting(String productId, String locationId, String variantId, BigDecimal quantity, Instant date,
            String sourceType, String sourceId, String postingKey, String description, String movementType,
            BigDecimal overrideTotalValue) {}

    /** Issue (sale / consumption / waste / ...): Dr {expense} / Cr Stock Valuation. */
    public Resolution postIssue(IssuePosting p) {
        BigDecimal qty = p.quantity();
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("Issue quantity must be positive");
        }
        Map<String, Object> product = product(p.productId());
        if (product == null) {
            throw ApiException.badRequest("Product not found");
        }
        String org = TenantContext.organizationId();
        Map<String, Object> stockItem = SqlRows.one(jdbc.sql("SELECT * FROM \"StockItem\" WHERE \"organizationId\" = ?"
                        + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ? LIMIT 1")
                .params(org, p.productId(), p.variantId() == null ? "" : p.variantId(), p.locationId()));
        String costing = String.valueOf(product.get("costingMethod"));
        BigDecimal costPrice = (BigDecimal) product.get("costPrice");
        Resolution resolution;
        if (p.overrideTotalValue() != null) {
            BigDecimal total = p.overrideTotalValue();
            resolution = new Resolution(total.signum() > 0 ? Money.div(total, qty) : Money.ZERO, total, null, null);
        } else if ("FIFO".equals(costing)) {
            if (!Boolean.TRUE.equals(product.get("batchTracking"))) {
                throw ApiException.badRequest("FIFO costing requires batchTracking=true on the product");
            }
            List<Layer> layers = new ArrayList<>();
            for (Map<String, Object> b : jdbc.sql("SELECT * FROM \"InventoryBatch\" WHERE \"organizationId\" = ?"
                            + " AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ? AND \"locationId\" = ?"
                            + " AND quantity > 0 AND \"isActive\" = true ORDER BY \"expiryDate\" ASC NULLS LAST,"
                            + " \"receivedAt\" ASC")
                    .params(org, p.productId(), p.variantId(), p.locationId()).query().listOfRows()) {
                Map<String, Object> row = SqlRows.normalize(b);
                layers.add(new Layer((BigDecimal) row.get("quantity"), (BigDecimal) row.get("unitCost"),
                        (Instant) row.get("expiryDate"), (Instant) row.get("receivedAt")));
            }
            resolution = costResolver.resolveIssueCost(new ProductCost("FIFO", costPrice), quant(stockItem), qty,
                    layers, null);
        } else if ("AVCO".equals(costing)) {
            BigDecimal unit = stockItem != null ? (BigDecimal) stockItem.get("runningAverageCost") : Money.dec(costPrice);
            resolution = new Resolution(unit, unit.multiply(qty), null, null);
        } else {
            resolution = costResolver.resolveIssueCost(new ProductCost(costing, costPrice), quant(stockItem), qty,
                    null, null);
        }
        if (resolution.totalValue().signum() <= 0) {
            return resolution;
        }
        String mt = p.movementType() == null ? "STOCK_OUT" : p.movementType();
        String name = (String) product.get("name");
        List<Line> lines = resolveLines(mt, p.productId(), resolution.totalValue());
        if (lines == null) {
            lines = fallbackLinesIssue(mt, resolution.totalValue(), name);
        }
        if (!lines.isEmpty()) {
            String key = p.postingKey() != null ? p.postingKey()
                    : "inventory:issue:" + p.sourceType() + ":" + p.sourceId() + ":" + p.productId() + ":"
                            + (p.variantId() == null ? "" : p.variantId()) + ":" + p.locationId() + ":" + mt;
            posting.post(PostingRequest.of("INV", p.date(),
                    p.description() != null ? p.description() : "Stock issue " + name, p.sourceType(), p.sourceId(),
                    key, lines));
        }
        return resolution;
    }

    /** Return restock: Dr Stock Valuation / Cr COGS. */
    public void postReturnRestock(String productId, BigDecimal totalValue, Instant date, String sourceType,
            String sourceId, String description) {
        if (totalValue.signum() <= 0) {
            return;
        }
        String name = productName(productId);
        List<Line> lines = resolveLines("RETURN_RESTOCK", productId, totalValue);
        if (lines == null) {
            String v = JsNumbers.dec(totalValue);
            lines = List.of(Line.debit(determination.mapped("stock_valuation"), v, "Return restock · " + name),
                    Line.credit(determination.mapped("cogs"), v, "COGS reversal · " + name));
        }
        post("INV", date, description != null ? description : "Return restock " + name, sourceType, sourceId, null,
                lines);
    }

    /** Capitalise a stock receipt: Dr Stock Valuation / Cr GRNI-Accrued. */
    public Resolution postReceipt(String productId, BigDecimal quantity, BigDecimal unitCost, Instant date,
            String sourceType, String sourceId, String description) {
        if (quantity.signum() <= 0) {
            throw ApiException.badRequest("Receive quantity must be positive");
        }
        if (unitCost.signum() < 0) {
            throw ApiException.badRequest("Unit cost cannot be negative");
        }
        BigDecimal totalValue = unitCost.multiply(quantity);
        if (totalValue.signum() <= 0) {
            return new Resolution(unitCost, totalValue, unitCost, null);
        }
        List<Line> lines = resolveLines("STOCK_IN", productId, totalValue);
        if (lines == null) {
            String v = JsNumbers.dec(totalValue);
            lines = List.of(Line.debit(determination.mapped("stock_valuation"), v, "Stock receipt"),
                    Line.credit(determination.mapped("grni_accrued"), v, "GRNI accrual"));
        }
        post("INV", date, description != null ? description : "Stock receipt", sourceType, sourceId, null, lines);
        return new Resolution(unitCost, totalValue, unitCost, null);
    }

    /** Voucher a goods receipt against the supplier: Dr GRNI + Dr Input Tax / Cr AP. */
    public void postReceiptVoucher(String partnerId, BigDecimal netTotal, BigDecimal taxTotal, Instant date,
            String sourceType, String sourceId, String description, String postingKey) {
        BigDecimal net = Money.dec(netTotal);
        BigDecimal tax = Money.dec(taxTotal);
        BigDecimal gross = net.add(tax);
        if (gross.signum() <= 0) {
            return;
        }
        String apAccount = determination.payableAccount(partnerPayable(partnerId));
        String grni = determination.mapped("grni_accrued");
        List<Line> lines = new ArrayList<>();
        if (net.signum() > 0) {
            lines.add(Line.debit(grni, JsNumbers.dec(net), "GRNI clearing"));
        }
        if (tax.signum() > 0) {
            lines.add(Line.debit(determination.mapped("tax_receivable"), JsNumbers.dec(tax), "Input tax"));
        }
        String label = description != null ? description : "Goods received";
        lines.add(new Line(apAccount, null, JsNumbers.dec(gross), partnerId, label, null, null, null));
        posting.post(PostingRequest.of("PURCH", date, label, sourceType, sourceId,
                postingKey != null ? postingKey : "receipt_voucher:" + sourceType + ":" + sourceId, lines));
    }

    /** Settle a purchase against cash/bank: Dr AP / Cr Cash|Bank. */
    public Map<String, Object> postPurchasePayment(String cashSessionId, String partnerId, BigDecimal amount,
            String method, Instant date, String sourceType, String sourceId, String description, String postingKey) {
        if (amount.signum() <= 0) {
            return null;
        }
        String apAccount = determination.payableAccount(partnerPayable(partnerId));
        String funds = TenderAccount.resolve(jdbc, determination, TenantContext.organizationId(), method, null,
                cashSessionId).accountId();
        String label = description != null ? description : "Purchase payment";
        String v = JsNumbers.dec(amount);
        return posting.post(PostingRequest.of("cash".equals(method) ? "CASH" : "BANK", date, label, sourceType,
                sourceId, postingKey, List.of(new Line(apAccount, v, null, partnerId, label, null, null, null),
                        Line.credit(funds, v, label))));
    }

    /** Negative-stock cost correction (AVCO): amount &gt; 0 → Dr COGS / Cr Stock Valuation, else the reverse. */
    public void postNegativeStockCostCorrection(String productId, BigDecimal amount, Instant date, String sourceType,
            String sourceId, String description) {
        if (amount.signum() == 0) {
            return;
        }
        String name = productName(productId);
        BigDecimal magnitude = amount.abs();
        List<Line> lines = resolveLines("COGS_CORRECTION", productId, magnitude);
        if (lines == null) {
            String sv = determination.mapped("stock_valuation");
            String cogs = determination.mapped("cogs");
            String label = "Negative-stock cost correction · " + name;
            String v = JsNumbers.dec(magnitude);
            lines = amount.signum() > 0
                    ? List.of(Line.debit(cogs, v, label), Line.credit(sv, v, label))
                    : List.of(Line.debit(sv, v, label), Line.credit(cogs, v, label));
        }
        post("INV", date, description != null ? description : "Negative-stock cost correction · " + name, sourceType,
                sourceId, null, lines);
        log.warn("[stock] negative-stock cost correction {} posted for {} ({}:{})", JsNumbers.dec(amount), name,
                sourceType, sourceId);
    }

    /** One landed-cost line: share capitalised into stock and share already expensed. */
    public record LandedLine(String productId, BigDecimal capitalized, BigDecimal expensed) {}

    /** Landed cost: Dr Stock Valuation (on hand) + Dr COGS (consumed) / Cr the charge account. */
    public Map<String, Object> postLandedCost(List<LandedLine> landed, String creditAccountId, Instant date,
            String sourceType, String sourceId, String description) {
        List<Line> lines = new ArrayList<>();
        BigDecimal credit = Money.ZERO;
        BigDecimal cogsTotal = Money.ZERO;
        for (LandedLine l : landed) {
            BigDecimal cap = Money.dec(l.capitalized());
            BigDecimal exp = Money.dec(l.expensed());
            if (cap.signum() > 0) {
                List<Line> ruled = resolveLines("STOCK_IN", l.productId(), cap);
                String stockAccount = null;
                if (ruled != null) {
                    for (Line r : ruled) {
                        if (r.debit() != null && Money.dec(r.debit()).signum() > 0) {
                            stockAccount = r.accountId();
                            break;
                        }
                    }
                }
                if (stockAccount == null) {
                    stockAccount = determination.mapped("stock_valuation");
                }
                lines.add(Line.debit(stockAccount, JsNumbers.dec(cap), description));
                credit = credit.add(cap);
            }
            if (exp.signum() > 0) {
                cogsTotal = cogsTotal.add(exp);
                credit = credit.add(exp);
            }
        }
        if (cogsTotal.signum() > 0) {
            lines.add(Line.debit(determination.mapped("cogs"), JsNumbers.dec(cogsTotal),
                    description + " · already consumed"));
        }
        if (credit.signum() <= 0) {
            return null;
        }
        lines.add(Line.credit(creditAccountId, JsNumbers.dec(credit), description));
        return posting.post(PostingRequest.of("INV", date, description, sourceType, sourceId,
                "inventory:landed_cost:" + TenantContext.organizationId() + ":" + sourceId, lines));
    }

    /** Count/adjustment variance: gain → ADJUSTMENT_GAIN, loss → ADJUSTMENT_LOSS. */
    public void postAdjustment(String productId, BigDecimal delta, BigDecimal unitCost, Instant date, String sourceType,
            String sourceId, String description) {
        if (delta.signum() == 0) {
            return;
        }
        BigDecimal totalValue = unitCost.multiply(delta.abs());
        if (totalValue.signum() <= 0) {
            return;
        }
        boolean gain = delta.signum() > 0;
        List<Line> lines = resolveLines(gain ? "ADJUSTMENT_GAIN" : "ADJUSTMENT_LOSS", productId, totalValue);
        if (lines == null) {
            String sv = determination.mapped("stock_valuation");
            String v = JsNumbers.dec(totalValue);
            lines = gain
                    ? List.of(Line.debit(sv, v, "Stock variance gain"),
                            Line.credit(determination.mapped("stock_adjustment_income"), v, "Stock variance gain"))
                    : List.of(Line.debit(determination.mapped("stock_adjustment_expense"), v, "Stock variance loss"),
                            Line.credit(sv, v, "Stock variance loss"));
        }
        post("ADJ", date, description != null ? description : "Stock adjustment", sourceType, sourceId, null, lines);
    }

    /** Inter-location transfer: STOCK_TRANSFER_OUT and STOCK_TRANSFER_IN rule entries (no fallback). */
    public void postTransfer(String productId, BigDecimal totalValue, String fromLocationId, String toLocationId,
            Instant date, String sourceType, String sourceId, String description) {
        if (totalValue.signum() <= 0) {
            return;
        }
        String name = productName(productId);
        List<Line> out = resolveLines("STOCK_TRANSFER_OUT", productId, totalValue);
        if (out != null && !out.isEmpty()) {
            post("INV", date, description != null ? description : "Transfer out · " + name, sourceType, sourceId,
                    null, out);
        }
        List<Line> in = resolveLines("STOCK_TRANSFER_IN", productId, totalValue);
        if (in != null && !in.isEmpty()) {
            post("INV", date, description != null ? description : "Transfer in · " + name, sourceType, sourceId,
                    null, in);
        }
    }

    /** Return to supplier: Dr GRNI / Cr Stock Valuation. */
    public void postReturnToSupplier(String productId, BigDecimal totalValue, Instant date, String sourceType,
            String sourceId, String description) {
        simple("RETURN_TO_SUPPLIER", productId, totalValue, date, sourceType, sourceId, description,
                "Return to supplier · ", "grni_accrued", "stock_valuation", true);
    }

    /** Internal consumption: Dr default expense / Cr Stock Valuation. */
    public void postInternalConsumption(String productId, BigDecimal totalValue, Instant date, String sourceType,
            String sourceId, String description) {
        simple("INTERNAL_CONSUMPTION", productId, totalValue, date, sourceType, sourceId, description,
                "Internal consumption · ", "default_expense", "stock_valuation", false);
    }

    /** Promo sample: Dr adjustment expense / Cr Stock Valuation. */
    public void postPromoSample(String productId, BigDecimal totalValue, Instant date, String sourceType,
            String sourceId, String description) {
        simple("PROMO_SAMPLE", productId, totalValue, date, sourceType, sourceId, description, "Promo sample · ",
                "stock_adjustment_expense", "stock_valuation", false);
    }

    /** Production consume: Dr WIP / Cr Stock Valuation. */
    public void postProductionConsume(String productId, BigDecimal totalValue, Instant date, String sourceType,
            String sourceId, String description) {
        if (totalValue.signum() <= 0) {
            return;
        }
        String name = productName(productId);
        List<Line> lines = resolveLines("PRODUCTION_CONSUME", productId, totalValue);
        if (lines == null) {
            String sv = determination.mapped("stock_valuation");
            String wip = wipOr(sv);
            String v = JsNumbers.dec(totalValue);
            lines = List.of(Line.debit(wip, v, "Production consume · " + name),
                    Line.credit(sv, v, "Production consume · " + name));
        }
        post("INV", date, description != null ? description : "Production consume · " + name, sourceType, sourceId,
                null, lines);
    }

    /** Production output: Dr Stock Valuation / Cr WIP. */
    public void postProductionOutput(String productId, BigDecimal totalValue, Instant date, String sourceType,
            String sourceId, String description) {
        if (totalValue.signum() <= 0) {
            return;
        }
        String name = productName(productId);
        List<Line> lines = resolveLines("PRODUCTION_OUTPUT", productId, totalValue);
        if (lines == null) {
            String sv = determination.mapped("stock_valuation");
            String wip = wipOr(sv);
            String v = JsNumbers.dec(totalValue);
            lines = List.of(Line.debit(sv, v, "Production output · " + name),
                    Line.credit(wip, v, "Production output · " + name));
        }
        post("INV", date, description != null ? description : "Production output · " + name, sourceType, sourceId,
                null, lines);
    }

    /** Production scrap write-off: Dr Stock Adjustment Expense / Cr WIP. */
    public void postProductionScrap(BigDecimal totalValue, Instant date, String sourceType, String sourceId,
            String description) {
        if (totalValue.signum() <= 0) {
            return;
        }
        String label = description != null ? description : "Production scrap";
        String v = JsNumbers.dec(totalValue);
        posting.post(PostingRequest.of("INV", date, label, sourceType, sourceId, null,
                List.of(Line.debit(determination.mapped("stock_adjustment_expense"), v, label),
                        Line.credit(determination.mapped("wip"), v, label))));
    }

    /** Overhead absorption: Dr WIP / Cr Overhead Absorbed. */
    public void postOverheadAbsorption(BigDecimal totalValue, Instant date, String sourceType, String sourceId,
            String description) {
        if (totalValue.signum() <= 0) {
            return;
        }
        String label = description != null ? description : "Overhead absorbed";
        String v = JsNumbers.dec(totalValue);
        posting.post(PostingRequest.of("INV", date, label, sourceType, sourceId, null,
                List.of(Line.debit(determination.mapped("wip"), v, label),
                        Line.credit(determination.mapped("overhead_absorbed"), v, label))));
    }

    // ------------------------------------------------------------------ helpers

    /** Rule-engine lines, or {@code null} when no rule is configured (caller falls back). */
    private List<Line> resolveLines(String movementType, String productId, BigDecimal totalValue) {
        try {
            return rules.resolve(movementType, productId, totalValue, null);
        } catch (ApiException err) {
            if (String.valueOf(err.getMessage()).contains("No posting rule configured")) {
                log.warn("No posting rule for {}, falling back to AccountMapping defaults", movementType);
                return null;
            }
            throw err;
        }
    }

    private List<Line> fallbackLinesIssue(String movementType, BigDecimal totalValue, String productName) {
        String v = JsNumbers.dec(totalValue);
        if (movementType != null
                && List.of("WASTE", "EXPIRY_WRITE_OFF", "INTERNAL_CONSUMPTION", "PROMO_SAMPLE").contains(movementType)) {
            String sv = determination.mapped("stock_valuation");
            String expense = determination.mapped("stock_adjustment_expense");
            return List.of(Line.debit(expense, v, movementType + " · " + productName),
                    Line.credit(sv, v, movementType + " · " + productName));
        }
        String cogs = determination.mapped("cogs");
        String sv = determination.mapped("stock_valuation");
        return List.of(Line.debit(cogs, v, "COGS · " + productName), Line.credit(sv, v, "Stock issue · " + productName));
    }

    private void simple(String movementType, String productId, BigDecimal totalValue, Instant date, String sourceType,
            String sourceId, String description, String labelPrefix, String debitKey, String creditKey,
            boolean creditFirst) {
        if (totalValue.signum() <= 0) {
            return;
        }
        String name = productName(productId);
        List<Line> lines = resolveLines(movementType, productId, totalValue);
        if (lines == null) {
            String v = JsNumbers.dec(totalValue);
            String label = ("RETURN_TO_SUPPLIER".equals(movementType) ? "Return to supplier · "
                    : "INTERNAL_CONSUMPTION".equals(movementType) ? "Internal use · " : labelPrefix) + name;
            String debit = determination.mapped(debitKey);
            String credit = determination.mapped(creditKey);
            lines = creditFirst
                    ? List.of(Line.credit(credit, v, label), Line.debit(debit, v, label))
                    : List.of(Line.debit(debit, v, label), Line.credit(credit, v, label));
        }
        post("INV", date, description != null ? description : labelPrefix + name, sourceType, sourceId, null, lines);
    }

    private void post(String journal, Instant date, String description, String sourceType, String sourceId,
            String postingKey, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        posting.post(PostingRequest.of(journal, date, description, sourceType, sourceId, postingKey, lines));
    }

    private String wipOr(String fallback) {
        try {
            return determination.mapped("wip");
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private Map<String, Object> product(String productId) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL LIMIT 1")
                .params(productId, TenantContext.organizationId()));
    }

    private String productName(String productId) {
        Map<String, Object> p = product(productId);
        return p == null ? productId : (String) p.get("name");
    }

    private String partnerPayable(String partnerId) {
        if (partnerId == null) {
            return null;
        }
        return jdbc.sql("SELECT \"payableAccountId\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL")
                .params(partnerId, TenantContext.organizationId()).query(String.class).optional().orElse(null);
    }

    private static Quant quant(Map<String, Object> stockItem) {
        return stockItem == null ? null
                : new Quant((BigDecimal) stockItem.get("quantity"), (BigDecimal) stockItem.get("runningAverageCost"));
    }
}
