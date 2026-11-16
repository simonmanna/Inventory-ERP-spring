package com.inventoryerp.fixedasset.application;

import com.inventoryerp.fixedasset.domain.AcquisitionMethod;
import com.inventoryerp.fixedasset.domain.Asset;
import com.inventoryerp.fixedasset.domain.AssetCategory;
import com.inventoryerp.fixedasset.domain.AssetStatus;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assets created by capitalising Construction in Progress (ADR-S074 §3). The caller (the projects module) owns the
 * journal entry (Dr fixed_asset_valuation / Cr CIP) and its own approval control ({@code project:post}); this service
 * only writes the register: an {@code active}, {@code constructed} asset whose cost is the capitalised amount and whose
 * in-service date is where depreciation starts. No acquisition record is written, so the acquisition GL posting of
 * ACC-5 never runs for these assets.
 */
@Service
public class AssetCapitalisationService {

    /** One asset to create; {@code usefulLife} in months (the category default when null). */
    public record ConstructedAssetInput(String name, String categoryId, BigDecimal cost, Integer usefulLife,
            BigDecimal salvageValue, LocalDate inServiceDate, String branchId, String location, String description,
            String sourceReference) {}

    private final EntityManager em;
    private final EventBus events;
    private final SequenceService sequence;
    private final AssetService assets;

    public AssetCapitalisationService(EntityManager em, EventBus events, SequenceService sequence,
            AssetService assets) {
        this.em = em;
        this.events = events;
        this.sequence = sequence;
        this.assets = assets;
    }

    /** Create the asset; returns the asset JSON with its category and {@code inServiceDate}. */
    @Transactional
    public Map<String, Object> createConstructed(ConstructedAssetInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        if (in.inServiceDate() == null) {
            throw ApiException.badRequest("inServiceDate should not be empty");
        }
        if (in.cost() == null || in.cost().signum() <= 0) {
            throw ApiException.badRequest("amount must be greater than 0");
        }
        AssetCategory category = in.categoryId() == null ? null : em.createQuery("select c from AssetCategory c where"
                + " c.id = :id", AssetCategory.class).setParameter("id", in.categoryId()).getResultList().stream()
                .findFirst().orElse(null);
        if (category == null) {
            throw ApiException.notFound("Asset category not found");
        }
        Integer life = in.usefulLife() != null ? in.usefulLife() : category.getDefaultUsefulLife();
        if (life == null || life <= 0) {
            throw ApiException.badRequest("usefulLife must be greater than 0");
        }
        BigDecimal cost = in.cost().setScale(2, RoundingMode.HALF_UP);
        BigDecimal salvage = in.salvageValue() == null ? BigDecimal.ZERO : in.salvageValue();
        if (salvage.signum() < 0 || salvage.compareTo(cost) >= 0) {
            throw ApiException.badRequest("salvageValue must be at least 0 and below the amount");
        }
        Asset a = new Asset();
        a.setOrganizationId(TenantContext.organizationId());
        a.setAssetCode(sequence.next("asset", "AST-", null));
        a.setName(in.name().trim());
        a.setDescription(in.description());
        a.setCategoryId(category.getId());
        a.setBranchId(in.branchId());
        a.setLocation(in.location());
        a.setPurchaseDate(in.inServiceDate().atStartOfDay(ZoneOffset.UTC).toInstant());
        a.setPurchaseCost(cost);
        a.setCapitalizedCost(cost);
        a.setCurrentValue(cost);
        a.setSalvageValue(salvage);
        a.setUsefulLife(life);
        a.setUsefulLifeUnit("months");
        a.setAcquisitionMethod(AcquisitionMethod.CONSTRUCTED);
        a.setStatus(AssetStatus.ACTIVE);
        a.setNotes(in.sourceReference());
        a.setCreatedBy(TenantContext.userId());
        em.persist(a);
        em.flush();
        em.createNativeQuery("UPDATE \"Asset\" SET \"inServiceDate\" = ?1 WHERE id = ?2")
                .setParameter(1, java.sql.Date.valueOf(in.inServiceDate())).setParameter(2, a.getId())
                .executeUpdate();
        em.refresh(a);
        events.publish("fixed_asset.created", Obj.of("id", a.getId(), "organizationId", a.getOrganizationId()));
        Map<String, Object> out = assets.withCategory(a);
        out.put("inServiceDate", in.inServiceDate().toString());
        return out;
    }

    /** True when the asset has at least one posted depreciation entry. */
    @Transactional(readOnly = true)
    public boolean hasPostedDepreciation(String assetId) {
        return em.createQuery("select count(d) from AssetDepreciation d where d.assetId = :id and d.isPosted = true",
                Long.class).setParameter("id", assetId).getSingleResult() > 0;
    }

    /**
     * Undo {@link #createConstructed}: refused once depreciation is posted; unposted depreciation entries are deleted
     * and the asset is soft-deleted.
     */
    @Transactional
    public void removeConstructed(String assetId) {
        Asset a = assets.require(assetId);
        if (hasPostedDepreciation(assetId)) {
            throw ApiException.badRequest("Asset " + a.getAssetCode() + " already has posted depreciation; the"
                    + " capitalisation cannot be reversed");
        }
        em.createQuery("delete from AssetDepreciation d where d.assetId = :id").setParameter("id", assetId)
                .executeUpdate();
        em.createNativeQuery("UPDATE \"Asset\" SET \"deletedAt\" = " + AssetService.NOW + ", \"updatedAt\" = "
                        + AssetService.NOW + " WHERE id = ?1 AND \"organizationId\" = ?2")
                .setParameter(1, assetId).setParameter(2, TenantContext.organizationId()).executeUpdate();
        events.publish("fixed_asset.deleted", Obj.of("id", assetId, "organizationId", TenantContext.organizationId()));
    }

    /** The in-service date of an asset (null for assets created before ADR-S074 or without one). */
    public LocalDate inServiceDate(String assetId) {
        List<?> rows = em.createNativeQuery("SELECT \"inServiceDate\" FROM \"Asset\" WHERE id = ?1")
                .setParameter(1, assetId).getResultList();
        Object v = rows.isEmpty() ? null : rows.getFirst();
        return v == null ? null : LocalDate.parse(v.toString().substring(0, 10));
    }
}
