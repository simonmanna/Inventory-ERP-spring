package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.ProjectService.blank;
import static com.inventoryerp.projects.application.ProjectService.day;
import static com.inventoryerp.projects.application.ProjectService.org;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.fixedasset.application.AssetCapitalisationService;
import com.inventoryerp.fixedasset.application.AssetCapitalisationService.ConstructedAssetInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Own-use construction (ADR-S074 §3, IAS 16.22): a project flagged as capital accumulates its cost in Construction in
 * Progress instead of project WIP — every capture path, subcontract valuation and back-charge debits / credits the
 * account {@link ProjectCostService#costAccount} returns. Capitalisation moves all or part of the CIP balance into
 * new fixed assets: Dr fixed_asset_valuation per asset / Cr CIP in one journal entry, the assets created through the
 * fixed-asset module ({@code constructed}, in-service date) and linked to the project. A capitalisation is reversed
 * as a whole while none of its assets has posted depreciation.
 */
@Service
public class CapitalProjectService {

    public record AssetLine(String name, String categoryId, BigDecimal amount, Integer usefulLife,
            BigDecimal salvageValue, String inServiceDate, String location, String description) {}

    public record CapitaliseInput(String date, String notes, List<AssetLine> assets) {}

    private final JdbcClient jdbc;
    private final ProjectService projects;
    private final ProjectCostService costs;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final AssetCapitalisationService assets;
    private final SequenceService seq;
    private final AuditService audit;

    public CapitalProjectService(JdbcClient jdbc, ProjectService projects, ProjectCostService costs,
            PostingService posting, AccountDeterminationService determination, AssetCapitalisationService assets,
            SequenceService seq, AuditService audit) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.costs = costs;
        this.posting = posting;
        this.determination = determination;
        this.assets = assets;
        this.seq = seq;
        this.audit = audit;
    }

    /**
     * Flag (or unflag) the project as capital. Only before any cost is captured, so a project's cost never sits in both
     * WIP and CIP.
     */
    @Transactional
    public Map<String, Object> setCapital(String projectId, Boolean capital) {
        if (capital == null) {
            throw ApiException.badRequest("isCapital should not be empty");
        }
        Map<String, Object> p = projects.project(projectId, true);
        if (capital.equals(p.get("isCapital"))) {
            return cip(projectId);
        }
        if (jdbc.sql("SELECT count(*) FROM \"ProjectCostLine\" WHERE \"projectId\" = ?").param(projectId)
                .query(Long.class).single() > 0) {
            throw ApiException.badRequest("The capital flag can only change before any cost is captured on the"
                    + " project");
        }
        if (capital && jdbc.sql("SELECT (SELECT count(*) FROM \"ProgressClaim\" WHERE \"projectId\" = ?) + (SELECT"
                + " count(*) FROM \"RevenueRecognitionRun\" WHERE \"projectId\" = ?)").params(projectId, projectId)
                .query(Long.class).single() > 0) {
            throw ApiException.badRequest("A project with claims or revenue runs is a customer contract, not a capital"
                    + " project");
        }
        jdbc.sql("UPDATE \"Project\" SET \"isCapital\" = ?, \"updatedAt\" = now() WHERE id = ?").params(capital,
                projectId).update();
        audit.recordInTx(AuditInput.of("Project", projectId, "update", Obj.of("isCapital", p.get("isCapital")),
                Obj.of("isCapital", capital)));
        return cip(projectId);
    }

    /** CIP of one project: cost captured, capitalised, balance (sub-ledger) against the GL, and the capitalisations. */
    @Transactional
    public Map<String, Object> cip(String projectId) {
        Map<String, Object> p = projects.project(projectId, false);
        boolean capital = Boolean.TRUE.equals(p.get("isCapital"));
        BigDecimal captured = costs.actual(projectId, false);
        BigDecimal capitalised = costs.capitalised(projectId);
        BigDecimal balance = capital ? captured.subtract(capitalised) : BigDecimal.ZERO;
        BigDecimal gl = capital ? glBalance(projectId) : BigDecimal.ZERO;
        return Obj.of("projectId", projectId, "code", p.get("code"), "isCapital", capital, "cipAccountId", capital
                        ? costs.cipAccount() : null, "costCaptured", JsNumbers.dec(capital ? captured : BigDecimal.ZERO),
                "capitalised", JsNumbers.dec(capitalised), "balance", JsNumbers.dec(balance), "glBalance",
                JsNumbers.dec(gl), "difference", JsNumbers.dec(gl.subtract(balance)), "capitalisations",
                capitalisations(projectId));
    }

    /** Organisation-wide CIP per capital project, reconciled to the GL CIP account. */
    @Transactional
    public Map<String, Object> report() {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalBalance = BigDecimal.ZERO;
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, code, name, status FROM \"Project\" WHERE"
                + " \"organizationId\" = ? AND \"isCapital\" ORDER BY code").param(org()))) {
            String id = (String) p.get("id");
            BigDecimal captured = costs.actual(id, false);
            BigDecimal capitalised = costs.capitalised(id);
            BigDecimal balance = captured.subtract(capitalised);
            BigDecimal gl = glBalance(id);
            totalBalance = totalBalance.add(balance);
            rows.add(Obj.of("projectId", id, "code", p.get("code"), "name", p.get("name"), "status", p.get("status"),
                    "costCaptured", JsNumbers.dec(captured), "capitalised", JsNumbers.dec(capitalised), "balance",
                    JsNumbers.dec(balance), "glBalance", JsNumbers.dec(gl), "difference",
                    JsNumbers.dec(gl.subtract(balance))));
        }
        String account = costs.cipAccount();
        BigDecimal glTotal = jdbc.sql("SELECT coalesce(sum(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM \"JournalLine\""
                + " jl JOIN \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = ? AND"
                + " jl.\"accountId\" = ? AND e.status <> 'draft'").params(org(), account).query(BigDecimal.class)
                .single();
        return Obj.of("cipAccountId", account, "projects", rows, "totals", Obj.of("balance",
                JsNumbers.dec(totalBalance), "glBalance", JsNumbers.dec(glTotal), "difference",
                JsNumbers.dec(glTotal.subtract(totalBalance))));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> capitalisations(String projectId) {
        List<Map<String, Object>> caps = SqlRows.list(jdbc.sql("SELECT * FROM \"ProjectCapitalisation\" WHERE"
                + " \"projectId\" = ? AND \"organizationId\" = ? ORDER BY \"createdAt\"").params(projectId, org()));
        for (Map<String, Object> c : caps) {
            c.put("assets", SqlRows.list(jdbc.sql("SELECT ca.\"assetId\", ca.amount, ca.\"inServiceDate\","
                    + " a.\"assetCode\", a.name, a.status, a.\"deletedAt\" FROM \"ProjectCapitalisationAsset\" ca JOIN"
                    + " \"Asset\" a ON a.id = ca.\"assetId\" WHERE ca.\"capitalisationId\" = ? ORDER BY a.\"assetCode\"")
                    .param(c.get("id"))));
        }
        return caps;
    }

    /** Move {@code sum(assets[].amount)} of the CIP balance into new fixed assets (partial capitalisation allowed). */
    @Transactional
    public Map<String, Object> capitalise(String projectId, CapitaliseInput in) {
        Map<String, Object> p = projects.project(projectId, true);
        if (!Boolean.TRUE.equals(p.get("isCapital"))) {
            throw ApiException.badRequest("Only a capital project can be capitalised");
        }
        if (in.assets() == null || in.assets().isEmpty()) {
            throw ApiException.badRequest("assets should not be empty");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (AssetLine a : in.assets()) {
            if (a == null || a.amount() == null || a.amount().signum() <= 0) {
                throw ApiException.badRequest("Each asset amount must be greater than 0");
            }
            if (day(a.inServiceDate()) == null) {
                throw ApiException.badRequest("inServiceDate should not be empty");
            }
            total = total.add(a.amount().setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal balance = costs.actual(projectId, false).subtract(costs.capitalised(projectId));
        if (total.compareTo(balance) > 0) {
            throw ApiException.badRequest("Only " + balance.setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + " is left in construction in progress");
        }
        String d = day(in.date()) == null ? LocalDate.now(ZoneOffset.UTC).toString() : day(in.date());
        String id = UUID.randomUUID().toString();
        String number = seq.next("project_capitalisation", "CAP/", 5);
        String label = "Capitalisation " + number + " · project " + p.get("code");
        jdbc.sql("INSERT INTO \"ProjectCapitalisation\" (id, \"organizationId\", \"projectId\", number,"
                        + " \"capitalisationDate\", amount, notes, \"createdBy\") VALUES (?, ?, ?, ?, ?::date, ?, ?, ?)")
                .params(id, org(), projectId, number, d, total, blank(in.notes()), TenantContext.userId()).update();
        String fa = determination.mapped("fixed_asset_valuation");
        Map<String, Object> dims = Obj.of("projectId", projectId, "capitalisationId", id);
        List<Line> lines = new ArrayList<>();
        for (AssetLine a : in.assets()) {
            BigDecimal amount = a.amount().setScale(2, RoundingMode.HALF_UP);
            String inService = day(a.inServiceDate());
            Map<String, Object> asset = assets.createConstructed(new ConstructedAssetInput(a.name(), a.categoryId(),
                    amount, a.usefulLife(), a.salvageValue(), LocalDate.parse(inService), (String) p.get("branchId"),
                    a.location(), a.description(), "Capitalised from project " + p.get("code") + " (" + number + ")"));
            jdbc.sql("INSERT INTO \"ProjectCapitalisationAsset\" (id, \"organizationId\", \"capitalisationId\","
                            + " \"assetId\", amount, \"inServiceDate\") VALUES (?, ?, ?, ?, ?, ?::date)")
                    .params(UUID.randomUUID().toString(), org(), id, asset.get("id"), amount, inService).update();
            lines.add(new Line(fa, JsNumbers.dec(amount), null, null, label + " · " + asset.get("assetCode"),
                    (String) p.get("branchId"), (String) p.get("costCenterId"), Obj.merge(dims, Obj.of("assetId",
                            asset.get("id")))));
        }
        lines.add(new Line(costs.cipAccount(), null, JsNumbers.dec(total), null, label, (String) p.get("branchId"),
                (String) p.get("costCenterId"), dims));
        Map<String, Object> entry = posting.post(new PostingRequest("GEN", LocalDate.parse(d).atStartOfDay(
                ZoneOffset.UTC).toInstant(), label, null, null, "project_capitalisation", id, null,
                "projects:capitalisation:" + org() + ":" + id, (String) p.get("branchId"), null, null, lines));
        String je = entry == null ? null : (String) entry.get("id");
        jdbc.sql("UPDATE \"ProjectCapitalisation\" SET \"journalEntryId\" = ? WHERE id = ?").params(je, id).update();
        audit.recordInTx(AuditInput.of("ProjectCapitalisation", id, "post", null, Obj.of("projectId", projectId,
                "amount", JsNumbers.dec(total), "assets", in.assets().size())));
        return capitalisation(id);
    }

    /** Reverse a capitalisation: the journal entry, and the assets (refused once one has posted depreciation). */
    @Transactional
    public Map<String, Object> reverse(String capitalisationId) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectCapitalisation\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(capitalisationId, org()));
        if (c == null) {
            throw ApiException.notFound("Capitalisation not found");
        }
        if (!"posted".equals(c.get("status"))) {
            throw ApiException.badRequest("This capitalisation is already reversed");
        }
        projects.project((String) c.get("projectId"), true);
        List<String> assetIds = jdbc.sql("SELECT \"assetId\" FROM \"ProjectCapitalisationAsset\" WHERE"
                + " \"capitalisationId\" = ?").param(capitalisationId).query(String.class).list();
        for (String a : assetIds) {
            if (assets.hasPostedDepreciation(a)) {
                String code = jdbc.sql("SELECT \"assetCode\" FROM \"Asset\" WHERE id = ?").param(a).query(String.class)
                        .single();
                throw ApiException.badRequest("Asset " + code + " already has posted depreciation; the"
                        + " capitalisation cannot be reversed");
            }
        }
        String reversal = null;
        if (c.get("journalEntryId") != null) {
            reversal = (String) posting.reverse((String) c.get("journalEntryId"), Instant.now(), "Reversal of"
                    + " capitalisation " + c.get("number")).get("id");
        }
        for (String a : assetIds) {
            assets.removeConstructed(a);
        }
        jdbc.sql("UPDATE \"ProjectCapitalisation\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedBy\" = ?,"
                + " \"reversedAt\" = now() WHERE id = ?").params(reversal, TenantContext.userId(), capitalisationId)
                .update();
        audit.recordInTx(AuditInput.of("ProjectCapitalisation", capitalisationId, "cancel", null,
                Obj.of("reversalEntryId", reversal)));
        return capitalisation(capitalisationId);
    }

    private Map<String, Object> capitalisation(String id) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"ProjectCapitalisation\" WHERE id = ?").param(id));
        c.put("assets", SqlRows.list(jdbc.sql("SELECT ca.\"assetId\", ca.amount, ca.\"inServiceDate\", a.\"assetCode\","
                + " a.name, a.status, a.\"deletedAt\" FROM \"ProjectCapitalisationAsset\" ca JOIN \"Asset\" a ON a.id ="
                + " ca.\"assetId\" WHERE ca.\"capitalisationId\" = ? ORDER BY a.\"assetCode\"").param(id)));
        return c;
    }

    /** GL balance of the CIP account carrying the project dimension. */
    private BigDecimal glBalance(String projectId) {
        return jdbc.sql("SELECT coalesce(sum(jl.\"baseDebit\" - jl.\"baseCredit\"), 0) FROM \"JournalLine\" jl JOIN"
                + " \"JournalEntry\" e ON e.id = jl.\"journalEntryId\" WHERE jl.\"organizationId\" = ? AND"
                + " jl.\"accountId\" = ? AND jl.dimensions ->> 'projectId' = ? AND e.status <> 'draft'").params(org(),
                costs.cipAccount(), projectId).query(BigDecimal.class).single();
    }
}
