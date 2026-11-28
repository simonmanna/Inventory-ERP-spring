package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Finance readiness audit B1 (P2-2): keeps next year's fiscal periods in place, so a business that requires a period
 * for every posting does not stop on the first day of a new year. {@value #LEAD_DAYS} days before the organisation's
 * last fiscal period ends, the next fiscal year is created with its periods ({@link FiscalYearService#create}):
 * <ul>
 * <li>after a period of a fiscal year: from the next day, for a year, with that year's periodicity;</li>
 * <li>after a period of no fiscal year (onboarding and the seed create one yearly period): the fiscal year holding the
 * next day ({@code accounting.fiscalYearStartMonth}), with the periodicity of that last period's length.</li>
 * </ul>
 * With {@code accounting.autoCreatePeriods} off, or when the year cannot be created (for example a period crossing its
 * start), an in-app alert says so instead, at most once a week. An organisation without fiscal periods is left alone.
 */
@Service
public class FiscalPeriodCoverageService {

    /** How many days before the last period ends the next fiscal year is created. */
    public static final int LEAD_DAYS = 60;

    /** Notification payload kind. */
    static final String KIND = "fiscal_period_coverage";

    private static final Logger log = LoggerFactory.getLogger(FiscalPeriodCoverageService.class);

    /** What a run did: {@code none} (no periods), {@code covered}, {@code created} or {@code alerted}. */
    public record Result(String outcome, String detail) {}

    /** The last live period, with its fiscal year's periodicity when it belongs to one. */
    record Last(LocalDate start, LocalDate end, String fiscalYearId, String periodicity) {}

    /** The fiscal year to create. */
    record Plan(LocalDate start, LocalDate end, String periodicity) {}

    private record State(LocalDate today, Last last, boolean auto, int startMonth) {}

    private final JdbcClient jdbc;
    private final FiscalYearService fiscalYears;
    private final SettingResolverService settings;
    private final NotificationsService notifications;
    private final TransactionTemplate tx;

    public FiscalPeriodCoverageService(JdbcClient jdbc, FiscalYearService fiscalYears, SettingResolverService settings,
            NotificationsService notifications, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.fiscalYears = fiscalYears;
        this.settings = settings;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(txm);
    }

    /** Check the current organisation (tenant context): create next year's periods, or alert. */
    public Result run() {
        String orgId = TenantContext.organizationId();
        State s = tx.execute(st -> new State(AccountingDay.of(Instant.now(), AccountingDay.zone(jdbc, orgId)),
                last(orgId), settings.resolveBool(PeriodControlsService.AUTO_CREATE_SETTING, SettingContext.EMPTY),
                startMonth()));
        if (s.last() == null) {
            return new Result("none", null);
        }
        if (s.last().end().isAfter(s.today().plusDays(LEAD_DAYS))) {
            return new Result("covered", s.last().end().toString());
        }
        String failure = null;
        if (s.auto()) {
            Plan plan = plan(s.last(), s.startMonth());
            try {
                Map<String, Object> year = tx.execute(st -> fiscalYears.create(null, plan.start(), plan.end(),
                        plan.periodicity(), true));
                tx.executeWithoutResult(st -> notify(orgId, "Next year's fiscal periods were created", "Fiscal year "
                        + year.get("name") + " (" + plan.start() + " to " + plan.end() + ") was created with "
                        + ((List<?>) year.get("periods")).size() + " " + plan.periodicity() + " period(s), because the"
                        + " last period ends on " + s.last().end() + ". Review them under Accounting → Fiscal years.",
                        Obj.of("kind", KIND, "action", "created", "fiscalYearId", year.get("id"))));
                return new Result("created", String.valueOf(year.get("name")));
            } catch (ApiException e) {
                failure = e.getMessage();
            } catch (RuntimeException e) {
                log.warn("[fiscal-period-coverage] next year could not be created for {}: {}", orgId, e.toString());
                failure = "the database refused it (" + e.getClass().getSimpleName() + ")";
            }
        }
        String reason = failure;
        boolean sent = Boolean.TRUE.equals(tx.execute(st -> alertOnce(orgId, s, reason)));
        return new Result("alerted", (sent ? "" : "(alerted this week already) ") + (reason == null ? "off" : reason));
    }

    /** The next fiscal year after {@code last}. */
    static Plan plan(Last last, int startMonth) {
        LocalDate next = last.end().plusDays(1);
        if (last.fiscalYearId() != null) {
            return new Plan(next, next.plusYears(1).minusDays(1), last.periodicity() == null ? "monthly"
                    : last.periodicity());
        }
        LocalDate start = LocalDate.of(next.getYear(), startMonth, 1);
        if (start.isAfter(next)) {
            start = start.minusYears(1);
        }
        return new Plan(start, start.plusYears(1).minusDays(1), periodicityOf(last.start(), last.end()));
    }

    /** Monthly, quarterly or yearly, from a period's length. */
    static String periodicityOf(LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        return days <= 31 ? "monthly" : days <= 92 ? "quarterly" : "yearly";
    }

    private Last last(String orgId) {
        return jdbc.sql("SELECT p.\"startDate\", p.\"endDate\", p.\"fiscalYearId\", y.periodicity FROM \"FiscalPeriod\" p"
                        + " LEFT JOIN \"FiscalYear\" y ON y.id = p.\"fiscalYearId\" WHERE p.\"organizationId\" = ?"
                        + " AND p.\"deletedAt\" IS NULL ORDER BY p.\"endDate\" DESC, p.\"startDate\" DESC LIMIT 1")
                .param(orgId)
                .query((rs, n) -> new Last(day(rs.getTimestamp(1)), day(rs.getTimestamp(2)), rs.getString(3),
                        rs.getString(4)))
                .optional().orElse(null);
    }

    private static LocalDate day(Timestamp wallClock) {
        return wallClock.toLocalDateTime().toLocalDate();
    }

    private int startMonth() {
        Object v = settings.resolve("accounting.fiscalYearStartMonth", SettingContext.EMPTY);
        int month = v instanceof Number n ? n.intValue() : 1;
        return month >= 1 && month <= 12 ? month : 1;
    }

    /** The missing-periods alert, unless one was raised in the last seven days. */
    private boolean alertOnce(String orgId, State s, String failure) {
        boolean recent = jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"Notification\" WHERE \"organizationId\" = ?"
                        + " AND payload->>'kind' = ? AND payload->>'action' = 'missing'"
                        + " AND \"createdAt\" > now() - interval '7 days')")
                .params(orgId, KIND).query(Boolean.class).single();
        if (recent) {
            return false;
        }
        LocalDate end = s.last().end();
        notify(orgId, "Next year's fiscal periods are missing", "The last fiscal period ends on " + end
                + (end.isBefore(s.today()) ? ", and no period covers the days since" : "") + ". "
                + (failure == null ? "Creating them automatically is off."
                        : "They could not be created automatically: " + failure + ".")
                + " Create them under Accounting → Fiscal years.",
                Obj.of("kind", KIND, "action", "missing", "periodsThrough", end.toString()));
        return true;
    }

    private void notify(String orgId, String title, String body, Map<String, Object> payload) {
        try {
            // IPR OPS-1: a row without a user reaches no inbox; the people who can create fiscal periods get it.
            notifications.sendToPermission(orgId, Permissions.FISCAL_PERIOD__CREATE, "accounting", title, body,
                    payload, null);
        } catch (RuntimeException e) {
            log.error("[fiscal-period-coverage] failed to send the alert: {}", e.toString());
        }
    }
}
