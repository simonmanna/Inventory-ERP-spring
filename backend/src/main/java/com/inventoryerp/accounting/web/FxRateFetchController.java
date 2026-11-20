package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.FxRateFetchService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FX-8 (ADR-S082): automatic rate fetch status, manual trigger and the missing-rate warning. Extension (no Nest). */
@RestController
@RequestMapping("/currencies/rates")
public class FxRateFetchController {

    private final FxRateFetchService fetch;

    public FxRateFetchController(FxRateFetchService fetch) {
        this.fetch = fetch;
    }

    /** Whether automatic fetching is configured on this server. */
    @GetMapping("/fetch-status")
    @RequirePermissions(Permissions.CURRENCY__READ)
    public Map<String, Object> status() {
        return Map.of("configured", fetch.configured());
    }

    /** Fetch today's rates for the caller's company now (manual rates already on file are kept). */
    @PostMapping("/fetch")
    @Idempotent
    @RequirePermissions(Permissions.CURRENCY__UPDATE)
    public Map<String, Object> fetchNow() {
        return fetch.fetchForCurrentOrg();
    }

    /** Used currencies without an approved rate dated within {@code maxAgeDays} (default 3). */
    @GetMapping("/missing")
    @RequirePermissions(Permissions.CURRENCY__READ)
    public List<Map<String, Object>> missing(@RequestParam(required = false) Integer maxAgeDays) {
        return fetch.missing(maxAgeDays);
    }
}
