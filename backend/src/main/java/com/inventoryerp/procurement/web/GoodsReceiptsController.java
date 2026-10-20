package com.inventoryerp.procurement.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.procurement.application.GoodsReceiptsService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /procurement/goods-receipts} (port of {@code goods-receipts.controller.ts}). The create bodies are untyped
 * interfaces in Nest, so they are not validated here either.
 */
@RestController
@RequestMapping("/procurement/goods-receipts")
public class GoodsReceiptsController {

    private final GoodsReceiptsService svc;

    public GoodsReceiptsController(GoodsReceiptsService svc) {
        this.svc = svc;
    }

    /** Capture + post an ad-hoc stock-in (draft only without {@code goods_receipt:post} or pending approval). */
    @PostMapping("/adhoc")
    @Idempotent
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__CREATE)
    public Map<String, Object> createAdhoc(@RequestBody(required = false) Map<String, Object> body) {
        return svc.createAdhoc(body == null ? Map.of() : body);
    }

    /** Draft-only GRN: stock does not move until {@link #post}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__CREATE)
    public Map<String, Object> createDraft(@RequestBody(required = false) Map<String, Object> body) {
        return svc.createDraft(body == null ? Map.of() : body);
    }

    /** The irreversible step: moves stock, capitalises inventory and (PO receipts) vouchers the payable. */
    @PatchMapping("/{id}/post")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__POST)
    public Map<String, Object> post(@PathVariable String id) {
        return svc.post(id);
    }

    @GetMapping
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__READ)
    public Map<String, Object> list(@RequestParam(required = false) String page,
            @RequestParam(required = false) String pageSize, @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        return svc.list(page, pageSize, search, status);
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return svc.findOne(id);
    }

    /** Posted reversal; refused once billed / paid / landed-costed. */
    @PatchMapping("/{id}/reverse")
    @Idempotent
    @RequirePermissions(Permissions.PROCUREMENT__GOODS_RECEIPT__CANCEL)
    public Map<String, Object> reverse(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        if (reason != null && !(reason instanceof String)) {
            // `reason?.trim` is not a function on a non-string → Nest answers 500.
            throw new IllegalStateException("reason?.trim is not a function");
        }
        return svc.reverse(id, (String) reason);
    }
}
