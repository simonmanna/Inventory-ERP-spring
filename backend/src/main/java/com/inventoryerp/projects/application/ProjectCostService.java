package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.ProjectService.blank;
import static com.inventoryerp.projects.application.ProjectService.day;
import static com.inventoryerp.projects.application.ProjectService.element;
import static com.inventoryerp.projects.application.ProjectService.org;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.AccountingDay;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.application.StockPostingService;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project cost capture and the budget / committed / actual / EAC report (Cost Management CM-8a, ADR-S038).
 *
 * <p>Every capture path writes a {@code ProjectCostLine} and one journal entry that debits <b>1460 Project WIP</b>
 * with the {@code projectId}, {@code wbsId} (and {@code siteId}) dimensions; the credit depends on the path:
 *
 * <table>
 *   <caption>capture paths</caption>
 *   <tr><th>Path</th><th>Credit</th></tr>
 *   <tr><td>Stock issue to a WBS element / delivery to a site</td><td>the product's stock account (carrying amount
 *       from the stock engine)</td></tr>
 *   <tr><td>Approved timesheet entries linked to the project × rate</td><td>5390 Labour Absorbed to Projects</td></tr>
 *   <tr><td>Equipment hours × rate</td><td>5391 Equipment Absorbed to Projects</td></tr>
 *   <tr><td>Reclass of a posted cost line (purchase / subcontract bill, fleet trip, overhead)</td><td>the source
 *       line's own account, never more than its unreclassed amount</td></tr>
 * </table>
 *
 * <p>Commitments are the open purchase-request / purchase-order commitments that carry the project. EAC = actual +
 * committed + cost to complete. Reversal reverses the journal (and returns issued stock at its issue cost).
 */
@Service
public class ProjectCostService {

    static final Set<String> CAPTURE_STATUSES = Set.of("active");

    public record IssueInput(String wbsId, String siteId, String productId, String locationId, BigDecimal quantity,
            String date, Boolean excludeFromProgress, String notes) {}

    public record TimesheetInput(String wbsId, String from, String to, BigDecimal ratePerHour) {}

    /**
     * Equipment capture. Without {@code plantId}: hours × the given rate, credited to 5391 (CM-8a). With
     * {@code plantId} (ADR-S075): quantity (or hours) × the plant rate table's rate in force on the date (a manual
     * rate needs {@code project:rate_override}), credited to plant recovery on the plant's cost centre.
     */
    public record EquipmentInput(String wbsId, String siteId, String equipmentRef, BigDecimal hours,
            BigDecimal ratePerHour, String date, String notes, String plantId, BigDecimal quantity, String unit) {

        public EquipmentInput(String wbsId, String siteId, String equipmentRef, BigDecimal hours,
                BigDecimal ratePerHour, String date, String notes) {
            this(wbsId, siteId, equipmentRef, hours, ratePerHour, date, notes, null, null, null);
        }
    }

    public record ReclassInput(String wbsId, String siteId, String journalLineId, BigDecimal amount,
            String costElement, Boolean excludeFromProgress, String date, String notes) {}

    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final StockService stock;
    private final StockPostingService stockPosting;
    private final AuditService audit;
    private final ProjectPlantRateService plantRates;

    public ProjectCostService(JdbcClient jdbc, ProjectService projects, PostingService posting,
            AccountResolverService accounts, StockService stock, StockPostingService stockPosting,
            AuditService audit, ProjectPlantRateService plantRates) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.posting = posting;
        this.accounts = accounts;
        this.stock = stock;
        this.stockPosting = stockPosting;
        this.audit = audit;
        this.plantRates = plantRates;
    }

    public String wipAccount() {
        return accounts.ensureByCode("1460", "Project Work in Progress", "current_asset", "project_wip");
    }

    /**
     * Construction in Progress (ADR-S074): where a capital (own-use) project accumulates its cost instead of 1460.
     */
    public String cipAccount() {
        return accounts.ensureByCode("1690", "Construction in Progress", "construction_in_progress",
                "construction_in_progress");
    }

    /** The account a project's cost is debited to: CIP for a capital project, otherwise project WIP 1460. */
    public String costAccount(Map<String, Object> project) {
        return Boolean.TRUE.equals(project.get("isCapital")) ? cipAccount() : wipAccount();
    }

    public String labourAbsorbedAccount() {
        return accounts.ensureByCode("5390", "Labour Absorbed to Projects", "operating_expense",
                "project_labour_absorbed");
    }

    public String equipmentAbsorbedAccount() {
        return accounts.ensureByCode("5391", "Equipment Absorbed to Projects", "operating_expense",
                "project_equipment_absorbed");
    }

    /** Plant recovery (5392, {@code plant_recovery}): credited when plant cost is charged to a job (ADR-S075). */
    public String plantRecoveryAccount() {
        return accounts.ensureByCode("5392", "Plant Recovery to Projects", "operating_expense", "plant_recovery");
    }

    /** Overhead absorbed ({@code overhead_absorbed}; 5350 when the mapping is not configured yet, ADR-S075). */
    public String overheadAbsorbedAccount() {
        return accounts.ensureByCode("5350", "Overhead Absorbed", "operating_expense", "overhead_absorbed");
    }

    // ---- capture paths ------------------------------------------------------------------------------------------------

    /** Stock issued to a WBS element (or delivered to a project site) at carrying amount. */
    @Transactional
    public Map<String, Object> issue(String projectId, IssueInput in) {
        Map<String, Object> p = capturable(projectId);
        Map<String, Object> w = costableWbs(projectId, in.wbsId());
        String siteId = site(projectId, in.siteId());
        if (in.quantity() == null || in.quantity().signum() <= 0) {
            throw ApiException.badRequest("quantity must be greater than 0");
        }
        String location = blank(in.locationId());
        if (location == null && siteId != null) {
            location = jdbc.sql("SELECT \"deliveryLocationId\" FROM \"ProjectSite\" WHERE id = ?").param(siteId)
                    .query(String.class).optional().orElse(null);
        }
        if (location == null) {
            throw ApiException.badRequest("locationId should not be empty");
        }
        Instant date = instant(in.date());
        String id = UUID.randomUUID().toString();
        Issued issued = stock.issue(StockMove.of(in.productId(), location, in.quantity()).source("project_issue", id)
                .notes(in.notes() == null ? "Issue to project " + p.get("code") : in.notes()).date(date).skipGl());
        BigDecimal value = issued.totalValue().setScale(2, RoundingMode.HALF_UP);
        String text = "Project " + p.get("code") + " · " + w.get("code") + " · material";
        List<Line> lines = new ArrayList<>();
        String stockAccount = null;
        if (value.signum() > 0) {
            stockAccount = stockPosting.stockAccount(in.productId(), value);
            lines.add(debit(p, w, siteId, value, text));
            lines.add(new Line(stockAccount, null, JsNumbers.dec(value), null, text, branch(p), null,
                    Obj.of("projectId", projectId)));
        }
        String je = post(lines, text, date, "project_issue", id, p);
        insert(id, projectId, (String) w.get("id"), siteId, "material", "project_issue", id, in.productId(), date,
                in.quantity(), issued.unitCost(), value, Boolean.TRUE.equals(in.excludeFromProgress()), stockAccount,
                je, text);
        return line(id);
    }

    /** Approved timesheet entries linked to the project in the window, priced at the rate; each entry once. */
    @Transactional
    public Map<String, Object> captureTimesheets(String projectId, TimesheetInput in) {
        Map<String, Object> p = capturable(projectId);
        Map<String, Object> w = costableWbs(projectId, in.wbsId());
        if (in.ratePerHour() == null || in.ratePerHour().signum() <= 0) {
            throw ApiException.badRequest("ratePerHour must be greater than 0");
        }
        String from = required(day(in.from()), "from");
        String to = required(day(in.to()), "to");
        List<Map<String, Object>> entries = SqlRows.list(jdbc.sql("SELECT e.id, e.minutes, e.date, t.\"employeeId\""
                + " FROM \"HrTimesheetEntry\" e JOIN \"HrTimesheet\" t ON t.id = e.\"timesheetId\" WHERE"
                + " e.\"organizationId\" = ? AND e.\"deletedAt\" IS NULL AND t.status::text = 'APPROVED' AND"
                + " e.\"linkedEntityType\" = 'project' AND"
                + " e.\"linkedEntityId\" = ? AND e.date >= ?::date AND e.date < (?::date + 1) AND NOT EXISTS (SELECT 1"
                + " FROM \"ProjectCostLine\" c WHERE c.\"sourceType\" = 'project_timesheet' AND c.status = 'posted' AND"
                + " c.\"sourceLineId\" = e.id) ORDER BY e.date, e.id FOR UPDATE OF e").params(org(), projectId, from,
                to));
        if (entries.isEmpty()) {
            throw ApiException.badRequest("No approved, uncaptured timesheet entry is linked to this project in the"
                    + " period");
        }
        String batch = UUID.randomUUID().toString();
        String text = "Project " + p.get("code") + " · " + w.get("code") + " · labour";
        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            BigDecimal hours = new BigDecimal(e.get("minutes").toString()).divide(BigDecimal.valueOf(60), 6,
                    RoundingMode.HALF_UP);
            BigDecimal amount = hours.multiply(in.ratePerHour()).setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() == 0) {
                continue;
            }
            lines.add(debit(p, w, null, amount, text));
            total = total.add(amount);
            rows.add(new Object[] {e.get("id"), hours, amount, e.get("date")});
        }
        if (total.signum() == 0) {
            throw ApiException.badRequest("The linked timesheet entries carry no time");
        }
        Instant date = LocalDate.parse(to).atStartOfDay(ZoneOffset.UTC).toInstant();
        lines.add(new Line(labourAbsorbedAccount(), null, JsNumbers.dec(total), null, text, branch(p), null,
                Obj.of("projectId", projectId)));
        String je = post(lines, text, date, "project_timesheet", batch, p);
        for (Object[] r : rows) {
            insert(UUID.randomUUID().toString(), projectId, (String) w.get("id"), null, "labour", "project_timesheet",
                    batch, (String) r[0], date, (BigDecimal) r[1], in.ratePerHour(), (BigDecimal) r[2], false,
                    labourAbsorbedAccount(), je, text);
        }
        audit.recordInTx(AuditInput.of("ProjectCostLine", batch, "create", null, Obj.of("projectId", projectId,
                "entries", rows.size(), "total", total)));
        return Obj.of("batchId", batch, "journalEntryId", je, "entries", rows.size(), "total", JsNumbers.dec(total));
    }

    /** Equipment hours × rate, or a plant from the plant rate table × quantity (ADR-S075). */
    @Transactional
    public Map<String, Object> equipment(String projectId, EquipmentInput in) {
        if (blank(in.plantId()) != null) {
            return plantEquipment(projectId, in);
        }
        Map<String, Object> p = capturable(projectId);
        Map<String, Object> w = costableWbs(projectId, in.wbsId());
        String siteId = site(projectId, in.siteId());
        if (in.hours() == null || in.hours().signum() <= 0) {
            throw ApiException.badRequest("hours must be greater than 0");
        }
        if (in.ratePerHour() == null || in.ratePerHour().signum() <= 0) {
            throw ApiException.badRequest("ratePerHour must be greater than 0");
        }
        BigDecimal amount = in.hours().multiply(in.ratePerHour()).setScale(2, RoundingMode.HALF_UP);
        Instant date = instant(in.date());
        String id = UUID.randomUUID().toString();
        String text = "Project " + p.get("code") + " · " + w.get("code") + " · equipment "
                + (in.equipmentRef() == null ? "" : in.equipmentRef());
        String credit = equipmentAbsorbedAccount();
        String je = post(List.of(debit(p, w, siteId, amount, text), new Line(credit, null, JsNumbers.dec(amount), null,
                text, branch(p), null, Obj.of("projectId", projectId, "equipmentRef", in.equipmentRef()))), text, date,
                "project_equipment", id, p);
        insert(id, projectId, (String) w.get("id"), siteId, "equipment", "project_equipment", id, in.equipmentRef(),
                date, in.hours(), in.ratePerHour(), amount, false, credit, je, in.notes() == null ? text : in.notes());
        return line(id);
    }

    private Map<String, Object> plantEquipment(String projectId, EquipmentInput in) {
        Map<String, Object> p = capturable(projectId);
        Map<String, Object> w = costableWbs(projectId, in.wbsId());
        String siteId = site(projectId, in.siteId());
        BigDecimal qty = in.quantity() != null ? in.quantity() : in.hours();
        if (qty == null || qty.signum() <= 0) {
            throw ApiException.badRequest("quantity must be greater than 0");
        }
        Instant date = instant(in.date());
        LocalDate day = date.atZone(ZoneOffset.UTC).toLocalDate();
        Map<String, Object> rate = plantRates.resolve(in.plantId().trim(), blank(in.unit()), day);
        BigDecimal unitRate;
        if (in.ratePerHour() != null) {
            if (!TenantContext.has(Permissions.PROJECT__RATE_OVERRIDE)) {
                throw ApiException.forbidden("Overriding the plant rate needs the " + Permissions.PROJECT__RATE_OVERRIDE
                        + " permission");
            }
            if (in.ratePerHour().signum() <= 0) {
                throw ApiException.badRequest("ratePerHour must be greater than 0");
            }
            unitRate = in.ratePerHour();
        } else if (rate == null) {
            throw ApiException.badRequest("No plant rate is in force for " + in.plantId().trim() + " on " + day);
        } else {
            unitRate = SqlRows.dec(rate, "rate");
        }
        BigDecimal amount = qty.multiply(unitRate).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("The charge rounds to 0");
        }
        String unit = rate == null ? (blank(in.unit()) == null ? "hour" : in.unit()) : (String) rate.get("unit");
        String plantName = rate == null ? in.plantId().trim() : String.valueOf(rate.get("name"));
        String costCenter = plantRates.costCenter(rate, in.plantId().trim());
        String id = UUID.randomUUID().toString();
        String text = "Project " + p.get("code") + " · " + w.get("code") + " · plant " + plantName + " · "
                + qty.stripTrailingZeros().toPlainString() + " " + unit;
        String credit = plantRecoveryAccount();
        String je = post(List.of(debit(p, w, siteId, amount, text), new Line(credit, null, JsNumbers.dec(amount), null,
                text, branch(p), costCenter, Obj.of("projectId", projectId, "plantId", in.plantId().trim(),
                        "plantRateId", rate == null ? null : rate.get("id")))), text, date, "project_equipment", id, p);
        insert(id, projectId, (String) w.get("id"), siteId, "equipment", "project_equipment", id, in.plantId().trim(),
                date, qty, unitRate, amount, false, credit, je, in.notes() == null ? text : in.notes());
        return line(id);
    }

    /**
     * Reclass part (or all) of a posted cost line — a purchase or subcontract bill line, a fleet trip, an overhead
     * allocation — into project WIP. A line is never reclassed beyond its own net debit.
     */
    @Transactional
    public Map<String, Object> reclass(String projectId, ReclassInput in) {
        Map<String, Object> p = capturable(projectId);
        Map<String, Object> w = costableWbs(projectId, in.wbsId());
        String siteId = site(projectId, in.siteId());
        element(in.costElement());
        Map<String, Object> src = in.journalLineId() == null ? null : SqlRows.one(jdbc.sql("SELECT jl.*, e.status AS"
                + " \"entryStatus\", e.\"sourceType\" AS \"entrySourceType\", c.key AS \"categoryKey\" FROM"
                + " \"JournalLine\" jl JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" JOIN \"Account\" a ON"
                + " a.id = jl.\"accountId\" JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE jl.id = ? AND"
                + " jl.\"organizationId\" = ? FOR UPDATE OF jl").params(in.journalLineId(), org()));
        if (src == null) {
            throw ApiException.notFound("Journal line not found");
        }
        if (!"posted".equals(String.valueOf(src.get("entryStatus")))) {
            throw ApiException.badRequest("Only a line of a posted journal entry can be reclassed to a project");
        }
        if (!Set.of("operating_expense", "cost_of_goods_sold", "other_expense").contains(src.get("categoryKey"))) {
            throw ApiException.badRequest("Only an expense or cost-of-sales line can be reclassed to a project");
        }
        if (String.valueOf(src.get("entrySourceType")).startsWith("project_")) {
            throw ApiException.badRequest("A project posting cannot be reclassed again");
        }
        BigDecimal net = SqlRows.dec(src, "baseDebit").subtract(SqlRows.dec(src, "baseCredit"));
        BigDecimal used = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"ProjectCostLine\" WHERE \"sourceType\" ="
                + " 'project_reclass' AND status = 'posted' AND \"sourceLineId\" = ?").param(in.journalLineId())
                .query(BigDecimal.class).single();
        BigDecimal open = net.subtract(used).setScale(2, RoundingMode.HALF_UP);
        BigDecimal amount = in.amount() == null ? open : in.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("amount must be greater than 0");
        }
        if (amount.compareTo(open) > 0) {
            throw ApiException.badRequest("Only " + open.toPlainString() + " of this line is left to reclass");
        }
        Instant date = instant(in.date());
        String id = UUID.randomUUID().toString();
        String text = "Project " + p.get("code") + " · " + w.get("code") + " · " + in.costElement() + " reclass";
        String account = (String) src.get("accountId");
        String je = post(List.of(debit(p, w, siteId, amount, text), new Line(account, null, JsNumbers.dec(amount),
                (String) src.get("partnerId"), text, (String) src.get("branchId"), (String) src.get("costCenterId"),
                Obj.of("projectId", projectId, "reclassOfLineId", in.journalLineId()))), text, date, "project_reclass",
                id, p);
        insert(id, projectId, (String) w.get("id"), siteId, in.costElement(), "project_reclass", id,
                in.journalLineId(), date, null, null, amount, Boolean.TRUE.equals(in.excludeFromProgress()), account,
                je, in.notes() == null ? text : in.notes());
        return line(id);
    }

    /** A posted journal line that {@link #reclass} would accept, with what is already reclassed and still open. */
    public record ReclassableLine(String journalLineId, String journalEntryId, String entryNumber, Instant postingDate,
            String accountId, String accountCode, String accountName, String description, String partnerName,
            BigDecimal amount, BigDecimal reclassed, BigDecimal open) {}

    /**
     * Candidates for {@link #reclass}: debit lines of posted (not reversed, not reversal) journal entries on expense /
     * cost-of-sales accounts that are not project postings and still have an open amount — newest first, at most
     * {@code limit} (1–200, default 50). {@code search} matches the entry number, line / entry text, account code or
     * name and partner name; {@code from} / {@code to} bound the posting date (inclusive).
     */
    @Transactional(readOnly = true)
    public List<ReclassableLine> reclassable(String projectId, String search, String from, String to, Integer limit) {
        projects.project(projectId, false);
        int max = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        String q = blank(search) == null ? null : search.trim();
        String f = day(from);
        String t = day(to);
        // Org-local day bounds (ADR-S070), as UTC wall-clock text for the CAST.
        java.time.ZoneId zone = AccountingDay.zone(jdbc);
        String fromBound = f == null ? null
                : AccountingDay.startTs(LocalDate.parse(f), zone).toLocalDateTime().toString();
        String toBound = t == null ? null
                : AccountingDay.endExclusiveTs(LocalDate.parse(t), zone).toLocalDateTime().toString();
        return jdbc.sql("SELECT * FROM (SELECT jl.id, jl.\"journalEntryId\", e.\"entryNumber\", e.\"postingDate\","
                        + " a.id AS \"accountId\", a.code AS \"accountCode\", a.name AS \"accountName\","
                        + " coalesce(jl.description, e.description) AS description, pa.name AS \"partnerName\","
                        + " round(jl.\"baseDebit\" - jl.\"baseCredit\", 2) AS amount, (SELECT coalesce(sum(c.amount), 0)"
                        + " FROM \"ProjectCostLine\" c WHERE c.\"sourceType\" = 'project_reclass' AND c.status = 'posted'"
                        + " AND c.\"sourceLineId\" = jl.id) AS reclassed, jl.\"lineNumber\""
                        + " FROM \"JournalLine\" jl JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\""
                        + " JOIN \"Account\" a ON a.id = jl.\"accountId\" JOIN \"AccountCategory\" cat ON cat.id ="
                        + " a.\"categoryId\" LEFT JOIN \"Partner\" pa ON pa.id = jl.\"partnerId\""
                        + " WHERE jl.\"organizationId\" = :org AND e.status::text = 'posted' AND e.\"reversalOfId\" IS NULL"
                        + " AND cat.key IN ('operating_expense', 'cost_of_goods_sold', 'other_expense')"
                        + " AND coalesce(e.\"sourceType\", '') NOT LIKE 'project\\_%' AND jl.\"baseDebit\" > jl.\"baseCredit\""
                        + " AND (CAST(:from AS timestamp) IS NULL OR e.\"postingDate\" >= CAST(:from AS timestamp))"
                        + " AND (CAST(:to AS timestamp) IS NULL OR e.\"postingDate\" < CAST(:to AS timestamp))"
                        + " AND (CAST(:q AS text) IS NULL OR e.\"entryNumber\" ILIKE '%' || :q || '%' OR jl.description"
                        + " ILIKE '%' || :q || '%' OR e.description ILIKE '%' || :q || '%' OR a.code ILIKE :q || '%' OR"
                        + " a.name ILIKE '%' || :q || '%' OR pa.name ILIKE '%' || :q || '%')) x"
                        + " WHERE x.amount - x.reclassed > 0"
                        + " ORDER BY x.\"postingDate\" DESC, x.\"entryNumber\" DESC, x.\"lineNumber\" LIMIT :max")
                .param("org", org()).param("from", fromBound).param("to", toBound).param("q", q).param("max", max)
                .query((rs, i) -> {
                    BigDecimal amount = rs.getBigDecimal("amount").setScale(2, RoundingMode.HALF_UP);
                    BigDecimal reclassed = rs.getBigDecimal("reclassed").setScale(2, RoundingMode.HALF_UP);
                    Instant posted = rs.getTimestamp("postingDate").toLocalDateTime().toInstant(ZoneOffset.UTC);
                    return new ReclassableLine(rs.getString("id"), rs.getString("journalEntryId"),
                            rs.getString("entryNumber"), posted,
                            rs.getString("accountId"), rs.getString("accountCode"), rs.getString("accountName"),
                            rs.getString("description"), rs.getString("partnerName"), amount, reclassed,
                            amount.subtract(reclassed));
                })
                .list();
    }

    /** Reverse a capture: the journal entry (with every cost line it carries) and, for an issue, the stock. */
    @Transactional
    public Map<String, Object> reverse(String costLineId) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectCostLine\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(costLineId, org()));
        if (c == null) {
            throw ApiException.notFound("Project cost line not found");
        }
        if (!"posted".equals(c.get("status"))) {
            throw ApiException.badRequest("This cost line is already reversed");
        }
        String reversal = null;
        if (c.get("journalEntryId") != null) {
            reversal = (String) posting.reverse((String) c.get("journalEntryId"), Instant.now(), "Reversal of "
                    + c.get("description")).get("id");
        }
        if ("project_issue".equals(c.get("sourceType")) && c.get("quantity") != null) {
            Map<String, Object> led = SqlRows.one(jdbc.sql("SELECT \"locationId\" FROM \"InventoryLedger\" WHERE"
                    + " \"organizationId\" = ? AND \"referenceType\" = 'project_issue' AND \"referenceId\" = ? LIMIT 1")
                    .params(org(), c.get("sourceId")));
            if (led != null) {
                stock.receive(StockMove.of((String) c.get("sourceLineId"), (String) led.get("locationId"),
                        SqlRows.dec(c, "quantity")).unitCost(SqlRows.dec(c, "rate")).moveType("reversal_in")
                        .source("project_issue_reversal", costLineId).notes("Reversal of " + c.get("description")));
            }
        }
        String where = c.get("journalEntryId") == null ? "id = ?" : "\"journalEntryId\" = ?";
        jdbc.sql("UPDATE \"ProjectCostLine\" SET status = 'reversed', \"reversalEntryId\" = ? WHERE \"organizationId\" ="
                + " ? AND " + where).params(reversal, org(), c.get("journalEntryId") == null ? costLineId
                : c.get("journalEntryId")).update();
        audit.recordInTx(AuditInput.of("ProjectCostLine", costLineId, "cancel", null, Obj.of("reversalEntryId",
                reversal)));
        return line(costLineId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> costs(String projectId) {
        projects.project(projectId, false);
        return SqlRows.list(jdbc.sql("SELECT c.*, w.code AS \"wbsCode\" FROM \"ProjectCostLine\" c JOIN"
                + " \"ProjectWbsElement\" w ON w.id = c.\"wbsId\" WHERE c.\"projectId\" = ? ORDER BY c.date, c.\"createdAt\"")
                .param(projectId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> line(String id) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectCostLine\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (c == null) {
            throw ApiException.notFound("Project cost line not found");
        }
        return c;
    }

    // ---- report -------------------------------------------------------------------------------------------------------

    /** Actual cost captured (posted lines) — all of it, or only what depicts progress. */
    public BigDecimal actual(String projectId, boolean progressOnly) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"ProjectCostLine\" WHERE \"projectId\" = ? AND status ="
                + " 'posted'" + (progressOnly ? " AND NOT \"excludeFromProgress\"" : "")).param(projectId)
                .query(BigDecimal.class).single();
    }

    /** Remaining cost to complete in force. */
    public BigDecimal costToComplete(String projectId) {
        return projects.forecast(projectId).stream().map(r -> SqlRows.dec(r, "costToComplete"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Committed cost: open commitments (purchase requests / orders) that carry the project plus the unvalued balance of
     * its active subcontracts (value + approved subcontract variations − certified valuations, ADR-S075).
     */
    public BigDecimal committed(String projectId) {
        return committedByWbs(projectId).values().stream().map(c -> c[0].add(c[1])).reduce(BigDecimal.ZERO,
                BigDecimal::add);
    }

    /** Open purchasing commitments of the project per WBS element (key null = not assigned to an element). */
    public Map<String, BigDecimal> purchasingByWbs(String projectId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT coalesce(rl.\"wbsId\", prl.\"wbsId\") AS \"wbsId\","
                + " coalesce(sum(c.\"openAmount\"), 0) AS amount FROM \"Commitment\" c"
                + " LEFT JOIN \"PurchaseRequestLine\" rl ON c.\"sourceType\" = 'purchase_request' AND rl.id ="
                + " c.\"sourceLineId\" LEFT JOIN \"PurchaseOrderLine\" pl ON c.\"sourceType\" = 'purchase_order' AND"
                + " pl.id = c.\"sourceLineId\" LEFT JOIN \"PurchaseRequestLine\" prl ON prl.id = pl.\"requestLineId\""
                + " WHERE c.\"organizationId\" = ? AND c.\"projectId\" = ? AND c.status = 'open' GROUP BY 1 ORDER BY 1")
                .params(org(), projectId))) {
            out.merge((String) r.get("wbsId"), SqlRows.dec(r, "amount"), BigDecimal::add);
        }
        return out;
    }

    /** Unvalued balance of the project's active subcontracts per WBS element. */
    public Map<String, BigDecimal> subcontractsByWbs(String projectId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT s.\"wbsId\", sum(greatest(s.value + coalesce((SELECT"
                + " sum(v.\"valueDelta\") FROM \"SubcontractVariation\" v WHERE v.\"subcontractId\" = s.id AND v.status ="
                + " 'approved'), 0) - coalesce((SELECT max(sv.\"cumulativeValue\") FROM \"SubcontractValuation\" sv WHERE"
                + " sv.\"subcontractId\" = s.id), 0), 0)) AS amount FROM \"Subcontract\" s WHERE s.\"organizationId\" = ?"
                + " AND s.\"projectId\" = ? AND s.status = 'active' GROUP BY 1 ORDER BY 1").params(org(), projectId))) {
            out.merge((String) r.get("wbsId"), SqlRows.dec(r, "amount"), BigDecimal::add);
        }
        return out;
    }

    /**
     * Committed cost detail (ADR-S075): the open purchasing commitments (with their WBS element, from the request line
     * the order was sourced from) and the active subcontracts with their unvalued balance, plus per-WBS totals.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> commitments(String projectId) {
        projects.project(projectId, false);
        List<Map<String, Object>> purchasing = SqlRows.list(jdbc.sql("SELECT c.id, c.\"sourceType\", c.\"sourceId\","
                + " c.\"sourceNumber\", c.\"sourceLineId\", c.amount, c.\"openAmount\", c.period,"
                + " coalesce(rl.\"wbsId\", prl.\"wbsId\") AS \"wbsId\", w.code AS \"wbsCode\" FROM \"Commitment\" c"
                + " LEFT JOIN \"PurchaseRequestLine\" rl ON c.\"sourceType\" = 'purchase_request' AND rl.id ="
                + " c.\"sourceLineId\" LEFT JOIN \"PurchaseOrderLine\" pl ON c.\"sourceType\" = 'purchase_order' AND"
                + " pl.id = c.\"sourceLineId\" LEFT JOIN \"PurchaseRequestLine\" prl ON prl.id = pl.\"requestLineId\""
                + " LEFT JOIN \"ProjectWbsElement\" w ON w.id = coalesce(rl.\"wbsId\", prl.\"wbsId\")"
                + " WHERE c.\"organizationId\" = ? AND c.\"projectId\" = ? AND c.status = 'open'"
                + " ORDER BY c.\"sourceNumber\", c.id").params(org(), projectId));
        List<Map<String, Object>> subcontracts = SqlRows.list(jdbc.sql("SELECT x.*, greatest(x.\"adjustedValue\" -"
                + " x.valued, 0) AS balance FROM (SELECT s.id, s.number, s.\"wbsId\", w.code AS \"wbsCode\", pa.name AS"
                + " \"supplierName\", s.value, s.value + coalesce((SELECT sum(v.\"valueDelta\") FROM"
                + " \"SubcontractVariation\" v WHERE v.\"subcontractId\" = s.id AND v.status = 'approved'), 0) AS"
                + " \"adjustedValue\", coalesce((SELECT max(sv.\"cumulativeValue\") FROM \"SubcontractValuation\" sv"
                + " WHERE sv.\"subcontractId\" = s.id), 0) AS valued FROM \"Subcontract\" s JOIN \"ProjectWbsElement\" w"
                + " ON w.id = s.\"wbsId\" LEFT JOIN \"Partner\" pa ON pa.id = s.\"supplierId\" WHERE"
                + " s.\"organizationId\" = ? AND s.\"projectId\" = ? AND s.status = 'active') x ORDER BY x.number")
                .params(org(), projectId));
        Map<String, Object> codes = new LinkedHashMap<>();
        SqlRows.list(jdbc.sql("SELECT id, code FROM \"ProjectWbsElement\" WHERE \"projectId\" = ?").param(projectId))
                .forEach(r -> codes.put((String) r.get("id"), r.get("code")));
        List<Map<String, Object>> byWbs = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal[]> e : committedByWbs(projectId).entrySet()) {
            BigDecimal[] c = e.getValue();
            total = total.add(c[0]).add(c[1]);
            byWbs.add(Obj.of("wbsId", e.getKey(), "wbsCode", e.getKey() == null ? null : codes.get(e.getKey()),
                    "purchasing", JsNumbers.dec(c[0]), "subcontracts", JsNumbers.dec(c[1]), "committed",
                    JsNumbers.dec(c[0].add(c[1]))));
        }
        return Obj.of("projectId", projectId, "purchasing", purchasing, "subcontracts", subcontracts, "byWbs", byWbs,
                "total", JsNumbers.dec(total));
    }

    /** {@code [purchasing, subcontracts]} committed per WBS element (key null = unassigned). */
    public Map<String, BigDecimal[]> committedByWbs(String projectId) {
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        purchasingByWbs(projectId).forEach((k, v) -> out.computeIfAbsent(k, x -> zeros())[0] = v);
        subcontractsByWbs(projectId).forEach((k, v) -> out.computeIfAbsent(k, x -> zeros())[1] = v);
        return out;
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
    }

    /** Budget vs committed vs actual vs EAC per WBS element and cost element, with the WIP tie-out. */
    @Transactional
    public Map<String, Object> report(String projectId) {
        Map<String, Object> p = projects.project(projectId, false);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        String budget = jdbc.sql("SELECT id FROM \"ProjectBudget\" WHERE \"projectId\" = ? AND status = 'approved'")
                .param(projectId).query(String.class).optional().orElse(null);
        if (budget != null) {
            for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"wbsId\", \"costElement\", sum(amount) AS amount"
                    + " FROM \"ProjectBudgetLine\" WHERE \"budgetId\" = ? GROUP BY 1, 2").param(budget))) {
                row(rows, l).put("budget", SqlRows.dec(l, "amount"));
            }
        }
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT \"wbsId\", \"costElement\", sum(amount) AS amount"
                + " FROM \"ProjectCostLine\" WHERE \"projectId\" = ? AND status = 'posted' GROUP BY 1, 2")
                .param(projectId))) {
            row(rows, l).put("actual", SqlRows.dec(l, "amount"));
        }
        for (Map<String, Object> l : projects.forecast(projectId)) {
            row(rows, l).put("costToComplete", SqlRows.dec(l, "costToComplete"));
        }
        Map<String, Object> codes = new LinkedHashMap<>();
        SqlRows.list(jdbc.sql("SELECT id, code FROM \"ProjectWbsElement\" WHERE \"projectId\" = ?").param(projectId))
                .forEach(r -> codes.put((String) r.get("id"), r.get("code")));
        BigDecimal tb = BigDecimal.ZERO;
        BigDecimal ta = BigDecimal.ZERO;
        BigDecimal te = BigDecimal.ZERO;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows.values()) {
            BigDecimal b = (BigDecimal) r.getOrDefault("budget", BigDecimal.ZERO);
            BigDecimal a = (BigDecimal) r.getOrDefault("actual", BigDecimal.ZERO);
            BigDecimal etc = (BigDecimal) r.getOrDefault("costToComplete", BigDecimal.ZERO);
            tb = tb.add(b);
            ta = ta.add(a);
            te = te.add(etc);
            out.add(Obj.of("wbsId", r.get("wbsId"), "wbsCode", codes.get(r.get("wbsId")), "costElement",
                    r.get("costElement"), "budget", JsNumbers.dec(b), "actual", JsNumbers.dec(a), "costToComplete",
                    JsNumbers.dec(etc), "eac", JsNumbers.dec(a.add(etc)), "variance", JsNumbers.dec(b.subtract(
                            a.add(etc)))));
        }
        Map<String, BigDecimal[]> byWbs = committedByWbs(projectId);
        BigDecimal purchasing = BigDecimal.ZERO;
        BigDecimal subcontracts = BigDecimal.ZERO;
        for (BigDecimal[] c : byWbs.values()) {
            purchasing = purchasing.add(c[0]);
            subcontracts = subcontracts.add(c[1]);
        }
        BigDecimal committed = purchasing.add(subcontracts);
        BigDecimal eac = ta.add(committed).add(te);
        List<Map<String, Object>> wbsRows = wbsSummary(out, byWbs, codes);
        BigDecimal wip = jdbc.sql("SELECT coalesce(sum(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM \"JournalLine\" jl"
                + " JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = ? AND"
                + " jl.\"accountId\" = ? AND jl.dimensions ->> 'projectId' = ? AND e.status <> 'draft'").params(org(),
                costAccount(p), projectId).query(BigDecimal.class).single();
        BigDecimal released = releasedFromWip(projectId).add(capitalised(projectId));
        return Obj.of("projectId", projectId, "code", p.get("code"), "budgetId", budget, "rows", out, "wbs", wbsRows,
                "totals", Obj.of("budget", JsNumbers.dec(tb), "committed", JsNumbers.dec(committed),
                        "committedPurchasing", JsNumbers.dec(purchasing), "committedSubcontracts",
                        JsNumbers.dec(subcontracts), "actual", JsNumbers.dec(ta), "costToComplete", JsNumbers.dec(te),
                        "eac", JsNumbers.dec(eac), "variance", JsNumbers.dec(tb.subtract(eac))),
                "wipTieOut", Obj.of("glWip", JsNumbers.dec(wip), "subledger", JsNumbers.dec(ta.subtract(released)),
                        "difference", JsNumbers.dec(wip.subtract(ta.subtract(released)))));
    }

    /**
     * Per WBS element (ADR-S075): budget, actual, committed (purchasing + subcontract balance), cost to complete and
     * EAC = actual + committed + cost to complete. Commitments without an element are reported on a row with
     * {@code wbsId = null}.
     */
    private static List<Map<String, Object>> wbsSummary(List<Map<String, Object>> rows,
            Map<String, BigDecimal[]> committed, Map<String, Object> codes) {
        Map<String, BigDecimal[]> acc = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            BigDecimal[] a = acc.computeIfAbsent((String) r.get("wbsId"), k -> new BigDecimal[] {BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO});
            a[0] = a[0].add(new BigDecimal(String.valueOf(r.get("budget"))));
            a[1] = a[1].add(new BigDecimal(String.valueOf(r.get("actual"))));
            a[2] = a[2].add(new BigDecimal(String.valueOf(r.get("costToComplete"))));
        }
        for (String k : committed.keySet()) {
            acc.computeIfAbsent(k, x -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : acc.entrySet()) {
            BigDecimal[] a = e.getValue();
            BigDecimal[] c = committed.getOrDefault(e.getKey(), zeros());
            BigDecimal com = c[0].add(c[1]);
            BigDecimal eac = a[1].add(com).add(a[2]);
            out.add(Obj.of("wbsId", e.getKey(), "wbsCode", e.getKey() == null ? null : codes.get(e.getKey()), "budget",
                    JsNumbers.dec(a[0]), "actual", JsNumbers.dec(a[1]), "committedPurchasing", JsNumbers.dec(c[0]),
                    "committedSubcontracts", JsNumbers.dec(c[1]), "committed", JsNumbers.dec(com), "costToComplete",
                    JsNumbers.dec(a[2]), "eac", JsNumbers.dec(eac), "variance", JsNumbers.dec(a[0].subtract(eac))));
        }
        return out;
    }

    /** Cost already released from project WIP to cost of revenue (contract revenue recognition, CM-8b). */
    public BigDecimal releasedFromWip(String projectId) {
        return jdbc.sql("SELECT coalesce(sum(\"costThisPeriod\"), 0) FROM \"RevenueRecognitionRun\" WHERE"
                + " \"projectId\" = ? AND status = 'posted'").param(projectId).query(BigDecimal.class).single();
    }

    /** Cost moved out of CIP into fixed assets by posted capitalisations (ADR-S074); 0 for other projects. */
    public BigDecimal capitalised(String projectId) {
        return jdbc.sql("SELECT coalesce(sum(amount), 0) FROM \"ProjectCapitalisation\" WHERE \"projectId\" = ? AND"
                + " status = 'posted'").param(projectId).query(BigDecimal.class).single();
    }

    private static Map<String, Object> row(Map<String, Map<String, Object>> rows, Map<String, Object> l) {
        String key = l.get("wbsId") + "|" + l.get("costElement");
        return rows.computeIfAbsent(key, k -> new LinkedHashMap<>(Map.of("wbsId", l.get("wbsId"), "costElement",
                l.get("costElement"))));
    }

    /** The project row (404 otherwise). */
    Map<String, Object> projectRow(String projectId) {
        return projects.project(projectId, false);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    Map<String, Object> capturable(String projectId) {
        Map<String, Object> p = projects.project(projectId, true);
        if (!CAPTURE_STATUSES.contains(p.get("status"))) {
            throw ApiException.badRequest("Cost can only be captured on an active project");
        }
        return p;
    }

    Map<String, Object> costableWbs(String projectId, String wbsId) {
        Map<String, Object> w = projects.wbs(projectId, wbsId);
        if (!Boolean.TRUE.equals(w.get("isCostable"))) {
            throw ApiException.badRequest("WBS element " + w.get("code") + " does not accept cost");
        }
        return w;
    }

    String site(String projectId, String siteId) {
        if (blank(siteId) == null) {
            return null;
        }
        if (jdbc.sql("SELECT count(*) FROM \"ProjectSite\" WHERE id = ? AND \"projectId\" = ?").params(siteId,
                projectId).query(Long.class).single() == 0) {
            throw ApiException.notFound("Site not found on this project");
        }
        return siteId;
    }

    Line debit(Map<String, Object> p, Map<String, Object> w, String siteId, BigDecimal amount, String text) {
        return new Line(costAccount(p), JsNumbers.dec(amount), null, (String) p.get("customerId"), text, branch(p),
                (String) p.get("costCenterId"), Obj.of("projectId", p.get("id"), "wbsId", w.get("id"), "siteId",
                        siteId));
    }

    static String branch(Map<String, Object> p) {
        return (String) p.get("branchId");
    }

    String post(List<Line> lines, String text, Instant date, String sourceType, String sourceId,
            Map<String, Object> p) {
        if (lines.isEmpty()) {
            return null;
        }
        Map<String, Object> je = posting.post(new PostingRequest("GEN", date, text, null, null, sourceType, sourceId,
                null, "projects:" + sourceType + ":" + org() + ":" + sourceId, branch(p), null, null, lines));
        return je == null ? null : (String) je.get("id");
    }

    void insert(String id, String projectId, String wbsId, String siteId, String element, String sourceType,
            String sourceId, String sourceLineId, Instant date, BigDecimal qty, BigDecimal rate, BigDecimal amount,
            boolean exclude, String creditAccount, String je, String description) {
        jdbc.sql("INSERT INTO \"ProjectCostLine\" (id, \"organizationId\", \"projectId\", \"wbsId\", \"siteId\","
                        + " \"costElement\", \"sourceType\", \"sourceId\", \"sourceLineId\", date, quantity, rate, amount,"
                        + " \"excludeFromProgress\", \"creditAccountId\", \"journalEntryId\", description, \"createdBy\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), projectId, wbsId, siteId, element, sourceType, sourceId, sourceLineId,
                        java.sql.Date.valueOf(date.atZone(ZoneOffset.UTC).toLocalDate()), qty, rate, amount, exclude,
                        creditAccount, je, description, TenantContext.userId())
                .update();
    }

    static Instant instant(String d) {
        String v = day(d);
        return v == null ? Instant.now() : LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String required(String v, String field) {
        if (v == null) {
            throw ApiException.badRequest(field + " should not be empty");
        }
        return v;
    }
}
