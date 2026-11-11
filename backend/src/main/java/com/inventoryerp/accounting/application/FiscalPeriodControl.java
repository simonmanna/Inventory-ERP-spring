package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Period control (port of {@code posting/fiscal-period.service.ts}). A posting date is rejected when it is
 * on/before the org's {@code booksLockDate}, inside a closed/locked fiscal period, or — with
 * {@code requireFiscalPeriod} — not covered by any period. The covering period row is held FOR SHARE so a
 * concurrent close cannot flip it between the check and the write.
 */
@Service
public class FiscalPeriodControl {

    private final JdbcClient jdbc;

    public FiscalPeriodControl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** UTC {@code YYYY-MM-DD} like {@code toISOString().slice(0, 10)}. */
    static String day(Instant i) {
        return i.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** The accounting (org-local) {@code YYYY-MM-DD} of {@code i}; {@link #day(Instant)} for a UTC organisation. */
    static String day(Instant i, ZoneId zone) {
        return AccountingDay.of(i, zone).toString();
    }

    static Timestamp ts(Instant i) {
        return Timestamp.valueOf(i.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    /** Whether {@link #assertOpen} would accept {@code date} (books lock, period status, required period). */
    public boolean isOpen(Instant date) {
        try {
            assertOpen(date);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    /**
     * Refuse {@code date} when it is closed for posting. Decided on the accounting (org-local) day, ADR-S070: the
     * period bounds and the books lock are org-local wall-clock values, so the posting instant is compared as the
     * org-local wall clock (for a UTC organisation, exactly the instant).
     */
    public void assertOpen(Instant date) {
        String orgId = TenantContext.organizationId();
        ZoneId zone = AccountingDay.zone(jdbc, orgId);
        Instant local = AccountingDay.toWallClock(date, zone);
        record Org(Timestamp booksLockDate, boolean requireFiscalPeriod) {}
        Org org = jdbc.sql("SELECT \"booksLockDate\", \"requireFiscalPeriod\" FROM \"Organization\" WHERE id = ?")
                .param(orgId)
                .query((rs, n) -> new Org(rs.getTimestamp("booksLockDate"), rs.getBoolean("requireFiscalPeriod")))
                .optional().orElse(null);
        if (org != null && org.booksLockDate() != null) {
            Instant lock = org.booksLockDate().toLocalDateTime().toInstant(ZoneOffset.UTC);
            if (!local.isAfter(lock)) {
                throw ApiException.badRequest("Books are locked through " + day(lock) + "; cannot post on " + day(local));
            }
        }
        jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"startDate\" <= ? AND \"endDate\" >= ?"
                        + " FOR SHARE")
                .params(orgId, ts(local), ts(local)).query(String.class).list();
        record Period(String name, String status) {}
        Period period = jdbc.sql("SELECT name, status::text AS status FROM \"FiscalPeriod\" WHERE \"organizationId\" = ?"
                        + " AND \"startDate\" <= ? AND \"endDate\" >= ? AND \"deletedAt\" IS NULL LIMIT 1")
                .params(orgId, ts(local), ts(local))
                .query((rs, n) -> new Period(rs.getString("name"), rs.getString("status")))
                .optional().orElse(null);
        if (period == null) {
            if (org != null && org.requireFiscalPeriod()) {
                throw ApiException.badRequest("No fiscal period is defined for " + day(local)
                        + "; posting requires an open period");
            }
            return;
        }
        if ("locked".equals(period.status())) {
            throw ApiException.badRequest("Fiscal period '" + period.name() + "' is locked; cannot post on " + day(local));
        }
        if ("closed".equals(period.status())) {
            throw ApiException.badRequest("Fiscal period '" + period.name()
                    + "' is closed; reopen or post to a different period");
        }
    }
}
