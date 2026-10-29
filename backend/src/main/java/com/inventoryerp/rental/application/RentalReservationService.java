package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalBooking;
import com.inventoryerp.rental.domain.RentalReservation;
import com.inventoryerp.rental.domain.RentalReservationLine;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pre-booking holds (port of {@code rental-reservation.service.ts}): a reservation blocks the calendar with
 * {@code held} bookings until it expires (cron) or converts into an agreement (bookings re-pointed, confirmed).
 */
@Service
public class RentalReservationService {

    private final RentalRows rows;
    private final PosDb db;
    private final SequenceService sequence;
    private final SettingResolverService settings;
    private final RentalAvailabilityService availability;

    public RentalReservationService(RentalRows rows, SequenceService sequence, SettingResolverService settings,
            RentalAvailabilityService availability) {
        this.rows = rows;
        this.db = rows.db();
        this.sequence = sequence;
        this.settings = settings;
        this.availability = availability;
    }

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
        long total = db.count(RentalReservation.class, where.toString(), params.toArray());
        params.add(p.limit());
        params.add(p.offset());
        List<Map<String, Object>> items = db.list(RentalReservation.class, where
                + " ORDER BY \"createdAt\" DESC LIMIT ? OFFSET ?", params.toArray());
        for (Map<String, Object> r : items) {
            r.put("lines", linesWithProduct(r.get("id")));
        }
        return RentalRows.envelope(total, p, items);
    }

    private List<Map<String, Object>> linesWithProduct(Object reservationId) {
        return RentalRows.with(db.list(RentalReservationLine.class, "\"reservationId\" = ?", reservationId),
                "product", l -> rows.productBrief(l.get("productId")));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> res = db.first(RentalReservation.class, "id = ? AND \"organizationId\" = ?", id,
                RentalRows.org());
        if (res == null) {
            throw ApiException.notFound("Reservation not found");
        }
        res.put("lines", linesWithProduct(id));
        return res;
    }

    /** Create a hold; every line is availability-checked, the whole reservation is rejected otherwise. */
    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        String orgId = RentalRows.org();
        Instant startAt = RentalRows.date(dto.get("startAt"));
        Instant endAt = RentalRows.date(dto.get("endAt"));
        if (!RentalRows.after(endAt, startAt)) {
            throw ApiException.badRequest("endAt must be after startAt");
        }
        List<Map<String, Object>> lines = RentalRows.lines(dto, "lines");
        if (lines.isEmpty()) {
            throw ApiException.badRequest("At least one line is required");
        }
        double holdMinutes = dto.get("holdMinutes") != null ? RentalRows.num(dto, "holdMinutes")
                : settings.resolveNumber("rental.autoHoldMinutes", SettingContext.EMPTY).doubleValue();
        Instant expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() + (long) (holdMinutes * 60_000));

        for (Map<String, Object> line : lines) {
            assertAvailable(availability, rows, line, startAt, endAt);
        }
        String number = sequence.next("rental_reservation", "RSV-", 5);
        Map<String, Object> reservation = db.insert(RentalReservation.class, Obj.of("organizationId", orgId,
                "reservationNumber", number, "partnerId", dto.get("partnerId"), "status", "held", "startAt", startAt,
                "endAt", endAt, "expiresAt", expiresAt, "holdMinutes", (int) holdMinutes, "notes", dto.get("notes"),
                "branchId", dto.get("branchId"), "createdBy", RentalRows.user()));
        String id = (String) reservation.get("id");
        List<Map<String, Object>> created = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            created.add(db.insert(RentalReservationLine.class, Obj.of("organizationId", orgId, "reservationId", id,
                    "productId", l.get("productId"), "quantity", RentalRows.dec(RentalRows.numOr(l, "quantity", 1)))));
        }
        reservation.put("lines", created);
        // Block the calendar: one held booking per line (count-level, no unit assigned).
        for (Map<String, Object> l : lines) {
            db.insert(RentalBooking.class, Obj.of("organizationId", orgId, "productId", l.get("productId"),
                    "quantity", RentalRows.dec(RentalRows.numOr(l, "quantity", 1)), "startAt", startAt, "endAt", endAt,
                    "status", "held", "sourceType", "reservation", "sourceId", id, "expiresAt", expiresAt));
        }
        return reservation;
    }

    /** {@code Insufficient availability for '<name>': requested n, available m}. */
    static void assertAvailable(RentalAvailabilityService availability, RentalRows rows, Map<String, Object> line,
            Instant startAt, Instant endAt) {
        String productId = RentalRows.str(line, "productId");
        long available = availability.countAvailable(productId, startAt, endAt, null);
        double requested = RentalRows.numOr(line, "quantity", 1);
        if (available < requested) {
            Map<String, Object> product = rows.product(productId);
            String name = product != null && product.get("name") != null ? (String) product.get("name") : productId;
            throw ApiException.badRequest("Insufficient availability for '" + name + "': requested "
                    + RentalRows.js(requested) + ", available " + available);
        }
    }

    /** Cancel a held reservation: releases its bookings. */
    @Transactional
    public Map<String, Object> cancel(String id) {
        String orgId = RentalRows.org();
        Map<String, Object> res = db.first(RentalReservation.class, "id = ? AND \"organizationId\" = ?", id, orgId);
        if (res == null) {
            throw ApiException.notFound("Reservation not found");
        }
        if (!List.of("held", "draft").contains(res.get("status"))) {
            throw ApiException.badRequest("Cannot cancel a reservation in status '" + res.get("status") + "'");
        }
        db.updateMany(RentalBooking.class, Obj.of("status", "cancelled"),
                "\"organizationId\" = ? AND \"sourceType\" = 'reservation' AND \"sourceId\" = ?", orgId, id);
        return db.update(RentalReservation.class, Obj.of("status", "cancelled"), "id = ? AND \"organizationId\" = ?", id,
                orgId);
    }

    /**
     * Convert a held reservation into an agreement (caller's transaction): bookings re-pointed to the agreement and
     * promoted to {@code confirmed}; the reservation becomes {@code converted}.
     */
    @Transactional
    public Map<String, Object> convertToAgreement(String reservationId, String agreementId) {
        String orgId = RentalRows.org();
        Map<String, Object> res = db.first(RentalReservation.class, "id = ? AND \"organizationId\" = ?", reservationId,
                orgId);
        if (res == null) {
            throw ApiException.notFound("Reservation not found");
        }
        if (!"held".equals(res.get("status"))) {
            throw ApiException.badRequest("Cannot convert a reservation in status '" + res.get("status") + "'");
        }
        db.updateMany(RentalBooking.class, Obj.of("status", "confirmed", "sourceType", "agreement", "sourceId",
                agreementId, "expiresAt", null), "\"organizationId\" = ? AND \"sourceType\" = 'reservation' AND"
                + " \"sourceId\" = ?", orgId, reservationId);
        return db.update(RentalReservation.class, Obj.of("status", "converted"), "id = ? AND \"organizationId\" = ?",
                reservationId, orgId);
    }
}
