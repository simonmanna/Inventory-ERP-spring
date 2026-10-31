package com.inventoryerp.biexport.web;

import com.inventoryerp.biexport.application.BiExportService;
import com.inventoryerp.biexport.application.BiExportService.Export;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /bi/datasets}: the read-only reporting views as CSV (docs/integrations.md). */
@RestController
@RequestMapping("/bi/datasets")
public class BiExportController {

    private final BiExportService exports;

    public BiExportController(BiExportService exports) {
        this.exports = exports;
    }

    @GetMapping
    @RequirePermissions(Permissions.BI__EXPORT)
    public List<Map<String, Object>> datasets() {
        return exports.datasets();
    }

    @GetMapping("/{key}/export.csv")
    @RequirePermissions(Permissions.BI__EXPORT)
    public ResponseEntity<byte[]> export(@PathVariable String key, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Export file = exports.export(key, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .body(file.content());
    }
}
