package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.LicensePeriod;
import com.inventoryerp.license.domain.Plan;
import com.inventoryerp.license.domain.PlanRepository;
import com.inventoryerp.license.domain.ProductRepository;
import com.inventoryerp.license.shared.ApiException;
import com.inventoryerp.license.shared.Json;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The price list.
 *
 * <p>A plan's period shape is immutable once licences exist against it: changing MONTH to YEAR would
 * silently re-date every term it issued. Everything else — price, caps, features, grace — is
 * editable, and the change reaches licences that never overrode that field (see {@link Entitlements}).
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final PlanRepository plans;
    private final ProductRepository products;

    public PlanService(PlanRepository plans, ProductRepository products) {
        this.plans = plans;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<Plan> list(boolean activeOnly) {
        return activeOnly ? plans.findByIsActiveTrueOrderBySortOrderAscNameAsc()
                : plans.findAllByOrderBySortOrderAscNameAsc();
    }

    @Transactional(readOnly = true)
    public Plan get(String id) {
        return plans.findById(id).orElseThrow(() -> ApiException.notFound("No plan with id '" + id + "'"));
    }

    @Transactional
    public Plan create(String code, String name, String productCode, String period, Integer periodCount,
            Long priceMinor, String currency, Integer maxDevices, Integer maxUsers, Integer maxOrganizations,
            Integer instanceLimit, Set<String> features, Integer graceDays, Boolean isTrial, Integer sortOrder) {
        if (plans.findByCode(code).isPresent()) {
            throw ApiException.conflict("A plan with code '" + code + "' already exists.");
        }
        String product = productCode == null || productCode.isBlank() ? "erp" : productCode;
        if (products.findById(product).isEmpty()) {
            throw ApiException.badRequest("No product with code '" + product + "'");
        }
        LicensePeriod periodType = LicensePeriod.of(period);

        Plan plan = new Plan();
        plan.setCode(code.trim());
        plan.setName(name);
        plan.setProductCode(product);
        plan.setPeriod(periodType.name());
        plan.setPeriodCount(periodCount == null ? 1 : Math.max(1, periodCount));
        plan.setPriceMinor(priceMinor == null ? 0L : Math.max(0L, priceMinor));
        plan.setCurrency(currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase(
                java.util.Locale.ROOT));
        plan.setMaxDevices(maxDevices);
        plan.setMaxUsers(maxUsers);
        plan.setMaxOrganizations(maxOrganizations);
        plan.setInstanceLimit(instanceLimit == null ? 1 : Math.max(1, instanceLimit));
        plan.setFeatures(Json.writeStringCollection(features == null ? Set.of() : features));
        plan.setGraceDays(graceDays == null ? periodType.defaultGraceDays() : Math.max(0, graceDays));
        plan.setIsTrial(isTrial != null ? isTrial : periodType == LicensePeriod.TRIAL);
        plan.setIsActive(true);
        plan.setSortOrder(sortOrder == null ? 0 : sortOrder);
        warnOnUnknownFeatures(plan);
        return plans.saveAndFlush(plan);
    }

    @Transactional
    public Plan update(String id, String name, Long priceMinor, String currency, Integer maxDevices,
            Integer maxUsers, Integer maxOrganizations, Integer instanceLimit, Set<String> features,
            boolean featuresGiven, Integer graceDays, Boolean isActive, Integer sortOrder, Integer periodCount) {
        Plan plan = get(id);
        if (name != null) {
            plan.setName(name);
        }
        if (priceMinor != null) {
            plan.setPriceMinor(Math.max(0L, priceMinor));
        }
        if (currency != null && !currency.isBlank()) {
            plan.setCurrency(currency.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (maxDevices != null) {
            plan.setMaxDevices(maxDevices);
        }
        if (maxUsers != null) {
            plan.setMaxUsers(maxUsers);
        }
        if (maxOrganizations != null) {
            plan.setMaxOrganizations(maxOrganizations);
        }
        if (instanceLimit != null) {
            plan.setInstanceLimit(Math.max(1, instanceLimit));
        }
        if (featuresGiven) {
            plan.setFeatures(Json.writeStringCollection(features == null ? Set.of() : features));
            warnOnUnknownFeatures(plan);
        }
        if (graceDays != null) {
            plan.setGraceDays(Math.max(0, graceDays));
        }
        if (isActive != null) {
            // Deactivating only takes the plan off the price list; licences on it keep running.
            plan.setIsActive(isActive);
        }
        if (sortOrder != null) {
            plan.setSortOrder(sortOrder);
        }
        if (periodCount != null) {
            // Affects terms issued from now on, never one already sold.
            plan.setPeriodCount(Math.max(1, periodCount));
        }
        return plans.saveAndFlush(plan);
    }

    private void warnOnUnknownFeatures(Plan plan) {
        Set<String> unknown = Json.readStringSet(plan.getFeatures()).stream()
                .filter(f -> !Entitlements.KNOWN_FEATURES.contains(f))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (!unknown.isEmpty()) {
            log.warn("Plan {} lists feature keys no ERP release understands: {}", plan.getCode(), unknown);
        }
    }
}
