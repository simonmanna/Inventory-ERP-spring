package com.inventoryerp.documents.web;

import com.inventoryerp.documents.application.LabelService;
import com.inventoryerp.documents.application.LabelService.LabelRequest;
import com.inventoryerp.documents.application.LabelService.RenderedLabels;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Label printing (INT-3): pick what to label, render a PDF or a ZPL file. */
@RestController
@RequestMapping("/labels")
@Validated
public class LabelController {

    private final LabelService labels;

    public LabelController(LabelService labels) {
        this.labels = labels;
    }

    @GetMapping("/sources/{type}")
    @RequirePermissions(Permissions.LABEL__PRINT)
    public List<Map<String, Object>> sources(
            @PathVariable @OneOf({"product", "stockpile", "ticket"}) String type,
            @RequestParam(required = false) String search) {
        return labels.sources(type, search);
    }

    @PostMapping("/render")
    @RequirePermissions(Permissions.LABEL__PRINT)
    public ResponseEntity<byte[]> render(@Valid @RequestBody LabelRequest body) {
        RenderedLabels out = labels.render(body);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, out.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + out.filename() + "\"")
                .body(out.content());
    }
}
