package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.BankStatementFileService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.ApiException;
import java.io.IOException;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Bank statement file import (INT-3): CSV, MT940 or camt.053, with a dry run. The JSON import route is unchanged. */
@RestController
@RequestMapping("/bank-reconciliation")
@Validated
public class BankStatementFileController {

    private final BankStatementFileService files;

    public BankStatementFileController(BankStatementFileService files) {
        this.files = files;
    }

    /** Multipart {@code file}; {@code format} defaults to detection; {@code dryRun=true} only reports. */
    @PostMapping("/import-file")
    @Idempotent
    @RequirePermissions(Permissions.BANK_ACCOUNT__UPDATE)
    public Map<String, Object> importFile(@RequestParam String bankAccountId,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) @OneOf({"auto", "csv", "mt940", "camt053"}) String format,
            @RequestParam(required = false, defaultValue = "false") boolean dryRun,
            @RequestPart(value = "file", required = false) MultipartFile file) throws IOException {
        if (file == null) {
            throw ApiException.badRequest("Missing file");
        }
        return files.importFile(bankAccountId, currencyCode, format, dryRun, file.getBytes());
    }
}
