package com.inventoryerp.portal.web;

import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.portal.application.PortalAccountService;
import com.inventoryerp.portal.application.PortalAccountService.AccountInput;
import com.inventoryerp.portal.application.PortalAccountService.PasswordInput;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Staff administration of customer portal logins (PORTAL-1). */
@RestController
@RequestMapping("/portal-accounts")
@RequiresModule("portal")
@ConditionalOnProperty(name = "app.modules.portal", havingValue = "true")
@Validated
public class PortalAccountController {

    private final PortalAccountService accounts;

    public PortalAccountController(PortalAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @RequirePermissions(Permissions.PORTAL__MANAGE)
    public PageResult<Map<String, Object>> list(@RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return accounts.list(partnerId, search, page, pageSize);
    }

    @GetMapping("/customers")
    @RequirePermissions(Permissions.PORTAL__MANAGE)
    public List<Map<String, Object>> customers(@RequestParam(required = false) String search) {
        return accounts.customers(search);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PORTAL__MANAGE)
    public Map<String, Object> create(@Valid @RequestBody AccountInput body) {
        return accounts.create(body);
    }

    @PostMapping("/{id}/disable")
    @RequirePermissions(Permissions.PORTAL__MANAGE)
    public Map<String, Object> disable(@PathVariable String id) {
        return accounts.setActive(id, false);
    }

    @PostMapping("/{id}/enable")
    @RequirePermissions(Permissions.PORTAL__MANAGE)
    public Map<String, Object> enable(@PathVariable String id) {
        return accounts.setActive(id, true);
    }

    @PostMapping("/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.PORTAL__MANAGE)
    public void password(@PathVariable String id, @Valid @RequestBody PasswordInput body) {
        accounts.resetPassword(id, body.password());
    }
}
