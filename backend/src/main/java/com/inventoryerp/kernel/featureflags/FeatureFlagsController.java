package com.inventoryerp.kernel.featureflags;

import com.inventoryerp.kernel.modules.ModuleRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /feature-flags} and module enablement (port of {@code feature-flags.controller.ts}). Like Nest,
 * these require a session but no specific permission.
 */
@RestController
@RequestMapping("/feature-flags")
public class FeatureFlagsController {

    /** {@code SetFlagDto}. */
    public record SetFlagRequest(
            @NotNull(message = "must be a string") String key,
            @NotNull(message = "must be a boolean value") Boolean enabled,
            Map<String, Object> payload) {}

    /** {@code SetModuleConfigDto}. */
    public record SetModuleConfigRequest(Map<String, Object> config) {}

    private final FeatureFlagsService svc;
    private final ModuleRegistry registry;

    public FeatureFlagsController(FeatureFlagsService svc, ModuleRegistry registry) {
        this.svc = svc;
        this.registry = registry;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return svc.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> set(@Valid @RequestBody SetFlagRequest dto) {
        return svc.set(dto.key(), dto.enabled(), dto.payload());
    }

    @DeleteMapping("/{key}")
    public Map<String, Object> unset(@PathVariable String key) {
        svc.unset(key);
        return Map.of("ok", true);
    }

    /** Modules this deployment can actually serve (registered manifests). */
    @GetMapping("/modules/catalog")
    public List<Map<String, Object>> catalog() {
        return registry.list().stream()
                .sorted(Comparator.comparing(ModuleRegistry.ErpModule::name))
                .map(m -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", m.name());
                    row.put("version", m.version());
                    row.put("dependencies", m.dependencies());
                    return row;
                })
                .toList();
    }

    @GetMapping("/modules")
    public List<Map<String, Object>> modules() {
        return svc.listModules();
    }

    @PostMapping("/modules/{name}/enable")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> enable(@PathVariable String name, @RequestBody(required = false) SetModuleConfigRequest dto) {
        return svc.enableModule(name, dto == null ? null : dto.config());
    }

    @PatchMapping("/modules/{name}/disable")
    public Map<String, Object> disable(@PathVariable String name) {
        svc.disableModule(name);
        return Map.of("ok", true);
    }
}
