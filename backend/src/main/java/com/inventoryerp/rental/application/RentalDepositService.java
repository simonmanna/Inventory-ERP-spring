package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalDeposit;
import com.inventoryerp.rental.domain.RentalDepositMovement;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deposit ledger (port of {@code rental-deposit.service.ts}). The deposit is a liability, never revenue:
 * movements (collected / applied / refunded / forfeited) are append-only and the header totals are updated under a
 * {@code FOR UPDATE} lock on the deposit row. Every public method joins the caller's transaction (checkout, settle).
 */
@Service
public class RentalDepositService {

    private final PosDb db;

    public RentalDepositService(RentalRows rows) {
        this.db = rows.db();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getByAgreement(String agreementId) {
        Map<String, Object> deposit = db.first(RentalDeposit.class, "\"agreementId\" = ? AND \"organizationId\" = ?",
                agreementId, RentalRows.org());
        if (deposit == null) {
            throw ApiException.notFound("No deposit for this agreement");
        }
        deposit.put("movements", db.list(RentalDepositMovement.class, "\"depositId\" = ? ORDER BY \"createdAt\" ASC, id",
                deposit.get("id")));
        return deposit;
    }

    /** Create the deposit ledger for an agreement (called at checkout). */
    @Transactional
    public Map<String, Object> createForAgreement(String agreementId, String partnerId) {
        return db.insert(RentalDeposit.class, Obj.of("organizationId", RentalRows.org(), "agreementId", agreementId,
                "partnerId", partnerId, "status", "held"));
    }

    /** Record a collection ({@code method} mirrors the tender). */
    @Transactional
    public Map<String, Object> collect(String agreementId, Map<String, Object> dto) {
        double amount = RentalRows.num(dto, "amount");
        if (!(amount > 0)) {
            throw ApiException.badRequest("Deposit amount must be positive");
        }
        Map<String, Object> deposit = lock(agreementId);
        move(deposit, "collected", dto.get("method"), amount, dto.get("paymentId"), dto.get("note"));
        return set(deposit, "totalCollected", amount, status(deposit, amount, 0, 0, 0));
    }

    /** Apply deposit money to the settlement (liability → receivable). */
    @Transactional
    public Map<String, Object> apply(String agreementId, double amount, String note) {
        if (!(amount > 0)) {
            throw ApiException.badRequest("Amount must be positive");
        }
        Map<String, Object> deposit = lock(agreementId);
        double available = available(deposit);
        if (amount > available) {
            throw ApiException.badRequest("Cannot apply " + RentalRows.js(amount) + ": only " + RentalRows.js(available)
                    + " deposit is still available");
        }
        move(deposit, "applied", "applied", amount, null, note);
        return set(deposit, "totalApplied", amount, status(deposit, 0, amount, 0, 0));
    }

    /** Refund deposit money back to the customer. */
    @Transactional
    public Map<String, Object> refund(String agreementId, Map<String, Object> dto) {
        double amount = RentalRows.num(dto, "amount");
        if (!(amount > 0)) {
            throw ApiException.badRequest("Amount must be positive");
        }
        Map<String, Object> deposit = lock(agreementId);
        double available = available(deposit);
        if (amount > available) {
            throw ApiException.badRequest("Cannot refund " + RentalRows.js(amount) + ": only " + RentalRows.js(available)
                    + " deposit is available");
        }
        move(deposit, "refunded", dto.get("method"), amount, dto.get("paymentId"), dto.get("note"));
        return set(deposit, "totalRefunded", amount, status(deposit, 0, 0, amount, 0));
    }

    /** Forfeit deposit money (damage over deposit, no-show, abandoned return). */
    @Transactional
    public Map<String, Object> forfeit(String agreementId, Map<String, Object> dto) {
        double amount = RentalRows.num(dto, "amount");
        if (!(amount > 0)) {
            throw ApiException.badRequest("Amount must be positive");
        }
        Map<String, Object> deposit = lock(agreementId);
        double available = available(deposit);
        if (amount > available) {
            throw ApiException.badRequest("Cannot forfeit " + RentalRows.js(amount) + ": only " + RentalRows.js(available)
                    + " deposit is available");
        }
        move(deposit, "forfeited", "forfeited", amount, null, dto.get("note"));
        return set(deposit, "totalForfeited", amount, status(deposit, 0, 0, 0, amount));
    }

    /** Collected − applied − refunded − forfeited ({@code 0} when there is no deposit). */
    @Transactional(readOnly = true)
    public double availableFor(String agreementId) {
        Map<String, Object> deposit = db.first(RentalDeposit.class, "\"organizationId\" = ? AND \"agreementId\" = ?",
                RentalRows.org(), agreementId);
        return deposit == null ? 0 : available(deposit);
    }

    static double available(Map<String, Object> d) {
        return n(d, "totalCollected") - n(d, "totalApplied") - n(d, "totalRefunded") - n(d, "totalForfeited");
    }

    private static double n(Map<String, Object> d, String k) {
        return SqlRows.dec(d, k).doubleValue();
    }

    /** {@code SELECT ... FOR UPDATE} on the agreement's deposit row (concurrent settlement guard). */
    private Map<String, Object> lock(String agreementId) {
        String orgId = RentalRows.org();
        List<String> ids = db.jdbc().sql("SELECT id FROM \"RentalDeposit\" WHERE \"organizationId\" = ? AND"
                + " \"agreementId\" = ? FOR UPDATE").params(orgId, agreementId).query(String.class).list();
        if (ids.isEmpty()) {
            throw ApiException.notFound("No deposit for this agreement");
        }
        return db.first(RentalDeposit.class, "id = ? AND \"organizationId\" = ?", ids.getFirst(), orgId);
    }

    private void move(Map<String, Object> deposit, String type, Object method, double amount, Object paymentId,
            Object note) {
        db.insert(RentalDepositMovement.class, Obj.of("organizationId", RentalRows.org(), "depositId", deposit.get("id"),
                "type", type, "method", method, "amount", RentalRows.dec(amount), "paymentId", paymentId, "note", note));
    }

    private Map<String, Object> set(Map<String, Object> deposit, String total, double amount, String status) {
        return db.update(RentalDeposit.class, Obj.of(total, SqlRows.dec(deposit, total).add(RentalRows.dec(amount)),
                "status", status), "id = ? AND \"organizationId\" = ?", deposit.get("id"), RentalRows.org());
    }

    private static String status(Map<String, Object> d, double addCollected, double addApplied, double addRefunded,
            double addForfeited) {
        double collected = n(d, "totalCollected") + addCollected;
        double applied = n(d, "totalApplied") + addApplied;
        double refunded = n(d, "totalRefunded") + addRefunded;
        double forfeited = n(d, "totalForfeited") + addForfeited;
        if (collected == 0) {
            return "held";
        }
        double consumed = applied + refunded + forfeited;
        if (consumed >= collected) {
            if (refunded > 0 && applied == 0 && forfeited == 0) {
                return "refunded";
            }
            if (forfeited > 0 && applied == 0 && refunded == 0) {
                return "forfeited";
            }
            return "applied";
        }
        return "partially_applied";
    }
}
