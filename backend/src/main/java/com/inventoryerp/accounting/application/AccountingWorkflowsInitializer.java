package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.workflow.WorkflowDefinition;
import com.inventoryerp.kernel.workflow.WorkflowDefinition.Transition;
import com.inventoryerp.kernel.workflow.WorkflowRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Registers the {@code journal_entry} workflow (ADR-007; port of {@code accounting-workflows.initializer.ts}).
 * Owned by the accounting module because reversing a journal entry is the GL engine's job.
 */
@Component
public class AccountingWorkflowsInitializer {

    private final WorkflowRegistry registry;
    private final PostingService posting;

    public AccountingWorkflowsInitializer(WorkflowRegistry registry, PostingService posting) {
        this.registry = registry;
        this.posting = posting;
    }

    @PostConstruct
    void register() {
        registry.register(new WorkflowDefinition("journal_entry", "draft", List.of(
                new Transition("posted", "reversed", "reverse", "journal_entry:reverse", null,
                        ctx -> posting.reverse(ctx.entityId(), null, "Reversal of " + ctx.entity().get("entryNumber")),
                        null))));
    }
}
