package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalDeposit;
import com.inventoryerp.rental.domain.RentalDepositMovement;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** FX-5: deposit kinds (owner decision 3). */
    public static final List<String> KINDS = List.of("advance", "refundable");

    private final PosDb db;
    private final AuditService audit;

    public RentalDepositService(RentalRows rows, AuditService audit) {
        this.db = rows.db();
        this.audit = audit;
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
        return createForAgreement(agreementId, partnerId, null);
    }

    /**
     * As above with the deposit's kind. FX-5: a rental deposit is a refundable security deposit unless the agreement
     * says it is prepaid rent ({@code advance}).
     */
    @Transactional
    public Map<String, Object> createForAgreement(String agreementId, String partnerId, String kind) {
        String k = kind == null || kind.isBlank() ? "refundable" : kind.trim();
        if (!KINDS.contains(k)) {
            throw ApiException.badRequest("kind must be one of: advance, refundable");
        }
        return db.insert(RentalDeposit.class, Obj.of("organizationId", RentalRows.org(), "agreementId", agreementId,
                "partnerId", partnerId, "status", "held", "kind", k, "classifiedAt", PosDb.raw(RentalRows.NOW),
                "classifiedById", TenantContext.userId(), "classificationReason", "Set at checkout"));
    }

    /** FX-5: deposits not yet classified as advance or refundable (legacy rows), oldest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> classificationReview() {
        return db.list(RentalDeposit.class, "\"organizationId\" = ? AND \"kind\" IS NULL ORDER BY \"createdAt\", id",
                RentalRows.org());
    }

    /**
     * FX-5: classify (or reclassify) an agreement's deposit with a reason. Rental deposits are functional-currency
     * subledger balances, so the classification moves no GL value; it is audited and drives reporting.
     */
    @Transactional
    public Map<String, Object> classify(String agreementId, String kind, String reason) {
        if (kind == null || !KINDS.contains(kind)) {
            throw ApiException.badRequest("kind must be one of: advance, refundable");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("A reason is required to classify a deposit");
        }
        Map<String, Object> deposit = lock(agreementId);
        Object before = deposit.get("kind");
        db.update(RentalDeposit.class, Obj.of("kind", kind, "classifiedAt", PosDb.raw(RentalRows.NOW), "classifiedById",
                TenantContext.userId(), "classificationReason", reason.trim()), "id = ? AND \"organizationId\" = ?",
                deposit.get("id"), RentalRows.org());
        audit.recordInTx(AuditInput.of("RentalDeposit", (String) deposit.get("id"), "update", Obj.of("kind", before),
                Obj.of("kind", kind, "reason", reason.trim())));
        return getByAgreement(agreementId);
    }

    /** Record a collection ({@code method} mirrors the tender). */
    @Transactional
    public Map<String, Object> collect(String agreementId, Map<String, Object> dto) {
        BigDecimal amount = amount(dto);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Deposit amount must be positive");
        }
        Map<String, Object> deposit = lock(agreementId);
        move(deposit, "collected", dto.get("method"), amount, dto.get("paymentId"), dto.get("note"));
        return set(deposit, "totalCollected", amount, status(deposit, amount, Z, Z, Z));
    }

    /** Apply deposit money to the settlement (liability → receivable). */
    @Transactional
    public Map<String, Object> apply(String agreementId, double value, String note) {
        BigDecimal amount = RentalRows.dec(value);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        Map<String, Object> deposit = lock(agreementId);
        BigDecimal available = availableAmount(deposit);
        if (amount.compareTo(available) > 0) {
            throw ApiException.badRequest("Cannot apply " + RentalRows.js(amount.doubleValue()) + ": only " + RentalRows.js(available.doubleValue())
                    + " deposit is still available");
        }
        move(deposit, "applied", "applied", amount, null, note);
        return set(deposit, "totalApplied", amount, status(deposit, Z, amount, Z, Z));
    }

    /** Refund deposit money back to the customer. */
    @Transactional
    public Map<String, Object> refund(String agreementId, Map<String, Object> dto) {
        BigDecimal amount = amount(dto);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        Map<String, Object> deposit = lock(agreementId);
        BigDecimal available = availableAmount(deposit);
        if (amount.compareTo(available) > 0) {
            throw ApiException.badRequest("Cannot refund " + RentalRows.js(amount.doubleValue()) + ": only " + RentalRows.js(available.doubleValue())
                    + " deposit is available");
        }
        move(deposit, "refunded", dto.get("method"), amount, dto.get("paymentId"), dto.get("note"));
        return set(deposit, "totalRefunded", amount, status(deposit, Z, Z, amount, Z));
    }

    /** Forfeit deposit money (damage over deposit, no-show, abandoned return). */
    @Transactional
    public Map<String, Object> forfeit(String agreementId, Map<String, Object> dto) {
        BigDecimal amount = amount(dto);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        Map<String, Object> deposit = lock(agreementId);
        BigDecimal available = availableAmount(deposit);
        if (amount.compareTo(available) > 0) {
            throw ApiException.badRequest("Cannot forfeit " + RentalRows.js(amount.doubleValue()) + ": only " + RentalRows.js(available.doubleValue())
                    + " deposit is available");
        }
        move(deposit, "forfeited", "forfeited", amount, null, dto.get("note"));
        return set(deposit, "totalForfeited", amount, status(deposit, Z, Z, Z, amount));
    }

    /** Collected − applied − refunded − forfeited ({@code 0} when there is no deposit). */
    @Transactional(readOnly = true)
    public double availableFor(String agreementId) {
        Map<String, Object> deposit = db.first(RentalDeposit.class, "\"organizationId\" = ? AND \"agreementId\" = ?",
                RentalRows.org(), agreementId);
        return deposit == null ? 0 : available(deposit);
    }

    static double available(Map<String, Object> d) {
        return availableAmount(d).doubleValue();
    }

    /** FX-5: the open deposit in exact decimal arithmetic. */
    static BigDecimal availableAmount(Map<String, Object> d) {
        return n(d, "totalCollected").subtract(n(d, "totalApplied")).subtract(n(d, "totalRefunded"))
                .subtract(n(d, "totalForfeited"));
    }

    private static final BigDecimal Z = BigDecimal.ZERO;

    private static BigDecimal n(Map<String, Object> d, String k) {
        return SqlRows.dec(d, k);
    }

    private static BigDecimal amount(Map<String, Object> dto) {
        Object v = dto == null ? null : dto.get("amount");
        if (v == null || v.toString().isBlank()) {
            return Z;
        }
        try {
            return new BigDecimal(v.toString()).setScale(6, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return Z;
        }
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

    private void move(Map<String, Object> deposit, String type, Object method, BigDecimal amount, Object paymentId,
            Object note) {
        db.insert(RentalDepositMovement.class, Obj.of("organizationId", RentalRows.org(), "depositId", deposit.get("id"),
                "type", type, "method", method, "amount", amount, "paymentId", paymentId, "note", note));
    }

    private Map<String, Object> set(Map<String, Object> deposit, String total, BigDecimal amount, String status) {
        return db.update(RentalDeposit.class, Obj.of(total, SqlRows.dec(deposit, total).add(amount), "status", status),
                "id = ? AND \"organizationId\" = ?", deposit.get("id"), RentalRows.org());
    }

    private static String status(Map<String, Object> d, BigDecimal addCollected, BigDecimal addApplied,
            BigDecimal addRefunded, BigDecimal addForfeited) {
        return RentalMath.depositStatus(n(d, "totalCollected").add(addCollected), n(d, "totalApplied").add(addApplied),
                n(d, "totalRefunded").add(addRefunded), n(d, "totalForfeited").add(addForfeited));
    }
}
