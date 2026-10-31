package com.inventoryerp.core.web;

import com.inventoryerp.core.application.BranchScopeService;
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

/** {@code /user-data-scopes/:userId} (BR-1): which branches and locations a user is restricted to. */
@RestController
@RequestMapping("/user-data-scopes")
public class UserDataScopeController {

    /** Empty lists lift the restriction. */
    public record SaveScopeRequest(List<String> branchIds, List<String> locationIds) {}

    private final BranchScopeService scopes;

    public UserDataScopeController(BranchScopeService scopes) {
        this.scopes = scopes;
    }

    @GetMapping("/{userId}")
    @RequirePermissions(Permissions.USER__READ)
    public Map<String, Object> get(@PathVariable String userId) {
        return scopes.grants(userId);
    }

    @PutMapping("/{userId}")
    @RequirePermissions(Permissions.USER__UPDATE)
    public Map<String, Object> save(@PathVariable String userId, @Valid @RequestBody SaveScopeRequest dto) {
        return scopes.saveGrants(userId, dto.branchIds(), dto.locationIds());
    }
}
