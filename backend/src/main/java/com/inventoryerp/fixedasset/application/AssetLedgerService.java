package com.inventoryerp.fixedasset.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.fixedasset.domain.Asset;
import com.inventoryerp.fixedasset.domain.AssetAcquisition;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Fixed-asset GL lifecycle (ACC-5, finding B11). Depreciation already posted (Dr expense / Cr accumulated); this adds
 * the rest of the asset's life so the register and the GL agree:
 * <ul>
 *   <li><b>capitalisation</b> on the acquisition record: Dr fixed assets / Cr the supplier's payable (vendor given) or
 *       the asset clearing account ({@code suspense}) to be matched with the supplier bill;</li>
 *   <li><b>revaluation</b>: increase Cr revaluation surplus; decrease first releases the asset's own surplus, the rest
 *       is a loss (IAS 16.39-40);</li>
 *   <li><b>disposal</b>: Dr accumulated depreciation (posted), Dr proceeds, Cr fixed assets (cost), gain / loss to
 *       {@code asset_gain_loss}.</li>
 * </ul>
 * Every posting carries a deterministic posting key, so a retry never books twice.
 */
@Service
public class AssetLedgerService {

    private final JdbcClient jdbc;
    private final PostingService posting;
    private final AccountDeterminationService determination;

    public AssetLedgerService(JdbcClient jdbc, PostingService posting, AccountDeterminationService determination) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.determination = determination;
    }

    /** Capitalised cost of an asset: the acquisition's capitalised cost, else purchase cost + freight + install + other. */
    public static BigDecimal capitalisedCost(Asset asset, AssetAcquisition acq) {
        if (acq != null && acq.getCapitalizedCost() != null && acq.getCapitalizedCost().signum() > 0) {
            return acq.getCapitalizedCost();
        }
        if (asset.getCapitalizedCost() != null && asset.getCapitalizedCost().signum() > 0) {
            return asset.getCapitalizedCost();
        }
        BigDecimal cost = nz(asset.getPurchaseCost());
        if (acq != null) {
            cost = cost.add(nz(acq.getFreight())).add(nz(acq.getInstallationCost())).add(nz(acq.getOtherCosts()));
        }
        return cost;
    }

    /** Dr fixed assets / Cr payable (vendor) or asset clearing. Returns the entry id, or null for a zero cost. */
    public String capitalise(Asset asset, AssetAcquisition acq, Instant date) {
        BigDecimal cost = capitalisedCost(asset, acq);
        if (cost.signum() <= 0) {
            return null;
        }
        String vendor = acq != null && acq.getVendorId() != null ? acq.getVendorId() : asset.getSupplierId();
        String credit = vendor != null ? determination.payableAccount(payableOf(vendor))
                : determination.mapped("suspense");
        String value = JsNumbers.dec(cost);
        return id(posting.post(new PostingRequest("GEN", date, "Capitalisation " + asset.getAssetCode(), null, null,
                "asset_acquisition", asset.getId(), null, "asset_acquisition:" + asset.getId(), asset.getBranchId(),
                null, null, List.of(new Line(determination.mapped("fixed_asset_valuation"), value, null, null,
                        "Asset " + asset.getAssetCode(), asset.getBranchId(), null, null),
                        new Line(credit, null, value, vendor, vendor != null ? "Payable for asset" : "Asset clearing",
                                asset.getBranchId(), null, null)))));
    }

    /** Reverse the capitalisation (acquisition record removed before any other movement). */
    public void reverseCapitalisation(String assetId) {
        String entry = jdbc.sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"sourceType\" ="
                        + " 'asset_acquisition' AND \"sourceId\" = ? AND status = 'posted' LIMIT 1")
                .params(TenantContext.organizationId(), assetId).query(String.class).optional().orElse(null);
        if (entry != null) {
            posting.reverse(entry, null, "Acquisition record removed");
        }
    }

    /** Revaluation from {@code previous} to {@code next} book value; returns the entry id (null when unchanged). */
    public String revalue(Asset asset, String revaluationId, BigDecimal previous, BigDecimal next, Instant date) {
        BigDecimal diff = nz(next).subtract(nz(previous));
        if (diff.signum() == 0) {
            return null;
        }
        String fa = determination.mapped("fixed_asset_valuation");
        String surplus = determination.mapped("asset_revaluation_surplus");
        String loss = determination.mapped("asset_gain_loss");
        List<Line> lines = new ArrayList<>();
        if (diff.signum() > 0) {
            lines.add(Line.debit(fa, JsNumbers.dec(diff), "Revaluation increase"));
            lines.add(Line.credit(surplus, JsNumbers.dec(diff), "Revaluation surplus"));
        } else {
            BigDecimal decrease = diff.negate();
            BigDecimal available = surplusOf(asset.getId(), surplus).max(BigDecimal.ZERO);
            BigDecimal fromSurplus = available.min(decrease);
            BigDecimal toLoss = decrease.subtract(fromSurplus);
            if (fromSurplus.signum() > 0) {
                lines.add(Line.debit(surplus, JsNumbers.dec(fromSurplus), "Revaluation surplus released"));
            }
            if (toLoss.signum() > 0) {
                lines.add(Line.debit(loss, JsNumbers.dec(toLoss), "Revaluation loss"));
            }
            lines.add(Line.credit(fa, JsNumbers.dec(decrease), "Revaluation decrease"));
        }
        return id(posting.post(new PostingRequest("GEN", date, "Revaluation " + asset.getAssetCode(), null, null,
                "asset_revaluation", asset.getId(), null, "asset_revaluation:" + revaluationId, asset.getBranchId(),
                null, null, lines)));
    }

    /**
     * Disposal: removes the carrying amount (cost less posted accumulated depreciation, plus revaluations), books the
     * proceeds and the gain / loss. {@code proceedsAccountId} defaults to the default cash account.
     */
    public String dispose(Asset asset, AssetAcquisition acq, String disposalId, BigDecimal proceeds,
            String proceedsAccountId, Instant date) {
        String fa = determination.mapped("fixed_asset_valuation");
        String accumAcct = determination.mapped("accumulated_depreciation");
        String gainLoss = determination.mapped("asset_gain_loss");
        BigDecimal cost = capitalisedCost(asset, acq).add(revaluedOn(asset.getId(), fa));
        BigDecimal accumulated = jdbc.sql("SELECT coalesce(sum(\"depreciationAmount\"), 0) FROM \"AssetDepreciation\""
                + " WHERE \"assetId\" = ? AND \"isPosted\" = true").param(asset.getId()).query(BigDecimal.class).single();
        BigDecimal received = nz(proceeds);
        BigDecimal gain = received.subtract(cost.subtract(accumulated));
        List<Line> lines = new ArrayList<>();
        if (accumulated.signum() > 0) {
            lines.add(Line.debit(accumAcct, JsNumbers.dec(accumulated), "Accumulated depreciation removed"));
        }
        if (received.signum() > 0) {
            lines.add(Line.debit(proceedsAccountId != null ? proceedsAccountId : determination.mapped("default_cash"),
                    JsNumbers.dec(received), "Disposal proceeds"));
        }
        if (gain.signum() < 0) {
            lines.add(Line.debit(gainLoss, JsNumbers.dec(gain.negate()), "Loss on disposal"));
        }
        if (cost.signum() > 0) {
            lines.add(Line.credit(fa, JsNumbers.dec(cost), "Asset cost removed"));
        }
        if (gain.signum() > 0) {
            lines.add(Line.credit(gainLoss, JsNumbers.dec(gain), "Gain on disposal"));
        }
        if (lines.size() < 2) {
            return null;
        }
        return id(posting.post(new PostingRequest("GEN", date, "Disposal " + asset.getAssetCode(), null, null,
                "asset_disposal", asset.getId(), null, "asset_disposal:" + disposalId, asset.getBranchId(), null, null,
                lines)));
    }

    /** Net revaluation (base) booked on the fixed-asset account for this asset. */
    private BigDecimal revaluedOn(String assetId, String faAccount) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseDebit\" - l.\"baseCredit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND"
                + " e.\"sourceType\" = 'asset_revaluation' AND e.\"sourceId\" = ? AND e.status = 'posted' AND"
                + " l.\"accountId\" = ?").params(TenantContext.organizationId(), assetId, faAccount)
                .query(BigDecimal.class).single();
    }

    /** Surplus (credit balance) built up by this asset's revaluations. */
    private BigDecimal surplusOf(String assetId, String surplusAccount) {
        return jdbc.sql("SELECT coalesce(sum(l.\"baseCredit\" - l.\"baseDebit\"), 0) FROM \"JournalLine\" l JOIN"
                + " \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE e.\"organizationId\" = ? AND"
                + " e.\"sourceType\" = 'asset_revaluation' AND e.\"sourceId\" = ? AND e.status = 'posted' AND"
                + " l.\"accountId\" = ?").params(TenantContext.organizationId(), assetId, surplusAccount)
                .query(BigDecimal.class).single();
    }

    private String payableOf(String partnerId) {
        return jdbc.sql("SELECT \"payableAccountId\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(partnerId, TenantContext.organizationId()).query(String.class).optional().orElse(null);
    }

    private static String id(Map<String, Object> entry) {
        return entry == null ? null : (String) entry.get("id");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
