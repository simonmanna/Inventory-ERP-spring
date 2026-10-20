package com.inventoryerp.documents.web;

import com.inventoryerp.documents.application.DocumentEngineService;
import com.inventoryerp.documents.application.DocumentEngineService.ActionInput;
import com.inventoryerp.documents.application.DocumentPrintService;
import com.inventoryerp.documents.application.DocumentPrintService.PrintInput;
import com.inventoryerp.documents.application.DocumentRelationsService;
import com.inventoryerp.documents.application.DocumentSourcesService;
import com.inventoryerp.documents.application.DocumentsService;
import com.inventoryerp.documents.application.DocumentsService.Loaded;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.kernel.security.PermissionService;
import com.inventoryerp.shared.web.ApiException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * DMS document surface (port of {@code documents.controller.ts} + {@code document-actions.controller.ts}). No static
 * permissions: every route resolves {@code document:<type>:<action>} → category → generic at runtime. Bodies are
 * untyped interfaces in Nest (no DTO validation).
 */
@RestController
@RequestMapping("/documents")
public class DocumentsController {

    private final DocumentsService documents;
    private final DocumentEngineService engine;
    private final DocumentPrintService print;
    private final DocumentRelationsService relations;
    private final DocumentSourcesService sources;
    private final PermissionService permissions;

    public DocumentsController(DocumentsService documents, DocumentEngineService engine, DocumentPrintService print,
            DocumentRelationsService relations, DocumentSourcesService sources, PermissionService permissions) {
        this.documents = documents;
        this.engine = engine;
        this.print = print;
        this.relations = relations;
        this.sources = sources;
        this.permissions = permissions;
    }

    private List<String> keys(AuthUser user) {
        return permissions.permissionsOf(user.organizationId(), user.sub());
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }

    @GetMapping
    public Map<String, Object> list(AuthUser user, @RequestParam Map<String, String> query) {
        return documents.list(user.organizationId(), keys(user), query);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(AuthUser user, @PathVariable String id) {
        return documents.detail(user.organizationId(), keys(user), id);
    }

    @PatchMapping("/{id}")
    @SuppressWarnings("unchecked")
    public Map<String, Object> patch(AuthUser user, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Object fields = body == null ? null : body.get("fields");
        return documents.patch(user.organizationId(), user.sub(), keys(user), id, body == null ? null : body.get("version"),
                fields instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
    }

    /** {@code POST /documents/:id/actions/:action} — Idempotency-Key required. */
    @PostMapping("/{id}/actions/{action}")
    @ResponseStatus(HttpStatus.CREATED)
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(AuthUser user, @PathVariable String id, @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw ApiException.badRequest("Idempotency-Key header is required for lifecycle actions");
        }
        Object data = body == null ? null : body.get("data");
        return engine.runAction(new ActionInput(user.organizationId(), user.sub(), keys(user), id, action, idempotencyKey,
                str(body, "reason"), str(body, "note"), data instanceof Map<?, ?> m ? (Map<String, Object>) m : null));
    }

    @GetMapping("/{id}/preview")
    public Map<String, Object> preview(AuthUser user, @PathVariable String id,
            @RequestParam(required = false) String format) {
        return documents.preview(user.organizationId(), keys(user), id, format);
    }

    @PostMapping("/{id}/print")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> printDoc(AuthUser user, @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body) {
        Object copies = body == null ? null : body.get("copies");
        String format = str(body, "format");
        return print.print(new PrintInput(user.organizationId(), user.sub(), keys(user), id,
                format != null ? format : "a4", copies instanceof Number n ? n.intValue() : null, str(body, "printer"),
                str(body, "reason"), idempotencyKey != null ? idempotencyKey : ""));
    }

    /**
     * Nest declares {@code GET :id} before {@code GET templates}; Express matches in registration order, so this
     * path is served by the detail handler (id = "templates"). Kept for parity; {@link DocumentsService#listTemplates}
     * is the unreachable intended behaviour.
     */
    @GetMapping("/templates")
    public Map<String, Object> listTemplates(AuthUser user) {
        return documents.detail(user.organizationId(), keys(user), "templates");
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTemplate(AuthUser user, @RequestBody(required = false) Map<String, Object> body) {
        return documents.createTemplate(keys(user), user.sub(), str(body, "typeCode"), str(body, "format"),
                str(body, "name"), str(body, "content"), str(body, "engine"));
    }

    @PostMapping("/templates/{id}/activate")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> activateTemplate(AuthUser user, @PathVariable String id) {
        return documents.activateTemplate(keys(user), id);
    }

    // ------------------------------------------------------------------ relations

    @PostMapping("/{id}/relations")
    @ResponseStatus(HttpStatus.CREATED)
    @SuppressWarnings("unchecked")
    public Map<String, Object> createRelation(AuthUser user, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        String relationType = str(body, "relationType");
        if (relationType == null || relationType.isEmpty()) {
            throw ApiException.badRequest("relationType is required");
        }
        String target = documents.relationTarget(user.organizationId(), str(body, "toDocumentId"),
                str(body, "documentNumber"));
        Loaded l = documents.load(id, user.organizationId());
        documents.requireWrite(keys(user), l.def());
        Object payload = body.get("payload");
        return relations.createRelation(user.organizationId(), user.sub(), id, target, relationType,
                payload instanceof Map<?, ?> m ? (Map<String, Object>) m : null);
    }

    @GetMapping("/{id}/relations")
    public List<Map<String, Object>> listRelations(AuthUser user, @PathVariable String id) {
        documents.requireRead(keys(user), documents.load(id, user.organizationId()).def());
        return relations.listRelations(user.organizationId(), id);
    }

    @GetMapping("/{id}/relations/chain")
    public Map<String, Object> relationChain(AuthUser user, @PathVariable String id,
            @RequestParam(value = "type", required = false) String relationType) {
        if (relationType == null || relationType.isEmpty()) {
            throw ApiException.badRequest("?type= is required (e.g. FULFILLS)");
        }
        documents.requireRead(keys(user), documents.load(id, user.organizationId()).def());
        return relations.relationChain(user.organizationId(), id, relationType.toUpperCase());
    }

    @DeleteMapping("/{id}/relations/{rid}")
    public Map<String, Object> deleteRelation(AuthUser user, @PathVariable String id, @PathVariable String rid) {
        documents.requireWrite(keys(user), documents.load(id, user.organizationId()).def());
        return relations.deleteRelation(user.organizationId(), id, rid);
    }

    // ------------------------------------------------------------------ sources

    @PostMapping("/{id}/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addSource(AuthUser user, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        documents.requireWrite(keys(user), documents.load(id, user.organizationId()).def());
        String sourceType = str(body, "sourceType");
        String sourceId = str(body, "sourceId");
        return sources.addSource(user.organizationId(), user.sub(), id, sourceType != null ? sourceType : "",
                sourceId != null ? sourceId : "", str(body, "relationship"));
    }

    @GetMapping("/{id}/sources")
    public List<Map<String, Object>> listSources(AuthUser user, @PathVariable String id) {
        documents.requireRead(keys(user), documents.load(id, user.organizationId()).def());
        return sources.listSources(user.organizationId(), id);
    }

    @DeleteMapping("/{id}/sources/{sid}")
    public Map<String, Object> removeSource(AuthUser user, @PathVariable String id, @PathVariable String sid) {
        documents.requireWrite(keys(user), documents.load(id, user.organizationId()).def());
        return sources.removeSource(user.organizationId(), id, sid);
    }

    // ------------------------------------------------------------------ attachments

    @PostMapping("/{id}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> attach(AuthUser user, @PathVariable String id,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "metadata", required = false) String metadata) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("multipart field \"file\" is required");
        }
        documents.requireWrite(keys(user), documents.load(id, user.organizationId()).def());
        return sources.attach(user.organizationId(), user.sub(), id, role, kind, metadata, file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
    }

    @GetMapping("/{id}/attachments")
    public List<Map<String, Object>> listAttachments(AuthUser user, @PathVariable String id) {
        documents.requireRead(keys(user), documents.load(id, user.organizationId()).def());
        return sources.listAttachments(user.organizationId(), id);
    }

    @DeleteMapping("/{id}/attachments/{aid}")
    public Map<String, Object> removeAttachment(AuthUser user, @PathVariable String id, @PathVariable String aid) {
        documents.requireWrite(keys(user), documents.load(id, user.organizationId()).def());
        return sources.removeAttachment(user.organizationId(), id, aid);
    }

    @GetMapping("/{id}/timeline")
    public Map<String, Object> timeline(AuthUser user, @PathVariable String id) {
        return documents.timeline(user.organizationId(), keys(user), id);
    }
}
