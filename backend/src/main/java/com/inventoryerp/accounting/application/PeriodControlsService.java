package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.WallClockDays;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.settings.SettingsService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finance readiness audit B1 (P2-2): the organisation's period controls, read and set on the fiscal periods page.
 * <ul>
 * <li>{@code booksLockDate}: nothing dated on or before it posts ({@link FiscalPeriodControl}). A date-only lock covers
 * the whole day: it is stored as its last instant, org-local wall clock (ADR-S070), like a period end (A1).</li>
 * <li>{@code requireFiscalPeriod}: a posting on a day no open period covers is refused.</li>
 * <li>{@code autoCreatePeriods}: next year's periods are created ahead ({@link FiscalPeriodCoverageService}).</li>
 * <li>{@code futurePostingLimitDays}: a posting dated further ahead is refused ({@link FiscalPeriodControl}).</li>
 * </ul>
 * The first two are {@code Organization} columns (no route set them before B1); the other two are settings. Moving the
 * lock date earlier or removing it reopens posted history, so it also needs {@code fiscal_period:reopen}.
 */
@Service
public class PeriodControlsService {

    /** Setting: create next year's periods automatically. */
    public static final String AUTO_CREATE_SETTING = "accounting.autoCreatePeriods";

    /** The full set of controls; {@code booksLockDate} null = the books are not locked. */
    public record Controls(String booksLockDate, boolean requireFiscalPeriod, boolean autoCreatePeriods,
            int futurePostingLimitDays) {}

    private record OrgRow(Timestamp booksLockDate, boolean requireFiscalPeriod) {
        LocalDate lockDay() {
            return booksLockDate == null ? null : booksLockDate.toLocalDateTime().toLocalDate();
        }
    }

    private final JdbcClient jdbc;
    private final SettingResolverService resolver;
    private final SettingsService settings;
    private final FiscalPeriodControl control;
    private final AuditService audit;

    public PeriodControlsService(JdbcClient jdbc, SettingResolverService resolver, SettingsService settings,
            FiscalPeriodControl control, AuditService audit) {
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.settings = settings;
        this.control = control;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private OrgRow orgRow(boolean forUpdate) {
        return jdbc.sql("SELECT \"booksLockDate\", \"requireFiscalPeriod\" FROM \"Organization\" WHERE id = ?"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param(org())
                .query((rs, n) -> new OrgRow(rs.getTimestamp("booksLockDate"), rs.getBoolean("requireFiscalPeriod")))
                .optional()
                .orElseThrow(() -> ApiException.notFound("Organization not found"));
    }

    /** The controls, with how far the fiscal periods reach. */
    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        return view(orgRow(false));
    }

    /**
     * Replace the controls. A new or later lock date must be before today and leaves no draft journal entry on or
     * before it; an earlier or removed one needs {@code fiscal_period:reopen}. Requiring a period needs an open period
     * covering today. Each change is audited.
     */
    @Transactional
    public Map<String, Object> update(Controls in) {
        OrgRow before = orgRow(true);
        ZoneId zone = AccountingDay.zone(jdbc);
        LocalDate today = AccountingDay.of(Instant.now(), zone);
        LocalDate lock = parseDay(in.booksLockDate());
        Timestamp lockTs = lock == null ? null : WallClockDays.endTs(lock);
        Map<String, Object> oldValues = new LinkedHashMap<>();
        Map<String, Object> newValues = new LinkedHashMap<>();
        if (!Objects.equals(lockTs, before.booksLockDate())) {
            LocalDate was = before.lockDay();
            boolean loosens = lock == null || (was != null && lock.isBefore(was));
            if (loosens && !TenantContext.has(Permissions.FISCAL_PERIOD__REOPEN)) {
                throw ApiException.forbidden("Missing required permission(s): " + Permissions.FISCAL_PERIOD__REOPEN);
            }
            if (lock != null) {
                if (!lock.isBefore(today)) {
                    throw ApiException.badRequest("The books lock date must be before today (" + today + ")");
                }
                long drafts = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?"
                                + " AND status::text = 'draft' AND \"postingDate\" <= ?")
                        .params(org(), FiscalPeriodControl.ts(AccountingDay.endInclusive(lock, zone)))
                        .query(Long.class).single();
                if (drafts > 0) {
                    throw ApiException.badRequest(drafts + " draft journal " + (drafts == 1 ? "entry is" : "entries are")
                            + " dated on or before " + lock + "; post or delete "
                            + (drafts == 1 ? "it" : "them") + " before locking the books");
                }
            }
            jdbc.sql("UPDATE \"Organization\" SET \"booksLockDate\" = ?, \"updatedAt\" = now() WHERE id = ?")
                    .params(lockTs, org()).update();
            oldValues.put("booksLockDate", was == null ? null : was.toString());
            newValues.put("booksLockDate", lock == null ? null : lock.toString());
        }
        if (in.requireFiscalPeriod() != before.requireFiscalPeriod()) {
            if (in.requireFiscalPeriod() && !openPeriodCoversNow(zone)) {
                throw ApiException.badRequest("No open fiscal period covers today (" + today + "); create one before"
                        + " requiring a period for every posting");
            }
            jdbc.sql("UPDATE \"Organization\" SET \"requireFiscalPeriod\" = ?, \"updatedAt\" = now() WHERE id = ?")
                    .params(in.requireFiscalPeriod(), org()).update();
            oldValues.put("requireFiscalPeriod", before.requireFiscalPeriod());
            newValues.put("requireFiscalPeriod", in.requireFiscalPeriod());
        }
        if (!newValues.isEmpty()) {
            audit.recordInTx(AuditInput.of("Organization", org(), "update", oldValues, newValues));
        }
        // Settings: SettingsService audits each change itself.
        if (in.autoCreatePeriods() != resolver.resolveBool(AUTO_CREATE_SETTING, SettingContext.EMPTY)) {
            settings.set(AUTO_CREATE_SETTING, in.autoCreatePeriods(), null, null);
        }
        if (in.futurePostingLimitDays() != control.futurePostingLimitDays()) {
            settings.set(FiscalPeriodControl.FUTURE_LIMIT_SETTING, in.futurePostingLimitDays(), null, null);
        }
        return view(orgRow(false));
    }

    private static LocalDate parseDay(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("booksLockDate must be a valid date");
        }
    }

    /** Whether an open, live period covers the current org-local wall clock. */
    private boolean openPeriodCoversNow(ZoneId zone) {
        Timestamp now = FiscalPeriodControl.ts(AccountingDay.toWallClock(Instant.now(), zone));
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"FiscalPeriod\" WHERE \"organizationId\" = ? AND \"deletedAt\""
                        + " IS NULL AND status::text = 'open' AND \"startDate\" <= ? AND \"endDate\" >= ?)")
                .params(org(), now, now).query(Boolean.class).single();
    }

    private Map<String, Object> view(OrgRow row) {
        ZoneId zone = AccountingDay.zone(jdbc);
        Timestamp through = jdbc.sql("SELECT max(\"endDate\") FROM \"FiscalPeriod\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").param(org()).query(Timestamp.class).optional().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        LocalDate lock = row.lockDay();
        out.put("booksLockDate", lock == null ? null : lock.toString());
        out.put("requireFiscalPeriod", row.requireFiscalPeriod());
        out.put("autoCreatePeriods", resolver.resolveBool(AUTO_CREATE_SETTING, SettingContext.EMPTY));
        out.put("futurePostingLimitDays", control.futurePostingLimitDays());
        out.put("today", AccountingDay.of(Instant.now(), zone).toString());
        out.put("periodsThrough", through == null ? null : through.toLocalDateTime().toLocalDate().toString());
        out.put("openPeriodCoversToday", openPeriodCoversNow(zone));
        out.put("autoCreateLeadDays", FiscalPeriodCoverageService.LEAD_DAYS);
        return out;
    }
}
