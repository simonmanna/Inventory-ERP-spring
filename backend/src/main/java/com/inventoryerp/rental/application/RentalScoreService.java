package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalCustomerScore;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-partner rental trust score 0–100 (port of {@code rental-score.service.ts}), materialized on
 * {@code RentalCustomerScore}: 80+ good · 50–79 caution · below 50 blocked; {@code manualHold} blocks checkout
 * regardless of the score.
 */
@Service
public class RentalScoreService {

    /** {@code canCheckout} result. */
    public record Gate(boolean allowed, String reason, int score, String band) {}

    private final PosDb db;

    public RentalScoreService(RentalRows rows) {
        this.db = rows.db();
    }

    private Map<String, Object> row(String partnerId) {
        return db.first(RentalCustomerScore.class, "\"organizationId\" = ? AND \"partnerId\" = ?", RentalRows.org(),
                partnerId);
    }

    /** Can this partner check out a new agreement? */
    @Transactional(readOnly = true)
    public Gate canCheckout(String partnerId) {
        Map<String, Object> row = row(partnerId);
        if (row == null) {
            return new Gate(true, null, 100, "good");
        }
        int score = ((Number) row.get("score")).intValue();
        String band = (String) row.get("band");
        if (Boolean.TRUE.equals(row.get("manualHold"))) {
            return new Gate(false, "manual_hold", score, band);
        }
        if (score < 50) {
            return new Gate(false, "low_score", score, band);
        }
        return new Gate(true, null, score, band);
    }

    /**
     * Recompute the score from raw history and upsert the materialized row: late returns −5 (max −30), damage events
     * −15 (max −30), lost items −20 each, cancelled reservations −2 (max −10), unpaid balance −2 per 100,000 (max −10).
     */
    @Transactional
    public Map<String, Object> recompute(String partnerId) {
        String orgId = RentalRows.org();
        var jdbc = db.jdbc();
        Map<String, Object> agg = jdbc.sql("""
                        SELECT
                          count(*) FILTER (WHERE rl."lateDays" > 0) AS late,
                          count(*) FILTER (WHERE rl."isMissing") AS lost
                        FROM "RentalReturnLine" rl
                        WHERE rl."organizationId" = ? AND rl."agreementLineId" IN (
                          SELECT l.id FROM "RentalAgreementLine" l JOIN "RentalAgreement" a ON a.id = l."agreementId"
                          WHERE l."organizationId" = ? AND a."organizationId" = ? AND a."partnerId" = ?)""")
                .params(orgId, orgId, orgId, partnerId).query().singleRow();
        long damages = jdbc.sql("""
                        SELECT count(*) FROM "RentalDamage" d
                        JOIN "RentalReturnLine" rl ON rl.id = d."returnLineId"
                        JOIN "RentalReturn" r ON r.id = rl."returnId"
                        JOIN "RentalAgreement" a ON a.id = r."agreementId"
                        WHERE d."organizationId" = ? AND a."organizationId" = ? AND a."partnerId" = ?""")
                .params(orgId, orgId, partnerId).query(Long.class).single();
        long cancelled = jdbc.sql("SELECT count(*) FROM \"RentalReservation\" WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ? AND status = 'cancelled'").params(orgId, partnerId).query(Long.class).single();
        long completed = jdbc.sql("SELECT count(*) FROM \"RentalAgreement\" WHERE \"organizationId\" = ? AND"
                + " \"partnerId\" = ? AND status = 'closed'").params(orgId, partnerId).query(Long.class).single();
        // Deviation (docs/spec/rental.md): Nest aggregates invoices in status 'open', a value InvoiceStatus does not
        // have, so Prisma rejects the query and every recompute (GET /scores/:partnerId, settle) fails with 500. The
        // filter can match no invoice, so the balance it would sum is 0.
        double unpaidBalance = 0;

        int lateReturns = ((Number) agg.get("late")).intValue();
        int lostItems = ((Number) agg.get("lost")).intValue();
        int damageEvents = (int) damages;
        int score = RentalMath.score(lateReturns, damageEvents, lostItems, cancelled, unpaidBalance);
        String band = RentalMath.band(score);

        return SqlRows.one(RentalCustomerScore.class, jdbc.sql("""
                        INSERT INTO "RentalCustomerScore" (id, "organizationId", "partnerId", "lateReturns", "damageEvents",
                          "lostItems", "unpaidBalance", "cancelledReservations", "completedRentals", score, band)
                        VALUES (gen_random_uuid()::text, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT ("organizationId", "partnerId") DO UPDATE SET
                          "lateReturns" = EXCLUDED."lateReturns", "damageEvents" = EXCLUDED."damageEvents",
                          "lostItems" = EXCLUDED."lostItems", "unpaidBalance" = EXCLUDED."unpaidBalance",
                          "cancelledReservations" = EXCLUDED."cancelledReservations",
                          "completedRentals" = EXCLUDED."completedRentals", score = EXCLUDED.score,
                          band = EXCLUDED.band, "computedAt" = %s
                        RETURNING *""".formatted(RentalRows.NOW))
                .params(orgId, partnerId, lateReturns, damageEvents, lostItems, RentalRows.dec(unpaidBalance),
                        (int) cancelled, (int) completed, score, band));
    }

    @Transactional
    public Map<String, Object> setManualHold(String partnerId, boolean manualHold) {
        return SqlRows.one(RentalCustomerScore.class, db.jdbc().sql("""
                        INSERT INTO "RentalCustomerScore" (id, "organizationId", "partnerId", "manualHold", score, band)
                        VALUES (gen_random_uuid()::text, ?, ?, ?, 50, 'caution')
                        ON CONFLICT ("organizationId", "partnerId") DO UPDATE SET "manualHold" = EXCLUDED."manualHold"
                        RETURNING *""")
                .params(RentalRows.org(), partnerId, manualHold));
    }
}
