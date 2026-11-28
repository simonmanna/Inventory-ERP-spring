package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.workflow.LifecycleService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.rental.domain.RentalAgreement;
import com.inventoryerp.rental.domain.RentalAgreementLine;
import com.inventoryerp.rental.domain.RentalBooking;
import com.inventoryerp.rental.domain.RentalDamage;
import com.inventoryerp.rental.domain.RentalReturn;
import com.inventoryerp.rental.domain.RentalReturnLine;
import com.inventoryerp.rental.domain.RentalUnit;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Return → inspect → settle (port of {@code rental-return.service.ts}). The counter return logs what came back with
 * late days / late fees per line; inspection grades lines, records damages and moves stock / unit status; settlement
 * bills late + damage charges, applies the deposit, refunds the remainder, closes the agreement and recomputes the
 * customer score.
 */
@Service
public class RentalReturnService {

    private static final String BY_ID = "id = ? AND \"organizationId\" = ?";

    private final RentalRows rows;
    private final PosDb db;
    private final LifecycleService lifecycle;
    private final SequenceService sequence;
    private final RentalPostingService posting;
    private final RentalDepositService deposit;
    private final RentalScoreService score;

    public RentalReturnService(RentalRows rows, LifecycleService lifecycle, SequenceService sequence,
            RentalPostingService posting, RentalDepositService deposit, RentalScoreService score) {
        this.rows = rows;
        this.db = rows.db();
        this.lifecycle = lifecycle;
        this.sequence = sequence;
        this.posting = posting;
        this.deposit = deposit;
        this.score = score;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> q) {
        RentalRows.Paging p = RentalRows.paging(q);
        StringBuilder where = new StringBuilder("\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(RentalRows.org()));
        if (RentalUnitService.notEmpty(q.get("agreementId"))) {
            where.append(" AND \"agreementId\" = ?");
            params.add(q.get("agreementId"));
        }
        long total = db.count(RentalReturn.class, where.toString(), params.toArray());
        params.add(p.limit());
        params.add(p.offset());
        List<Map<String, Object>> items = db.list(RentalReturn.class, where
                + " ORDER BY \"receivedAt\" DESC LIMIT ? OFFSET ?", params.toArray());
        for (Map<String, Object> r : items) {
            decorate(r, false);
        }
        return RentalRows.envelope(total, p, items);
    }

    /**
     * {@code agreement: {select: {id, agreementNumber, partnerId}}, lines: {include: {damages: true}}}; only
     * {@code get} orders the lines by id, damages are never ordered (database order, like Prisma).
     */
    private Map<String, Object> decorate(Map<String, Object> ret, boolean linesById) {
        ret.put("agreement", RentalRows.pick(db.first(RentalAgreement.class, "id = ?", ret.get("agreementId")), "id",
                "agreementNumber", "partnerId"));
        List<Map<String, Object>> lines = db.list(RentalReturnLine.class, "\"returnId\" = ?"
                + (linesById ? " ORDER BY id ASC" : ""), ret.get("id"));
        for (Map<String, Object> l : lines) {
            l.put("damages", db.list(RentalDamage.class, "\"returnLineId\" = ?", l.get("id")));
        }
        ret.put("lines", lines);
        return ret;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String returnId) {
        Map<String, Object> ret = db.first(RentalReturn.class, BY_ID, returnId, RentalRows.org());
        if (ret == null) {
            throw ApiException.notFound("Return not found");
        }
        return decorate(ret, true);
    }

    // ------------------------------------------------------------------ counter return

    /**
     * Log what came back ({@code items: [{agreementLineId, quantity, quantityMissing, conditionGrade, notes}]}). IPR M-7
     * (deviation from Nest): what physically came back moves RENT-OUT → RENT-CLEAN here, so RENT-OUT is decremented
     * once per return; a line cannot be returned beyond what is still out (M-8).
     */
    @Transactional
    public Map<String, Object> createReturn(Map<String, Object> dto) {
        String orgId = RentalRows.org();
        List<Map<String, Object>> items = RentalRows.lines(dto, "items");
        if (items.isEmpty()) {
            throw ApiException.badRequest("At least one returned item is required");
        }
        String agreementId = RentalRows.str(dto, "agreementId");
        // IPR M-7 / M-8: the agreement row is locked (L1) before the status check and the returnable cap, so two
        // counter returns of one line run one after the other.
        lockAgreement(agreementId, orgId);
        Map<String, Object> agreement = db.first(RentalAgreement.class, BY_ID, agreementId, orgId);
        if (agreement == null) {
            throw ApiException.notFound("Agreement not found");
        }
        String status = (String) agreement.get("status");
        if (!List.of("checked_out", "partially_returned").contains(status)) {
            throw ApiException.badRequest("Agreement status '" + status + "' cannot be returned");
        }
        List<Map<String, Object>> agreementLines = db.list(RentalAgreementLine.class, "\"agreementId\" = ? AND"
                + " \"organizationId\" = ?", agreementId, orgId);

        String number = sequence.next("rental_return", "RET-", 5);
        Map<String, Object> ret = db.insert(RentalReturn.class, Obj.of("organizationId", orgId, "agreementId",
                agreementId, "returnNumber", number, "receivedById", RentalRows.user(), "notes", dto.get("notes")));
        Instant now = Instant.now();
        Map<String, Object> terms = PosSql.map(PosSql.java(agreement.get("termsSnapshot")));
        double lateFeePerDay = terms == null || terms.get("lateFeePerDay") == null ? 0 : PosSql.num(terms.get("lateFeePerDay"));
        for (Map<String, Object> item : items) {
            Map<String, Object> line = agreementLines.stream().filter(l -> l.get("id").equals(item.get("agreementLineId")))
                    .findFirst().orElse(null);
            if (line == null) {
                throw ApiException.badRequest("Unknown agreement line " + item.get("agreementLineId"));
            }
            double quantityMissing = RentalRows.numOr(item, "quantityMissing", 0);
            double quantity = RentalRows.numOr(item, "quantity", 1);
            boolean isMissing = quantityMissing >= quantity || quantityMissing == PosSql.num(line.get("quantity"));
            double back = physicallyBack(quantity, quantityMissing, isMissing);
            double stillOut = stillOut(line);
            if (back > stillOut + 1e-9) {
                throw ApiException.badRequest("Cannot return " + RentalRows.dec(back).stripTrailingZeros().toPlainString()
                        + " on agreement line " + line.get("id") + ": only "
                        + RentalRows.dec(Math.max(0, stillOut)).stripTrailingZeros().toPlainString() + " still out");
            }
            int lateDays = RentalMath.lateDays(now, (Instant) line.get("dueAt"));
            double lateFee = RentalMath.lateFee(lateDays, lateFeePerDay, quantity);
            Map<String, Object> retLine = db.insert(RentalReturnLine.class, Obj.of("organizationId", orgId,
                    "returnId", ret.get("id"),
                    "agreementLineId", line.get("id"), "unitId", line.get("unitId"),
                    "quantity", RentalRows.dec(quantity), "quantityMissing", RentalRows.dec(quantityMissing),
                    "returnedAt", isMissing ? null : now, "conditionGrade", item.get("conditionGrade"),
                    "isMissing", isMissing, "lateDays", lateDays, "lateFeeAmount", RentalRows.dec(lateFee),
                    "notes", item.get("notes")));
            if (back > 0) {
                // IPR M-7: what came back leaves RENT-OUT now, once (RENT-OUT → RENT-CLEAN); missing units stay out.
                posting.counterReturnTransfer(agreementId, (String) line.get("productId"), back,
                        (String) line.get("unitId"), (String) retLine.get("id"));
            }
        }
        Set<Object> returned = new HashSet<>();
        for (Map<String, Object> i : items) {
            returned.add(i.get("agreementLineId"));
        }
        boolean allBack = agreementLines.stream().allMatch(l -> returned.contains(l.get("id")));
        String to = allBack ? "returned" : "partially_returned";
        db.update(RentalAgreement.class, Obj.of("status", to), BY_ID, agreementId, orgId);
        lifecycle.transition(RentalLifecycles.AGREEMENT, "rental_agreement", agreementId, status, to,
                allBack ? "return" : "partial_return", "rental_return", (String) ret.get("id"), null);
        return ret;
    }

    // ------------------------------------------------------------------ inspection

    /**
     * Grade each returned line, record damages, move stock (clean: RENT-CLEAN → RENT-STOCK, damaged: RENT-CLEAN →
     * RENT-DAMAGED; IPR M-7, see {@code docs/spec/rental.md} Deviations) and set the unit status (damaged /
     * available). A return line is inspected once.
     */
    @Transactional
    public Map<String, Object> inspect(String returnId, Map<String, Object> dto) {
        String orgId = RentalRows.org();
        Map<String, Object> ret = db.first(RentalReturn.class, BY_ID, returnId, orgId);
        if (ret == null) {
            throw ApiException.notFound("Return not found");
        }
        // Nest answers with `this.get(ret.id)` on the non-transactional client from inside its interactive
        // transaction: that read cannot see the uncommitted inspection, so the response is the return as it was
        // before this call. Kept for parity (docs/spec/rental.md).
        Map<String, Object> committed = get(returnId);
        String agreementId = (String) ret.get("agreementId");
        // IPR M-7 / M-8: lock the return row (L1) so a return line is inspected, and its stock moved, exactly once.
        db.jdbc().sql("SELECT id FROM \"RentalReturn\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(returnId, orgId).query(String.class).list();
        List<Map<String, Object>> retLines = db.list(RentalReturnLine.class, "\"returnId\" = ? ORDER BY id", returnId);
        Object rawItems = dto.get("items");
        if (!(rawItems instanceof List<?>)) {
            throw new IllegalStateException("dto.items is not iterable");
        }
        for (Map<String, Object> item : RentalRows.lines(dto, "items")) {
            Map<String, Object> line = retLines.stream().filter(l -> l.get("id").equals(item.get("returnLineId")))
                    .findFirst().orElse(null);
            if (line == null) {
                throw ApiException.badRequest("Unknown return line " + item.get("returnLineId"));
            }
            if (line.get("inspectedAt") != null) {
                throw ApiException.badRequest("Return line " + line.get("id") + " has already been inspected");
            }
            Object grade = item.get("conditionGrade") != null ? item.get("conditionGrade")
                    : line.get("conditionGrade") != null ? line.get("conditionGrade") : "good";
            List<Map<String, Object>> damages = RentalRows.optLines(item, "damages");
            boolean damaged = !damages.isEmpty();
            db.update(RentalReturnLine.class, Obj.of("conditionGrade", grade, "inspectedById", RentalRows.user(),
                    "inspectedAt", PosDb.raw(RentalRows.NOW),
                    "notes", item.get("notes") != null ? item.get("notes") : line.get("notes")), BY_ID, line.get("id"),
                    orgId);
            for (Map<String, Object> dmg : damages) {
                db.insert(RentalDamage.class, Obj.of("organizationId", orgId, "returnLineId", line.get("id"),
                        "damageType", dmg.get("damageType"), "description", dmg.get("description"),
                        "chargeAmount", RentalRows.dec(RentalRows.numOr(dmg, "chargeAmount", 0))));
            }
            boolean missing = Boolean.TRUE.equals(line.get("isMissing"));
            Object unitId = line.get("unitId");
            String stockUnit = unitId != null ? (String) unitId : lineUnit(line.get("agreementLineId"));
            String productId = lineProduct(line.get("agreementLineId"));
            // IPR M-7: a line whose counter return moved its stock to RENT-CLEAN moves on from there; a line returned
            // before that fix still sits in RENT-OUT and moves from RENT-OUT (so RENT-OUT is decremented once either
            // way). Nest moved clean returns RENT-CLEAN → RENT-STOCK although nothing had left RENT-OUT.
            boolean inCleaning = posting.counterReturned((String) line.get("id"));
            double quantity = inCleaning ? posting.counterReturnedQuantity((String) line.get("id"))
                    : PosSql.num(line.get("quantity"));
            if (!damaged && !missing && quantity > 0) {
                if (inCleaning) {
                    posting.cleaningCompleteTransfer(agreementId, productId, quantity, stockUnit);
                } else {
                    posting.legacyReturnToStockTransfer(agreementId, productId, quantity, stockUnit);
                }
            } else if (damaged && !missing && quantity > 0) {
                // a missing line brought nothing back: it stays in RENT-OUT until a later return finds it, so it is
                // never moved to RENT-DAMAGED here (that would decrement RENT-OUT twice once it is found)
                posting.returnTransfer(agreementId, productId, quantity, "damaged", stockUnit, inCleaning);
            }
            if (unitId != null) {
                String live = "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL";
                if (damaged) {
                    db.update(RentalUnit.class, Obj.of("status", "damaged"), live, unitId, orgId);
                } else if (!missing) {
                    db.update(RentalUnit.class, Obj.of("status", "available", "lastInspectedAt",
                            PosDb.raw(RentalRows.NOW)), live, unitId, orgId);
                }
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("returnLineId", line.get("id"));
                lifecycle.transition(RentalLifecycles.UNIT, "rental_unit", (String) unitId, "checked_out",
                        damaged ? "damaged" : "available", damaged ? "dispose" : "inspect", "rental_return", returnId,
                        meta);
            }
        }
        return committed;
    }

    /** {@code SELECT ... FOR UPDATE} on the agreement row (IPR M-7 / M-8, lock level L1). */
    private void lockAgreement(String agreementId, String orgId) {
        db.jdbc().sql("SELECT id FROM \"RentalAgreement\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(agreementId, orgId).query(String.class).list();
    }

    /** What physically came back on a counter-return line: nothing when the line is missing. */
    static double physicallyBack(double quantity, double quantityMissing, boolean isMissing) {
        return isMissing ? 0 : Math.max(0, quantity - Math.max(0, quantityMissing));
    }

    /**
     * IPR M-7 / M-8: what of an agreement line is still out: the quantity checked out (as the checkout transfer reads
     * it: 0 or missing counts as 1) less what earlier counter returns brought back.
     */
    private double stillOut(Map<String, Object> agreementLine) {
        double q = agreementLine.get("quantity") == null ? 0 : PosSql.num(agreementLine.get("quantity"));
        double checkedOut = q == 0 || Double.isNaN(q) ? 1 : q;
        double back = 0;
        for (Map<String, Object> l : db.list(RentalReturnLine.class, "\"agreementLineId\" = ? AND"
                + " \"organizationId\" = ?", agreementLine.get("id"), RentalRows.org())) {
            double quantity = l.get("quantity") == null ? 1 : PosSql.num(l.get("quantity"));
            double missing = l.get("quantityMissing") == null ? 0 : PosSql.num(l.get("quantityMissing"));
            back += physicallyBack(quantity, missing, Boolean.TRUE.equals(l.get("isMissing")));
        }
        return checkedOut - back;
    }

    /** The rental unit of an agreement line (null when the line names none). */
    private String lineUnit(Object agreementLineId) {
        Map<String, Object> line = db.first(RentalAgreementLine.class, BY_ID, agreementLineId, RentalRows.org());
        return line == null ? null : (String) line.get("unitId");
    }

    private String lineProduct(Object agreementLineId) {
        Map<String, Object> line = db.first(RentalAgreementLine.class, BY_ID, agreementLineId, RentalRows.org());
        if (line == null) {
            throw ApiException.badRequest("Unknown agreement line " + agreementLineId);
        }
        return (String) line.get("productId");
    }

    // ------------------------------------------------------------------ settlement

    /**
     * Settlement: late fees + damage charges (minus {@code waiveDamageIds}); the deposit covers what it can (capped by
     * {@code depositApplied}), the residual is billed through the invoice spine, the leftover deposit is refunded, the
     * agreement closes, its bookings complete and the partner score is recomputed.
     */
    @Transactional
    public Map<String, Object> settle(String returnId, Map<String, Object> dto) {
        String orgId = RentalRows.org();
        Map<String, Object> ret = db.first(RentalReturn.class, BY_ID, returnId, orgId);
        if (ret == null) {
            throw ApiException.notFound("Return not found");
        }
        String agreementId = (String) ret.get("agreementId");
        Map<String, Object> agreement = db.first(RentalAgreement.class, BY_ID, agreementId, orgId);
        if (agreement == null) {
            throw ApiException.notFound("Agreement not found");
        }
        String status = (String) agreement.get("status");
        if (!List.of("returned", "partially_returned").contains(status)) {
            throw ApiException.badRequest("Agreement status '" + status + "' cannot be settled");
        }
        Set<String> waive = new HashSet<>(PosSql.strings(dto.get("waiveDamageIds")));
        double lateFeeTotal = 0;
        double damageTotal = 0;
        for (Map<String, Object> line : db.list(RentalReturnLine.class, "\"returnId\" = ?", returnId)) {
            lateFeeTotal += PosSql.num(line.get("lateFeeAmount"));
            for (Map<String, Object> dmg : db.list(RentalDamage.class, "\"returnLineId\" = ?", line.get("id"))) {
                if (!waive.contains((String) dmg.get("id"))) {
                    damageTotal += PosSql.num(dmg.get("chargeAmount"));
                }
            }
        }
        double settlementTotal = lateFeeTotal + damageTotal;
        String returnNumber = (String) ret.get("returnNumber");

        double available = deposit.availableFor(agreementId);
        double cap = dto.get("depositApplied") != null ? RentalRows.num(dto, "depositApplied") : available;
        double apply = Double.isNaN(cap) ? Double.NaN : Math.min(available, Math.min(settlementTotal, cap));
        if (apply > 0) {
            deposit.apply(agreementId, apply, "Settlement of " + returnNumber);
        }
        if (settlementTotal - apply > 0) {
            // R1 (P0-1): a fee line bills its fee product, never the rented product (that would issue it again).
            List<RentalPostingService.FeeLine> fees = new ArrayList<>();
            if (lateFeeTotal > 0) {
                fees.add(new RentalPostingService.FeeLine(posting.requireFeeProduct("RENT-LATE"),
                        "Late fees — " + returnNumber, 1, lateFeeTotal, "late"));
            }
            if (damageTotal > 0) {
                fees.add(new RentalPostingService.FeeLine(posting.requireFeeProduct("RENT-DAMAGE"),
                        "Damage charges — " + returnNumber, 1, damageTotal, "damage"));
            }
            posting.createFeeInvoice(agreementId, (String) agreement.get("partnerId"),
                    (String) agreement.get("branchId"), settlementTotal == lateFeeTotal ? "late" : "damage", fees,
                    RentalRows.str(dto, "paymentMode"));
        }
        double afterApply = deposit.availableFor(agreementId);
        if (afterApply > 0) {
            Map<String, Object> refund = new LinkedHashMap<>();
            refund.put("method", "cash");
            refund.put("amount", afterApply);
            refund.put("note", "Deposit refund after " + returnNumber);
            deposit.refund(agreementId, refund);
        }
        db.update(RentalAgreement.class, Obj.of("status", "closed", "closedAt", PosDb.raw(RentalRows.NOW),
                "settlementTotal", RentalRows.dec(settlementTotal), "lateFeeTotal", RentalRows.dec(lateFeeTotal),
                "damageTotal", RentalRows.dec(damageTotal), "depositApplied", RentalRows.dec(apply)), BY_ID,
                agreementId, orgId);
        lifecycle.transition(RentalLifecycles.AGREEMENT, "rental_agreement", agreementId, status, "closed", "settle",
                "rental_return", returnId, null);
        db.updateMany(RentalBooking.class, Obj.of("status", "completed"), "\"organizationId\" = ? AND"
                + " \"sourceType\" = 'agreement' AND \"sourceId\" = ? AND status IN ('held', 'confirmed', 'active')",
                orgId, agreementId);
        score.recompute((String) agreement.get("partnerId"));
        return get(returnId);
    }

    /** Waive a damage record (the controller requires {@code rental:waive}). */
    @Transactional
    public Map<String, Object> waiveDamage(String damageId, String reason) {
        String orgId = RentalRows.org();
        if (db.first(RentalDamage.class, BY_ID, damageId, orgId) == null) {
            throw ApiException.notFound("Damage record not found");
        }
        return db.update(RentalDamage.class, Obj.of("waivedById", RentalRows.user(), "waiveReason", reason), BY_ID,
                damageId, orgId);
    }
}
