package com.inventoryerp.license.web;

import com.inventoryerp.license.application.AdminAudits;
import com.inventoryerp.license.application.AdminTokens;
import com.inventoryerp.license.application.CustomerService;
import com.inventoryerp.license.domain.AdminRole;
import com.inventoryerp.license.shared.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customers. Readable by any admin; changing them is {@code owner} work. */
@RestController
@RequestMapping("/admin/customers")
@RequireAdmin
public class AdminCustomerController {

    private final CustomerService customers;
    private final AdminAudits audits;

    public AdminCustomerController(CustomerService customers, AdminAudits audits) {
        this.customers = customers;
        this.audits = audits;
    }

    @GetMapping
    public PageResult<AdminDtos.CustomerResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return PageResult.of(customers.search(q, page, pageSize), AdminDtos.CustomerResponse::of);
    }

    @GetMapping("/{id}")
    public AdminDtos.CustomerResponse get(@PathVariable String id) {
        return AdminDtos.CustomerResponse.of(customers.get(id));
    }

    @PostMapping
    @RequireAdmin(AdminRole.OWNER)
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.CustomerResponse create(@Valid @RequestBody AdminDtos.CustomerRequest body,
            AdminTokens.Principal actor, HttpServletRequest request) {
        var created = customers.create(body.name(), body.email(), body.phone(), body.country(), body.taxId(),
                body.notes());
        audits.record(actor, "customer.create", created.getId(), ClientRateLimitInterceptor.clientIp(request),
                Map.of("name", created.getName()));
        return AdminDtos.CustomerResponse.of(created);
    }

    @PutMapping("/{id}")
    @RequireAdmin(AdminRole.OWNER)
    public AdminDtos.CustomerResponse update(@PathVariable String id,
            @Valid @RequestBody AdminDtos.CustomerRequest body, AdminTokens.Principal actor,
            HttpServletRequest request) {
        var updated = customers.update(id, body.name(), body.email(), body.phone(), body.country(), body.taxId(),
                body.notes());
        audits.record(actor, "customer.update", id, ClientRateLimitInterceptor.clientIp(request), Map.of());
        return AdminDtos.CustomerResponse.of(updated);
    }

    @DeleteMapping("/{id}")
    @RequireAdmin(AdminRole.OWNER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, AdminTokens.Principal actor, HttpServletRequest request) {
        customers.delete(id);
        audits.record(actor, "customer.delete", id, ClientRateLimitInterceptor.clientIp(request), Map.of());
    }
}
