package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosTableZonesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/** {@code /pos/tables/zones} (port of {@code pos-table-zones.controller.ts}). */
@RestController
@RequestMapping("/pos/tables/zones")
public class PosTableZonesController {

    static final String MAX40 = "must be shorter than or equal to 40 characters";
    static final String MAX80 = "must be shorter than or equal to 80 characters";
    static final String MAX9 = "must be shorter than or equal to 9 characters";

    /** {@code CreateZoneBody}. */
    public record CreateZoneRequest(
            @Size(max = 40, message = MAX40) String key,
            @NotNull(message = STR) @Size(max = 80, message = MAX80) String name,
            Integer sortOrder,
            @Size(max = 9, message = MAX9) String color,
            Boolean active) {}

    /** {@code UpdateZoneBody}. */
    public record UpdateZoneRequest(
            @Size(max = 40, message = MAX40) String key,
            @Size(max = 80, message = MAX80) String name,
            @Min(value = 0, message = "must not be less than 0") Integer sortOrder,
            @Size(max = 9, message = MAX9) String color,
            Boolean active) {}

    private final PosTableZonesService svc;

    public PosTableZonesController(PosTableZonesService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.TABLES__VIEW)
    public List<Map<String, Object>> list() {
        return svc.list();
    }

    @GetMapping("/deleted")
    @RequirePermissions(Permissions.TABLES__VIEW)
    public List<Map<String, Object>> listDeleted() {
        return svc.listDeleted();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.TABLES__ZONES)
    public Map<String, Object> create(@Valid @RequestBody CreateZoneRequest dto) {
        return svc.create(PosDtos.data(dto));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.TABLES__ZONES)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateZoneRequest dto) {
        return svc.update(id, PosDtos.data(dto));
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.TABLES__ZONES)
    public Map<String, Object> archive(@PathVariable String id) {
        return svc.archive(id);
    }

    @PatchMapping("/{id}/restore")
    @RequirePermissions(Permissions.TABLES__ZONES)
    public Map<String, Object> restore(@PathVariable String id) {
        return svc.restore(id);
    }
}
