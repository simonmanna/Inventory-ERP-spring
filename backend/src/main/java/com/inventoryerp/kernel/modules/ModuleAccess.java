package com.inventoryerp.kernel.modules;

import com.inventoryerp.kernel.featureflags.FeatureFlagsService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Whether a module is on for an organization: the one answer the module guard ({@link RequiresModule}), the Modules
 * routes and an add-on's own gates share (ADR-S086). The rules are the Nest {@code ModuleEnabledGuard}'s:
 * <ul>
 *   <li>a tenant with no {@code OrganizationModule} rows at all is allowed everything (never set up);</li>
 *   <li>otherwise the module's row must be active, or its deployment flag ({@code app.modules.*}) set;</li>
 * </ul>
 * plus add-ons: an {@link ModuleRegistry.ErpModule#addOn() add-on} is on wherever every module it depends on is on,
 * unless the organization switched it off (a row with {@code isActive = false}).
 *
 * <p>Organization-level answers come from the current tenant context, as the guard always did: call it inside the
 * organization's context.
 */
@Component
public class ModuleAccess {

    private static final Logger log = LoggerFactory.getLogger(ModuleAccess.class);

    /** Modules whose boot-time gate is an env flag (Nest {@code MODULE_ENV_FLAGS} → app.modules.*). */
    private static final Map<String, String> MODULE_ENV_FLAGS = Map.ofEntries(
            Map.entry("task", "app.modules.tasks"),
            Map.entry("manufacturing", "app.modules.manufacturing"),
            Map.entry("rental", "app.modules.rental"),
            Map.entry("repair", "app.modules.repair"),
            Map.entry("hr", "app.modules.hr"),
            Map.entry("beverage", "app.modules.beverage"),
            Map.entry("fixed-asset", "app.modules.assets"),
            Map.entry("communication", "app.modules.communication"),
            Map.entry("fleet", "app.modules.fleet"),
            Map.entry("quarry", "app.modules.quarry"),
            Map.entry("portal", "app.modules.portal"),
            Map.entry("alerts", "app.modules.alerts"),
            Map.entry("loss-prevention", "app.modules.lossprevention"));

    private final FeatureFlagsService flags;
    private final Environment env;
    private final ModuleRegistry registry;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    @Autowired
    public ModuleAccess(FeatureFlagsService flags, Environment env, ModuleRegistry registry) {
        this.flags = flags;
        this.env = env;
        this.registry = registry;
    }

    /** Whether {@code module} is on for the organization of the current tenant context. */
    public boolean allowed(String module) {
        return allowed(TenantContext.organizationId(), module);
    }

    /** Whether {@code module} is on for {@code orgId} (call inside that organization's tenant context). */
    public boolean allowed(String orgId, String module) {
        ModuleRegistry.ErpModule manifest = registry.find(module);
        if (manifest != null && manifest.addOn()) {
            for (String dependency : manifest.dependencies()) {
                if (!allowed(orgId, dependency)) {
                    return false;
                }
            }
            return !flags.isModuleSwitchedOff(module);
        }
        if (!flags.hasAnyModuleRow()) {
            if (warned.add(orgId)) {
                log.warn("Organization {} has no OrganizationModule rows; allowing '{}' by default. "
                        + "Backfill organization modules to make module gating explicit for this tenant.", orgId, module);
            }
            return true;
        }
        if (flags.isModuleEnabled(module)) {
            return true;
        }
        String property = MODULE_ENV_FLAGS.get(module);
        return property != null && env.getProperty(property, Boolean.class, false);
    }

    /**
     * The modules {@code module} depends on that are off for the current organization (empty when it declares none or
     * all are on). Enabling a module is refused while this is not empty.
     */
    public java.util.List<String> missingDependencies(String module) {
        ModuleRegistry.ErpModule manifest = registry.find(module);
        if (manifest == null) {
            return java.util.List.of();
        }
        String orgId = TenantContext.organizationId();
        return manifest.dependencies().stream().filter(d -> !allowed(orgId, d)).toList();
    }
}
