package com.inventoryerp.pos.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosInvoiceService;
import com.inventoryerp.pos.web.PosOrderDtos.ReceivePaymentRequest;
import com.inventoryerp.pos.web.PosOrderDtos.RefundInvoiceRequest;
import com.inventoryerp.pos.web.PosOrderDtos.SettleCreditRequest;
import com.inventoryerp.pos.web.PosOrderDtos.WriteOffRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/invoices} (port of {@code PosBillingController} in {@code order/pos-orders.controller.ts}). */
@RestController
@RequestMapping("/pos/invoices")
public class PosBillingController {

    private final PosInvoiceService billing;

    public PosBillingController(PosInvoiceService billing) {
        this.billing = billing;
    }

    @PostMapping("/{id}/payments")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> receivePayment(@PathVariable String id, @Valid @RequestBody ReceivePaymentRequest dto) {
        return billing.receivePayment(id, PosDtos.data(dto));
    }

    @PostMapping("/{id}/credit")
    @Idempotent(required = true)
    @RequirePermissions({Permissions.POS__CHECKOUT, Permissions.POS__CREDIT})
    public Map<String, Object> settleCredit(@PathVariable String id, @Valid @RequestBody SettleCreditRequest dto) {
        return billing.settleCredit(id, PosDtos.data(dto));
    }

    @PostMapping("/{id}/refund")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__REFUND)
    @SuppressWarnings("unchecked")
    public Map<String, Object> refund(@PathVariable String id, @Valid @RequestBody RefundInvoiceRequest dto) {
        List<Map<String, Object>> lines = dto.lines() == null ? null : (List<Map<String, Object>>) PosDtos.js(dto.lines());
        return billing.refund(id, dto.reason(), dto.overrideById(), dto.overridePin(), dto.stockDisposition(),
                dto.cashSessionId(), lines);
    }

    @PostMapping("/{id}/write-off")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.POS__WRITE_OFF)
    public Map<String, Object> writeOff(@PathVariable String id, @Valid @RequestBody WriteOffRequest dto) {
        return billing.writeOff(id, PosDtos.data(dto));
    }
}
