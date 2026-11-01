package com.inventoryerp.customization.web;

import com.inventoryerp.customization.application.PrintTemplateService;
import com.inventoryerp.customization.application.PrintTemplateService.Rendered;
import com.inventoryerp.customization.application.PrintTemplateService.TemplateInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

/** {@code /print-templates}: print-template designer, preview and rendering (ADR-S029 §3, extension). */
@RestController
@RequestMapping("/print-templates")
public class PrintTemplatesController {

    public record TemplateRequest(
            @NotBlank(message = "documentKind should not be empty") String documentKind,
            @NotBlank(message = "name should not be empty") @Size(max = 120, message = "name must be at most 120"
                    + " characters") String name,
            @NotBlank(message = "content should not be empty") String content,
            String css,
            @OneOf({"A4", "A5", "Letter"}) String paperSize,
            @OneOf({"portrait", "landscape"}) String orientation,
            Boolean isDefault,
            Boolean isActive) {}

    public record TemplateUpdateRequest(
            @Size(min = 1, max = 120, message = "name must be 1 to 120 characters") String name,
            String content,
            String css,
            @OneOf({"A4", "A5", "Letter"}) String paperSize,
            @OneOf({"portrait", "landscape"}) String orientation,
            Boolean isDefault,
            Boolean isActive) {}

    public record PreviewRequest(
            @NotBlank(message = "documentKind should not be empty") String documentKind,
            @NotBlank(message = "content should not be empty") String content,
            String css,
            String recordId,
            @OneOf({"A4", "A5", "Letter"}) String paperSize,
            @OneOf({"portrait", "landscape"}) String orientation) {}

    private final PrintTemplateService templates;

    public PrintTemplatesController(PrintTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    @RequirePermissions(Permissions.PRINT_TEMPLATE__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String documentKind) {
        return templates.list(documentKind);
    }

    @GetMapping("/fields/{documentKind}")
    @RequirePermissions(Permissions.PRINT_TEMPLATE__READ)
    public List<Map<String, Object>> fields(@PathVariable String documentKind) {
        return templates.fields(documentKind);
    }

    @PostMapping("/preview")
    @RequirePermissions(Permissions.PRINT_TEMPLATE__READ)
    public Map<String, Object> preview(@Valid @RequestBody PreviewRequest dto) {
        return templates.preview(dto.documentKind(), dto.content(), dto.css(), dto.recordId(), dto.paperSize(),
                dto.orientation());
    }

    /** Render a record: {@code format=html} (default) or {@code pdf}. */
    @GetMapping("/render/{documentKind}/{recordId}")
    @RequirePermissions(Permissions.PRINT_TEMPLATE__READ)
    public ResponseEntity<byte[]> render(@PathVariable String documentKind, @PathVariable String recordId,
            @RequestParam(required = false) String templateId, @RequestParam(required = false) String format) {
        if (format != null && !"html".equals(format) && !"pdf".equals(format)) {
            throw com.inventoryerp.shared.web.ApiException.badRequest(
                    "format must be one of the following values: html, pdf");
        }
        boolean pdf = "pdf".equals(format);
        Rendered r = templates.render(documentKind, recordId, templateId, pdf);
        if (pdf) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + documentKind + ".pdf\"")
                    .header("X-Template-Version", String.valueOf(r.version())).body(r.pdf());
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header("X-Template-Version", String.valueOf(r.version()))
                .header("Content-Security-Policy", "script-src 'none'")
                .body(r.html().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PRINT_TEMPLATE__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return templates.get(id);
    }

    @GetMapping("/{id}/versions")
    @RequirePermissions(Permissions.PRINT_TEMPLATE__READ)
    public List<Map<String, Object>> versions(@PathVariable String id) {
        return templates.versions(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PRINT_TEMPLATE__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody TemplateRequest dto) {
        return templates.create(new TemplateInput(dto.documentKind(), dto.name(), dto.content(), dto.css(),
                dto.paperSize(), dto.orientation(), dto.isDefault(), dto.isActive()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.PRINT_TEMPLATE__MANAGE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody TemplateUpdateRequest dto) {
        return templates.update(id, new TemplateInput(null, dto.name(), dto.content(), dto.css(), dto.paperSize(),
                dto.orientation(), dto.isDefault(), dto.isActive()));
    }

    @PostMapping("/{id}/versions/{version}/restore")
    @RequirePermissions(Permissions.PRINT_TEMPLATE__MANAGE)
    public Map<String, Object> restore(@PathVariable String id, @PathVariable int version) {
        return templates.restore(id, version);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.PRINT_TEMPLATE__MANAGE)
    public Map<String, Object> delete(@PathVariable String id) {
        return templates.delete(id);
    }
}
