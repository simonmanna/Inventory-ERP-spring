package com.inventoryerp.kernel.auth;

import com.inventoryerp.kernel.common.ContractCatalogs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Permission catalog for the admin matrix (port of {@code permissions.controller.ts}): the nested
 * {@code PERMISSIONS} object flattened to {@code {groups: [{resource, permissions: [{action, key}]}]}}.
 * Requires a session (no specific permission).
 */
@RestController
@RequestMapping("/auth/permissions")
public class PermissionsController {

    @GetMapping
    public Map<String, Object> catalog() {
        List<Map<String, Object>> groups = new ArrayList<>();
        ContractCatalogs.permissionTree().properties().forEach(entry -> {
            List<Map<String, Object>> permissions = new ArrayList<>();
            JsonNode value = entry.getValue();
            if (value.isString()) {
                permissions.add(permission(value.asString()));
            } else if (value.isObject()) {
                value.properties().forEach(p -> {
                    if (p.getValue().isString()) {
                        permissions.add(permission(p.getValue().asString()));
                    }
                });
            }
            if (!permissions.isEmpty()) {
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("resource", entry.getKey());
                group.put("permissions", permissions);
                groups.add(group);
            }
        });
        return Map.of("groups", groups);
    }

    private static Map<String, Object> permission(String key) {
        String[] parts = key.split(":", -1);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("action", parts.length > 1 ? parts[1] : key);
        p.put("key", key);
        return p;
    }
}
