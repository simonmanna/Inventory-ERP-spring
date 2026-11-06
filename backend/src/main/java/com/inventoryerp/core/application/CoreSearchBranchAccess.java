package com.inventoryerp.core.application;

import com.inventoryerp.kernel.search.SearchBranchAccess;
import org.springframework.stereotype.Component;

/** Global search applies the same data scope as the document lists. */
@Component
class CoreSearchBranchAccess implements SearchBranchAccess {

    private final BranchScopeService scopes;

    CoreSearchBranchAccess(BranchScopeService scopes) {
        this.scopes = scopes;
    }

    @Override
    public boolean restricted() {
        return scopes.scope().restricted();
    }

    @Override
    public boolean allowsBranch(String branchId) {
        return scopes.scope().allowsBranch(branchId);
    }
}
