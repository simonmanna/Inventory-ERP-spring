package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.STR;
import static com.inventoryerp.pos.web.PosDtos.UUID_MSG;
import static com.inventoryerp.pos.web.PosDtos.UUID_RE;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosReceiptsService;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/receipts} (port of {@code PosReceiptsController} in {@code pos-receipts.service.ts}). */
@RestController
@RequestMapping("/pos/receipts")
public class PosReceiptsController {

    /** {@code ReprintDto}. */
    public record ReprintRequest(@NotNull(message = STR) String reason) {}

    /** {@code ClosingStatementPrintDto}. */
    public record ClosingStatementPrintRequest(
            @NotNull(message = UUID_MSG) @Pattern(regexp = UUID_RE, message = UUID_MSG) String sessionId,
            @NotNull(message = STR) @Size(max = 8000,
                    message = "must be shorter than or equal to 8000 characters") String text) {}

    /** {@code ReceiptSettingsDto}. */
    public record ReceiptSettingsRequest(
            String businessName,
            String addressLine1,
            String addressLine2,
            String phone,
            String taxId,
            String footerMessage) {}

    private final PosReceiptsService svc;

    public PosReceiptsController(PosReceiptsService svc) {
        this.svc = svc;
    }

    @PostMapping("/print-closing-statement")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> printClosingStatement(@Valid @RequestBody ClosingStatementPrintRequest dto) {
        return svc.printClosingStatement(dto.sessionId(), dto.text());
    }

    @GetMapping("/{invoiceId}/pdf")
    @RequirePermissions(Permissions.POS__READ)
    public ResponseEntity<byte[]> pdf(@PathVariable("invoiceId") String id) {
        byte[] buf = svc.buildPdfReceipt(id, false);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"receipt-" + id + ".pdf\"")
                .body(buf);
    }

    @GetMapping("/{invoiceId}/text")
    @RequirePermissions(Permissions.POS__READ)
    public ResponseEntity<String> text(@PathVariable("invoiceId") String id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .body(svc.buildTextReceipt(id));
    }

    @PostMapping("/{invoiceId}/print")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> print(@PathVariable("invoiceId") String id) {
        return svc.printReceipt(id, PosSql.user(), false);
    }

    /** {@code ?to=} is accepted and ignored, like Nest (the partner's email on file is used). */
    @PostMapping("/{invoiceId}/email")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> email(@PathVariable("invoiceId") String id,
            @RequestParam(required = false) String to) {
        Map<String, Object> r = svc.emailReceipt(id);
        if (!Boolean.TRUE.equals(r.get("ok"))) {
            Object message = r.get("message");
            throw ApiException.badRequest(message == null ? "email failed" : message.toString());
        }
        return r;
    }

    @PostMapping("/{invoiceId}/reprint")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> reprint(@PathVariable("invoiceId") String id, @Valid @RequestBody ReprintRequest dto) {
        return svc.reprint(id, "RECEIPT", dto.reason());
    }

    @PostMapping("/{invoiceId}/print-bill")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> printBill(@PathVariable("invoiceId") String id) {
        return svc.printBill(id, PosSql.user(), false);
    }

    @PostMapping("/{invoiceId}/print-additional-bill")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> printAdditionalBill(@PathVariable("invoiceId") String id) {
        return svc.printAdditionalBill(id, PosSql.user());
    }

    @PostMapping("/{invoiceId}/reprint-bill")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> reprintBill(@PathVariable("invoiceId") String id,
            @Valid @RequestBody ReprintRequest dto) {
        return svc.reprint(id, "BILL", dto.reason());
    }

    /** Server-authoritative: callers cannot force a KOT to repeat already printed food. */
    @PostMapping("/{invoiceId}/print-kot")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> printKot(@PathVariable("invoiceId") String id) {
        return svc.printKotDelta(id, PosSql.user());
    }

    @GetMapping("/{invoiceId}")
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> get(@PathVariable("invoiceId") String id) {
        return svc.getReceiptDetail(id);
    }

    @GetMapping
    @RequirePermissions(Permissions.POS__READ)
    public Map<String, Object> list(@RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize, @RequestParam(required = false) String search,
            @RequestParam(required = false) String paymentMode,
            @RequestParam(required = false) String settlementStatus,
            @RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo) {
        return svc.listReceipts(orDefault(page, 1), Math.min(orDefault(pageSize, 20), 100), search, paymentMode,
                settlementStatus, dateFrom, dateTo);
    }

    @PatchMapping("/{invoiceId}/settings/receipt")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> updateReceiptSettings(@PathVariable("invoiceId") String id,
            @Valid @RequestBody ReceiptSettingsRequest dto) {
        return svc.saveReceiptSettings(PosDtos.data(dto));
    }

    /** JS {@code Number(raw) || def}. */
    private static int orDefault(String raw, int def) {
        double n = raw == null ? Double.NaN : PosSql.num(raw);
        return Double.isNaN(n) || n == 0 ? def : (int) n;
    }
}
