package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.FiscalPeriod;
import com.inventoryerp.core.domain.FiscalPeriodStatus;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of {@code fiscal-period.service.ts} (generic CRUD) with the period controls Nest lacks (owner-approved
 * deviation, accounting controls review 2026-11-17):
 *
 * <ul>
 *   <li>{@code status} never changes here: close, reopen and lock go through {@code PeriodCloseService}
 *       ({@code POST /fiscal-periods/:id/close|reopen|lock}), which posts and reverses the closing journal;
 *   <li>the dates of a period change only while it is open and no posted journal entry falls in its current or its
 *       new range; a period is deleted only on the same conditions;
 *   <li>the periods of one organization never overlap and never end before they start (also enforced in the
 *       database by the {@code fiscal_period_no_overlap} trigger, V512).
 * </ul>
 *
 * Bounds are stored as org-local wall clock (ADR-S070); the posted-entry check compares them with
 * {@code JournalEntry.postingDate} (a real instant) in the organization's time zone.
 */
@Service
public class FiscalPeriodService extends BaseCrudService<FiscalPeriod> {

    static final String STATUS_REFUSED = "The status of a fiscal period cannot be changed with PATCH; use"
            + " POST /fiscal-periods/:id/close, /fiscal-periods/:id/reopen or /fiscal-periods/:id/lock";

    private final JdbcClient jdbc;

    public FiscalPeriodService(EntityManager em, JdbcClient jdbc) {
        super(em, FiscalPeriod.class);
        this.jdbc = jdbc;
    }

    @Override
    protected String entityName() {
        return "FiscalPeriod";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("name");
    }

    /** Create an open period; refused when it ends before it starts or overlaps another period of the org. */
    @Override
    @Transactional
    public Map<String, Object> create(Record dto) {
        String organizationId = TenantContext.organizationId();
        FiscalPeriod row = EntityPatch.apply(newInstance(), dto);
        lockPeriods(organizationId);
        assertRange(row.getStartDate(), row.getEndDate());
        assertNoOverlap(organizationId, null, row.getStartDate(), row.getEndDate());
        return render(persist(row));
    }

    /**
     * Rename or re-date a period. {@code status} is refused (close / reopen / lock routes); dates move only while the
     * period is open and no posted entry lies in the old or the new range.
     */
    @Override
    @Transactional
    public Map<String, Object> update(String id, Record dto) {
        Map<String, Object> data = EntityPatch.data(dto);
        if (data.containsKey("status")) {
            throw ApiException.badRequest(STATUS_REFUSED);
        }
        if (!data.containsKey("startDate") && !data.containsKey("endDate")) {
            return super.update(id, dto);
        }
        String organizationId = TenantContext.organizationId();
        lockPeriods(organizationId);
        lockRow(id, organizationId);
        FiscalPeriod row = require(id);
        Instant start = data.containsKey("startDate") ? date(data.get("startDate"), "startDate") : row.getStartDate();
        Instant end = data.containsKey("endDate") ? date(data.get("endDate"), "endDate") : row.getEndDate();
        boolean moved = !start.equals(row.getStartDate()) || !end.equals(row.getEndDate());
        if (moved) {
            if (row.getStatus() != FiscalPeriodStatus.OPEN) {
                throw ApiException.badRequest("Fiscal period '" + row.getName() + "' is " + row.getStatus().value()
                        + "; only an open period's dates can be changed");
            }
            assertRange(start, end);
            if (hasPostings(organizationId, row.getStartDate(), row.getEndDate())
                    || hasPostings(organizationId, start, end)) {
                throw ApiException.badRequest("Fiscal period '" + row.getName() + "' has posted journal entries in its"
                        + " current or new date range; its dates cannot be changed");
            }
            assertNoOverlap(organizationId, row.getId(), start, end);
        }
        EntityPatch.apply(row, dto);
        em.flush();
        return render(row);
    }

    /** Soft-delete an open period with no posted journal entry in its range. */
    @Override
    @Transactional
    public void remove(String id) {
        String organizationId = TenantContext.organizationId();
        lockPeriods(organizationId);
        lockRow(id, organizationId);
        FiscalPeriod row = require(id);
        if (row.getStatus() != FiscalPeriodStatus.OPEN) {
            throw ApiException.badRequest("Fiscal period '" + row.getName() + "' is " + row.getStatus().value()
                    + "; only an open period can be deleted");
        }
        if (hasPostings(organizationId, row.getStartDate(), row.getEndDate())) {
            throw ApiException.badRequest("Fiscal period '" + row.getName() + "' has posted journal entries; it cannot"
                    + " be deleted");
        }
        super.remove(id);
    }

    // ------------------------------------------------------------------ helpers

    /** Serialises the period writes of one organization (same key as the {@code fiscal_period_no_overlap} trigger). */
    private void lockPeriods(String organizationId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('fiscal_period:' || ?, 0))")
                .param(organizationId).query().listOfRows();
    }

    /** Row lock: a concurrent close / reopen / posting check waits until this change commits. */
    private void lockRow(String id, String organizationId) {
        jdbc.sql("SELECT id FROM \"FiscalPeriod\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, organizationId).query(String.class).list();
    }

    private static Instant date(Object value, String field) {
        if (value == null) {
            throw ApiException.badRequest(field + " must be a valid ISO 8601 date string");
        }
        return EntityPatch.toInstant(value.toString());
    }

    private static void assertRange(Instant start, Instant end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.badRequest("The end date of a fiscal period cannot be before its start date");
        }
    }

    private void assertNoOverlap(String organizationId, String id, Instant start, Instant end) {
        if (start == null || end == null) {
            return;
        }
        String clash = jdbc.sql("SELECT name FROM \"FiscalPeriod\" WHERE \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND id <> ? AND \"startDate\" <= ? AND \"endDate\" >= ?"
                        + " ORDER BY \"startDate\" LIMIT 1")
                .params(organizationId, id == null ? "" : id, ts(end), ts(start))
                .query(String.class).optional().orElse(null);
        if (clash != null) {
            throw ApiException.badRequest("The dates overlap fiscal period '" + clash + "'");
        }
    }

    /** Whether a posted (or since reversed) journal entry is dated inside the org-local range {@code [start, end]}. */
    private boolean hasPostings(String organizationId, Instant start, Instant end) {
        ZoneId zone = zone(organizationId);
        return jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?"
                        + " AND status::text IN ('posted', 'reversed') AND \"postingDate\" >= ?"
                        + " AND \"postingDate\" <= ?")
                .params(organizationId, ts(fromWallClock(start, zone)), ts(fromWallClock(end, zone)))
                .query(Long.class).single() > 0;
    }

    /** The organization's time zone (UTC when unset or invalid), as {@code OrgDates.orgTimezone} reads it. */
    private ZoneId zone(String organizationId) {
        String tz = jdbc.sql("SELECT timezone FROM \"Organization\" WHERE id = ?").param(organizationId)
                .query(String.class).optional().orElse(null);
        if (tz == null || tz.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /** A stored org-local wall-clock bound (read as UTC fields) as the real instant it denotes. */
    private static Instant fromWallClock(Instant wallClock, ZoneId zone) {
        return wallClock.atOffset(ZoneOffset.UTC).toLocalDateTime().atZone(zone).toInstant();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.valueOf(i.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }
}
