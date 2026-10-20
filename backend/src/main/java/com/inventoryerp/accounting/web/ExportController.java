package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.ReportExportService;
import com.inventoryerp.accounting.application.ReportExportService.Export;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /reports/export} (port of {@code export.controller.ts}): CSV and PDF downloads. Like Nest, these routes
 * require authentication but no specific permission.
 */
@RestController
@RequestMapping("/reports/export")
public class ExportController {

    private static final String CSV = "text/csv; charset=utf-8";

    private final ReportExportService exports;

    public ExportController(ReportExportService exports) {
        this.exports = exports;
    }

    private static ResponseEntity<byte[]> file(Export export, String contentType, String disposition) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + export.filename() + "\"")
                .body(export.content());
    }

    @GetMapping("/trial-balance.csv")
    public ResponseEntity<byte[]> trialBalanceCsv(@RequestParam(required = false) String asOf) {
        return file(exports.trialBalanceCsv(asOf), CSV, "attachment");
    }

    @GetMapping("/ar-aging.csv")
    public ResponseEntity<byte[]> arAgingCsv() {
        return file(exports.arAgingCsv(), CSV, "attachment");
    }

    /** Nest binds {@code id} from the query string ({@code @Query('id')}); the path segment is not used. */
    @GetMapping("/invoice/{pathId}.pdf")
    public ResponseEntity<byte[]> invoicePdf(@RequestParam(required = false) String id) {
        Export pdf = exports.invoicePdf(id);
        if (pdf == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).header(HttpHeaders.CONTENT_TYPE, "text/html; charset=utf-8")
                    .body("Not found".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return file(pdf, "application/pdf", "inline");
    }
}
