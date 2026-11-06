package com.inventoryerp.kernel.search;

/** The current user's branch restriction, supplied by the module that owns data scopes. */
public interface SearchBranchAccess {

    /** True when the user may only see some branches. */
    boolean restricted();

    /** True when a record in {@code branchId} (null = no branch) is visible to the user. */
    boolean allowsBranch(String branchId);
}
