package com.inventoryerp.invoicing.web;

import com.inventoryerp.invoicing.application.CustomerDepositService;
import com.inventoryerp.invoicing.application.CustomerDepositService.DepositInput;
import com.inventoryerp.invoicing.application.MobileMoneyService;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customer deposits (advances) and mobile-money collections (ADR-S011 S6). Additive routes beyond the NestJS
 * reference; every money route requires an Idempotency-Key.
 */
@RestController
public class CustomerDepositController {

    private final CustomerDepositService deposits;
    private final MobileMoneyService mobileMoney;

    public CustomerDepositController(CustomerDepositService deposits, MobileMoneyService mobileMoney) {
        this.deposits = deposits;
        this.mobileMoney = mobileMoney;
    }

    private static Map<String, Object> body(Map<String, Object> b) {
        return b == null ? new HashMap<>() : b;
    }

    private static String s(Map<String, Object> b, String k) {
        Object v = body(b).get(k);
        return v == null || v.toString().isBlank() ? null : v.toString();
    }

    private static BigDecimal d(Map<String, Object> b, String k) {
        Object v = body(b).get(k);
        return v == null ? null : new BigDecimal(v.toString());
    }

    @GetMapping("/customer-deposits")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public List<Map<String, Object>> list(@RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String salesOrderId, @RequestParam(required = false) String status) {
        return deposits.list(partnerId, salesOrderId, status);
    }

    @GetMapping("/customer-deposits/{id}")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return deposits.get(id);
    }

    @PostMapping("/customer-deposits")
    @Idempotent
    @RequirePermissions(Permissions.SALES__DEPOSIT_RECORD)
    public Map<String, Object> receive(@RequestBody Map<String, Object> b) {
        return deposits.receive(new DepositInput(s(b, "partnerId"), s(b, "salesOrderId"), d(b, "amount"),
                s(b, "paymentMethod"), s(b, "accountId"), s(b, "receivedDate"), s(b, "reference"), s(b, "notes")));
    }

    @PostMapping("/customer-deposits/{id}/apply")
    @Idempotent
    @RequirePermissions(Permissions.SALES__DEPOSIT_RECORD)
    public Map<String, Object> apply(@PathVariable String id, @RequestBody Map<String, Object> b) {
        return deposits.apply(id, s(b, "documentId"), d(b, "amount"));
    }

    @PostMapping("/customer-deposits/{id}/refund")
    @Idempotent
    @RequirePermissions(Permissions.SALES__DEPOSIT_REFUND)
    public Map<String, Object> refund(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return deposits.refund(id, d(b, "amount"), s(b, "paymentMethod"), s(b, "accountId"), s(b, "reason"));
    }

    @PostMapping("/customer-deposits/{id}/void")
    @Idempotent
    @RequirePermissions(Permissions.SALES__DEPOSIT_REFUND)
    public Map<String, Object> voidDeposit(@PathVariable String id, @RequestBody(required = false) Map<String, Object> b) {
        return deposits.voidDeposit(id, s(b, "reason"));
    }

    @GetMapping("/payments/mobile-money/providers")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public List<Map<String, Object>> providers() {
        return mobileMoney.providers();
    }

    @PostMapping("/payments/mobile-money")
    @Idempotent
    @RequirePermissions(Permissions.PAYMENT__CREATE)
    public Map<String, Object> initiate(@RequestBody Map<String, Object> b) {
        return mobileMoney.initiate(body(b));
    }

    @GetMapping("/payments/mobile-money/{id}")
    @RequirePermissions(Permissions.PAYMENT__READ)
    public Map<String, Object> request(@PathVariable String id) {
        return mobileMoney.get(id);
    }

    @PostMapping("/payments/mobile-money/{id}/refresh")
    @RequirePermissions(Permissions.PAYMENT__CREATE)
    public Map<String, Object> refresh(@PathVariable String id) {
        return mobileMoney.refresh(id);
    }
}
