package com.inventoryerp.fixedasset.application;

import static com.inventoryerp.accounting.application.PostingService.plain;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.fixedasset.domain.Asset;
import com.inventoryerp.fixedasset.domain.AssetCategory;
import com.inventoryerp.fixedasset.domain.AssetStatus;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed-asset impairment (IAS 36; ADR-S073, closes the ACC-5 gap). An impairment writes the carrying amount down to the
 * recoverable amount: Dr {@code asset_impairment_loss} / Cr {@code accumulated_impairment}. Straight-line depreciation
 * afterwards spreads the new carrying amount less residual value over the remaining useful life; the declining
 * methods already run on the (lower) carrying amount. A reversal books the opposite entry, capped at the carrying
 * amount the asset would have had without any impairment (cost less the depreciation the original schedule would
 * have charged) and at the impairment still on the books. Disposal clears the accumulated impairment
 * ({@link AssetLedgerService#dispose}). Impairment always goes to profit or loss (cost model).
 */
@Service
public class AssetImpairmentService {

    /** {@code POST /fixed-assets/:assetId/impairments} and {@code /impairments/reversal}. */
    public record ImpairmentInput(
            @NotNull @DecimalMin("0") BigDecimal recoverableAmount,
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}.*$") String date,
            @Size(max = 1000) String reason) {}

    private final EntityManager em;
    private final JdbcClient jdbc;
    private final AssetService assets;
    private final PostingService posting;
    private final AccountResolverService accounts;
    private final AuditService audit;

    public AssetImpairmentService(EntityManager em, JdbcClient jdbc, AssetService assets, PostingService posting,
            AccountResolverService accounts, AuditService audit) {
        this.em = em;
        this.jdbc = jdbc;
        this.assets = assets;
        this.posting = posting;
        this.accounts = accounts;
        this.audit = audit;
    }

    /** Re-basing data of the latest impairment event of an asset, for the depreciation run. */
    public record Base(BigDecimal carryingAfter, int remainingLifeMonths) {}

    /** Carrying amount, accumulated impairment, the reversal headroom and the impairment history of an asset. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(String assetId) {
        Asset asset = owned(assetId);
        BigDecimal carrying = nz(asset.getCurrentValue());
        BigDecimal accumulated = accumulated(assetId);
        BigDecimal headroom = accumulated.signum() > 0
                ? accumulated.min(withoutImpairment(asset).subtract(carrying)).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT i.id, i.kind, i.\"impairmentDate\","
                + " i.\"carryingBefore\", i.\"recoverableAmount\", i.amount, i.\"carryingAfter\","
                + " i.\"remainingLifeMonths\", i.reason, i.\"journalEntryId\", e.\"entryNumber\", i.\"createdAt\""
                + " FROM \"AssetImpairment\" i LEFT JOIN \"JournalEntry\" e ON e.id = i.\"journalEntryId\" WHERE"
                + " i.\"organizationId\" = ? AND i.\"assetId\" = ? ORDER BY i.\"createdAt\" DESC")
                .params(TenantContext.organizationId(), assetId));
        for (Map<String, Object> r : rows) {
            for (String k : List.of("carryingBefore", "recoverableAmount", "amount", "carryingAfter")) {
                r.put(k, plain(SqlRows.dec(r, k)));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", assetId);
        out.put("carryingAmount", plain(carrying));
        out.put("accumulatedImpairment", plain(accumulated));
        out.put("maxReversal", plain(headroom));
        out.put("impairments", rows);
        return out;
    }

    /** Write the asset down to {@code recoverableAmount} (must be below the carrying amount). */
    @Transactional
    public Map<String, Object> impair(String assetId, ImpairmentInput in) {
        Asset asset = owned(assetId);
        assertActive(asset);
        BigDecimal carrying = nz(asset.getCurrentValue());
        BigDecimal recoverable = in.recoverableAmount();
        if (recoverable.compareTo(carrying) >= 0) {
            throw ApiException.badRequest("Recoverable amount " + plain(recoverable) + " is not below the carrying"
                    + " amount " + plain(carrying) + "; there is no impairment");
        }
        return record(asset, "impairment", carrying, recoverable, carrying.subtract(recoverable), in);
    }

    /** Reverse (part of) earlier impairment losses when the recoverable amount has recovered. */
    @Transactional
    public Map<String, Object> reverse(String assetId, ImpairmentInput in) {
        Asset asset = owned(assetId);
        assertActive(asset);
        BigDecimal accumulated = accumulated(assetId);
        if (accumulated.signum() <= 0) {
            throw ApiException.badRequest("Asset has no impairment loss to reverse");
        }
        BigDecimal carrying = nz(asset.getCurrentValue());
        BigDecimal recoverable = in.recoverableAmount();
        if (recoverable.compareTo(carrying) <= 0) {
            throw ApiException.badRequest("Recoverable amount " + plain(recoverable) + " does not exceed the carrying"
                    + " amount " + plain(carrying) + "; there is nothing to reverse");
        }
        BigDecimal cap = accumulated.min(withoutImpairment(asset).subtract(carrying));
        if (cap.signum() <= 0) {
            throw ApiException.badRequest("The carrying amount already equals what it would be without impairment");
        }
        BigDecimal amount = recoverable.subtract(carrying).min(cap);
        return record(asset, "reversal", carrying, recoverable, amount, in);
    }

    /** Latest impairment event of an asset (impairment or reversal), or {@code null} when it was never impaired. */
    public Base latestBase(String assetId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT \"carryingAfter\", \"remainingLifeMonths\" FROM"
                + " \"AssetImpairment\" WHERE \"assetId\" = ? ORDER BY \"createdAt\" DESC, id DESC LIMIT 1")
                .param(assetId));
        return r == null ? null : new Base(SqlRows.dec(r, "carryingAfter"),
                ((Number) r.get("remainingLifeMonths")).intValue());
    }

    // ------------------------------------------------------------------ internals

    private Map<String, Object> record(Asset asset, String kind, BigDecimal carrying, BigDecimal recoverable,
            BigDecimal amount, ImpairmentInput in) {
        String org = TenantContext.organizationId();
        int count = depreciationCount(asset.getId());
        int life = asset.getUsefulLife() == null ? 60 : asset.getUsefulLife();
        int remaining = Math.max(1, life - count);
        BigDecimal after = "impairment".equals(kind) ? carrying.subtract(amount) : carrying.add(amount);
        Instant date = in.date() == null || in.date().isEmpty() ? Instant.now()
                : LocalDate.parse(in.date().substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant();
        String id = UUID.randomUUID().toString();
        String loss = accounts.ensureByCode("IMPAIRMENT-LOSS", "Asset Impairment Loss", "other_expense",
                "asset_impairment_loss");
        String accum = accounts.ensureByCode("ACCUM-IMPAIRMENT", "Accumulated Impairment", "contra_asset",
                "accumulated_impairment");
        String value = plain(amount);
        List<Line> lines = "impairment".equals(kind)
                ? List.of(new Line(loss, value, null, null, "Impairment loss " + asset.getAssetCode(),
                                asset.getBranchId(), null, null),
                        new Line(accum, null, value, null, "Accumulated impairment " + asset.getAssetCode(),
                                asset.getBranchId(), null, null))
                : List.of(new Line(accum, value, null, null, "Impairment reversed " + asset.getAssetCode(),
                                asset.getBranchId(), null, null),
                        new Line(loss, null, value, null, "Reversal of impairment loss " + asset.getAssetCode(),
                                asset.getBranchId(), null, null));
        Map<String, Object> entry = posting.post(new PostingRequest("GEN", date,
                ("impairment".equals(kind) ? "Impairment " : "Impairment reversal ") + asset.getAssetCode(), null, null,
                "asset_impairment", asset.getId(), null, "asset_impairment:" + id, asset.getBranchId(), null, null,
                lines));
        jdbc.sql("INSERT INTO \"AssetImpairment\" (id, \"organizationId\", \"assetId\", kind, \"impairmentDate\","
                        + " \"carryingBefore\", \"recoverableAmount\", amount, \"carryingAfter\", \"remainingLifeMonths\","
                        + " \"depreciationCount\", reason, \"journalEntryId\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?)")
                .params(id, org, asset.getId(), kind, Timestamp.valueOf(date.atOffset(ZoneOffset.UTC).toLocalDateTime()),
                        carrying, recoverable, amount, after, remaining, count, in.reason(), entry.get("id"),
                        TenantContext.userId())
                .update();
        asset.setCurrentValue(after);
        em.flush();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("assetId", asset.getId());
        out.put("kind", kind);
        out.put("carryingBefore", plain(carrying));
        out.put("recoverableAmount", plain(recoverable));
        out.put("amount", value);
        out.put("carryingAfter", plain(after));
        out.put("remainingLifeMonths", remaining);
        out.put("journalEntryId", entry.get("id"));
        out.put("entryNumber", entry.get("entryNumber"));
        audit.recordInTx(AuditInput.of("AssetImpairment", id, "create", null, out));
        return out;
    }

    /** Net impairment still on the books: losses less reversals. */
    BigDecimal accumulated(String assetId) {
        return jdbc.sql("SELECT coalesce(sum(CASE WHEN kind = 'impairment' THEN amount ELSE -amount END), 0) FROM"
                + " \"AssetImpairment\" WHERE \"organizationId\" = ? AND \"assetId\" = ?")
                .params(TenantContext.organizationId(), assetId).query(BigDecimal.class).single();
    }

    /** Carrying amount the asset would have had without impairment: the original schedule replayed for its runs. */
    private BigDecimal withoutImpairment(Asset asset) {
        BigDecimal cost = asset.getPurchaseCost() != null ? asset.getPurchaseCost() : nz(asset.getCurrentValue());
        BigDecimal salvage = nz(asset.getSalvageValue());
        int life = asset.getUsefulLife() == null ? 60 : asset.getUsefulLife();
        AssetCategory category = asset.getCategoryId() == null ? null : em.find(AssetCategory.class, asset.getCategoryId());
        String method = category != null && category.getDepreciationMethod() != null
                ? category.getDepreciationMethod().value() : "straight_line";
        BigDecimal book = cost;
        BigDecimal acc = BigDecimal.ZERO;
        int n = depreciationCount(asset.getId());
        for (int i = 0; i < n; i++) {
            DepreciationCalculator.Result r = DepreciationCalculator.calculate(method,
                    new DepreciationCalculator.Input(cost, salvage, life, book, acc));
            book = r.bookValue();
            acc = r.accumulatedDepreciation();
        }
        return book;
    }

    private int depreciationCount(String assetId) {
        return jdbc.sql("SELECT count(*) FROM \"AssetDepreciation\" WHERE \"assetId\" = ?").param(assetId)
                .query(Long.class).single().intValue();
    }

    private Asset owned(String assetId) {
        Asset asset = assets.require(assetId);
        if (!TenantContext.organizationId().equals(asset.getOrganizationId())) {
            throw ApiException.notFound("Asset " + assetId + " not found");
        }
        return asset;
    }

    private static void assertActive(Asset asset) {
        if (asset.getStatus() == AssetStatus.DISPOSED) {
            throw ApiException.badRequest("A disposed asset cannot be impaired");
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
