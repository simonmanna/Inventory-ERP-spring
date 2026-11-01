package com.inventoryerp.customization.web;

import com.inventoryerp.customization.application.CustomFieldService;
import com.inventoryerp.customization.application.CustomFieldService.DefinitionInput;
import com.inventoryerp.customization.application.CustomObjectService;
import com.inventoryerp.customization.application.CustomObjectService.ObjectInput;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /custom-fields} and {@code /custom-objects}: custom field definitions and values, custom objects and their
 * records (ADR-S029 §1–2, extension).
 */
@RestController
public class CustomFieldsController {

    public record DefinitionRequest(
            @NotBlank(message = "entity should not be empty") String entity,
            @NotBlank(message = "key should not be empty") String key,
            @NotBlank(message = "label should not be empty") @Size(max = 120, message = "label must be at most 120"
                    + " characters") String label,
            @NotBlank(message = "type should not be empty") String type,
            Map<String, Object> options,
            Boolean required,
            Object defaultValue,
            @Size(max = 500, message = "helpText must be at most 500 characters") String helpText,
            Integer sortOrder,
            Boolean showInList,
            Boolean isActive) {}

    public record DefinitionUpdateRequest(
            @Size(min = 1, max = 120, message = "label must be 1 to 120 characters") String label,
            String type,
            Map<String, Object> options,
            Boolean required,
            Object defaultValue,
            @Size(max = 500, message = "helpText must be at most 500 characters") String helpText,
            Integer sortOrder,
            Boolean showInList,
            Boolean isActive) {}

    public record ValuesRequest(@NotNull(message = "values must be an object") Map<String, Object> values) {}

    public record ObjectRequest(
            @NotBlank(message = "key should not be empty") String key,
            @NotBlank(message = "label should not be empty") @Size(max = 120, message = "label must be at most 120"
                    + " characters") String label,
            @Size(max = 120, message = "pluralLabel must be at most 120 characters") String pluralLabel,
            String icon,
            String displayField,
            @Size(max = 500, message = "description must be at most 500 characters") String description,
            Boolean isActive) {}

    public record ObjectUpdateRequest(
            @Size(min = 1, max = 120, message = "label must be 1 to 120 characters") String label,
            @Size(min = 1, max = 120, message = "pluralLabel must be 1 to 120 characters") String pluralLabel,
            String icon,
            String displayField,
            @Size(max = 500, message = "description must be at most 500 characters") String description,
            Boolean isActive) {}

    private final CustomFieldService fields;
    private final CustomObjectService objects;

    public CustomFieldsController(CustomFieldService fields, CustomObjectService objects) {
        this.fields = fields;
        this.objects = objects;
    }

    // ---- field definitions ----------------------------------------------------------------------------------------

    @GetMapping("/custom-fields/entities")
    @RequirePermissions(Permissions.CUSTOMIZATION__READ)
    public List<Map<String, Object>> entities() {
        return fields.entityCatalog();
    }

    @GetMapping("/custom-fields/definitions")
    @RequirePermissions(Permissions.CUSTOMIZATION__READ)
    public List<Map<String, Object>> definitions(@RequestParam String entity,
            @RequestParam(required = false) Boolean includeInactive) {
        return fields.listDefinitions(entity, Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/custom-fields/definitions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CUSTOMIZATION__MANAGE)
    public Map<String, Object> createDefinition(@Valid @RequestBody DefinitionRequest dto) {
        return fields.createDefinition(new DefinitionInput(dto.entity(), dto.key(), dto.label(), dto.type(),
                dto.options(), dto.required(), dto.defaultValue(), dto.helpText(), dto.sortOrder(), dto.showInList(),
                dto.isActive()));
    }

    @PatchMapping("/custom-fields/definitions/{id}")
    @RequirePermissions(Permissions.CUSTOMIZATION__MANAGE)
    public Map<String, Object> updateDefinition(@PathVariable String id,
            @Valid @RequestBody DefinitionUpdateRequest dto) {
        return fields.updateDefinition(id, new DefinitionInput(null, null, dto.label(), dto.type(), dto.options(),
                dto.required(), dto.defaultValue(), dto.helpText(), dto.sortOrder(), dto.showInList(), dto.isActive()));
    }

    @DeleteMapping("/custom-fields/definitions/{id}")
    @RequirePermissions(Permissions.CUSTOMIZATION__MANAGE)
    public Map<String, Object> deleteDefinition(@PathVariable String id) {
        return fields.deleteDefinition(id);
    }

    // ---- values ---------------------------------------------------------------------------------------------------

    @GetMapping("/custom-fields/values/{entity}/{recordId}")
    @RequirePermissions(Permissions.CUSTOM_RECORD__READ)
    public Map<String, Object> values(@PathVariable String entity, @PathVariable String recordId) {
        return fields.getValues(entity, recordId);
    }

    @PutMapping("/custom-fields/values/{entity}/{recordId}")
    @RequirePermissions(Permissions.CUSTOM_RECORD__WRITE)
    public Map<String, Object> putValues(@PathVariable String entity, @PathVariable String recordId,
            @Valid @RequestBody ValuesRequest dto) {
        return fields.putValues(entity, recordId, dto.values());
    }

    // ---- custom objects -------------------------------------------------------------------------------------------

    @GetMapping("/custom-objects")
    @RequirePermissions(Permissions.CUSTOM_RECORD__READ)
    public List<Map<String, Object>> objects() {
        return objects.list();
    }

    @PostMapping("/custom-objects")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CUSTOMIZATION__MANAGE)
    public Map<String, Object> createObject(@Valid @RequestBody ObjectRequest dto) {
        return objects.create(new ObjectInput(dto.key(), dto.label(), dto.pluralLabel(), dto.icon(), dto.displayField(),
                dto.description(), dto.isActive()));
    }

    @GetMapping("/custom-objects/{key}")
    @RequirePermissions(Permissions.CUSTOM_RECORD__READ)
    public Map<String, Object> object(@PathVariable String key) {
        return objects.get(key);
    }

    @PatchMapping("/custom-objects/{key}")
    @RequirePermissions(Permissions.CUSTOMIZATION__MANAGE)
    public Map<String, Object> updateObject(@PathVariable String key, @Valid @RequestBody ObjectUpdateRequest dto) {
        return objects.update(key, new ObjectInput(null, dto.label(), dto.pluralLabel(), dto.icon(), dto.displayField(),
                dto.description(), dto.isActive()));
    }

    @GetMapping("/custom-objects/{key}/records")
    @RequirePermissions(Permissions.CUSTOM_RECORD__READ)
    public PageResult<Map<String, Object>> records(@PathVariable String key,
            @RequestParam Map<String, String> query) {
        Map<String, String> paging = new LinkedHashMap<>();
        Map<String, String> filters = new LinkedHashMap<>();
        query.forEach((k, v) -> {
            if (k.startsWith("f.") && k.length() > 2) {
                filters.put(k.substring(2), v);
            } else {
                paging.put(k, v);
            }
        });
        PageQuery q = QueryParams.page(paging);
        return objects.records(key, q, filters);
    }

    @PostMapping("/custom-objects/{key}/records")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.CUSTOM_RECORD__WRITE)
    public Map<String, Object> createRecord(@PathVariable String key, @Valid @RequestBody ValuesRequest dto) {
        return objects.createRecord(key, dto.values());
    }

    @GetMapping("/custom-objects/{key}/records/{id}")
    @RequirePermissions(Permissions.CUSTOM_RECORD__READ)
    public Map<String, Object> record(@PathVariable String key, @PathVariable String id) {
        return objects.record(key, id);
    }

    @PatchMapping("/custom-objects/{key}/records/{id}")
    @RequirePermissions(Permissions.CUSTOM_RECORD__WRITE)
    public Map<String, Object> updateRecord(@PathVariable String key, @PathVariable String id,
            @Valid @RequestBody ValuesRequest dto) {
        return objects.updateRecord(key, id, dto.values());
    }

    @DeleteMapping("/custom-objects/{key}/records/{id}")
    @RequirePermissions(Permissions.CUSTOM_RECORD__WRITE)
    public Map<String, Object> deleteRecord(@PathVariable String key, @PathVariable String id) {
        return objects.deleteRecord(key, id);
    }
}
