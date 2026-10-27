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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Depreciation runs and GL posting (port of {@code asset-depreciation.service.ts}). A run computes one entry per
 * active asset for the period (skipping assets already depreciated for it), moves the asset's current value to the
 * new book value and optionally posts {@code Dr depreciation_expense / Cr accumulated_depreciation} on the period's
 * first day. Like Nest (no transaction), each asset commits on its own and a failed GL post leaves its entry
 * unposted without aborting the run. Posted entries are immutable: posting again is a no-op.
 */
@Service
public class AssetDepreciationService {

    private static final Logger log = LoggerFactory.getLogger(AssetDepreciationService.class);
    private static final Pattern PERIOD = Pattern.compile("^\\d{4}-\\d{2}.*");

    private final EntityManager em;
    private final EventBus events;
    private final AssetService assets;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final TransactionTemplate tx;

    public AssetDepreciationService(EntityManager em, EventBus events, AssetService assets, PostingService posting,
            AccountDeterminationService determination, PlatformTransactionManager txm) {
        this.em = em;
        this.events = events;
        this.assets = assets;
        this.posting = posting;
        this.determination = determination;
        this.tx = new TransactionTemplate(txm);
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

    /** Run the period for every active asset (or one asset); returns the entries created. */
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
        List<Map<String, Object>> results = new ArrayList<>();
        for (String id : ids) {
            AssetDepreciation entry = tx.execute(s -> depreciate(id, period));
            if (entry == null) {
                continue;
            }
            if (Boolean.TRUE.equals(postEntries)) {
                try {
                    tx.execute(s -> postJournal(em.find(AssetDepreciation.class, entry.getId())));
                } catch (RuntimeException e) {
                    log.warn("[depreciation] GL post failed for {}: {}", entry.getId(), e.getMessage());
                }
            }
            results.add(EntityJson.of(entry));
        }
        events.publish("fixed_asset.depreciation_run", Obj.of("organizationId", org, "period", period,
                "entriesCount", results.size()));
        return results;
    }

    private AssetDepreciation depreciate(String assetId, String period) {
        Asset asset = em.find(Asset.class, assetId);
        long already = em.createQuery("select count(d) from AssetDepreciation d where d.assetId = :id"
                + " and d.period = :p", Long.class).setParameter("id", assetId).setParameter("p", period)
                .getSingleResult();
        if (asset == null || already > 0) {
            return null;
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
        BigDecimal accum = lastDepr != null ? lastDepr.getAccumulatedDepr() : BigDecimal.ZERO;
        BigDecimal book = lastDepr != null ? lastDepr.getBookValue() : cost.subtract(accum);
        DepreciationCalculator.Result r = DepreciationCalculator.calculate(method,
                new DepreciationCalculator.Input(cost, salvage, usefulLife, book, accum));
        if (r.depreciationAmount().signum() <= 0) {
            return null;
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
        return e;
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
                "asset_depreciation", dep.getId(), null, List.of(
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
