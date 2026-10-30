package com.inventoryerp.quality.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quality.application.QualityCommands.CancelInput;
import com.inventoryerp.quality.application.QualityCommands.DowngradeInput;
import com.inventoryerp.quality.application.QualityCommands.ReasonInput;
import com.inventoryerp.quality.application.QualityCommands.RejectInput;
import com.inventoryerp.quality.application.QualityCommands.ReleaseInput;
import com.inventoryerp.quality.domain.QualityInspectionLot;
import com.inventoryerp.quality.domain.QualityLotDecision;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lot decisions (ADR-S013 §3): hold, quarantine, release, reject, downgrade, cancel. Each locks the lot row, writes a
 * {@code QualityLotDecision}, audits and publishes {@code quality.lot.decided}. For {@code stockControl = quality} lots
 * the stock moves through {@link StockService} (quarantine / release transfers, reject issue, downgrade
 * reclassification with one balanced journal entry); {@code source} lots only record the decision.
 */
@Service
public class QualityDecisionService {

    static final String DOWNGRADE_SOURCE = "quality_downgrade";

    private final QualityDb db;
    private final QualityLotService lots;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final PostingService posting;
    private final AccountDeterminationService accounts;
    private final AuditService audit;
    private final EventBus events;

    public QualityDecisionService(QualityDb db, QualityLotService lots, StockService stock,
            StockPostingService stockPosting, PostingService posting, AccountDeterminationService accounts,
            AuditService audit, EventBus events) {
        this.db = db;
        this.lots = lots;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.posting = posting;
        this.accounts = accounts;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ hold / quarantine

    @Transactional
    public Map<String, Object> hold(String id, ReasonInput in) {
        Map<String, Object> lot = live(id);
        String status = Q.str(lot, "status");
        if (!"open".equals(status)) {
            throw ApiException.badRequest("Lot " + lot.get("lotNumber") + " is " + status + "; only an open lot can be"
                    + " put on hold.");
        }
        db.update(QualityInspectionLot.class, id, Obj.of("status", "hold", "updatedBy", TenantContext.userId()));
        lots.decision(id, "hold", openQty(lot), null, null, in == null ? null : in.reason(), null, null);
        return done(lot, "hold", openQty(lot));
    }

    @Transactional
    public Map<String, Object> quarantine(String id, ReasonInput in) {
        Map<String, Object> lot = live(id);
        String status = Q.str(lot, "status");
        if ("quarantined".equals(status)) {
            throw ApiException.badRequest("Lot " + lot.get("lotNumber") + " is already in quarantine.");
        }
        BigDecimal qty = openQty(lot);
        if (quality(lot)) {
            if (!Q.has(Q.str(lot, "locationId"))) {
                throw ApiException.badRequest("Lot " + lot.get("lotNumber") + " has no stock location to quarantine.");
            }
            lots.quarantineStock(lot, qty, in == null ? null : in.reason());
        } else {
            db.update(QualityInspectionLot.class, id, Obj.of("status", "quarantined", "updatedBy",
                    TenantContext.userId()));
            lots.decision(id, "quarantine", qty, null, null, in == null ? null : in.reason(), null, null);
        }
        return done(lot, "quarantine", qty);
    }

    // ------------------------------------------------------------------ release

    @Transactional
    public Map<String, Object> release(String id, ReleaseInput in) {
        Map<String, Object> lot = live(id);
        BigDecimal qty = quantity(lot, in.quantity());
        independent(lot);
        boolean concession = !"pass".equals(lot.get("result"));
        if (concession && !Q.has(in.concessionReason())) {
            throw ApiException.badRequest("Lot " + lot.get("lotNumber") + " has not passed (result " + lot.get("result")
                    + "); a concession reason is required to release it.");
        }
        String quarantine = Q.str(lot, "quarantineLocationId");
        String to = Q.has(in.toLocationId()) ? in.toLocationId() : Q.str(lot, "locationId");
        String ledger = null;
        String from = null;
        if (quality(lot) && quarantine != null) {
            if (to == null) {
                throw ApiException.badRequest("Choose the location to release lot " + lot.get("lotNumber") + " to.");
            }
            if (!db.exists("InventoryLocation", to, true)) {
                throw ApiException.notFound("Location not found");
            }
            from = quarantine;
            ledger = stock.transfer(StockMove.transfer(Q.str(lot, "productId"), quarantine, to, qty)
                    .variant(Q.str(lot, "variantId")).batch(Q.str(lot, "batchNumber"))
                    .source(QualityLotService.STOCK_SOURCE, Q.str(lot, "lotNumber"))
                    .notes("Release " + lot.get("lotNumber"))).ledgerCode();
        }
        String reason = concession ? in.concessionReason() : in.reason();
        lots.decision(id, "release", qty, from, from == null ? null : to, reason, ledger,
                Obj.of("concession", concession));
        settle(lot, "releasedQty", qty);
        return done(lot, "release", qty);
    }

    // ------------------------------------------------------------------ reject

    @Transactional
    public Map<String, Object> reject(String id, RejectInput in) {
        Map<String, Object> lot = live(id);
        BigDecimal qty = quantity(lot, in.quantity());
        independent(lot);
        String disposition = Q.or(in.disposition(), "scrap");
        if ("return_to_supplier".equals(disposition) && !Q.has(Q.str(lot, "supplierPartnerId"))) {
            throw ApiException.badRequest("Lot " + lot.get("lotNumber") + " has no supplier to return to.");
        }
        String from = stockLocation(lot);
        String ledger = null;
        BigDecimal value = null;
        if (quality(lot) && from != null) {
            StockMove.Issued issued = stock.issue(StockMove.of(Q.str(lot, "productId"), from, qty)
                    .variant(Q.str(lot, "variantId")).batch(Q.str(lot, "batchNumber"))
                    .strategy(Q.has(Q.str(lot, "batchNumber")) ? "MANUAL" : null)
                    .moveType("scrap".equals(disposition) ? "waste" : "return_to_supplier")
                    .source(QualityLotService.STOCK_SOURCE, Q.str(lot, "lotNumber")).requireAvailable(true)
                    .notes("QC reject " + lot.get("lotNumber") + " · " + in.reason().trim()));
            ledger = issued.ledgerCode();
            value = issued.totalValue();
        }
        lots.decision(id, "reject", qty, from, null, in.reason(), ledger, Obj.of("disposition", disposition,
                "stockValue", value));
        settle(lot, "rejectedQty", qty);
        return done(lot, "reject", qty);
    }

    // ------------------------------------------------------------------ downgrade

    /**
     * Reclassify (part of) the lot as a second-grade product: issue the first grade (no GL), receive the second grade
     * at the carried value or a lower unit cost, and post Dr stock (second grade) + Dr stock adjustment expense
     * (write-down) / Cr stock (first grade) when the accounts differ or there is a write-down.
     */
    @Transactional
    public Map<String, Object> downgrade(String id, DowngradeInput in) {
        Map<String, Object> lot = live(id);
        BigDecimal qty = quantity(lot, in.quantity());
        independent(lot);
        String fromProduct = Q.str(lot, "productId");
        if (in.productId().equals(fromProduct)) {
            throw ApiException.badRequest("Choose a different (second-grade) product to downgrade to.");
        }
        if (!db.exists("Product", in.productId(), true)) {
            throw ApiException.notFound("Product " + in.productId() + " not found");
        }
        BigDecimal toQty = in.toQuantity() != null ? in.toQuantity() : qty;
        String batch = Q.has(in.batchNumber()) ? in.batchNumber().trim()
                : Q.has(Q.str(lot, "batchNumber")) ? Q.str(lot, "batchNumber") : Q.str(lot, "lotNumber");
        String from = stockLocation(lot);
        String to = Q.has(in.toLocationId()) ? in.toLocationId() : Q.str(lot, "locationId") != null
                ? Q.str(lot, "locationId") : from;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("downgradeProductId", in.productId());
        extra.put("downgradeBatchNumber", batch);
        extra.put("downgradeQty", toQty);
        String decisionId;
        if (quality(lot) && from != null) {
            if (to == null || !db.exists("InventoryLocation", to, true)) {
                throw ApiException.notFound("Location not found");
            }
            String code = Q.str(lot, "lotNumber");
            Instant now = Instant.now();
            StockMove.Issued out = stock.issue(StockMove.of(fromProduct, from, qty).variant(Q.str(lot, "variantId"))
                    .batch(Q.str(lot, "batchNumber")).strategy(Q.has(Q.str(lot, "batchNumber")) ? "MANUAL" : null)
                    .moveType("adjustment_out").skipGl().requireAvailable(true).source(DOWNGRADE_SOURCE, code)
                    .date(now).notes("Downgrade " + code + " · " + in.reason().trim()));
            BigDecimal carried = Money.dec(out.totalValue());
            BigDecimal unit = in.unitCost() != null ? in.unitCost()
                    : toQty.signum() > 0 ? carried.divide(toQty, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal newValue = unit.multiply(toQty).setScale(6, RoundingMode.HALF_UP);
            if (newValue.compareTo(carried.setScale(6, RoundingMode.HALF_UP)) > 0
                    && newValue.subtract(carried).abs().compareTo(new BigDecimal("0.01")) > 0) {
                throw ApiException.badRequest("A downgrade cannot revalue stock upward (carried "
                        + JsNumbers.dec(carried) + ", new " + JsNumbers.dec(newValue) + ").");
            }
            if (newValue.compareTo(carried) > 0) {
                newValue = carried;
            }
            StockMove.Received in2 = stock.receive(StockMove.of(in.productId(), to, toQty).variant(Q.trim(in.variantId()))
                    .unitCost(unit).batch(batch).moveType("adjustment_in").source(DOWNGRADE_SOURCE, code).date(now)
                    .notes("Downgrade from " + code));
            BigDecimal writeDown = carried.subtract(newValue).max(BigDecimal.ZERO);
            extra.put("unitCost", unit);
            extra.put("stockValue", carried);
            extra.put("writeDownValue", writeDown);
            decisionId = lots.decision(id, "downgrade", qty, from, to, in.reason(),
                    out.ledgerCode() + "," + in2.ledgerCode(), extra);
            String je = postDowngrade(decisionId, code, fromProduct, in.productId(), carried, newValue, writeDown, now);
            if (je != null) {
                db.update(QualityLotDecision.class, decisionId, Obj.of("journalEntryId", je));
            }
        } else {
            lots.decision(id, "downgrade", qty, null, null, in.reason(), null, extra);
        }
        settle(lot, "downgradedQty", qty);
        return done(lot, "downgrade", qty);
    }

    private String postDowngrade(String decisionId, String code, String fromProduct, String toProduct,
            BigDecimal carried, BigDecimal newValue, BigDecimal writeDown, Instant date) {
        if (carried.signum() <= 0) {
            return null;
        }
        String fromAccount = stockPosting.stockAccount(fromProduct, carried);
        String toAccount = stockPosting.stockAccount(toProduct, newValue.signum() > 0 ? newValue : carried);
        if (fromAccount.equals(toAccount) && writeDown.signum() <= 0) {
            return null;
        }
        String label = "Quality downgrade " + code;
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        net.merge(toAccount, newValue, BigDecimal::add);
        if (writeDown.signum() > 0) {
            net.merge(accounts.mapped("stock_adjustment_expense"), writeDown, BigDecimal::add);
        }
        net.merge(fromAccount, carried.negate(), BigDecimal::add);
        List<Line> lines = new ArrayList<>();
        net.forEach((account, amount) -> {
            BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP);
            if (a.signum() > 0) {
                lines.add(Line.debit(account, JsNumbers.dec(a), label));
            } else if (a.signum() < 0) {
                lines.add(Line.credit(account, JsNumbers.dec(a.negate()), label));
            }
        });
        if (lines.isEmpty()) {
            return null;
        }
        Map<String, Object> je = posting.post(PostingRequest.of("INV", date, label, DOWNGRADE_SOURCE, decisionId,
                "quality:downgrade:" + QualityDb.org() + ":" + decisionId, lines));
        return je == null ? null : Q.str(je, "id");
    }

    // ------------------------------------------------------------------ cancel

    @Transactional
    public Map<String, Object> cancel(String id, CancelInput in) {
        Map<String, Object> lot = live(id);
        if (decided(lot).signum() > 0) {
            throw ApiException.badRequest("Lot " + lot.get("lotNumber") + " already has dispositions and cannot be"
                    + " cancelled.");
        }
        String quarantine = Q.str(lot, "quarantineLocationId");
        String ledger = null;
        BigDecimal qty = openQty(lot);
        if (quality(lot) && quarantine != null && Q.has(Q.str(lot, "locationId"))
                && !quarantine.equals(Q.str(lot, "locationId"))) {
            ledger = stock.transfer(StockMove.transfer(Q.str(lot, "productId"), quarantine, Q.str(lot, "locationId"), qty)
                    .variant(Q.str(lot, "variantId")).batch(Q.str(lot, "batchNumber"))
                    .source(QualityLotService.STOCK_SOURCE, Q.str(lot, "lotNumber"))
                    .notes("Cancel " + lot.get("lotNumber"))).ledgerCode();
        }
        db.update(QualityInspectionLot.class, id, Obj.of("status", "cancelled", "cancelReason", in.reason().trim(),
                "closedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        lots.decision(id, "cancel", qty, quarantine, ledger == null ? null : Q.str(lot, "locationId"), in.reason(),
                ledger, null);
        return done(lot, "cancel", qty);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> live(String id) {
        Map<String, Object> lot = lots.lockLot(id);
        String status = Q.str(lot, "status");
        if ("closed".equals(status) || "cancelled".equals(status)) {
            throw ApiException.badRequest("Lot " + lot.get("lotNumber") + " is " + status + ".");
        }
        return lot;
    }

    private static boolean quality(Map<String, Object> lot) {
        return "quality".equals(lot.get("stockControl"));
    }

    /** Where the lot's undecided stock is: quarantine when quarantined, else its location. */
    private static String stockLocation(Map<String, Object> lot) {
        return Q.str(lot, "quarantineLocationId") != null ? Q.str(lot, "quarantineLocationId")
                : Q.str(lot, "locationId");
    }

    private static BigDecimal decided(Map<String, Object> lot) {
        return Q.dec(lot, "releasedQty").add(Q.dec(lot, "rejectedQty")).add(Q.dec(lot, "downgradedQty"));
    }

    static BigDecimal openQty(Map<String, Object> lot) {
        return Q.dec(lot, "quantity").subtract(decided(lot));
    }

    private static BigDecimal quantity(Map<String, Object> lot, BigDecimal requested) {
        BigDecimal open = openQty(lot);
        BigDecimal qty = requested != null ? requested : open;
        if (qty.signum() <= 0) {
            throw ApiException.badRequest("Quantity must be positive.");
        }
        if (qty.compareTo(open) > 0) {
            throw ApiException.badRequest("Lot " + lot.get("lotNumber") + " has only " + JsNumbers.dec(open)
                    + " undecided; requested " + JsNumbers.dec(qty) + ".");
        }
        return qty;
    }

    /** SoD: with independentRelease, the inspector who recorded the results cannot dispose of the lot. */
    private static void independent(Map<String, Object> lot) {
        String user = TenantContext.userId();
        if (!Boolean.FALSE.equals(lot.get("independentRelease")) && user != null
                && user.equals(lot.get("inspectedBy"))) {
            throw ApiException.forbidden("Segregation of duties: the inspector who recorded the results of lot "
                    + lot.get("lotNumber") + " cannot decide on it.");
        }
    }

    private void settle(Map<String, Object> lot, String column, BigDecimal qty) {
        BigDecimal after = Q.dec(lot, column).add(qty);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(column, after);
        if (openQty(lot).subtract(qty).signum() <= 0) {
            data.put("status", "closed");
            data.put("closedAt", Instant.now());
        }
        data.put("updatedBy", TenantContext.userId());
        db.update(QualityInspectionLot.class, Q.str(lot, "id"), data);
    }

    /** The {@code AuditAction} enum value recording a decision (the decision itself is in the new values). */
    static String auditAction(String decision) {
        return switch (decision) {
            case "quarantine" -> "transfer";
            case "release" -> "approve";
            case "reject" -> "reject";
            case "downgrade" -> "adjust";
            case "cancel" -> "cancel";
            default -> "update";
        };
    }

    private Map<String, Object> done(Map<String, Object> lot, String decision, BigDecimal qty) {
        String id = Q.str(lot, "id");
        audit.recordInTx(AuditInput.of(QualityLotService.AUDIT_ENTITY, id, auditAction(decision), Obj.of("status",
                lot.get("status")), Obj.of("decision", decision, "lotNumber", lot.get("lotNumber"), "quantity",
                JsNumbers.dec(qty))));
        Map<String, Object> after = lots.get(id);
        events.publish(DomainEvents.QUALITY_LOT_DECIDED, Obj.of("organizationId", QualityDb.org(), "lotId", id,
                "lotNumber", lot.get("lotNumber"), "decision", decision, "quantity", JsNumbers.dec(qty), "status",
                after.get("status"), "result", after.get("result"), "stockControl", lot.get("stockControl"),
                "sourceType", lot.get("sourceType"), "sourceId", lot.get("sourceId"), "stageKey", lot.get("stageKey"),
                "productId", lot.get("productId"), "batchNumber", lot.get("batchNumber")));
        return after;
    }
}
