package com.inventoryerp.projects.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.projects.application.CapitalProjectService;
import com.inventoryerp.projects.application.CapitalProjectService.AssetLine;
import com.inventoryerp.projects.application.CapitalProjectService.CapitaliseInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Capital (own-use) projects: CIP and capitalisation into fixed assets (ADR-S074 §3; extension). */
@RestController
@RequestMapping("/projects")
public class CapitalProjectController {

    public record CapitalRequest(Boolean isCapital) {}

    public record AssetRequest(String name, String categoryId, BigDecimal amount, Integer usefulLife,
            BigDecimal salvageValue, String inServiceDate, String location, String description) {}

    public record CapitaliseRequest(String date, String notes, List<AssetRequest> assets) {}

    private final CapitalProjectService capital;

    public CapitalProjectController(CapitalProjectService capital) {
        this.capital = capital;
    }

    @GetMapping("/cip-report")
    @RequirePermissions(Permissions.PROJECT__READ)
    public Map<String, Object> report() {
        return capital.report();
    }

    @PutMapping("/{id}/capital")
    @RequirePermissions(Permissions.PROJECT__MANAGE)
    public Map<String, Object> setCapital(@PathVariable String id, @RequestBody CapitalRequest r) {
        return capital.setCapital(id, r.isCapital());
    }

    @GetMapping("/{id}/cip")
    @RequirePermissions(Permissions.PROJECT__READ)
    public Map<String, Object> cip(@PathVariable String id) {
        return capital.cip(id);
    }

    @PostMapping("/{id}/capitalisations")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> capitalise(@PathVariable String id, @RequestBody CapitaliseRequest r) {
        return capital.capitalise(id, new CapitaliseInput(r.date(), r.notes(), r.assets() == null ? null
                : r.assets().stream().map(a -> a == null ? null : new AssetLine(a.name(), a.categoryId(), a.amount(),
                        a.usefulLife(), a.salvageValue(), a.inServiceDate(), a.location(), a.description())).toList()));
    }

    @PostMapping("/capitalisations/{capitalisationId}/reverse")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROJECT__POST)
    public Map<String, Object> reverse(@PathVariable String capitalisationId) {
        return capital.reverse(capitalisationId);
    }
}
