package com.inventoryerp.kernel.approvals;

import java.util.Map;

/** Applies one kind of controlled configuration change once it is approved. Runs in the deciding user's context. */
public interface ConfigChangeApplier {

    /** Whether this applier owns the change type. */
    boolean supports(String changeType);

    /** Make the change. {@code payload} is the JSON body held for approval. */
    void apply(String changeType, String targetId, Map<String, Object> payload);
}
