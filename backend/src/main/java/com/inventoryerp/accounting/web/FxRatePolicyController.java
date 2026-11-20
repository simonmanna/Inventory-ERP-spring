package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.web.CurrencyController.RatePolicyRequest;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FX-2 B: the company's exchange-rate policy per transaction context (extension; no Nest counterpart). */
@RestController
@RequestMapping("/fx-rate-policies")
public class FxRatePolicyController {

    private final CurrencyService svc;

    public FxRatePolicyController(CurrencyService svc) {
        this.svc = svc;
    }

    @GetMapping
    @RequirePermissions(Permissions.CURRENCY__READ)
    public List<Map<String, Object>> list() {
        return svc.listPolicies();
    }

    @PutMapping("/{context}")
    @RequirePermissions(Permissions.CURRENCY__POLICY)
    public Map<String, Object> save(@PathVariable String context, @Valid @RequestBody RatePolicyRequest dto) {
        return svc.savePolicy(context, dto.rateType(), dto.requireApprovedRate(), dto.statedRateTolerancePct(),
                dto.allowStatedRate());
    }
}
