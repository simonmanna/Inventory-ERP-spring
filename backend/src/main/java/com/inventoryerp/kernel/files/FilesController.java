package com.inventoryerp.kernel.files;

import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.shared.web.ApiException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /files} (port of {@code files.controller.ts}). */
@RestController
@RequestMapping("/files")
public class FilesController {

    private final FilesService files;

    public FilesController(FilesService files) {
        this.files = files;
    }

    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> upload(@RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "ownerType", required = false) String ownerType,
            @RequestParam(value = "ownerId", required = false) String ownerId) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        Map<String, Object> res = files.upload(file.getOriginalFilename(), file.getContentType(), file.getBytes(),
                ownerType, ownerId);
        Map<String, Object> out = new LinkedHashMap<>(res);
        out.putAll(files.signDownload((String) res.get("id")));
        out.put("filename", file.getOriginalFilename());
        out.put("byteSize", file.getSize());
        out.put("contentType", file.getContentType());
        return out;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String ownerType,
            @RequestParam(required = false) String ownerId) {
        return files.listForOwner(ownerType, ownerId);
    }

    @PostMapping("/{id}/signed-url")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> signedUrl(@PathVariable String id) {
        return files.signDownload(id);
    }

    /** Public: the token proves the caller has the URL. */
    @Public
    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String id,
            @RequestParam(required = false) String token, @RequestParam(required = false) String expires) {
        FilesService.Download file = files.resolveSignedDownload(id, token, expires);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .body(new InputStreamResource(file.stream()));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable String id) {
        files.remove(id);
        return Map.of("ok", true);
    }
}
