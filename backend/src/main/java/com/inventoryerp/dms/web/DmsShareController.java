package com.inventoryerp.dms.web;

import com.inventoryerp.dms.application.DmsShareService;
import com.inventoryerp.dms.application.DmsShareService.ShareInput;
import com.inventoryerp.kernel.files.FilesController;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.kernel.security.Throttle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * External share links: {@code /dms/share-links} (staff, {@code dms:document:share}) and the public, token-authorized
 * {@code /public/dms/share/{token}} routes (rate limited; password in the {@code X-Share-Password} header).
 */
@RestController
public class DmsShareController {

    public record ShareRequest(
            @Size(max = 120, message = "name must be at most 120 characters") String name,
            List<String> documentIds,
            String workspaceId,
            String folderId,
            Boolean allowDownload,
            Boolean allowUpload,
            @Size(max = 128, message = "password must be at most 128 characters") String password,
            Integer expiresInDays,
            Integer maxDownloads) {}

    private final DmsShareService shares;

    public DmsShareController(DmsShareService shares) {
        this.shares = shares;
    }

    @GetMapping("/dms/share-links")
    @RequirePermissions(Permissions.DMS__DOCUMENT_SHARE)
    public List<Map<String, Object>> list(@RequestParam(required = false) String documentId) {
        return shares.list(DmsParts.blank(documentId));
    }

    @PostMapping("/dms/share-links")
    @RequirePermissions(Permissions.DMS__DOCUMENT_SHARE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ShareRequest r) {
        return shares.create(new ShareInput(r.name(), r.documentIds(), DmsParts.blank(r.workspaceId()),
                DmsParts.blank(r.folderId()), r.allowDownload(), r.allowUpload(), r.password(), r.expiresInDays(),
                r.maxDownloads()));
    }

    @PostMapping("/dms/share-links/{id}/revoke")
    @RequirePermissions(Permissions.DMS__DOCUMENT_SHARE)
    public Map<String, Object> revoke(@PathVariable String id) {
        return shares.revoke(id);
    }

    @Public
    @Throttle(limit = 60, ttlMs = 60_000)
    @GetMapping("/public/dms/share/{token}")
    public Map<String, Object> view(@PathVariable String token,
            @RequestHeader(value = "X-Share-Password", required = false) String password) {
        return shares.publicView(token, password);
    }

    @Public
    @Throttle(limit = 30, ttlMs = 60_000)
    @GetMapping("/public/dms/share/{token}/documents/{documentId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String token, @PathVariable String documentId,
            @RequestHeader(value = "X-Share-Password", required = false) String password,
            @RequestParam(required = false) String inline) {
        return FilesController.stream(shares.publicDownload(token, password, documentId), "true".equals(inline));
    }

    @Public
    @Throttle(limit = 10, ttlMs = 60_000)
    @PostMapping("/public/dms/share/{token}/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> upload(@PathVariable String token,
            @RequestHeader(value = "X-Share-Password", required = false) String password,
            @RequestParam(value = "file", required = false) List<MultipartFile> file,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(required = false) String uploaderName) {
        return shares.publicUpload(token, password, DmsParts.parts(file, files), DmsParts.blank(uploaderName));
    }
}
