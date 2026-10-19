package com.inventoryerp.core.web;

import com.inventoryerp.core.application.PaymentTermService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /payment-terms} (port of {@code payment-term.controller.ts}). PATCH takes Nest's
 * {@code Partial<UpsertPaymentTermDto>}, which the ValidationPipe does not validate (erased generic).
 */
@RestController
@RequestMapping("/payment-terms")
public class PaymentTermController {

    /** {@code UpsertPaymentTermDto}. */
    public record UpsertPaymentTermRequest(
            @NotNull(message = "must be a string") String code,
            @NotNull(message = "must be a string") String name,
            @OneOf(value = {"immediate", "net_days", "end_of_following_month"},
                    message = "must be one of the following values: immediate, net_days, end_of_following_month")
            String method,
            @Min(value = 0, message = "must not be less than 0") Integer netDays,
            @Min(value = 0, message = "must not be less than 0") Integer discountDays,
            BigDecimal discountPercent,
            Boolean isActive,
            @Min(value = 0, message = "must not be less than 0") Integer sortOrder) {}

    private final PaymentTermService terms;

    public PaymentTermController(PaymentTermService terms) {
        this.terms = terms;
    }

    @GetMapping
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public List<Map<String, Object>> list() {
        return terms.list();
    }

    @GetMapping("/deleted")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public List<Map<String, Object>> deleted() {
        return terms.deleted();
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public Map<String, Object> get(@PathVariable String id) {
        return terms.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ACCOUNT__CREATE)
    public Map<String, Object> create(@Valid @RequestBody UpsertPaymentTermRequest dto) {
        return terms.create(new PaymentTermService.PaymentTermInput(dto.code(), dto.name(), dto.method(), dto.netDays(),
                dto.discountDays(), dto.discountPercent(), dto.isActive(), dto.sortOrder()));
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return terms.update(id, dto == null ? Map.of() : dto);
    }

    @PatchMapping("/{id}/restore")
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> restore(@PathVariable String id) {
        return terms.restore(id);
    }

    @DeleteMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__DELETE)
    public Map<String, Object> remove(@PathVariable String id) {
        return terms.remove(id);
    }
}
