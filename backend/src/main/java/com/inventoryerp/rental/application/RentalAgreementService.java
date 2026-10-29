package com.inventoryerp.rental.application;

import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.workflow.LifecycleService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.rental.domain.RentalAgreement;
import com.inventoryerp.rental.domain.RentalAgreementLine;
import com.inventoryerp.rental.domain.RentalBooking;
import com.inventoryerp.rental.domain.RentalDeposit;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rental contract (port of {@code rental-agreement.service.ts}): draft → confirmed (approval-gated) →
 * checked_out → (partially_)returned → closed, cancel from draft / pending_approval. Checkout is the money path: stock
 * transfer RENT-STOCK → RENT-OUT, deposit ledger + collection, the rental fee invoice, unit and booking status — one
 * transaction.
 */
@Service
public class RentalAgreementService {

    private static final String BY_ID = "id = ? AND \"organizationId\" = ?";

    private final RentalRows rows;
    private final PosDb db;
    private final LifecycleService lifecycle;
    private final SequenceService sequence;
    private final SettingResolverService settings;
    private final ApprovalsService approvals;
    private final RentalCatalogService catalog;
    private final RentalAvailabilityService availability;
    private final RentalReservationService reservations;
    private final RentalScoreService score;
    private final RentalPostingService posting;
    private final RentalDepositService deposit;
    private final TransactionTemplate newTx;

    public RentalAgreementService(RentalRows rows, LifecycleService lifecycle, SequenceService sequence,
            SettingResolverService settings, ApprovalsService approvals, RentalCatalogService catalog,
            RentalAvailabilityService availability, RentalReservationService reservations, RentalScoreService score,
            RentalPostingService posting, RentalDepositService deposit, PlatformTransactionManager txm) {
        this.rows = rows;
        this.db = rows.db();
        this.lifecycle = lifecycle;
        this.sequence = sequence;
        this.settings = settings;
        this.approvals = approvals;
        this.catalog = catalog;
        this.availability = availability;
        this.reservations = reservations;
        this.score = score;
        this.posting = posting;
        this.deposit = deposit;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> q) {
        RentalRows.Paging p = RentalRows.paging(q);
        StringBuilder where = new StringBuilder("\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(RentalRows.org()));
        if (RentalUnitService.notEmpty(q.get("status"))) {
            where.append(" AND status = ?");
            params.add(q.get("status"));
        }
        if (RentalUnitService.notEmpty(q.get("partnerId"))) {
            where.append(" AND \"partnerId\" = ?");
            params.add(q.get("partnerId"));
        }
        if (RentalUnitService.notEmpty(q.get("search"))) {
            where.append(" AND \"agreementNumber\" ILIKE ? ESCAPE '\\'");
            params.add("%" + BaseCrudService.escapeLike(q.get("search")) + "%");
        }
        long total = db.count(RentalAgreement.class, where.toString(), params.toArray());
        params.add(p.limit());
        params.add(p.offset());
        List<Map<String, Object>> items = db.list(RentalAgreement.class, where
                + " ORDER BY \"createdAt\" DESC LIMIT ? OFFSET ?", params.toArray());
        for (Map<String, Object> a : items) {
            a.put("partner", rows.partnerBrief(a.get("partnerId"), "id", "name", "code"));
            a.put("lines", lines(a.get("id"), false, "id", "unitCode"));
        }
        return RentalRows.envelope(total, p, items);
    }

    /**
     * Lines with {@code product} and (when {@code unitKeys} are given) {@code unit} briefs. Only {@code get} orders
     * them ({@code orderBy: {id: 'asc'}}); the other Nest includes have no order, so the rows come back in the
     * database's order like Prisma's.
     */
    private List<Map<String, Object>> lines(Object agreementId, boolean byId, String... unitKeys) {
        List<Map<String, Object>> lines = db.list(RentalAgreementLine.class, "\"agreementId\" = ?"
                + (byId ? " ORDER BY id ASC" : ""), agreementId);
        for (Map<String, Object> l : lines) {
            l.put("product", rows.productBrief(l.get("productId")));
            if (unitKeys.length > 0) {
                l.put("unit", rows.unitBrief(l.get("unitId"), unitKeys));
            }
        }
        return lines;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> agreement = db.first(RentalAgreement.class, BY_ID, id, RentalRows.org());
        if (agreement == null) {
            throw ApiException.notFound("Agreement not found");
        }
        agreement.put("partner", rows.partnerBrief(agreement.get("partnerId"), "id", "name", "code", "phone"));
        agreement.put("lines", lines(id, true, "id", "unitCode", "conditionGrade"));
        return agreement;
    }

    // ------------------------------------------------------------------ create

    /**
     * Create a draft agreement: lines resolve their rate through the catalog (falling back to the product sale
     * price), the window is availability-checked per line, one held booking blocks each line and a
     * {@code reservationId} converts that hold.
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        String orgId = RentalRows.org();
        Instant startAt = RentalRows.date(dto.get("startAt"));
        Instant dueAt = RentalRows.date(dto.get("dueAt"));
        if (!RentalRows.after(dueAt, startAt)) {
            throw ApiException.badRequest("dueAt must be after startAt");
        }
        List<Map<String, Object>> lines = RentalRows.lines(dto, "lines");
        if (lines.isEmpty()) {
            throw ApiException.badRequest("At least one line is required");
        }
        if (db.first(Partner.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", dto.get("partnerId"),
                orgId) == null) {
            throw ApiException.notFound("Partner not found");
        }
        for (Map<String, Object> line : lines) {
            RentalReservationService.assertAvailable(availability, rows, line, startAt, dueAt);
        }

        String number = sequence.next("rental_agreement", "RAG-", 5);
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("lateFeePerDay", settings.resolveNumber("rental.lateFeePerDay", SettingContext.EMPTY));
        terms.put("holdMinutes", settings.resolveNumber("rental.autoHoldMinutes", SettingContext.EMPTY));
        terms.put("depositPolicy", settings.resolve("rental.depositPolicy", SettingContext.EMPTY));
        Map<String, Object> agreement = db.insert(RentalAgreement.class, Obj.of("organizationId", orgId,
                "agreementNumber", number, "partnerId", dto.get("partnerId"), "status", "draft", "startAt", startAt,
                "dueAt", dueAt, "packageId", dto.get("packageId"), "branchId", dto.get("branchId"),
                "notes", dto.get("notes"), "termsSnapshot", PosDb.json(terms), "createdBy", RentalRows.user()));
        String id = (String) agreement.get("id");

        for (Map<String, Object> line : lines) {
            String productId = RentalRows.str(line, "productId");
            String period = line.get("ratePeriod") != null ? line.get("ratePeriod").toString() : "day";
            double rateUnits = RentalRows.numOr(line, "rateUnits", 1);
            double unitRate = RentalRows.numOr(line, "unitRate", 0);
            if (!(unitRate > 0)) {
                RentalCatalogService.ResolvedRate resolved = catalog.resolveRate(productId, period, rateUnits);
                unitRate = resolved == null ? 0 : resolved.unitRate();
            }
            if (!(unitRate > 0)) {
                Map<String, Object> product = rows.product(productId);
                unitRate = product == null ? 0 : PosSql.num(product.get("salePrice"));
            }
            double quantity = RentalRows.numOr(line, "quantity", 1);
            double lineTotal = unitRate * rateUnits * quantity;
            if (rateUnits != Math.rint(rateUnits)) {
                throw new IllegalStateException("Argument `rateUnits`: Invalid value provided. Expected Int.");
            }
            db.insert(RentalAgreementLine.class, Obj.of("organizationId", orgId, "agreementId", id,
                    "productId", productId, "unitId", line.get("unitId"), "quantity", RentalRows.dec(quantity),
                    "ratePeriod", period, "rateUnits", (int) rateUnits, "unitRate", RentalRows.dec(unitRate),
                    "lineTotal", RentalRows.dec(lineTotal), "dueAt", dueAt));
        }
        for (Map<String, Object> l : db.list(RentalAgreementLine.class, "\"agreementId\" = ?", id)) {
            db.insert(RentalBooking.class, Obj.of("organizationId", orgId, "productId", l.get("productId"),
                    "unitId", l.get("unitId"), "quantity", l.get("quantity"), "startAt", startAt, "endAt", dueAt,
                    "status", "held", "sourceType", "agreement", "sourceId", id));
        }
        if (RentalRows.str(dto, "reservationId") != null && !RentalRows.str(dto, "reservationId").isEmpty()) {
            reservations.convertToAgreement(RentalRows.str(dto, "reservationId"), id);
        }
        Map<String, Object> out = db.first(RentalAgreement.class, "id = ?", id);
        out.put("partner", rows.partnerBrief(out.get("partnerId"), "id", "name", "code"));
        out.put("lines", lines(id, false));
        return out;
    }

    // ------------------------------------------------------------------ confirm / cancel

    /** Confirm a draft (approval-gated: a pending request parks it in {@code pending_approval}). */
    @Transactional
    public Map<String, Object> confirm(String id) {
        String orgId = RentalRows.org();
        Map<String, Object> agreement = require(id);
        if (!"draft".equals(agreement.get("status"))) {
            throw ApiException.badRequest("Cannot confirm an agreement in status '" + agreement.get("status") + "'");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("agreementNumber", agreement.get("agreementNumber"));
        snapshot.put("partnerId", agreement.get("partnerId"));
        snapshot.put("status", agreement.get("status"));
        Map<String, Object> approval = approvals.checkOrRequestApproval("rental_agreement", id, snapshot);
        if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
            return db.update(RentalAgreement.class, Obj.of("status", "pending_approval"), BY_ID, id, orgId);
        }
        lifecycle.transition(RentalLifecycles.AGREEMENT, "rental_agreement", id, "draft", "confirmed", "confirm",
                "rental_agreement", id, null);
        db.updateMany(RentalBooking.class, Obj.of("status", "confirmed"), "\"organizationId\" = ? AND"
                + " \"sourceType\" = 'agreement' AND \"sourceId\" = ? AND status = 'held'", orgId, id);
        return db.update(RentalAgreement.class, Obj.of("status", "confirmed", "confirmedAt", PosDb.raw(RentalRows.NOW),
                "confirmedBy", RentalRows.user()), BY_ID, id, orgId);
    }

    /** Cancel a draft / pending agreement — releases its bookings. */
    @Transactional
    public Map<String, Object> cancel(String id) {
        String orgId = RentalRows.org();
        Map<String, Object> agreement = require(id);
        String status = (String) agreement.get("status");
        if (!List.of("draft", "pending_approval").contains(status)) {
            throw ApiException.badRequest("Cannot cancel an agreement in status '" + status + "'");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reason", null);
        lifecycle.transition(RentalLifecycles.AGREEMENT, "rental_agreement", id, status, "cancelled", "cancel",
                "rental_agreement", id, meta);
        db.updateMany(RentalBooking.class, Obj.of("status", "cancelled"), "\"organizationId\" = ? AND"
                + " \"sourceType\" = 'agreement' AND \"sourceId\" = ? AND status IN ('held', 'confirmed')", orgId, id);
        return db.update(RentalAgreement.class, Obj.of("status", "cancelled", "notes", agreement.get("notes")), BY_ID,
                id, orgId);
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> agreement = db.first(RentalAgreement.class, BY_ID, id, RentalRows.org());
        if (agreement == null) {
            throw ApiException.notFound("Agreement not found");
        }
        return agreement;
    }

    // ------------------------------------------------------------------ checkout

    /**
     * Checkout — the money path, in one transaction: score gate (manager override recorded), stock transfer per line,
     * deposit ledger + collection, the rental fee invoice, units → checked_out, bookings → active.
     */
    @Transactional
    public Map<String, Object> checkout(String id, Map<String, Object> dto) {
        String orgId = RentalRows.org();
        Map<String, Object> agreement = get(id);
        if (!"confirmed".equals(agreement.get("status"))) {
            throw ApiException.badRequest("Checkout requires a confirmed agreement (status: '" + agreement.get("status")
                    + "')");
        }
        String partnerId = (String) agreement.get("partnerId");
        RentalScoreService.Gate gate = score.canCheckout(partnerId);
        if (!gate.allowed()) {
            String overrideById = RentalRows.str(dto, "overrideById");
            if (PosSql.truthy(overrideById)) {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("partnerId", partnerId);
                snapshot.put("reason", gate.reason());
                snapshot.put("score", gate.score());
                // Nest records the override before (outside) the checkout transaction.
                newTx.executeWithoutResult(s -> approvals.recordSynchronousOverride("rental_score_hold", id,
                        overrideById, snapshot, null));
            } else {
                throw ApiException.badRequest("Partner blocked from checkout (" + gate.reason() + ", score "
                        + gate.score() + ") — requires a manager override");
            }
        }
        Object policy = settings.resolve("rental.depositPolicy", SettingContext.EMPTY);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agreementLines = (List<Map<String, Object>>) agreement.get("lines");
        double depositAmount = dto.get("depositAmount") != null ? RentalRows.num(dto, "depositAmount")
                : RentalMath.computeDeposit(agreementLines, policy instanceof Map<?, ?> m ? m : null);

        List<Map<String, Object>> lines = db.list(RentalAgreementLine.class, "\"agreementId\" = ? AND"
                + " \"organizationId\" = ?", id, orgId);
        for (Map<String, Object> line : lines) {
            posting.checkoutTransfer(id, (String) line.get("productId"), line.get("quantity"));
        }
        deposit.createForAgreement(id, partnerId);
        if (depositAmount > 0) {
            Map<String, Object> collect = new LinkedHashMap<>();
            collect.put("method", dto.get("depositMethod") != null ? dto.get("depositMethod") : "cash");
            collect.put("amount", depositAmount);
            collect.put("note", "Deposit collected at checkout");
            deposit.collect(id, collect);
        }
        Map<String, Object> fresh = db.first(RentalAgreement.class, BY_ID, id, orgId);
        Map<String, Object> billed = posting.checkoutInvoice(fresh, lines);

        for (Map<String, Object> line : lines) {
            Object unitId = line.get("unitId");
            // Deviation (docs/spec/rental.md): Nest's `updateMany({where: {organizationId, id: line.unitId ??
            // undefined}})` drops the id filter for a line without a unit and checks out every unit of the org.
            if (unitId != null) {
                db.updateMany(com.inventoryerp.rental.domain.RentalUnit.class, Obj.of("status", "checked_out",
                        "rentalCount", PosDb.raw("\"rentalCount\" + 1")), "\"organizationId\" = ? AND id = ? AND"
                        + " \"deletedAt\" IS NULL", orgId, unitId);
            }
            db.update(RentalAgreementLine.class, Obj.of("checkedOutAt", PosDb.raw(RentalRows.NOW)), BY_ID,
                    line.get("id"), orgId);
            lifecycle.transition(RentalLifecycles.UNIT, "rental_unit",
                    (String) (unitId != null ? unitId : line.get("id")), "available", "checked_out", "checkout",
                    "rental_agreement", id, null);
        }
        db.updateMany(RentalBooking.class, Obj.of("status", "active"), "\"organizationId\" = ? AND"
                + " \"sourceType\" = 'agreement' AND \"sourceId\" = ? AND status = 'confirmed'", orgId, id);
        lifecycle.transition(RentalLifecycles.AGREEMENT, "rental_agreement", id, "confirmed", "checked_out", "checkout",
                "rental_agreement", id, null);
        Map<String, Object> dep = db.first(RentalDeposit.class, "\"agreementId\" = ? AND \"organizationId\" = ?", id,
                orgId);
        return db.update(RentalAgreement.class, Obj.of("status", "checked_out", "orderId", billed.get("orderId"),
                "invoiceId", billed.get("invoiceId"), "depositId", dep == null ? null : dep.get("id"),
                "checkedOutAt", PosDb.raw(RentalRows.NOW)), BY_ID, id, orgId);
    }
}
