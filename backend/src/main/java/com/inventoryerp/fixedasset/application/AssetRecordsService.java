package com.inventoryerp.fixedasset.application;

import com.inventoryerp.fixedasset.domain.Asset;
import com.inventoryerp.fixedasset.domain.AssetAcquisition;
import com.inventoryerp.fixedasset.domain.AssetAssignment;
import com.inventoryerp.fixedasset.domain.AssetCheckInOut;
import com.inventoryerp.fixedasset.domain.AssetDisposal;
import com.inventoryerp.fixedasset.domain.AssetInspection;
import com.inventoryerp.fixedasset.domain.AssetInsurance;
import com.inventoryerp.fixedasset.domain.AssetMaintenance;
import com.inventoryerp.fixedasset.domain.AssetRepair;
import com.inventoryerp.fixedasset.domain.AssetRevaluation;
import com.inventoryerp.fixedasset.domain.AssetStatus;
import com.inventoryerp.fixedasset.domain.AssetTransfer;
import com.inventoryerp.fixedasset.domain.AssetWarranty;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-asset sub-records (ports of the acquisition, assignment, check-in/out, inspection, insurance, maintenance,
 * repair, warranty, revaluation, transfer and disposal services). Creates spread the DTO onto the row with
 * {@code assetId}; untyped {@code PATCH} bodies ({@code Partial<CreateXDto>}, never validated by Nest) are applied
 * as given. Lists are not asset-checked, exactly as in Nest.
 */
@Service
public class AssetRecordsService {

    private final EntityManager em;
    private final EventBus events;
    private final AssetService assets;
    private final AssetLedgerService ledger;

    public AssetRecordsService(EntityManager em, EventBus events, AssetService assets, AssetLedgerService ledger) {
        this.em = em;
        this.events = events;
        this.assets = assets;
        this.ledger = ledger;
    }

    private <E> List<Map<String, Object>> byAsset(Class<E> type, String assetId, String orderBy) {
        return EntityJson.list(em.createQuery("select e from " + type.getSimpleName() + " e where e.assetId = :id"
                + " order by e." + orderBy + " desc", type).setParameter("id", assetId).getResultList());
    }

    private <E> E one(Class<E> type, String id) {
        List<E> rows = em.createQuery("select e from " + type.getSimpleName() + " e where e.id = :id", type)
                .setParameter("id", id).getResultList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private <E> E insert(E row, String assetId, Record dto) {
        EntityPatch.apply(row, dto);
        EntityPatch.set(row, "assetId", assetId);
        em.persist(row);
        em.flush();
        em.refresh(row);
        return row;
    }

    private <E> E patch(E row, Map<String, Object> body) {
        EntityPatch.apply(row, body);
        em.flush();
        em.refresh(row);
        return row;
    }

    private static Instant dateOrNow(String raw) {
        return raw == null || raw.isEmpty() ? Instant.now() : EntityPatch.toInstant(raw);
    }

    // ------------------------------------------------------------------ acquisition (1:1)

    @Transactional(readOnly = true)
    public Map<String, Object> findAcquisition(String assetId) {
        AssetAcquisition acq = acquisition(assetId);
        if (acq == null) {
            throw ApiException.notFound("Acquisition for asset " + assetId + " not found");
        }
        return EntityJson.of(acq);
    }

    private AssetAcquisition acquisition(String assetId) {
        List<AssetAcquisition> rows = em.createQuery("select a from AssetAcquisition a where a.assetId = :id",
                AssetAcquisition.class).setParameter("id", assetId).getResultList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Upsert; creating the first acquisition record passes the {@code asset_acquisition} approval gate. */
    @Transactional
    public Map<String, Object> upsertAcquisition(String assetId, Record dto) {
        Asset asset = assets.require(assetId);
        AssetAcquisition existing = acquisition(assetId);
        if (existing == null) {
            Map<String, Object> data = EntityPatch.data(dto);
            Map<String, Object> gate = assets.gate("asset_acquisition", assetId, Obj.of(
                    "assetCode", asset.getAssetCode(),
                    "assetName", asset.getName(),
                    "vendor", data.get("vendor"),
                    "invoiceId", data.get("invoiceId"),
                    "purchaseOrderId", data.get("purchaseOrderId"),
                    "capitalizedCost", AssetService.num((BigDecimal) data.get("capitalizedCost"))));
            if (AssetService.needsApproval(gate)) {
                throw ApiException.forbidden("Asset acquisition details require approval. Request ID: "
                        + gate.get("requestId") + ".");
            }
            AssetAcquisition row = insert(new AssetAcquisition(), assetId, dto);
            // ACC-5 (B11): the acquisition capitalises the asset in the GL (once; retries replay the posting key).
            ledger.capitalise(asset, row, row.getAcquisitionDate() != null ? row.getAcquisitionDate() : Instant.now());
            return EntityJson.of(row);
        }
        EntityPatch.apply(existing, dto);
        em.flush();
        em.refresh(existing);
        return EntityJson.of(existing);
    }

    @Transactional
    public void removeAcquisition(String assetId) {
        ledger.reverseCapitalisation(assetId);
        em.createQuery("delete from AssetAcquisition a where a.assetId = :id").setParameter("id", assetId)
                .executeUpdate();
    }

    // ------------------------------------------------------------------ assignments

    @Transactional(readOnly = true)
    public List<Map<String, Object>> assignments(String assetId) {
        return byAsset(AssetAssignment.class, assetId, "assignedDate");
    }

    /** Record the assignment and stamp the holder on the asset; publishes {@code fixed_asset.assigned}. */
    @Transactional
    public Map<String, Object> assign(String assetId, Record dto) {
        Asset asset = assets.require(assetId);
        Map<String, Object> data = EntityPatch.data(dto);
        AssetAssignment row = insert(new AssetAssignment(), assetId, dto);
        if (data.containsKey("assignedToId")) {
            asset.setAssignedToId((String) data.get("assignedToId"));
        }
        if (data.containsKey("assignedToType")) {
            EntityPatch.set(asset, "assignedToType", data.get("assignedToType"));
        }
        asset.setAssignmentDate(data.get("assignedDate") != null ? row.getAssignedDate() : Instant.now());
        em.flush();
        events.publish("fixed_asset.assigned", Obj.of(
                "organizationId", asset.getOrganizationId(),
                "assetId", assetId,
                "assignedToId", data.get("assignedToId") == null ? "" : data.get("assignedToId"),
                "assignedToType", data.get("assignedToType") == null ? "" : data.get("assignedToType")));
        return EntityJson.of(row);
    }

    @Transactional
    public Map<String, Object> returnAssignment(String id, String returnDate, String condition) {
        AssetAssignment row = one(AssetAssignment.class, id);
        if (row == null) {
            throw ApiException.notFound("Assignment " + id + " not found");
        }
        row.setReturnedDate(dateOrNow(returnDate));
        if (condition != null) {
            row.setConditionAfter(condition);
        }
        em.flush();
        em.refresh(row);
        return EntityJson.of(row);
    }

    // ------------------------------------------------------------------ check-in / out

    @Transactional(readOnly = true)
    public List<Map<String, Object>> checkInOuts(String assetId) {
        return byAsset(AssetCheckInOut.class, assetId, "checkoutDate");
    }

    @Transactional
    public Map<String, Object> checkOut(String assetId, Record dto) {
        assets.require(assetId);
        return EntityJson.of(insert(new AssetCheckInOut(), assetId, dto));
    }

    @Transactional
    public Map<String, Object> checkIn(String id, String returnDate, String condition) {
        AssetCheckInOut row = one(AssetCheckInOut.class, id);
        if (row == null) {
            throw ApiException.notFound("CheckInOut " + id + " not found");
        }
        row.setReturnDate(dateOrNow(returnDate));
        if (condition != null) {
            row.setConditionAfter(condition);
        }
        row.setStatus("returned");
        em.flush();
        em.refresh(row);
        return EntityJson.of(row);
    }

    // ------------------------------------------------------------------ inspections

    @Transactional(readOnly = true)
    public List<Map<String, Object>> inspections(String assetId) {
        return byAsset(AssetInspection.class, assetId, "inspectionDate");
    }

    @Transactional
    public Map<String, Object> inspect(String assetId, Record dto) {
        assets.require(assetId);
        return EntityJson.of(insert(new AssetInspection(), assetId, dto));
    }

    // ------------------------------------------------------------------ insurance

    @Transactional(readOnly = true)
    public List<Map<String, Object>> insurance(String assetId) {
        return byAsset(AssetInsurance.class, assetId, "renewalDate");
    }

    @Transactional
    public Map<String, Object> insure(String assetId, Record dto) {
        assets.require(assetId);
        return EntityJson.of(insert(new AssetInsurance(), assetId, dto));
    }

    @Transactional
    public Map<String, Object> updateInsurance(String id, Map<String, Object> body) {
        AssetInsurance row = one(AssetInsurance.class, id);
        if (row == null) {
            throw ApiException.notFound("Insurance " + id + " not found");
        }
        return EntityJson.of(patch(row, body));
    }

    // ------------------------------------------------------------------ maintenance

    @Transactional(readOnly = true)
    public List<Map<String, Object>> maintenance(String assetId) {
        return byAsset(AssetMaintenance.class, assetId, "scheduledDate");
    }

    /** A scheduled or in-progress job puts the asset {@code under_maintenance}. */
    @Transactional
    public Map<String, Object> scheduleMaintenance(String assetId, Record dto) {
        Asset asset = assets.require(assetId);
        AssetMaintenance row = insert(new AssetMaintenance(), assetId, dto);
        if ("in_progress".equals(row.getStatus()) || "scheduled".equals(row.getStatus())) {
            asset.setStatus(AssetStatus.UNDER_MAINTENANCE);
            em.flush();
        }
        return EntityJson.of(row);
    }

    /** Completing the job returns the asset to {@code active}. */
    @Transactional
    public Map<String, Object> updateMaintenance(String id, Map<String, Object> body) {
        AssetMaintenance row = one(AssetMaintenance.class, id);
        if (row == null) {
            throw ApiException.notFound("Maintenance " + id + " not found");
        }
        patch(row, body);
        if ("completed".equals(row.getStatus())) {
            setStatus(row.getAssetId(), AssetStatus.ACTIVE);
        }
        return EntityJson.of(row);
    }

    /** Open jobs whose next maintenance falls within 30 days (or is overdue), soonest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dueMaintenance() {
        List<AssetMaintenance> rows = em.createQuery("select m from AssetMaintenance m where m.nextMaintenanceDate"
                        + " <= :to and m.status <> 'completed' order by m.nextMaintenanceDate asc", AssetMaintenance.class)
                .setParameter("to", Instant.now().plusSeconds(30L * 86400)).getResultList();
        return assets.withAssetBrief(rows, false);
    }

    /** Prisma {@code asset.update({where: {id}})}: tenant + live-row scoped. */
    private void setStatus(String assetId, AssetStatus status) {
        Asset a = assets.find(assetId);
        if (a == null) {
            throw ApiException.notFound("Asset " + assetId + " not found");
        }
        a.setStatus(status);
        em.flush();
    }

    // ------------------------------------------------------------------ repairs

    @Transactional(readOnly = true)
    public List<Map<String, Object>> repairs(String assetId) {
        return byAsset(AssetRepair.class, assetId, "breakdownDate");
    }

    /** A pending or in-progress repair puts the asset {@code in_repair}. */
    @Transactional
    public Map<String, Object> logRepair(String assetId, Record dto) {
        Asset asset = assets.require(assetId);
        AssetRepair row = insert(new AssetRepair(), assetId, dto);
        if ("pending".equals(row.getStatus()) || "in_progress".equals(row.getStatus())) {
            asset.setStatus(AssetStatus.IN_REPAIR);
            em.flush();
        }
        return EntityJson.of(row);
    }

    @Transactional
    public Map<String, Object> updateRepair(String id, Map<String, Object> body) {
        AssetRepair row = one(AssetRepair.class, id);
        if (row == null) {
            throw ApiException.notFound("Repair " + id + " not found");
        }
        patch(row, body);
        if ("completed".equals(row.getStatus())) {
            setStatus(row.getAssetId(), AssetStatus.ACTIVE);
        }
        return EntityJson.of(row);
    }

    // ------------------------------------------------------------------ warranties

    @Transactional(readOnly = true)
    public List<Map<String, Object>> warranties(String assetId) {
        return byAsset(AssetWarranty.class, assetId, "warrantyEnd");
    }

    @Transactional
    public Map<String, Object> addWarranty(String assetId, Record dto) {
        assets.require(assetId);
        return EntityJson.of(insert(new AssetWarranty(), assetId, dto));
    }

    @Transactional
    public Map<String, Object> updateWarranty(String id, Map<String, Object> body) {
        AssetWarranty row = one(AssetWarranty.class, id);
        if (row == null) {
            throw ApiException.notFound("Warranty " + id + " not found");
        }
        return EntityJson.of(patch(row, body));
    }

    /** Warranties ending between now and {@code days} ahead, soonest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> expiringWarranties(int days) {
        Instant now = Instant.now();
        List<AssetWarranty> rows = em.createQuery("select w from AssetWarranty w where w.warrantyEnd >= :now"
                        + " and w.warrantyEnd <= :to order by w.warrantyEnd asc", AssetWarranty.class)
                .setParameter("now", now).setParameter("to", now.plusMillis(days * 86_400_000L)).getResultList();
        return assets.withAssetBrief(rows, false);
    }

    // ------------------------------------------------------------------ revaluations

    @Transactional(readOnly = true)
    public List<Map<String, Object>> revaluations(String assetId) {
        return byAsset(AssetRevaluation.class, assetId, "revaluationDate");
    }

    /** Behind the {@code asset_revaluation} gate; sets the asset's current value to {@code newValue}. */
    @Transactional
    public Map<String, Object> revalue(String assetId, Record dto) {
        Asset asset = assets.require(assetId);
        Map<String, Object> data = EntityPatch.data(dto);
        Map<String, Object> gate = assets.gate("asset_revaluation", assetId, Obj.of(
                "assetCode", asset.getAssetCode(),
                "assetName", asset.getName(),
                "previousValue", AssetService.num(orZero(asset.getCurrentValue())),
                "newValue", AssetService.num((BigDecimal) data.get("newValue")),
                "reason", data.get("reason")));
        if (AssetService.needsApproval(gate)) {
            throw ApiException.forbidden("Asset revaluation requires approval. Request ID: " + gate.get("requestId")
                    + ".");
        }
        BigDecimal previous = orZero(asset.getCurrentValue());
        AssetRevaluation row = insert(new AssetRevaluation(), assetId, dto);
        asset.setCurrentValue((BigDecimal) data.get("newValue"));
        em.flush();
        // ACC-5 (B11): the change in carrying amount reaches the GL (surplus up / surplus then loss down).
        ledger.revalue(asset, row.getId(), previous, (BigDecimal) data.get("newValue"),
                row.getRevaluationDate() != null ? row.getRevaluationDate() : Instant.now());
        return EntityJson.of(row);
    }

    // ------------------------------------------------------------------ transfers

    @Transactional(readOnly = true)
    public List<Map<String, Object>> transfers(String assetId) {
        return byAsset(AssetTransfer.class, assetId, "transferDate");
    }

    /** Behind the {@code asset_transfer} gate; moves location / branch / department / holder on the asset. */
    @Transactional
    public Map<String, Object> transfer(String assetId, Record dto) {
        Asset asset = assets.require(assetId);
        Map<String, Object> data = EntityPatch.data(dto);
        Map<String, Object> gate = assets.gate("asset_transfer", assetId, Obj.of(
                "assetCode", asset.getAssetCode(),
                "assetName", asset.getName(),
                "fromLocation", data.get("fromLocation"),
                "toLocation", data.get("toLocation"),
                "toBranchId", data.get("toBranchId"),
                "reason", data.get("reason")));
        if (AssetService.needsApproval(gate)) {
            throw ApiException.forbidden("Asset transfer requires approval. Request ID: " + gate.get("requestId")
                    + ".");
        }
        AssetTransfer row = insert(new AssetTransfer(), assetId, dto);
        if (truthy(data.get("toLocation"))) {
            asset.setLocation((String) data.get("toLocation"));
        }
        if (truthy(data.get("toBranchId"))) {
            asset.setBranchId((String) data.get("toBranchId"));
        }
        if (truthy(data.get("toDepartment"))) {
            asset.setDepartment((String) data.get("toDepartment"));
        }
        if (truthy(data.get("toPersonId"))) {
            asset.setAssignedToId((String) data.get("toPersonId"));
        }
        em.flush();
        events.publish("fixed_asset.transferred", Obj.of(
                "organizationId", asset.getOrganizationId(),
                "assetId", assetId,
                "fromLocation", data.get("fromLocation") == null ? "" : data.get("fromLocation"),
                "toLocation", data.get("toLocation") == null ? "" : data.get("toLocation")));
        return EntityJson.of(row);
    }

    // ------------------------------------------------------------------ disposal (1:1)

    /** The disposal record or {@code null} (Nest returns an empty 200 body). */
    @Transactional(readOnly = true)
    public Map<String, Object> disposal(String assetId) {
        List<AssetDisposal> rows = em.createQuery("select d from AssetDisposal d where d.assetId = :id",
                AssetDisposal.class).setParameter("id", assetId).getResultList();
        return rows.isEmpty() ? null : EntityJson.of(rows.getFirst());
    }

    /**
     * Behind the {@code asset_disposal} gate: records book value at disposal and {@code gainLoss = disposalValue -
     * currentValue}, then marks the asset {@code disposed} with a zero current value.
     */
    @Transactional
    public Map<String, Object> dispose(String assetId, Record dto) {
        Asset asset = assets.require(assetId);
        if (disposal(assetId) != null) {
            throw ApiException.notFound("Asset " + assetId + " already disposed");
        }
        Map<String, Object> data = EntityPatch.data(dto);
        BigDecimal book = orZero(asset.getCurrentValue());
        BigDecimal value = (BigDecimal) data.get("disposalValue");
        Map<String, Object> gate = assets.gate("asset_disposal", assetId, Obj.of(
                "assetCode", asset.getAssetCode(),
                "assetName", asset.getName(),
                "currentValue", AssetService.num(book),
                "disposalValue", AssetService.num(value),
                "disposalMethod", data.get("disposalMethod"),
                "reason", data.get("reason")));
        if (AssetService.needsApproval(gate)) {
            throw ApiException.forbidden("Asset disposal requires approval. Request ID: " + gate.get("requestId")
                    + ". Please have an authorized person approve it via the approvals endpoint.");
        }
        AssetDisposal row = new AssetDisposal();
        EntityPatch.apply(row, dto);
        row.setAssetId(assetId);
        row.setBookValueAtDisposal(book);
        if (value != null) {
            row.setGainLoss(value.subtract(book));
        }
        em.persist(row);
        em.flush();
        em.refresh(row);
        // ACC-5 (B11): derecognise the asset in the GL with the gain / loss on disposal.
        ledger.dispose(asset, acquisition(assetId), row.getId(), value, null,
                row.getDisposalDate() != null ? row.getDisposalDate() : Instant.now());
        asset.setStatus(AssetStatus.DISPOSED);
        asset.setCurrentValue(BigDecimal.ZERO);
        em.flush();
        events.publish("fixed_asset.disposed", Obj.of(
                "organizationId", asset.getOrganizationId(),
                "assetId", assetId,
                "method", data.get("disposalMethod"),
                "value", value == null ? "0" : JsNumbers.toJsString(value.doubleValue())));
        return EntityJson.of(row);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean truthy(Object v) {
        return v != null && !(v instanceof String s && s.isEmpty());
    }
}
