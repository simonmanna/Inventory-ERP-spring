package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one-call hook for a sensitive action: approval engine first, then separation of duties. Use the pair key of
 * {@link SegregationOfDutiesService} as {@code pairKey}. It is also the approval entity type, so an owner gates the
 * action just by adding a workflow for it, and listens to {@code approval.decided} for that type if the decision must
 * do something by itself. The caller stops when {@link Outcome#allowed()} is false and continues on a repeat call
 * after the approval. The approval request is committed in its own transaction, so it survives the caller's refusal.
 *
 * <p>Credit overrides (lane C) and supplier bill approval (lane D) call this. Nothing in kernel calls it for them.
 */
@Service
public class ControlledActionService {

    /** The action to clear. {@code details} are extra snapshot keys for the approver; any may be null. */
    public record Action(String pairKey, String auditEntity, String entityId, BigDecimal amount, String branchId,
            String departmentId, Collection<String> makerIds, Map<String, Object> details) {}

    /** {@code allowed}, or the pending request the caller should report. */
    public record Outcome(boolean allowed, String approvalRequestId) {}

    private final ApprovalsService approvals;
    private final SegregationOfDutiesService sod;
    private final TransactionTemplate newTx;

    public ControlledActionService(ApprovalsService approvals, SegregationOfDutiesService sod,
            PlatformTransactionManager txm) {
        this.approvals = approvals;
        this.sod = sod;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Clear the action, or return the pending request. Throws a 403 on a separation-of-duties refusal. */
    public Outcome authorize(Action a) {
        Collection<String> makers = a.makerIds() == null ? List.of() : a.makerIds();
        Map<String, Object> snapshot = Obj.of("amount", a.amount(), "branchId", a.branchId(), "departmentId",
                a.departmentId(), "makerIds", makers.stream().filter(Objects::nonNull).distinct()
                        .collect(Collectors.joining(",")));
        if (a.details() != null) {
            snapshot.putAll(a.details());
        }
        Map<String, Object> gate = newTx.execute(s -> approvals.checkOrRequestApproval(a.pairKey(), a.entityId(),
                snapshot));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            return new Outcome(false, (String) gate.get("requestId"));
        }
        sod.assertSeparated(a.pairKey(), a.auditEntity(), a.entityId(), makers);
        return new Outcome(true, null);
    }
}
