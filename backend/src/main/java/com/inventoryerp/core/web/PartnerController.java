package com.inventoryerp.core.web;

import com.inventoryerp.core.application.PartnerService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /partners} (port of {@code partner.controller.ts}). */
@RestController
@RequestMapping("/partners")
public class PartnerController {

    /** {@code CreatePartnerDto}. */
    public record CreatePartnerRequest(
            String code,
            @NotNull(message = "must be a string") @NotBlank(message = "should not be empty") String name,
            Boolean isCompany,
            Boolean isCustomer,
            Boolean isSupplier,
            Boolean isEmployee,
            @Email(message = "must be an email") String email,
            String phone,
            @OneOf({"bronze", "silver", "gold"}) String membershipLevel,
            @OneOf({"male", "female", "other", "prefer_not_to_say"}) String gender,
            String website,
            String taxNumber,
            @OneOf({"active", "inactive", "archived"}) String status,
            String categoryId,
            String paymentTermId,
            String notes,
            Map<String, Object> customFields,
            List<Map<String, Object>> contacts,
            List<Map<String, Object>> addresses) {}

    /** {@code UpdatePartnerDto}. */
    public record UpdatePartnerRequest(
            String code,
            String name,
            Boolean isCompany,
            Boolean isCustomer,
            Boolean isSupplier,
            Boolean isEmployee,
            @Email(message = "must be an email") String email,
            String phone,
            String membershipLevel,
            String gender,
            String website,
            String taxNumber,
            @OneOf({"active", "inactive", "archived"}) String status,
            String categoryId,
            String paymentTermId,
            String notes,
            Map<String, Object> customFields,
            List<Map<String, Object>> contacts,
            List<Map<String, Object>> addresses) {}

    private final PartnerService partners;

    public PartnerController(PartnerService partners) {
        this.partners = partners;
    }

    @GetMapping
    @RequirePermissions(Permissions.PARTNER__READ)
    public PageResult<Map<String, Object>> list(@RequestParam Map<String, String> query) {
        return partners.list(QueryParams.page(query));
    }

    @GetMapping("/{id}")
    @RequirePermissions(Permissions.PARTNER__READ)
    public Map<String, Object> findOne(@PathVariable String id) {
        return partners.findOne(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PARTNER__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreatePartnerRequest dto) {
        return partners.create(dto, dto.contacts(), dto.addresses());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.PARTNER__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdatePartnerRequest dto) {
        return partners.update(id, dto, dto.contacts(), dto.addresses());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.PARTNER__DELETE)
    public void remove(@PathVariable String id) {
        partners.remove(id);
    }
}
