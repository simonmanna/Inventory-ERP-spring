package com.inventoryerp.dms.web;

import com.inventoryerp.dms.application.DmsDocumentService;
import com.inventoryerp.dms.application.DmsDocumentService.BulkInput;
import com.inventoryerp.dms.application.DmsDocumentService.ListFilter;
import com.inventoryerp.dms.application.DmsDocumentService.UpdateInput;
import com.inventoryerp.dms.application.DmsDocumentService.UploadInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /dms/documents} (DMS, ADR-S044): browse, upload, version, tag, move, link, lock, approve, share URLs. */
@RestController
@RequestMapping("/dms/documents")
public class DmsDocumentController {

    static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}.*$";

    public record UpdateRequest(
            @Size(max = 255, message = "name must be at most 255 characters") String name,
            @Size(max = 4000, message = "description must be at most 4000 characters") String description,
            String folderId,
            Boolean moveToRoot,
            String workspaceId,
            @Pattern(regexp = ISO_DATE, message = "expiresOn must be a valid ISO 8601 date string") String expiresOn,
            Boolean clearExpiry,
            Integer reminderLeadDays,
            String partnerId,
            List<String> tagIds,
            Integer version) {}

    public record LinkRequest(
            @NotBlank(message = "resType should not be empty") String resType,
            @NotBlank(message = "resId should not be empty") String resId) {}

    public record DecisionRequest(@Size(max = 2000, message = "comment must be at most 2000 characters") String comment) {}

    public record BulkRequest(
            @NotEmpty(message = "ids should not be empty") List<String> ids,
            @NotBlank(message = "action should not be empty") String action,
            String workspaceId,
            String folderId,
            List<String> tagIds) {}

    private final DmsDocumentService documents;

    public DmsDocumentController(DmsDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) String folderId, @RequestParam(required = false) String root,
            @RequestParam(required = false) String tagId, @RequestParam(required = false) String resType,
            @RequestParam(required = false) String resId, @RequestParam(required = false) String q,
            @RequestParam(required = false) String status, @RequestParam(required = false) String expiringBefore,
            @RequestParam(required = false) String deleted, @RequestParam(required = false) String mine,
            @RequestParam(required = false) String sort, @RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize) {
        Integer p = DmsParts.integer(page, "page");
        Integer s = DmsParts.integer(pageSize, "pageSize");
        return documents.list(new ListFilter(DmsParts.blank(workspaceId), DmsParts.blank(folderId),
                "true".equals(root), DmsParts.blank(tagId), DmsParts.blank(resType), DmsParts.blank(resId), q,
                DmsParts.blank(status), DmsParts.date(expiringBefore, "expiringBefore"), "true".equals(deleted),
                "true".equals(mine), sort, p == null ? 1 : p, s == null ? 50 : s));
    }

    @PostMapping
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> upload(@RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(required = false) String workspaceId, @RequestParam(required = false) String folderId,
            @RequestParam(required = false) String resType, @RequestParam(required = false) String resId,
            @RequestParam(required = false) List<String> tags, @RequestParam(required = false) String description,
            @RequestParam(required = false) String expiresOn, @RequestParam(required = false) String reminderLeadDays,
            @RequestParam(required = false) String partnerId) {
        return documents.upload(new UploadInput(DmsParts.blank(workspaceId), DmsParts.blank(folderId),
                DmsParts.blank(resType), DmsParts.blank(resId), DmsParts.list(tags), description,
                DmsParts.date(expiresOn, "expiresOn"), DmsParts.integer(reminderLeadDays, "reminderLeadDays"),
                DmsParts.blank(partnerId)), DmsParts.parts(file, files));
    }

    @GetMapping("/resource-types")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public List<Map<String, Object>> resourceTypes() {
        return documents.resourceTypes();
    }

    @PostMapping("/bulk")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> bulk(@Valid @RequestBody BulkRequest r) {
        return documents.bulk(new BulkInput(r.ids(), r.action(), r.workspaceId(), r.folderId(), r.tagIds()));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public Map<String, Object> get(@PathVariable String id) {
        return documents.get(id);
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateRequest r) {
        boolean setFolder = r.folderId() != null || Boolean.TRUE.equals(r.moveToRoot());
        boolean setExpiry = r.expiresOn() != null || Boolean.TRUE.equals(r.clearExpiry());
        return documents.update(id, new UpdateInput(r.name(), r.description(),
                Boolean.TRUE.equals(r.moveToRoot()) ? null : r.folderId(), setFolder, r.workspaceId(),
                Boolean.TRUE.equals(r.clearExpiry()) ? null : DmsParts.date(r.expiresOn(), "expiresOn"), setExpiry,
                r.reminderLeadDays(), r.partnerId(), r.tagIds(), r.version()));
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.DMS__DOCUMENT_DELETE)
    public Map<String, Object> delete(@PathVariable String id) {
        return documents.delete(id);
    }

    @PostMapping("/{id}/restore")
    @RequirePermissions(Permissions.DMS__DOCUMENT_DELETE)
    public Map<String, Object> restore(@PathVariable String id) {
        return documents.restore(id);
    }

    @GetMapping("/{id}/versions")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public List<Map<String, Object>> versions(@PathVariable String id) {
        return documents.versions(id);
    }

    @PostMapping("/{id}/versions")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addVersion(@PathVariable String id,
            @RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(required = false) String note) {
        return documents.addVersion(id, DmsParts.parts(file, null).getFirst(), note);
    }

    @PostMapping("/{id}/versions/{versionNo}/restore")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> restoreVersion(@PathVariable String id, @PathVariable String versionNo) {
        Integer v = DmsParts.integer(versionNo, "versionNo");
        return documents.restoreVersion(id, v == null ? 0 : v);
    }

    @PostMapping("/{id}/links")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> link(@PathVariable String id, @Valid @RequestBody LinkRequest r) {
        return documents.link(id, r.resType(), r.resId());
    }

    @DeleteMapping("/{id}/links/{linkId}")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> unlink(@PathVariable String id, @PathVariable String linkId) {
        return documents.unlink(id, linkId);
    }

    @PostMapping("/{id}/lock")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> lock(@PathVariable String id) {
        return documents.lock(id, true);
    }

    @PostMapping("/{id}/unlock")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> unlock(@PathVariable String id) {
        return documents.lock(id, false);
    }

    @PostMapping("/{id}/archive")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> archive(@PathVariable String id) {
        return documents.archive(id, true);
    }

    @PostMapping("/{id}/unarchive")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> unarchive(@PathVariable String id) {
        return documents.archive(id, false);
    }

    @PostMapping("/{id}/signed-url")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> signedUrl(@PathVariable String id, @RequestParam(required = false) String version) {
        return documents.signedUrl(id, DmsParts.integer(version, "version"));
    }

    @GetMapping("/{id}/timeline")
    @RequirePermissions(Permissions.DMS__DOCUMENT_READ)
    public List<Map<String, Object>> timeline(@PathVariable String id) {
        return documents.timeline(id);
    }

    @PostMapping("/{id}/submit")
    @RequirePermissions(Permissions.DMS__DOCUMENT_WRITE)
    public Map<String, Object> submit(@PathVariable String id) {
        return documents.submit(id);
    }

    @PostMapping("/{id}/approve")
    @RequirePermissions(Permissions.DMS__DOCUMENT_APPROVE)
    public Map<String, Object> approve(@PathVariable String id, @Valid @RequestBody(required = false) DecisionRequest r) {
        return documents.decide(id, true, r == null ? null : r.comment());
    }

    @PostMapping("/{id}/reject")
    @RequirePermissions(Permissions.DMS__DOCUMENT_APPROVE)
    public Map<String, Object> reject(@PathVariable String id, @Valid @RequestBody(required = false) DecisionRequest r) {
        return documents.decide(id, false, r == null ? null : r.comment());
    }
}
