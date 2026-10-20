package com.inventoryerp.documents.web;

import com.inventoryerp.documents.application.DocumentTypeDefsService;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
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

/** DMS registry endpoints (port of {@code document-types.controller.ts}). Bodies are untyped in Nest. */
@RestController
@RequestMapping("/documents")
public class DocumentTypesController {

    private final DocumentTypeDefsService typeDefs;

    public DocumentTypesController(DocumentTypeDefsService typeDefs) {
        this.typeDefs = typeDefs;
    }

    /** Registry effective for the requesting org (ADR O2). */
    @GetMapping("/types")
    @RequirePermissions(Permissions.DOCUMENT__READ)
    public List<Map<String, Object>> types(AuthUser user) {
        return typeDefs.listEffective(user.organizationId());
    }

    @GetMapping("/types/{code}")
    @RequirePermissions(Permissions.DOCUMENT__READ)
    public Map<String, Object> type(@PathVariable String code) {
        return typeDefs.getByCode(code);
    }

    /**
     * Nest declares {@code types/:code} before {@code types/categories}; Express matches in registration order, so
     * this path is served by the {@code :code} handler ({@code getByCode('categories')}). Kept for parity;
     * {@link DocumentTypeDefsService#listCategories()} is the unreachable intended behaviour.
     */
    @GetMapping("/types/categories")
    @RequirePermissions(Permissions.DOCUMENT__READ)
    public Map<String, Object> categories() {
        return typeDefs.getByCode("categories");
    }

    @GetMapping("/lifecycles")
    @RequirePermissions(Permissions.DOCUMENT__READ)
    public List<Map<String, Object>> listLifecycles() {
        return typeDefs.listLifecycles();
    }

    @GetMapping("/lifecycles/{code}")
    @RequirePermissions(Permissions.DOCUMENT__READ)
    public Map<String, Object> lifecycle(@PathVariable String code) {
        return typeDefs.lifecycle(code);
    }

    @PostMapping("/types")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.DOCUMENT_TYPE__CREATE)
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(AuthUser user, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return typeDefs.create(user.organizationId(), str(b.get("code")), str(b.get("name")), str(b.get("category")),
                str(b.get("lifecycleId")), b.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
    }

    @PatchMapping("/types/{code}")
    @RequirePermissions(Permissions.DOCUMENT_TYPE__UPDATE)
    public Map<String, Object> update(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        return typeDefs.updateByCode(code, body == null ? Map.of() : new LinkedHashMap<>(body));
    }

    @DeleteMapping("/types/{code}")
    @RequirePermissions(Permissions.DOCUMENT_TYPE__DELETE)
    public Map<String, Object> remove(@PathVariable String code) {
        return typeDefs.removeByCode(code);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
