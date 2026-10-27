package com.inventoryerp.fixedasset.application;

import com.inventoryerp.fixedasset.domain.Asset;
import com.inventoryerp.fixedasset.domain.AssetCategory;
import com.inventoryerp.fixedasset.domain.AssetDepreciation;
import com.inventoryerp.fixedasset.domain.AssetMaintenance;
import com.inventoryerp.fixedasset.domain.AssetWarranty;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@code /fixed-assets-dashboard} (port of {@code asset-dashboard.service.ts}): summary, upcoming work, reports. */
@Service
public class AssetDashboardService {

    private final EntityManager em;
    private final AssetService assets;

    public AssetDashboardService(EntityManager em, AssetService assets) {
        this.em = em;
        this.assets = assets;
    }

    /** Live categories with {@code _count.assets} (Prisma relation counts ignore soft delete). */
    private List<Map<String, Object>> categoriesWithCount() {
        List<AssetCategory> cats = em.createQuery("select c from AssetCategory c", AssetCategory.class)
                .getResultList();
        Map<Object, Long> counts = BaseCrudService.countBy(em, "Asset", "categoryId", BaseCrudService.ids(cats));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AssetCategory c : cats) {
            Map<String, Object> m = EntityJson.of(c);
            Map<String, Object> count = new LinkedHashMap<>();
            count.put("assets", counts.getOrDefault(c.getId(), 0L));
            m.put("_count", count);
            out.add(m);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalAssets", assets.count(null));
        out.put("activeAssets", assets.count("active"));
        out.put("maintenanceAssets", assets.count("under_maintenance"));
        out.put("disposedAssets", assets.count("disposed"));
        out.put("totalValue", assets.sumCurrentValue());
        BigDecimal cost = assets.sumPurchaseCost();
        out.put("totalPurchaseCost", cost == null ? (Object) 0 : cost);
        out.put("byCategory", categoriesWithCount());
        return out;
    }

    /** Maintenance due in 30 days, warranties ending in 90, ten newest assets and ten latest posted runs. */
    @Transactional(readOnly = true)
    public Map<String, Object> getUpcoming() {
        Instant now = Instant.now();
        List<AssetMaintenance> due = em.createQuery("select m from AssetMaintenance m where m.nextMaintenanceDate"
                        + " <= :to and m.nextMaintenanceDate >= :now and m.status <> 'completed'"
                        + " order by m.nextMaintenanceDate asc", AssetMaintenance.class)
                .setParameter("to", now.plusSeconds(30L * 86400)).setParameter("now", now)
                .setMaxResults(10).getResultList();
        List<AssetWarranty> warranties = em.createQuery("select w from AssetWarranty w where w.warrantyEnd <= :to"
                        + " and w.warrantyEnd >= :now order by w.warrantyEnd asc", AssetWarranty.class)
                .setParameter("to", now.plusSeconds(90L * 86400)).setParameter("now", now)
                .setMaxResults(10).getResultList();
        List<Asset> recent = em.createQuery("select a from Asset a order by a.createdAt desc", Asset.class)
                .setMaxResults(10).getResultList();
        List<AssetDepreciation> posted = em.createQuery("select d from AssetDepreciation d where d.isPosted = true"
                + " order by d.createdAt desc", AssetDepreciation.class).setMaxResults(10).getResultList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maintenanceDue", assets.withAssetBrief(due, false));
        out.put("warrantyExpiring", assets.withAssetBrief(warranties, false));
        List<Map<String, Object>> recentOut = new ArrayList<>();
        recent.forEach(a -> recentOut.add(assets.withCategory(a)));
        out.put("recentAssets", recentOut);
        out.put("recentDepreciation", assets.withAssetBrief(posted, false));
        return out;
    }

    /** {@code by-location}, {@code by-department}, {@code by-status}, {@code by-category}, {@code age-analysis}. */
    @Transactional(readOnly = true)
    public Object getReports(String type) {
        switch (type) {
            case "by-location":
                return group("location");
            case "by-department":
                return group("department");
            case "by-status": {
                Map<String, Object> grouped = new LinkedHashMap<>();
                for (Asset a : em.createQuery("select a from Asset a", Asset.class).getResultList()) {
                    grouped.merge(a.getStatus().value(), 1L, (x, y) -> (Long) x + 1);
                }
                return grouped;
            }
            case "by-category": {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> c : categoriesWithCount()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", c.get("name"));
                    m.put("count", ((Map<?, ?>) c.get("_count")).get("assets"));
                    out.add(m);
                }
                return out;
            }
            case "age-analysis": {
                ZonedDateTime now = ZonedDateTime.now(BusinessZone.get());
                List<Map<String, Object>> out = new ArrayList<>();
                for (Asset a : em.createQuery("select a from Asset a", Asset.class).getResultList()) {
                    int ageMonths = 0;
                    if (a.getPurchaseDate() != null) {
                        ZonedDateTime p = a.getPurchaseDate().atZone(BusinessZone.get());
                        ageMonths = (now.getYear() - p.getYear()) * 12 + (now.getMonthValue() - p.getMonthValue());
                    }
                    int life = a.getUsefulLife() != null ? a.getUsefulLife() : 60;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", a.getName());
                    m.put("ageMonths", ageMonths);
                    m.put("remainingMonths", Math.max(0, life - ageMonths));
                    m.put("totalLife", life);
                    m.put("purchasedDate", a.getPurchaseDate());
                    out.add(m);
                }
                return out;
            }
            default:
                return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> group(String field) {
        Map<String, Object> grouped = new LinkedHashMap<>();
        for (Asset a : em.createQuery("select a from Asset a", Asset.class).getResultList()) {
            String key = "location".equals(field) ? a.getLocation() : a.getDepartment();
            grouped.merge(key == null ? "Unspecified" : key, 1L, (x, y) -> (Long) x + 1);
        }
        return grouped;
    }
}
