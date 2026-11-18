package com.inventoryerp.fixedasset.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.fixedasset.domain.Asset;
import com.inventoryerp.fixedasset.domain.AssetCategory;
import com.inventoryerp.fixedasset.domain.AssetDepreciation;
import com.inventoryerp.fixedasset.domain.AssetStatus;
import com.inventoryerp.fixedasset.domain.DepreciationMethod;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Depreciation runs and GL posting (port of {@code asset-depreciation.service.ts}). A run computes one entry per
 * active asset for the period (skipping assets already depreciated for it), moves the asset's current value to the
 * new book value and optionally posts {@code Dr depreciation_expense / Cr accumulated_depreciation} on the period's
 * first day. Each asset runs in its own transaction: its entry, its new book value and (with {@code postEntries})
 * its journal entry commit together, so a failed GL post rolls the asset back instead of leaving the register ahead
 * of the ledger (R14); the failure is logged and reported and the run goes on with the next asset. Posted entries are
 * immutable: posting again is a no-op, and the unique (asset, period) entry keeps a period from depreciating twice.
 *
 * <p>Units-of-production assets need {@code Asset.totalUnits} and the period's usage ({@code AssetUsage}, recorded
 * through {@link #recordUsage}); without them the asset is skipped and reported, never depreciated on straight line.
 */
@Service
public class AssetDepreciationService {

    private static final Logger log = LoggerFactory.getLogger(AssetDepreciationService.class);
    private static final Pattern PERIOD = Pattern.compile("^\\d{4}-\\d{2}.*");

    private final EntityManager em;
    private final JdbcClient jdbc;
    private final EventBus events;
    private final AssetService assets;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final TransactionTemplate tx;
    private AssetImpairmentService impairments;

    public AssetDepreciationService(EntityManager em, JdbcClient jdbc, EventBus events, AssetService assets,
            PostingService posting, AccountDeterminationService determination, PlatformTransactionManager txm) {
        this.em = em;
        this.jdbc = jdbc;
        this.events = events;
        this.assets = assets;
        this.posting = posting;
        this.determination = determination;
        this.tx = new TransactionTemplate(txm);
    }

    /** ADR-S073: impaired assets depreciate from their carrying amount; optional for hand-built instances. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setImpairments(AssetImpairmentService impairments) {
        this.impairments = impairments;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> findByAsset(String assetId) {
        return EntityJson.list(em.createQuery("select d from AssetDepreciation d where d.assetId = :id"
                + " order by d.period desc", AssetDepreciation.class).setParameter("id", assetId).getResultList());
    }

    /** Every entry of the period with {@code asset: {name, assetCode, categoryId}}, by asset name. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findByPeriod(String period) {
        List<AssetDepreciation> rows = em.createQuery("select d from AssetDepreciation d left join d.asset a"
                + " where d.period = :p order by a.name asc", AssetDepreciation.class).setParameter("p", period)
                .getResultList();
        return assets.withAssetBrief(rows, true);
    }

    /** What one asset's step of a run did: the entry created, or why the asset was skipped (both null: nothing due). */
    private record Outcome(AssetDepreciation entry, String skipped) {

        static final Outcome NONE = new Outcome(null, null);

        static Outcome skip(String reason) {
            return new Outcome(null, reason);
        }
    }

    /**
     * Run the period for every active asset (or one asset); returns the entries created. Assets skipped (units of
     * production without total units or usage) or rolled back (the GL post failed) are logged and listed under
     * {@code skipped} in the {@code fixed_asset.depreciation_run} event.
     */
    public List<Map<String, Object>> run(String period, Boolean postEntries, String assetId) {
        String org = TenantContext.organizationId();
        List<String> ids = tx.execute(s -> {
            String jpql = "select a.id from Asset a where a.status = :st" + (assetId != null ? " and a.id = :id" : "");
            var q = em.createQuery(jpql, String.class).setParameter("st", AssetStatus.ACTIVE);
            if (assetId != null) {
                q.setParameter("id", assetId);
            }
            return q.getResultList();
        });
        boolean post = Boolean.TRUE.equals(postEntries);
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (String id : ids) {
            Outcome outcome;
            try {
                // R14: the register moves only together with its journal entry; a failed post rolls both back.
                outcome = tx.execute(s -> {
                    Outcome o = depreciate(id, period);
                    if (o.entry() != null && post) {
                        postJournal(o.entry());
                    }
                    return o;
                });
            } catch (RuntimeException e) {
                log.warn("[depreciation] asset {} not depreciated for {}: {}", id, period, e.getMessage());
                skipped.add(Obj.of("assetId", id, "reason", post ? "GL posting failed; the asset was not depreciated: "
                        + e.getMessage() : "Depreciation failed: " + e.getMessage()));
                continue;
            }
            if (outcome == null) {
                continue;
            }
            if (outcome.skipped() != null) {
                log.warn("[depreciation] asset {} skipped for {}: {}", id, period, outcome.skipped());
                skipped.add(Obj.of("assetId", id, "reason", outcome.skipped()));
            }
            if (outcome.entry() != null) {
                results.add(EntityJson.of(outcome.entry()));
            }
        }
        events.publish("fixed_asset.depreciation_run", Obj.of("organizationId", org, "period", period,
                "entriesCount", results.size(), "skipped", skipped));
        return results;
    }

    private Outcome depreciate(String assetId, String period) {
        Asset asset = em.find(Asset.class, assetId);
        long already = em.createQuery("select count(d) from AssetDepreciation d where d.assetId = :id"
                + " and d.period = :p", Long.class).setParameter("id", assetId).setParameter("p", period)
                .getSingleResult();
        if (asset == null || already > 0 || beforeInService(assetId, period)) {
            return Outcome.NONE;
        }
        List<AssetDepreciation> last = em.createQuery("select d from AssetDepreciation d where d.assetId = :id"
                + " order by d.period desc", AssetDepreciation.class).setParameter("id", assetId).setMaxResults(1)
                .getResultList();
        AssetDepreciation lastDepr = last.isEmpty() ? null : last.getFirst();
        BigDecimal cost = asset.getPurchaseCost() != null ? asset.getPurchaseCost()
                : asset.getCurrentValue() != null ? asset.getCurrentValue() : BigDecimal.ZERO;
        BigDecimal salvage = asset.getSalvageValue() != null ? asset.getSalvageValue() : BigDecimal.ZERO;
        int usefulLife = asset.getUsefulLife() != null ? asset.getUsefulLife() : 60;
        AssetCategory category = asset.getCategoryId() == null ? null
                : em.find(AssetCategory.class, asset.getCategoryId());
        String method = category != null && category.getDepreciationMethod() != null
                ? category.getDepreciationMethod().value() : "straight_line";
        BigDecimal factor = category != null ? category.getDecliningFactor() : null;
        DepreciationCalculator.Usage usage = null;
        if (DepreciationMethod.UNITS_OF_PRODUCTION.value().equals(method)) {
            // R14: units of production never falls back to straight line.
            if (asset.getTotalUnits() == null || asset.getTotalUnits().signum() <= 0) {
                return Outcome.skip("Units of production needs the asset's total estimated units (totalUnits)");
            }
            String month = usageMonth(period);
            BigDecimal units = usageUnits(assetId, month);
            if (units == null) {
                return Outcome.skip("No usage recorded for " + month);
            }
            usage = new DepreciationCalculator.Usage(units, asset.getTotalUnits(), unitsBefore(assetId, month));
        }
        BigDecimal accum = lastDepr != null ? lastDepr.getAccumulatedDepr() : BigDecimal.ZERO;
        BigDecimal book = lastDepr != null ? lastDepr.getBookValue() : cost.subtract(accum);
        AssetImpairmentService.Base impaired = impairments == null ? null : impairments.latestBase(assetId);
        DepreciationCalculator.Result r = impaired == null
                ? DepreciationCalculator.calculate(method,
                        new DepreciationCalculator.Input(cost, salvage, usefulLife, book, accum), factor, usage)
                : DepreciationCalculator.calculateImpaired(method, salvage, usefulLife,
                        asset.getCurrentValue() != null ? asset.getCurrentValue() : book, accum,
                        impaired.carryingAfter(), impaired.remainingLifeMonths(), factor, usage);
        if (r.depreciationAmount().signum() <= 0) {
            return Outcome.NONE;
        }
        AssetDepreciation e = new AssetDepreciation();
        e.setAssetId(assetId);
        e.setPeriod(period);
        e.setMethod(DbEnum.fromValue(DepreciationMethod.class, method));
        e.setAssetCost(cost);
        e.setSalvageValue(salvage);
        e.setUsefulLife(usefulLife);
        e.setDepreciationAmount(r.depreciationAmount());
        e.setAccumulatedDepr(r.accumulatedDepreciation());
        e.setBookValue(r.bookValue());
        e.setIsPosted(false);
        em.persist(e);
        em.flush();
        em.refresh(e);
        asset.setCurrentValue(r.bookValue());
        em.flush();
        return new Outcome(e, null);
    }

    /** The usage month of a run period: its {@code YYYY-MM} prefix (the period itself when it has none). */
    static String usageMonth(String period) {
        return period != null && PERIOD.matcher(period).matches() ? period.substring(0, 7) : period;
    }

    /** Units recorded for the asset in {@code month}, or null when none were recorded. */
    private BigDecimal usageUnits(String assetId, String month) {
        return jdbc.sql("SELECT units FROM \"AssetUsage\" WHERE \"organizationId\" = ? AND \"assetId\" = ? AND"
                + " period = ?").params(TenantContext.organizationId(), assetId, month).query(BigDecimal.class)
                .optional().orElse(null);
    }

    /** Units recorded for the asset before {@code month} (units-of-production re-basing after an impairment). */
    private BigDecimal unitsBefore(String assetId, String month) {
        return jdbc.sql("SELECT coalesce(sum(units), 0) FROM \"AssetUsage\" WHERE \"organizationId\" = ? AND"
                + " \"assetId\" = ? AND period < ?").params(TenantContext.organizationId(), assetId, month)
                .query(BigDecimal.class).single();
    }

    /** R14: the usage recorded for an asset, newest month first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> usage(String assetId) {
        assets.require(assetId);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT id, \"assetId\", period, units, notes,"
                + " \"createdBy\", \"createdAt\", \"updatedAt\" FROM \"AssetUsage\" WHERE \"organizationId\" = ? AND"
                + " \"assetId\" = ? ORDER BY period DESC").params(TenantContext.organizationId(), assetId));
        rows.forEach(r -> r.put("units", JsNumbers.dec(SqlRows.dec(r, "units"))));
        return rows;
    }

    /**
     * R14: record (or correct) the units an asset used in {@code period} ({@code YYYY-MM}) for units-of-production
     * depreciation. Refused once that month is depreciated: the posted charge was computed from it.
     */
    @Transactional
    public Map<String, Object> recordUsage(String assetId, String period, BigDecimal units, String notes) {
        assets.require(assetId);
        if (units == null || units.signum() < 0) {
            throw ApiException.badRequest("units must not be less than 0");
        }
        long done = em.createQuery("select count(d) from AssetDepreciation d where d.assetId = :id"
                + " and substring(d.period, 1, 7) = :p", Long.class).setParameter("id", assetId)
                .setParameter("p", period).getSingleResult();
        if (done > 0) {
            throw ApiException.badRequest("Depreciation for " + period + " is already recorded; its usage can no"
                    + " longer change");
        }
        jdbc.sql("INSERT INTO \"AssetUsage\" (id, \"organizationId\", \"assetId\", period, units, notes, \"createdBy\")"
                + " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"assetId\", period) DO UPDATE SET"
                + " units = EXCLUDED.units, notes = EXCLUDED.notes, \"updatedAt\" = CURRENT_TIMESTAMP")
                .params(UUID.randomUUID().toString(), TenantContext.organizationId(), assetId, period, units, notes,
                        TenantContext.userId()).update();
        return usage(assetId).stream().filter(r -> period.equals(r.get("period"))).findFirst().orElseThrow();
    }

    /**
     * ADR-S074: an asset with an in-service date (capitalised from a capital project) is not depreciated for periods
     * before the month it went into service. Assets without one are unaffected.
     */
    private boolean beforeInService(String assetId, String period) {
        if (period == null || !PERIOD.matcher(period).matches()) {
            return false;
        }
        List<?> rows = em.createNativeQuery("SELECT \"inServiceDate\" FROM \"Asset\" WHERE id = ?1")
                .setParameter(1, assetId).getResultList();
        Object v = rows.isEmpty() ? null : rows.getFirst();
        return v != null && period.substring(0, 7).compareTo(v.toString().substring(0, 7)) < 0;
    }

    /** Dr Depreciation Expense / Cr Accumulated Depreciation; stamps the entry. No-op when already posted. */
    private String postJournal(AssetDepreciation dep) {
        if (dep.getJournalEntryId() != null) {
            return dep.getJournalEntryId();
        }
        BigDecimal amount = dep.getDepreciationAmount();
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        String expense = determination.mapped("depreciation_expense");
        String accum = determination.mapped("accumulated_depreciation");
        Instant date = PERIOD.matcher(dep.getPeriod()).matches()
                ? LocalDate.parse(dep.getPeriod().substring(0, 7) + "-01").atStartOfDay(ZoneOffset.UTC).toInstant()
                : Instant.now();
        String value = JsNumbers.dec(amount);
        Map<String, Object> je = posting.post(PostingRequest.of("GEN", date, "Depreciation " + dep.getPeriod(),
                "asset_depreciation", dep.getId(), "asset_depreciation:" + dep.getId(), List.of(
                        Line.debit(expense, value, "Depreciation expense"),
                        Line.credit(accum, value, "Accumulated depreciation"))));
        dep.setJournalEntryId((String) je.get("id"));
        dep.setIsPosted(true);
        dep.setPostedAt(Instant.now());
        em.flush();
        return dep.getJournalEntryId();
    }

    /** Post one entry behind the {@code depreciation_run} approval gate (400 while pending). */
    @Transactional
    public Map<String, Object> postEntry(String id) {
        List<AssetDepreciation> rows = em.createQuery("select d from AssetDepreciation d where d.id = :id",
                AssetDepreciation.class).setParameter("id", id).getResultList();
        if (rows.isEmpty()) {
            throw ApiException.notFound("Depreciation entry " + id + " not found");
        }
        AssetDepreciation dep = rows.getFirst();
        Map<String, Object> gate = assets.gate("depreciation_run", dep.getId(), Obj.of(
                "amount", JsNumbers.js(dep.getDepreciationAmount() == null ? BigDecimal.ZERO
                        : dep.getDepreciationAmount()),
                "period", dep.getPeriod()));
        if (AssetService.needsApproval(gate)) {
            throw ApiException.badRequest("Approval required before posting depreciation. Pending approval request "
                    + gate.get("requestId") + ".");
        }
        postJournal(dep);
        em.refresh(dep);
        return EntityJson.of(dep);
    }
}
