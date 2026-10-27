package com.inventoryerp.fixedasset.application;

import com.inventoryerp.fixedasset.domain.Asset;
import com.inventoryerp.fixedasset.domain.AssetAcquisition;
import com.inventoryerp.fixedasset.domain.AssetAssignment;
import com.inventoryerp.fixedasset.domain.AssetCategory;
import com.inventoryerp.fixedasset.domain.AssetCheckInOut;
import com.inventoryerp.fixedasset.domain.AssetDepreciation;
import com.inventoryerp.fixedasset.domain.AssetDisposal;
import com.inventoryerp.fixedasset.domain.AssetDocument;
import com.inventoryerp.fixedasset.domain.AssetInspection;
import com.inventoryerp.fixedasset.domain.AssetInsurance;
import com.inventoryerp.fixedasset.domain.AssetMaintenance;
import com.inventoryerp.fixedasset.domain.AssetRepair;
import com.inventoryerp.fixedasset.domain.AssetRevaluation;
import com.inventoryerp.fixedasset.domain.AssetTransfer;
import com.inventoryerp.fixedasset.domain.AssetWarranty;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asset register (port of {@code asset.service.ts}): paged list with filters, search, lookups by code and barcode,
 * the full detail include, create behind the {@code asset_acquisition} approval gate, update, soft delete / restore
 * and the register dashboard.
 */
@Service
public class AssetService {

    /** {@code AssetQueryDto} after transformation. */
    public record AssetQuery(int page, int pageSize, String search, String sortBy, String sortOrder,
            String categoryId, String status, String branchId, String department, String supplierId,
            String location) {}

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Sortable scalar fields (Prisma rejects anything else; here: 400 instead of a 500). */
    private static final Set<String> SORTABLE = Set.copyOf(EntityJson.columnNames(Asset.class));

    private final EntityManager em;
    private final EventBus events;
    private final SequenceService sequence;
    private final ApprovalsService approvals;
    private final TransactionTemplate newTx;

    public AssetService(EntityManager em, EventBus events, SequenceService sequence, ApprovalsService approvals,
            PlatformTransactionManager txm) {
        this.em = em;
        this.events = events;
        this.sequence = sequence;
        this.approvals = approvals;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Nest's approval gate: the request row is committed on its own (Nest runs without a transaction), so it
     * survives the exception the caller throws.
     */
    Map<String, Object> gate(String entityType, String entityId, Map<String, Object> snapshot) {
        return newTx.execute(s -> approvals.checkOrRequestApproval(entityType, entityId, snapshot));
    }

    static boolean needsApproval(Map<String, Object> gate) {
        return gate != null && Boolean.TRUE.equals(gate.get("needsApproval"));
    }

    static Object num(BigDecimal v) {
        return v == null ? null : JsNumbers.js(v);
    }

    /** {@code findFirst({where: {id}})} on the asset (tenant + soft-delete filtered), or null. */
    Asset find(String id) {
        List<Asset> rows = em.createQuery("select a from Asset a where a.id = :id", Asset.class)
                .setParameter("id", id).getResultList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** {@code findFirst({where: {id: assetId}})} or 404 {@code Asset <id> not found}. */
    Asset require(String id) {
        Asset a = find(id);
        if (a == null) {
            throw ApiException.notFound("Asset " + id + " not found");
        }
        return a;
    }

    /** {@code include: {category: true}}. */
    Map<String, Object> withCategory(Asset a) {
        Map<String, Object> m = EntityJson.of(a);
        m.put("category", BaseCrudService.one(em, AssetCategory.class, a.getCategoryId()));
        return m;
    }

    /** {@code include: {asset: {select: {name: true, assetCode: true}}}} on sub-records. */
    List<Map<String, Object>> withAssetBrief(List<?> rows, boolean categoryId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object r : rows) {
            Map<String, Object> m = EntityJson.of(r);
            Asset a = em.find(Asset.class, m.get("assetId"));
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("name", a == null ? null : a.getName());
            brief.put("assetCode", a == null ? null : a.getAssetCode());
            if (categoryId) {
                brief.put("categoryId", a == null ? null : a.getCategoryId());
            }
            m.put("asset", a == null ? null : brief);
            out.add(m);
        }
        return out;
    }

    private <E> List<Map<String, Object>> children(Class<E> type, String assetId, String orderBy, Integer take) {
        TypedQuery<E> q = em.createQuery("select e from " + type.getSimpleName() + " e where e.assetId = :id"
                + (orderBy == null ? "" : " order by e." + orderBy + " desc"), type).setParameter("id", assetId);
        if (take != null) {
            q.setMaxResults(take);
        }
        return EntityJson.list(q.getResultList());
    }

    private void publish(String event, Map<String, Object> payload) {
        events.publish(event, payload);
    }

    // ------------------------------------------------------------------ reads

    /** {@code {data, meta}}, newest first unless {@code sortBy} is given. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(AssetQuery q) {
        if (q.sortBy() != null && !SORTABLE.contains(q.sortBy())) {
            throw ApiException.badRequest("Invalid sortBy field: " + q.sortBy());
        }
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (q.search() != null && !q.search().isEmpty()) {
            where.append(" and (lower(a.name) like :s escape '\\' or lower(a.assetCode) like :s escape '\\'"
                    + " or lower(a.serialNumber) like :s escape '\\' or lower(a.barcode) like :s escape '\\')");
            params.put("s", "%" + BaseCrudService.escapeLike(q.search().toLowerCase()) + "%");
        }
        if (q.categoryId() != null) {
            where.append(" and a.categoryId = :categoryId");
            params.put("categoryId", q.categoryId());
        }
        if (q.status() != null) {
            where.append(" and a.status = :status");
            params.put("status", com.inventoryerp.shared.persistence.DbEnum.fromValue(
                    com.inventoryerp.fixedasset.domain.AssetStatus.class, q.status()));
        }
        if (q.branchId() != null) {
            where.append(" and a.branchId = :branchId");
            params.put("branchId", q.branchId());
        }
        if (q.department() != null) {
            where.append(" and lower(a.department) like :department escape '\\'");
            params.put("department", "%" + BaseCrudService.escapeLike(q.department().toLowerCase()) + "%");
        }
        if (q.supplierId() != null) {
            where.append(" and a.supplierId = :supplierId");
            params.put("supplierId", q.supplierId());
        }
        if (q.location() != null) {
            where.append(" and lower(a.location) like :location escape '\\'");
            params.put("location", "%" + BaseCrudService.escapeLike(q.location().toLowerCase()) + "%");
        }
        String order = q.sortBy() != null
                ? " order by a." + q.sortBy() + ("desc".equals(q.sortOrder()) ? " desc" : " asc")
                : " order by a.createdAt desc";
        TypedQuery<Asset> rows = em.createQuery("select a from Asset a" + where + order, Asset.class);
        TypedQuery<Long> count = em.createQuery("select count(a) from Asset a" + where, Long.class);
        params.forEach((k, v) -> {
            rows.setParameter(k, v);
            count.setParameter(k, v);
        });
        List<Asset> data = rows.setFirstResult((q.page() - 1) * q.pageSize()).setMaxResults(q.pageSize())
                .getResultList();
        return PageResult.of(EntityJson.list(data), q.page(), q.pageSize(), count.getSingleResult());
    }

    /** Asset with category, acquisition and the recent history of every sub-record. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Asset a = require(id);
        Map<String, Object> m = withCategory(a);
        List<Map<String, Object>> acq = children(AssetAcquisition.class, id, null, 1);
        m.put("acquisition", acq.isEmpty() ? null : acq.getFirst());
        m.put("assignments", children(AssetAssignment.class, id, "assignedDate", 5));
        m.put("transfers", children(AssetTransfer.class, id, "transferDate", 5));
        m.put("depreciations", children(AssetDepreciation.class, id, "period", 12));
        m.put("maintenanceLogs", children(AssetMaintenance.class, id, "scheduledDate", 5));
        m.put("repairs", children(AssetRepair.class, id, "breakdownDate", 5));
        m.put("warranties", children(AssetWarranty.class, id, "warrantyEnd", null));
        m.put("insurance", children(AssetInsurance.class, id, "renewalDate", 5));
        m.put("inspections", children(AssetInspection.class, id, "inspectionDate", 5));
        m.put("disposals", children(AssetDisposal.class, id, null, null));
        m.put("revaluations", children(AssetRevaluation.class, id, "revaluationDate", 5));
        m.put("checkInOuts", children(AssetCheckInOut.class, id, "checkoutDate", 5));
        m.put("documents", children(AssetDocument.class, id, "uploadedAt", 10));
        return m;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findByCode(String code) {
        List<Asset> rows = em.createQuery("select a from Asset a where a.assetCode = :c", Asset.class)
                .setParameter("c", code).setMaxResults(1).getResultList();
        if (rows.isEmpty()) {
            throw ApiException.notFound("Asset " + code + " not found");
        }
        return withCategory(rows.getFirst());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findByBarcode(String barcode) {
        List<Asset> rows = em.createQuery("select a from Asset a where a.barcode = :b", Asset.class)
                .setParameter("b", barcode).setMaxResults(1).getResultList();
        if (rows.isEmpty()) {
            throw ApiException.notFound("Asset with barcode " + barcode + " not found");
        }
        return withCategory(rows.getFirst());
    }

    /** Up to 20 assets matching name / code / serial / barcode (case-insensitive), name asc, with category. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String q) {
        String like = "%" + BaseCrudService.escapeLike(q == null ? "" : q.toLowerCase()) + "%";
        List<Asset> rows = em.createQuery("select a from Asset a where lower(a.name) like :s escape '\\'"
                        + " or lower(a.assetCode) like :s escape '\\' or lower(a.serialNumber) like :s escape '\\'"
                        + " or lower(a.barcode) like :s escape '\\' order by a.name asc", Asset.class)
                .setParameter("s", like).setMaxResults(20).getResultList();
        List<Map<String, Object>> out = new ArrayList<>();
        rows.forEach(a -> out.add(withCategory(a)));
        return out;
    }

    // ------------------------------------------------------------------ writes

    /** Create (code from the {@code asset} sequence when absent); {@code currentValue} defaults to the cost. */
    @Transactional
    public Map<String, Object> create(Record dto) {
        Map<String, Object> data = EntityPatch.data(dto);
        String assetCode = data.get("assetCode") != null ? (String) data.get("assetCode")
                : sequence.next("asset", "AST-", null);
        Map<String, Object> gate = gate("asset_acquisition", "new-" + assetCode, Obj.of(
                "code", assetCode,
                "name", data.get("name"),
                "purchaseCost", data.get("purchaseCost") == null ? null : num((BigDecimal) data.get("purchaseCost")),
                "categoryId", data.get("categoryId"),
                "acquisitionMethod", data.get("acquisitionMethod")));
        if (needsApproval(gate)) {
            throw ApiException.forbidden("Asset acquisition requires approval. Request ID: " + gate.get("requestId")
                    + ".");
        }
        Asset a = new Asset();
        EntityPatch.apply(a, dto);
        a.setAssetCode(assetCode);
        if (data.get("purchaseCost") != null && data.get("currentValue") == null) {
            a.setCurrentValue((BigDecimal) data.get("purchaseCost"));
        }
        em.persist(a);
        em.flush();
        em.refresh(a);
        publish("fixed_asset.created", Obj.of("id", a.getId(), "organizationId", a.getOrganizationId()));
        return withCategory(a);
    }

    @Transactional
    public Map<String, Object> update(String id, Record dto) {
        Asset a = require(id);
        EntityPatch.apply(a, dto);
        em.flush();
        em.refresh(a);
        publish("fixed_asset.updated", Obj.of("id", a.getId(), "organizationId", a.getOrganizationId()));
        return withCategory(a);
    }

    /** Soft delete ({@code updateMany} under the tenant + live-row filter). */
    @Transactional
    public void remove(String id) {
        int res = em.createNativeQuery("UPDATE \"Asset\" SET \"deletedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                        + " WHERE id = ?1 AND \"organizationId\" = ?2 AND \"deletedAt\" IS NULL")
                .setParameter(1, id).setParameter(2, TenantContext.organizationId()).executeUpdate();
        if (res == 0) {
            throw ApiException.notFound("Asset " + id + " not found");
        }
        publish("fixed_asset.deleted", Obj.of("id", id, "organizationId", ""));
    }

    /**
     * Nest's restore: {@code updateMany({where: {id}, data: {deletedAt: null}})} — the tenancy extension adds
     * {@code deletedAt: null} to the where, so only a live asset matches (a deleted one answers 404).
     */
    @Transactional
    public Map<String, Object> restore(String id) {
        int res = em.createNativeQuery("UPDATE \"Asset\" SET \"deletedAt\" = NULL, \"updatedAt\" = " + NOW
                        + " WHERE id = ?1 AND \"organizationId\" = ?2 AND \"deletedAt\" IS NULL")
                .setParameter(1, id).setParameter(2, TenantContext.organizationId()).executeUpdate();
        if (res == 0) {
            throw ApiException.notFound("Asset " + id + " not found");
        }
        em.clear();
        return findOne(id);
    }

    /** Register counters, total current value, ten newest assets and warranties ending within 90 days. */
    @Transactional(readOnly = true)
    public Map<String, Object> getDashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalAssets", count(null));
        out.put("activeAssets", count("active"));
        out.put("maintenanceAssets", count("under_maintenance"));
        out.put("totalValue", sumCurrentValue());
        out.put("disposalCount", count("disposed"));
        List<Asset> recent = em.createQuery("select a from Asset a order by a.createdAt desc", Asset.class)
                .setMaxResults(10).getResultList();
        List<Map<String, Object>> recentOut = new ArrayList<>();
        recent.forEach(a -> recentOut.add(withCategory(a)));
        out.put("recentAssets", recentOut);
        Instant now = Instant.now();
        List<AssetWarranty> expiring = em.createQuery("select w from AssetWarranty w where w.warrantyEnd >= :now"
                        + " and w.warrantyEnd <= :to order by w.warrantyEnd asc", AssetWarranty.class)
                .setParameter("now", now).setParameter("to", now.plusSeconds(90L * 86400))
                .setMaxResults(10).getResultList();
        out.put("warrantyExpiring", withAssetBrief(expiring, false));
        return out;
    }

    long count(String status) {
        String jpql = "select count(a) from Asset a" + (status == null ? "" : " where a.status = :st");
        TypedQuery<Long> q = em.createQuery(jpql, Long.class);
        if (status != null) {
            q.setParameter("st", com.inventoryerp.shared.persistence.DbEnum.fromValue(
                    com.inventoryerp.fixedasset.domain.AssetStatus.class, status));
        }
        return q.getSingleResult();
    }

    /** {@code _sum.currentValue ?? 0}: a Decimal (wire string) or the number 0 when every value is null. */
    Object sumCurrentValue() {
        BigDecimal sum = em.createQuery("select sum(a.currentValue) from Asset a", BigDecimal.class)
                .getSingleResult();
        return sum == null ? (Object) 0 : sum;
    }

    BigDecimal sumPurchaseCost() {
        return em.createQuery("select sum(a.purchaseCost) from Asset a", BigDecimal.class).getSingleResult();
    }
}
