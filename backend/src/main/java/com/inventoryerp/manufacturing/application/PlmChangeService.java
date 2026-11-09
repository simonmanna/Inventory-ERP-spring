package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.ReviseBom;
import com.inventoryerp.manufacturing.application.MfgCommands.RoutingOperationInput;
import com.inventoryerp.manufacturing.application.PlmCommands.CreateChange;
import com.inventoryerp.manufacturing.application.PlmCommands.Decide;
import com.inventoryerp.manufacturing.application.PlmCommands.UpdateChange;
import com.inventoryerp.manufacturing.domain.Bom;
import com.inventoryerp.manufacturing.domain.EngineeringChange;
import com.inventoryerp.manufacturing.domain.PlmChangeActivity;
import com.inventoryerp.manufacturing.domain.PlmChangeApproval;
import com.inventoryerp.manufacturing.domain.PlmChangeRequest;
import com.inventoryerp.manufacturing.domain.Routing;
import com.inventoryerp.manufacturing.domain.RoutingOperation;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Engineering change orders under PLM (ADR-S063). An ECO is typed:
 * <ul>
 *   <li>{@code bom} — a new draft BOM version (ADR-S006 revise); apply activates it and archives the old one;</li>
 *   <li>{@code routing} — an inactive copy of a routing, edited under the ECO; apply activates it and re-points the
 *       product's BOMs;</li>
 *   <li>{@code product} — a lifecycle transition of a product under PLM;</li>
 *   <li>{@code document} — draft engineering documents, released on apply.</li>
 * </ul>
 * It moves through the organization's stages; a stage that requires approval is left forwards only when its
 * required approvers (or, without named approvers, any holder of {@code plm:approve}) approved and nobody
 * rejected. The last stage means {@code approved}. Apply honours the effectivity date (a nightly job applies due
 * ECOs), releases attached documents and bumps the product revision.
 */
@Service
public class PlmChangeService {

    private final MfgDb db;
    private final PlmSupport plm;
    private final PlmStageService stages;
    private final EngineeringService engineering;
    private final BomService boms;
    private final CostingService costing;
    private final PlmLifecycleService lifecycle;
    private final PlmDocumentService documents;
    private final SequenceService seq;
    private final TransactionTemplate isolated;
    private final PlmDispositionService dispositions;

    public PlmChangeService(MfgDb db, PlmSupport plm, PlmStageService stages, EngineeringService engineering,
            BomService boms, CostingService costing, PlmLifecycleService lifecycle, PlmDocumentService documents,
            SequenceService seq, PlatformTransactionManager txm, PlmDispositionService dispositions) {
        this.dispositions = dispositions;
        this.isolated = new TransactionTemplate(txm);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolated.setReadOnly(true);
        this.db = db;
        this.plm = plm;
        this.stages = stages;
        this.engineering = engineering;
        this.boms = boms;
        this.costing = costing;
        this.lifecycle = lifecycle;
        this.documents = documents;
        this.seq = seq;
    }

    // ================================================================== queries

    @Transactional
    public List<Map<String, Object>> list(String status, String stageId, String type, String productId, String q) {
        stages.ordered();
        StringBuilder sql = new StringBuilder("SELECT e.*, s.name AS \"stageName\", s.sequence AS \"stageSequence\","
                + " r.number AS \"requestNumber\" FROM \"EngineeringChange\" e LEFT JOIN \"PlmChangeStage\" s ON s.id ="
                + " e.\"stageId\" LEFT JOIN \"PlmChangeRequest\" r ON r.id = e.\"requestId\" WHERE"
                + " e.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        if ("open".equals(status)) {
            sql.append(" AND e.status IN ('draft', 'approved')");
        } else if (status != null && !status.isBlank()) {
            sql.append(" AND e.status = ?");
            params.add(status);
        }
        for (String[] f : new String[][] {{"stageId", stageId}, {"type", type}, {"productId", productId}}) {
            if (f[1] != null && !f[1].isBlank()) {
                sql.append(" AND e.\"").append(f[0]).append("\" = ?");
                params.add(f[1]);
            }
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (e.number ILIKE ? OR e.title ILIKE ?)");
            params.add("%" + q.trim() + "%");
            params.add("%" + q.trim() + "%");
        }
        sql.append(" ORDER BY e.\"createdAt\" DESC LIMIT 500");
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(sql.toString()).params(params));
        Set<String> products = new HashSet<>();
        Set<String> users = new HashSet<>();
        rows.forEach(r -> {
            products.add(str(r, "productId"));
            users.add(str(r, "ownerId"));
        });
        Map<String, Map<String, Object>> p = plm.products(products);
        Map<String, String> names = plm.userNames(users);
        rows.forEach(r -> {
            r.put("product", p.get(str(r, "productId")));
            r.put("ownerName", names.get(str(r, "ownerId")));
        });
        return rows;
    }

    /** The ECO with stage, approvals, comparison, documents, request and activity. */
    @Transactional
    public Map<String, Object> get(String id) {
        Map<String, Object> eco = engineering.get(id);
        List<Map<String, Object>> all = stages.ordered();
        Map<String, Object> stage = stages.stageOf(eco);
        Map<String, Object> out = new LinkedHashMap<>(eco);
        nameComparisonLines(out.get("comparison"));
        out.put("stage", stage);
        out.put("stages", all);
        out.put("product", plm.productOrNull(str(eco, "productId")));
        out.put("routingComparison", eco.get("routingId") != null && eco.get("newRoutingId") != null
                ? compareRoutings(str(eco, "routingId"), str(eco, "newRoutingId")) : null);
        out.put("newRouting", eco.get("newRoutingId") == null ? null : routing(str(eco, "newRoutingId")));
        out.put("documents", documents.forChange(id));
        out.put("request", eco.get("requestId") == null ? null : db.byId(PlmChangeRequest.class,
                str(eco, "requestId")));
        Map<String, Object> item = eco.get("productId") == null ? null : lifecycle.byProduct(str(eco, "productId"));
        out.put("plmItem", item);
        out.put("approval", approvalState(eco, stage));
        out.put("pending", "approved".equals(eco.get("status")) ? pendingReason(eco, false) : null);

        List<Map<String, Object>> approvals = db.list(PlmChangeApproval.class, " AND \"changeId\" = ?"
                + " ORDER BY \"createdAt\" DESC", id);
        List<Map<String, Object>> activity = db.list(PlmChangeActivity.class, " AND \"changeId\" = ?"
                + " ORDER BY \"createdAt\" DESC", id);
        Set<String> users = new HashSet<>();
        users.add(str(eco, "ownerId"));
        users.add(str(eco, "approvedById"));
        users.add(str(eco, "createdBy"));
        approvals.forEach(a -> users.add(str(a, "userId")));
        activity.forEach(a -> users.add(str(a, "userId")));
        Map<String, String> names = plm.userNames(users);
        Map<String, String> stageNames = new LinkedHashMap<>();
        all.forEach(s -> stageNames.put(str(s, "id"), str(s, "name")));
        approvals.forEach(a -> {
            a.put("userName", names.get(str(a, "userId")));
            a.put("stageName", stageNames.get(str(a, "stageId")));
        });
        activity.forEach(a -> a.put("userName", names.get(str(a, "userId"))));
        out.put("ownerName", names.get(str(eco, "ownerId")));
        out.put("approvedByName", names.get(str(eco, "approvedById")));
        out.put("createdByName", names.get(str(eco, "createdBy")));
        out.put("approvals", approvals);
        out.put("activity", activity);
        return out;
    }

    /** Add {@code product: {id, code, name}} to every line of a BOM comparison. */
    @SuppressWarnings("unchecked")
    private void nameComparisonLines(Object comparison) {
        if (!(comparison instanceof Map<?, ?> c)) {
            return;
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (String k : List.of("added", "removed", "changed")) {
            lines.addAll((List<Map<String, Object>>) c.get(k));
        }
        Set<String> ids = new HashSet<>();
        lines.forEach(l -> ids.add(str(l, "componentProductId")));
        Map<String, Map<String, Object>> p = plm.products(ids);
        lines.forEach(l -> l.put("product", p.get(str(l, "componentProductId"))));
    }

    // ================================================================== create / edit

    @Transactional
    public Map<String, Object> create(CreateChange dto) {
        plm.requireUser(PlmSupport.blank(dto.ownerId()), "ownerId");
        if ("date".equals(dto.effectivity()) && PlmSupport.date(dto.effectiveDate()) == null) {
            throw ApiException.badRequest("effectiveDate is required when effectivity is date.");
        }
        Map<String, Object> request = null;
        if (PlmSupport.blank(dto.requestId()) != null) {
            request = db.byId(PlmChangeRequest.class, dto.requestId());
            if (request == null) {
                throw ApiException.notFound("Change request not found");
            }
            if (!Set.of("new", "accepted").contains(str(request, "status"))) {
                throw ApiException.badRequest("Change request " + request.get("number") + " is "
                        + request.get("status") + ".");
            }
        }
        String id;
        String productId;
        switch (dto.type()) {
            case "bom" -> {
                if (PlmSupport.blank(dto.bomId()) == null) {
                    throw ApiException.badRequest("bomId is required for a BOM change.");
                }
                Map<String, Object> revised = engineering.revise(dto.bomId(), new ReviseBom(PlmSupport.blank(
                        dto.title()), PlmSupport.blank(dto.reason())));
                @SuppressWarnings("unchecked")
                Map<String, Object> eco = (Map<String, Object>) revised.get("change");
                id = str(eco, "id");
                productId = str(eco, "productId");
            }
            case "routing" -> {
                if (PlmSupport.blank(dto.routingId()) == null) {
                    throw ApiException.badRequest("routingId is required for a routing change.");
                }
                Map<String, Object> old = routing(dto.routingId());
                List<Map<String, Object>> open = db.list(EngineeringChange.class, " AND \"routingId\" = ? AND status IN"
                        + " ('draft', 'approved')", dto.routingId());
                if (!open.isEmpty()) {
                    throw ApiException.badRequest("Routing " + old.get("name") + " already has an open change "
                            + open.getFirst().get("number") + ".");
                }
                productId = PlmSupport.blank(dto.productId()) != null ? dto.productId()
                        : old.get("productId") != null ? str(old, "productId") : outputOfRouting(dto.routingId());
                if (productId != null) {
                    plm.product(productId);
                }
                String number = seq.next("engineering_change", "ECO-", 5);
                Map<String, Object> copy = cloneRouting(old, number);
                id = insertChange(number, dto, "routing", productId, Obj.of("routingId", old.get("id"),
                        "newRoutingId", copy.get("id")), "Revision of routing " + old.get("name"));
                plm.activity(id, "created", "Created from routing " + old.get("name") + "; new inactive copy "
                        + copy.get("name") + ".");
            }
            case "product" -> {
                if (PlmSupport.blank(dto.productId()) == null) {
                    throw ApiException.badRequest("productId is required for a product change.");
                }
                if (dto.targetLifecycleState() == null) {
                    throw ApiException.badRequest("targetLifecycleState is required for a product change.");
                }
                productId = dto.productId();
                Map<String, Object> product = plm.product(productId);
                Map<String, Object> item = lifecycle.byProduct(productId);
                if (item == null) {
                    throw ApiException.badRequest("Product " + product.get("code")
                            + " is not under lifecycle management; register it first.");
                }
                checkTransition(item, dto.targetLifecycleState(), product);
                String number = seq.next("engineering_change", "ECO-", 5);
                id = insertChange(number, dto, "product", productId, Obj.of("targetLifecycleState",
                        dto.targetLifecycleState()), "Lifecycle of " + product.get("code") + " → "
                        + PlmLifecycleService.human(dto.targetLifecycleState()));
                plm.activity(id, "created", "Lifecycle change of " + PlmSupport.label(product) + ": "
                        + PlmLifecycleService.human(str(item, "lifecycleState")) + " → "
                        + PlmLifecycleService.human(dto.targetLifecycleState()) + ".");
            }
            default -> {
                if (dto.documentIds() == null || dto.documentIds().isEmpty()) {
                    throw ApiException.badRequest("documentIds is required for a document change.");
                }
                productId = PlmSupport.blank(dto.productId());
                if (productId != null) {
                    plm.product(productId);
                }
                String number = seq.next("engineering_change", "ECO-", 5);
                id = insertChange(number, dto, "document", productId, Obj.of(), "Document change");
                plm.activity(id, "created", "Document change of " + dto.documentIds().size() + " document(s).");
            }
        }
        // Fields every type shares (the BOM path created its row through revise()).
        Map<String, Object> data = new LinkedHashMap<>();
        if ("bom".equals(dto.type())) {
            data.putAll(common(dto));
        }
        if (request != null) {
            data.put("requestId", request.get("id"));
        }
        if (!data.isEmpty()) {
            db.update(EngineeringChange.class, id, data);
        }
        if (dto.documentIds() != null && !dto.documentIds().isEmpty()) {
            documents.linkToChange(id, productId, dto.documentIds());
        }
        if (request != null) {
            db.update(PlmChangeRequest.class, str(request, "id"), Obj.of("status", "converted", "changeId", id,
                    "reviewedById", request.get("reviewedById") != null ? request.get("reviewedById")
                            : TenantContext.userId(), "reviewedAt", request.get("reviewedAt") != null
                                    ? request.get("reviewedAt") : Instant.now()));
            plm.activity(id, "created", "From change request " + request.get("number") + ".");
        }
        if (!"bom".equals(dto.type())) {
            Map<String, Object> eco = engineering.require(id);
            plm.event(DomainEvents.PLM_CHANGE_CREATED, Obj.of("changeId", id, "number", eco.get("number"), "type",
                    dto.type(), "productId", productId));
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, UpdateChange dto) {
        Map<String, Object> eco = engineering.require(id);
        if (!"draft".equals(eco.get("status"))) {
            throw ApiException.badRequest("Only a draft change can be edited (status " + eco.get("status") + ").");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.title() != null) {
            String t = PlmSupport.blank(dto.title());
            if (t == null) {
                throw ApiException.badRequest("title should not be empty");
            }
            data.put("title", t);
        }
        if (dto.description() != null) {
            data.put("description", PlmSupport.blank(dto.description()));
        }
        if (dto.reason() != null) {
            data.put("reason", PlmSupport.blank(dto.reason()));
        }
        if (dto.priority() != null) {
            data.put("priority", dto.priority());
        }
        if (dto.ownerId() != null) {
            plm.requireUser(PlmSupport.blank(dto.ownerId()), "ownerId");
            data.put("ownerId", PlmSupport.blank(dto.ownerId()));
        }
        if (dto.targetLifecycleState() != null) {
            if (!"product".equals(eco.get("type"))) {
                throw ApiException.badRequest("targetLifecycleState only applies to a product change.");
            }
            Map<String, Object> product = plm.product(str(eco, "productId"));
            checkTransition(lifecycle.byProduct(str(eco, "productId")), dto.targetLifecycleState(), product);
            data.put("targetLifecycleState", dto.targetLifecycleState());
        }
        if (dto.effectivity() != null) {
            data.put("effectivity", dto.effectivity());
        }
        if (dto.effectiveDate() != null) {
            data.put("effectiveDate", PlmSupport.date(dto.effectiveDate()));
        }
        if (dto.stockDisposition() != null) {
            data.put("stockDisposition", dto.stockDisposition());
        }
        if (dto.wipDisposition() != null) {
            data.put("wipDisposition", dto.wipDisposition());
        }
        if (dto.newRevision() != null) {
            data.put("newRevision", PlmSupport.blank(dto.newRevision()));
        }
        String effectivity = data.containsKey("effectivity") ? (String) data.get("effectivity")
                : str(eco, "effectivity");
        Object date = data.containsKey("effectiveDate") ? data.get("effectiveDate") : eco.get("effectiveDate");
        if ("date".equals(effectivity) && date == null) {
            throw ApiException.badRequest("effectiveDate is required when effectivity is date.");
        }
        if (!data.isEmpty()) {
            db.update(EngineeringChange.class, id, data);
            plm.activity(id, "edited", "Edited " + String.join(", ", data.keySet()) + ".");
        }
        return get(id);
    }

    /** Replace the operations of a routing ECO's new routing (routings are otherwise immutable). */
    @Transactional
    public Map<String, Object> saveRoutingOperations(String id, List<RoutingOperationInput> operations) {
        Map<String, Object> eco = engineering.require(id);
        if (!"routing".equals(eco.get("type")) || eco.get("newRoutingId") == null) {
            throw ApiException.badRequest("Change " + eco.get("number") + " is not a routing change.");
        }
        if (!"draft".equals(eco.get("status"))) {
            throw ApiException.badRequest("Only a draft change can be edited (status " + eco.get("status") + ").");
        }
        Set<Integer> sequences = new HashSet<>();
        for (RoutingOperationInput o : operations) {
            if (!sequences.add(o.sequence())) {
                throw ApiException.badRequest("Operation sequence " + o.sequence() + " is used twice.");
            }
        }
        String newRouting = str(eco, "newRoutingId");
        db.jdbc().sql("DELETE FROM \"RoutingStageSetup\" WHERE \"organizationId\" = ? AND \"routingOperationId\" IN"
                + " (SELECT id FROM \"RoutingOperation\" WHERE \"routingId\" = ?)").params(MfgDb.org(), newRouting)
                .update();
        db.jdbc().sql("DELETE FROM \"RoutingOperation\" WHERE \"organizationId\" = ? AND \"routingId\" = ?")
                .params(MfgDb.org(), newRouting).update();
        for (RoutingOperationInput o : operations) {
            db.insert(RoutingOperation.class, Obj.of("routingId", newRouting, "sequence", o.sequence(), "name",
                    o.name(), "workCenterId", PlmSupport.blank(o.workCenterId()), "durationMins",
                    o.durationMins() != null ? o.durationMins() : 0, "setupMins", o.setupMins() != null
                            ? o.setupMins() : 0, "instructions", o.instructions(), "isSubcontracted",
                    Boolean.TRUE.equals(o.isSubcontracted()), "subcontractorId", PlmSupport.blank(o.subcontractorId()),
                    "subcontractCostPerUnit", o.subcontractCostPerUnit() != null ? o.subcontractCostPerUnit()
                            : BigDecimal.ZERO));
        }
        copyStageSetups(str(eco, "routingId"), newRouting);
        plm.activity(id, "edited", "Routing operations updated (" + operations.size() + ").");
        return get(id);
    }

    @Transactional
    public Map<String, Object> comment(String id, String message) {
        engineering.require(id);
        plm.activity(id, "comment", message.trim());
        return get(id);
    }

    @Transactional
    public Map<String, Object> linkDocuments(String id, List<String> documentIds) {
        Map<String, Object> eco = engineering.require(id);
        if (!"draft".equals(eco.get("status"))) {
            throw ApiException.badRequest("Only a draft change can be edited (status " + eco.get("status") + ").");
        }
        documents.linkToChange(id, str(eco, "productId"), documentIds);
        plm.activity(id, "edited", "Attached " + documentIds.size() + " document(s).");
        return get(id);
    }

    // ================================================================== stages & approvals

    /** Move to another stage: forwards only through satisfied approval stages; backwards voids later approvals. */
    @Transactional
    public Map<String, Object> moveStage(String id, String stageId) {
        Map<String, Object> eco = lockOpen(id);
        Map<String, Object> target = stages.require(stageId);
        move(eco, target);
        return get(id);
    }

    /** Approve / reject at the current stage; the last required approval advances the ECO one stage. */
    @Transactional
    public Map<String, Object> decide(String id, Decide dto) {
        Map<String, Object> eco = lockOpen(id);
        if (!"draft".equals(eco.get("status"))) {
            throw ApiException.badRequest("Change " + eco.get("number") + " is already approved.");
        }
        Map<String, Object> stage = stages.stageOf(eco);
        if (!Boolean.TRUE.equals(stage.get("requiresApproval"))) {
            throw ApiException.badRequest("Stage " + stage.get("name") + " does not need approval.");
        }
        List<Map<String, Object>> approvers = stages.approversOf(str(stage, "id"));
        String me = TenantContext.userId();
        if (!approvers.isEmpty() && approvers.stream().noneMatch(a -> me.equals(a.get("userId")))) {
            throw ApiException.forbidden("You are not an approver of stage " + stage.get("name") + ".");
        }
        db.updateWhere(PlmChangeApproval.class, Obj.of("voided", true), "\"organizationId\" = ? AND \"changeId\" = ?"
                + " AND \"stageId\" = ? AND \"userId\" = ? AND NOT voided", MfgDb.org(), id, stage.get("id"), me);
        String comment = PlmSupport.blank(dto.comment());
        db.insert(PlmChangeApproval.class, Obj.of("changeId", id, "stageId", stage.get("id"), "userId", me,
                "decision", dto.decision(), "comment", comment, "voided", false));
        plm.activity(id, "approval", ("approved".equals(dto.decision()) ? "Approved" : "Rejected") + " at "
                + stage.get("name") + (comment == null ? "." : ": " + comment));
        if ("approved".equals(dto.decision())) {
            Map<String, Object> state = approvalState(eco, stage);
            if (Boolean.TRUE.equals(state.get("satisfied"))) {
                Map<String, Object> next = nextStage(stage);
                if (next != null) {
                    move(eco, next);
                }
            }
        }
        return get(id);
    }

    /**
     * ADR-S006 route {@code POST /engineering-changes/:id/approve}: a {@code bom:activate} holder fast-tracks a draft
     * ECO straight to the last stage (recording their approval).
     */
    @Transactional
    public Map<String, Object> approveDirect(String id) {
        Map<String, Object> eco = engineering.require(id);
        if (!"draft".equals(eco.get("status"))) {
            throw ApiException.badRequest("Only a draft change can be approved (status " + eco.get("status") + ").");
        }
        Map<String, Object> stage = stages.stageOf(eco);
        Map<String, Object> last = stages.last();
        db.insert(PlmChangeApproval.class, Obj.of("changeId", id, "stageId", stage.get("id"), "userId",
                TenantContext.userId(), "decision", "approved", "comment", "Approved directly", "voided", false));
        plm.activity(id, "approval", "Approved directly (from " + stage.get("name") + ").");
        Map<String, Object> row = db.update(EngineeringChange.class, id, Obj.of("status", "approved", "stageId",
                last.get("id"), "approvedById", TenantContext.userId(), "approvedAt", Instant.now()));
        plm.event(DomainEvents.PLM_CHANGE_APPROVED, Obj.of("changeId", id, "number", eco.get("number")));
        return row;
    }

    // ================================================================== apply / cancel

    /**
     * Apply an approved ECO. When it cannot switch over yet (effective date ahead on the organization's calendar, or
     * stock of removed components still to be used up) it is <b>queued</b> instead: the row comes back with
     * {@code applyQueued = true} and {@code pending} (why), and {@link PlmEffectivityJob} applies it as soon as nothing
     * blocks it. {@code force} switches over now despite remaining use-up stock (never before the effective date).
     */
    @Transactional
    public Map<String, Object> apply(String id, boolean force) {
        Map<String, Object> eco = engineering.require(id);
        if (!"approved".equals(eco.get("status"))) {
            throw ApiException.badRequest("Only an approved change can be applied (status " + eco.get("status") + ").");
        }
        String pending = pendingReason(eco, force);
        if (pending == null) {
            return doApply(eco);
        }
        Map<String, Object> row = db.update(EngineeringChange.class, id, Obj.of("applyQueued", true));
        if (!Boolean.TRUE.equals(eco.get("applyQueued"))) {
            plm.activity(id, "status", "Queued: " + pending);
        }
        row.put("pending", pending);
        return row;
    }

    /** Why an approved ECO cannot switch over yet, or null. */
    String pendingReason(Map<String, Object> eco, boolean force) {
        LocalDate effective = effectiveDate(eco);
        if (effective != null && effective.isAfter(plm.orgToday())) {
            return "takes effect on " + effective + " (" + plm.orgZone().getId() + ").";
        }
        if (force || !"bom".equals(eco.get("type"))) {
            return null;
        }
        List<Map<String, Object>> waiting = dispositions.useUpPending(eco);
        if (waiting.isEmpty()) {
            return null;
        }
        return "waits for the stock of " + String.join(", ", waiting.stream().map(w -> w.get("label") + " ("
                + com.inventoryerp.kernel.common.JsNumbers.dec((BigDecimal) w.get("onHand")) + ")").toList()) + " to be used up.";
    }

    /** ADR-S006 route {@code POST /engineering-changes/:id/reject} and the PLM cancel: drop the draft artefacts. */
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        Map<String, Object> eco = engineering.require(id);
        if (!PlmSupport.OPEN_CHANGE.contains(str(eco, "status"))) {
            throw ApiException.badRequest("Cannot reject a " + eco.get("status") + " change.");
        }
        if (eco.get("newBomId") != null) {
            Map<String, Object> bom = db.byId(Bom.class, str(eco, "newBomId"));
            if (bom != null && "draft".equals(bom.get("status"))) {
                boms.remove(str(bom, "id"));
            }
        }
        if (eco.get("newRoutingId") != null) {
            Map<String, Object> r = db.byId(Routing.class, str(eco, "newRoutingId"));
            if (r != null && !Boolean.TRUE.equals(r.get("isActive"))) {
                db.update(Routing.class, str(r, "id"), Obj.of("deletedAt", Instant.now()));
            }
        }
        db.jdbc().sql("UPDATE \"PlmDocument\" SET \"changeId\" = NULL, \"updatedAt\" = now() WHERE"
                + " \"organizationId\" = ? AND \"changeId\" = ? AND status = 'draft'").params(MfgDb.org(), id).update();
        String why = PlmSupport.blank(reason);
        plm.activity(id, "status", "Rejected" + (why == null ? "." : ": " + why));
        return db.update(EngineeringChange.class, id, Obj.of("status", "rejected", "rejectedReason", why,
                "applyQueued", false));
    }

    /** Approved ECOs of the current organization that are queued or date-effective (candidates for the job). */
    @Transactional(readOnly = true)
    public List<String> dueChanges() {
        return db.jdbc().sql("SELECT id FROM \"EngineeringChange\" WHERE \"organizationId\" = ? AND status = 'approved'"
                + " AND (\"applyQueued\" OR effectivity = 'date') ORDER BY \"effectiveDate\" NULLS FIRST, \"createdAt\"")
                .param(MfgDb.org()).query(String.class).list();
    }

    /** Job step (own transaction): apply one candidate when nothing blocks it; returns its number when applied. */
    @Transactional
    public String autoApply(String id) {
        Map<String, Object> eco = engineering.require(id);
        if (!"approved".equals(eco.get("status")) || pendingReason(eco, false) != null) {
            return null;
        }
        doApply(eco);
        return str(eco, "number");
    }

    List<String> dueOrganizations() {
        return db.jdbc().sql("SELECT DISTINCT \"organizationId\" FROM \"EngineeringChange\" WHERE status = 'approved'"
                + " AND (\"applyQueued\" OR effectivity = 'date')").query(String.class).list();
    }

    // ================================================================== impact

    /** What applying the ECO touches: open orders, stock of removed / added components, cost delta, parents. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> impact(String id) {
        Map<String, Object> eco = engineering.require(id);
        String productId = str(eco, "productId");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changeId", id);
        out.put("type", eco.get("type"));
        out.put("stockDisposition", eco.get("stockDisposition"));
        out.put("wipDisposition", eco.get("wipDisposition"));

        String oldBom = str(eco, "bomId");
        List<Map<String, Object>> orders = SqlRows.list(db.jdbc().sql("SELECT id, \"orderCode\", status::text AS status,"
                + " \"plannedQty\", \"bomId\", \"bomVersion\", \"scheduledFor\" FROM \"ProductionOrder\" WHERE"
                + " \"organizationId\" = ? AND status::text IN ('draft', 'confirmed', 'in_progress', 'qc_hold') AND ("
                + (oldBom != null ? "\"bomId\" = ?" : "\"outputProductId\" = ?") + ") ORDER BY \"createdAt\"")
                .params(MfgDb.org(), oldBom != null ? oldBom : productId));
        out.put("openOrders", orders);

        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        Map<String, Object> cost = null;
        if (oldBom != null && eco.get("newBomId") != null) {
            Map<String, Object> diff = engineering.compare(oldBom, str(eco, "newBomId"));
            Set<String> ids = new HashSet<>();
            ((List<Map<String, Object>>) diff.get("removed")).forEach(l -> ids.add(str(l, "componentProductId")));
            ((List<Map<String, Object>>) diff.get("added")).forEach(l -> ids.add(str(l, "componentProductId")));
            ((List<Map<String, Object>>) diff.get("changed")).forEach(l -> ids.add(str(l, "componentProductId")));
            Map<String, Map<String, Object>> p = plm.products(ids);
            for (Map<String, Object> l : (List<Map<String, Object>>) diff.get("removed")) {
                String c = str(l, "componentProductId");
                BigDecimal onHand = onHand(c);
                long otherUses = otherActiveUses(c, oldBom);
                removed.add(Obj.of("productId", c, "product", p.get(c), "quantity", l.get("quantity"), "onHand",
                        onHand, "usedElsewhere", otherUses, "obsolescenceRisk",
                        onHand.signum() > 0 && otherUses == 0));
            }
            for (Map<String, Object> l : (List<Map<String, Object>>) diff.get("added")) {
                String c = str(l, "componentProductId");
                added.add(Obj.of("productId", c, "product", p.get(c), "quantity", l.get("quantity"), "onHand",
                        onHand(c), "lifecycleState", lifecycleState(c)));
            }
            for (Map<String, Object> l : (List<Map<String, Object>>) diff.get("changed")) {
                String c = str(l, "componentProductId");
                changed.add(Obj.of("productId", c, "product", p.get(c), "changes", l.get("changes"), "onHand",
                        onHand(c)));
            }
            cost = costDelta(oldBom, str(eco, "newBomId"));
        }
        out.put("removedComponents", removed);
        out.put("addedComponents", added);
        out.put("changedComponents", changed);
        out.put("cost", cost);
        out.put("routing", eco.get("routingId") == null ? null : Obj.of("bomsRepointed", SqlRows.list(db.jdbc().sql(
                "SELECT id, code, version, status::text AS status FROM \"Bom\" WHERE \"organizationId\" = ? AND"
                        + " \"routingId\" = ? AND \"deletedAt\" IS NULL AND status::text <> 'archived' ORDER BY code")
                .params(MfgDb.org(), eco.get("routingId")))));
        if (productId != null) {
            out.put("onHand", onHand(productId));
            out.put("whereUsed", costing.whereUsed(productId));
            out.put("lifecycleState", lifecycleState(productId));
        }
        List<String> warnings = new ArrayList<>();
        if (!orders.isEmpty()) {
            warnings.add(orders.size() + " open production order(s) use the current version; wip disposition: "
                    + eco.get("wipDisposition") + ".");
        }
        removed.stream().filter(r -> Boolean.TRUE.equals(r.get("obsolescenceRisk"))).forEach(r -> warnings.add(
                "Removed component " + PlmSupport.label((Map<String, Object>) r.get("product")) + " has "
                        + r.get("onHand") + " on hand and no other active use; stock disposition: "
                        + eco.get("stockDisposition") + "."));
        added.stream().filter(a -> a.get("lifecycleState") != null && Set.of("concept", "design", "obsolete")
                .contains(a.get("lifecycleState"))).forEach(a -> warnings.add("Added component "
                        + PlmSupport.label((Map<String, Object>) a.get("product")) + " is "
                        + PlmLifecycleService.human((String) a.get("lifecycleState")) + "."));
        Map<String, Object> plan = dispositions.plan(eco);
        out.put("dispositionPlan", plan);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> waiting = (List<Map<String, Object>>) plan.get("useUpPending");
        if (!waiting.isEmpty()) {
            warnings.add("Use-up: the switch-over waits until " + waiting.size()
                    + " removed component(s) are consumed (apply with force to switch now).");
        }
        out.put("pending", "approved".equals(eco.get("status")) ? pendingReason(eco, false) : null);
        out.put("warnings", warnings);
        return out;
    }

    // ================================================================== internals

    private Map<String, Object> doApply(Map<String, Object> eco) {
        String id = str(eco, "id");
        String number = str(eco, "number");
        String productId = str(eco, "productId");
        List<String> summary = new ArrayList<>();
        Map<String, Object> disposition = null;
        switch (str(eco, "type")) {
            case "bom" -> {
                Map<String, Object> bom = boms.activate(str(eco, "newBomId"));
                summary.add("Activated " + bom.get("code") + " v" + bom.get("version") + ".");
                disposition = dispositions.execute(eco);
                String done = PlmDispositionService.summary(disposition);
                if (!done.isEmpty()) {
                    summary.add(done);
                }
            }
            case "routing" -> summary.add(swapRouting(str(eco, "routingId"), str(eco, "newRoutingId")));
            case "product" -> {
                Map<String, Object> item = lifecycle.byProduct(productId);
                if (item == null) {
                    throw ApiException.badRequest("The product of " + number + " is no longer under lifecycle"
                            + " management.");
                }
                lifecycle.transition(item, str(eco, "targetLifecycleState"), "Applied by " + number, null, id);
                summary.add("Lifecycle → " + PlmLifecycleService.human(str(eco, "targetLifecycleState")) + ".");
            }
            default -> {
                // document changes release their documents below
            }
        }
        List<String> released = documents.releaseForChange(id);
        if (!released.isEmpty()) {
            summary.add("Released " + String.join(", ", released) + ".");
        }
        String revision = null;
        if (productId != null && (!"product".equals(eco.get("type")) || eco.get("newRevision") != null)) {
            revision = lifecycle.bumpRevision(productId, str(eco, "newRevision"), id, "Applied by " + number);
            if (revision != null) {
                summary.add("Revision " + revision + ".");
            }
        }
        Map<String, Object> data = Obj.of("status", "applied", "appliedAt", Instant.now(), "applyQueued", false);
        if (disposition != null) {
            data.put("dispositionResult", new MfgDb.Json(disposition));
        }
        if (revision != null) {
            data.put("newRevision", revision);
        }
        Map<String, Object> row = db.update(EngineeringChange.class, id, data);
        plm.activity(id, "applied", "Applied. " + String.join(" ", summary));
        plm.event(DomainEvents.PLM_CHANGE_APPLIED, Obj.of("changeId", id, "number", number, "type", eco.get("type"),
                "productId", productId, "revision", revision));
        return row;
    }

    private void move(Map<String, Object> eco, Map<String, Object> target) {
        String id = str(eco, "id");
        Map<String, Object> current = stages.stageOf(eco);
        if (current.get("id").equals(target.get("id"))) {
            throw ApiException.badRequest("Change " + eco.get("number") + " is already in stage " + target.get("name")
                    + ".");
        }
        int from = (Integer) current.get("sequence");
        int to = (Integer) target.get("sequence");
        if (to > from) {
            for (Map<String, Object> s : stages.ordered()) {
                int sq = (Integer) s.get("sequence");
                if (sq < from || sq >= to || !Boolean.TRUE.equals(s.get("requiresApproval"))) {
                    continue;
                }
                if (!s.get("id").equals(current.get("id"))) {
                    throw ApiException.badRequest("Cannot skip stage " + s.get("name") + ": it needs approval.");
                }
                Map<String, Object> state = approvalState(eco, s);
                if (!Boolean.TRUE.equals(state.get("satisfied"))) {
                    throw ApiException.badRequest((String) state.get("blocker"));
                }
            }
        } else {
            List<String> later = new ArrayList<>();
            for (Map<String, Object> s : stages.ordered()) {
                if ((Integer) s.get("sequence") >= to) {
                    later.add(str(s, "id"));
                }
            }
            db.jdbc().sql("UPDATE \"PlmChangeApproval\" SET voided = true WHERE \"organizationId\" = ? AND"
                    + " \"changeId\" = ? AND \"stageId\" = ANY(?::text[]) AND NOT voided")
                    .params(MfgDb.org(), id, later.toArray(String[]::new)).update();
        }
        boolean last = target.get("id").equals(stages.last().get("id"));
        Map<String, Object> data = Obj.of("stageId", target.get("id"), "status", last ? "approved" : "draft");
        if (last) {
            data.put("approvedById", TenantContext.userId());
            data.put("approvedAt", Instant.now());
        } else if ("approved".equals(eco.get("status"))) {
            data.put("approvedById", null);
            data.put("approvedAt", null);
            data.put("applyQueued", false);
        }
        db.update(EngineeringChange.class, id, data);
        eco.putAll(data);
        plm.activity(id, "stage", "Moved from " + current.get("name") + " to " + target.get("name") + ".");
        plm.event(DomainEvents.PLM_CHANGE_STAGE_CHANGED, Obj.of("changeId", id, "number", eco.get("number"),
                "fromStageId", current.get("id"), "toStageId", target.get("id")));
        if (last) {
            plm.event(DomainEvents.PLM_CHANGE_APPROVED, Obj.of("changeId", id, "number", eco.get("number")));
        }
    }

    /** Approvers of the stage with their latest decision, whether the stage is satisfied, and why not. */
    Map<String, Object> approvalState(Map<String, Object> eco, Map<String, Object> stage) {
        String stageId = str(stage, "id");
        List<Map<String, Object>> approvers = stages.approversOf(stageId);
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        for (Map<String, Object> a : db.list(PlmChangeApproval.class, " AND \"changeId\" = ? AND \"stageId\" = ?"
                + " AND NOT voided ORDER BY \"createdAt\"", eco.get("id"), stageId)) {
            latest.put(str(a, "userId"), a);
        }
        Set<String> users = new HashSet<>(latest.keySet());
        approvers.forEach(a -> users.add(str(a, "userId")));
        Map<String, String> names = plm.userNames(users);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> a : approvers) {
            Map<String, Object> d = latest.get(str(a, "userId"));
            rows.add(Obj.of("userId", a.get("userId"), "userName", names.get(str(a, "userId")), "kind", a.get("kind"),
                    "decision", d == null ? null : d.get("decision"), "comment", d == null ? null : d.get("comment"),
                    "decidedAt", d == null ? null : d.get("createdAt")));
            if ("required".equals(a.get("kind")) && (d == null || !"approved".equals(d.get("decision")))) {
                missing.add(names.getOrDefault(str(a, "userId"), str(a, "userId")));
            }
        }
        Set<String> listed = new HashSet<>();
        approvers.forEach(a -> listed.add(str(a, "userId")));
        latest.forEach((u, d) -> {
            if (!listed.contains(u)) {
                rows.add(Obj.of("userId", u, "userName", names.get(u), "kind", null, "decision", d.get("decision"),
                        "comment", d.get("comment"), "decidedAt", d.get("createdAt")));
            }
        });
        String rejectedBy = latest.values().stream().filter(d -> "rejected".equals(d.get("decision")))
                .map(d -> names.getOrDefault(str(d, "userId"), str(d, "userId"))).findFirst().orElse(null);
        boolean needs = Boolean.TRUE.equals(stage.get("requiresApproval"));
        boolean anyApproved = latest.values().stream().anyMatch(d -> "approved".equals(d.get("decision")));
        boolean hasRequired = approvers.stream().anyMatch(a -> "required".equals(a.get("kind")));
        String blocker = null;
        if (rejectedBy != null) {
            blocker = "Stage " + stage.get("name") + " was rejected by " + rejectedBy + ".";
        } else if (needs && hasRequired && !missing.isEmpty()) {
            blocker = "Stage " + stage.get("name") + " needs approval from: " + String.join(", ", missing) + ".";
        } else if (needs && !hasRequired && !anyApproved) {
            blocker = "Stage " + stage.get("name") + " needs an approval.";
        }
        String me = TenantContext.userId();
        boolean canDecide = needs && "draft".equals(eco.get("status")) && (approvers.isEmpty() ? TenantContext.has(
                com.inventoryerp.kernel.security.Permissions.PLM__APPROVE) : listed.contains(me));
        return Obj.of("stageId", stageId, "requiresApproval", needs, "approvers", rows, "satisfied", blocker == null,
                "blocker", blocker, "canDecide", canDecide, "myDecision", latest.containsKey(me)
                        ? latest.get(me).get("decision") : null);
    }

    private Map<String, Object> nextStage(Map<String, Object> stage) {
        int sq = (Integer) stage.get("sequence");
        for (Map<String, Object> s : stages.ordered()) {
            if ((Integer) s.get("sequence") > sq) {
                return s;
            }
        }
        return null;
    }

    private Map<String, Object> lockOpen(String id) {
        engineering.require(id);
        Map<String, Object> eco = SqlRows.one(EngineeringChange.class, db.jdbc().sql("SELECT * FROM"
                + " \"EngineeringChange\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, MfgDb.org()));
        if (!PlmSupport.OPEN_CHANGE.contains(str(eco, "status"))) {
            throw ApiException.badRequest("Change " + eco.get("number") + " is " + eco.get("status") + ".");
        }
        if (eco.get("stageId") == null) {
            eco.put("stageId", stages.stageOf(eco).get("id"));
        }
        return eco;
    }

    private String insertChange(String number, CreateChange dto, String type, String productId,
            Map<String, Object> extra, String defaultTitle) {
        Map<String, Object> data = Obj.of("number", number, "title", PlmSupport.blank(dto.title()) != null
                ? dto.title().trim() : defaultTitle, "reason", PlmSupport.blank(dto.reason()), "status", "draft",
                "type", type, "productId", productId, "stageId", stages.first().get("id"), "createdBy",
                TenantContext.userId(), "ownerId", TenantContext.userId());
        data.putAll(extra);
        data.putAll(common(dto));
        return str(db.insert(EngineeringChange.class, data), "id");
    }

    private static Map<String, Object> common(CreateChange dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.description() != null) {
            data.put("description", PlmSupport.blank(dto.description()));
        }
        if (dto.priority() != null) {
            data.put("priority", dto.priority());
        }
        if (dto.effectivity() != null) {
            data.put("effectivity", dto.effectivity());
        }
        if (dto.effectiveDate() != null) {
            data.put("effectiveDate", PlmSupport.date(dto.effectiveDate()));
        }
        if (dto.stockDisposition() != null) {
            data.put("stockDisposition", dto.stockDisposition());
        }
        if (dto.wipDisposition() != null) {
            data.put("wipDisposition", dto.wipDisposition());
        }
        if (PlmSupport.blank(dto.newRevision()) != null) {
            data.put("newRevision", dto.newRevision().trim());
        }
        if (PlmSupport.blank(dto.ownerId()) != null) {
            data.put("ownerId", dto.ownerId());
        }
        return data;
    }

    private void checkTransition(Map<String, Object> item, String to, Map<String, Object> product) {
        String from = str(item, "lifecycleState");
        if (from.equals(to)) {
            throw ApiException.badRequest("Product " + product.get("code") + " is already "
                    + PlmLifecycleService.human(to) + ".");
        }
        if (!PlmLifecycleService.TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw ApiException.badRequest("Cannot move " + product.get("code") + " from "
                    + PlmLifecycleService.human(from) + " to " + PlmLifecycleService.human(to) + ".");
        }
    }

    private static LocalDate effectiveDate(Map<String, Object> eco) {
        if (!"date".equals(eco.get("effectivity")) || eco.get("effectiveDate") == null) {
            return null;
        }
        Object v = eco.get("effectiveDate");
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof Instant i) {
            return i.atZone(ZoneOffset.UTC).toLocalDate();
        }
        return LocalDate.parse(v.toString().substring(0, 10));
    }

    // ---- routings --------------------------------------------------------------------------------------------------

    private Map<String, Object> routing(String id) {
        Map<String, Object> r = db.byId(Routing.class, id);
        if (r == null) {
            throw ApiException.notFound("Routing not found");
        }
        r.put("operations", db.children(RoutingOperation.class, "routingId", id, "sequence ASC"));
        return r;
    }

    private String outputOfRouting(String routingId) {
        return db.jdbc().sql("SELECT \"outputProductId\" FROM \"Bom\" WHERE \"organizationId\" = ? AND \"routingId\" = ?"
                + " AND \"deletedAt\" IS NULL ORDER BY (status::text = 'active') DESC, version DESC LIMIT 1")
                .params(MfgDb.org(), routingId).query(String.class).optional().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cloneRouting(Map<String, Object> old, String number) {
        Map<String, Object> copy = db.insert(Routing.class, Obj.of("name", old.get("name") + " (" + number + ")",
                "productId", old.get("productId"), "bomId", old.get("bomId"), "isActive", false, "createdBy",
                TenantContext.userId()));
        for (Map<String, Object> o : (List<Map<String, Object>>) old.get("operations")) {
            Map<String, Object> op = new LinkedHashMap<>();
            for (String f : List.of("sequence", "name", "workCenterId", "durationMins", "setupMins", "instructions",
                    "isSubcontracted", "subcontractorId", "subcontractCostPerUnit")) {
                op.put(f, o.get(f));
            }
            op.put("routingId", copy.get("id"));
            db.insert(RoutingOperation.class, op);
        }
        copyStageSetups(str(old, "id"), str(copy, "id"));
        return copy;
    }

    /** Copy multi-stage set-ups (ADR-S060) from one routing to another, matching operations by sequence. */
    private void copyStageSetups(String fromRouting, String toRouting) {
        db.jdbc().sql("INSERT INTO \"RoutingStageSetup\" (id, \"organizationId\", \"routingOperationId\", \"issueMode\","
                + " \"outputProductId\", \"outputPerUnit\", \"stageLocationId\", \"expectedYieldPct\", \"qcGate\","
                + " \"qcCheckpointCode\", \"minCuringDays\", \"materialProductIds\", \"createdAt\", \"updatedAt\")"
                + " SELECT gen_random_uuid()::text, s.\"organizationId\", n.id, s.\"issueMode\", s.\"outputProductId\","
                + " s.\"outputPerUnit\", s.\"stageLocationId\", s.\"expectedYieldPct\", s.\"qcGate\","
                + " s.\"qcCheckpointCode\", s.\"minCuringDays\", s.\"materialProductIds\", now(), now()"
                + " FROM \"RoutingStageSetup\" s JOIN \"RoutingOperation\" o ON o.id = s.\"routingOperationId\""
                + " JOIN \"RoutingOperation\" n ON n.\"routingId\" = ? AND n.sequence = o.sequence"
                + " WHERE s.\"organizationId\" = ? AND o.\"routingId\" = ?").params(toRouting, MfgDb.org(), fromRouting)
                .update();
    }

    private String swapRouting(String oldId, String newId) {
        db.update(Routing.class, newId, Obj.of("isActive", true, "updatedBy", TenantContext.userId()));
        List<Map<String, Object>> repointed = db.updateWhere(Bom.class, Obj.of("routingId", newId, "updatedBy",
                TenantContext.userId()), "\"organizationId\" = ? AND \"routingId\" = ? AND \"deletedAt\" IS NULL AND"
                        + " status::text <> 'archived'", MfgDb.org(), oldId);
        db.update(Routing.class, oldId, Obj.of("isActive", false, "updatedBy", TenantContext.userId()));
        Map<String, Object> r = db.byId(Routing.class, newId);
        return "Activated routing " + r.get("name") + "; " + repointed.size() + " BOM(s) re-pointed.";
    }

    private Map<String, Object> compareRoutings(String fromId, String toId) {
        Map<String, Map<String, Object>> a = bySequence(fromId);
        Map<String, Map<String, Object>> b = bySequence(toId);
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();
        b.forEach((k, o) -> {
            if (!a.containsKey(k)) {
                added.add(o);
            }
        });
        a.forEach((k, o) -> {
            Map<String, Object> other = b.get(k);
            if (other == null) {
                removed.add(o);
                return;
            }
            Map<String, Object> diff = new LinkedHashMap<>();
            for (String f : List.of("name", "workCenterId", "durationMins", "setupMins", "isSubcontracted",
                    "subcontractCostPerUnit", "instructions")) {
                Object x = o.get(f);
                Object y = other.get(f);
                boolean same = x instanceof BigDecimal bx && y instanceof BigDecimal by ? bx.compareTo(by) == 0
                        : Objects.equals(x, y);
                if (!same) {
                    diff.put(f, Obj.of("from", x, "to", y));
                }
            }
            if (!diff.isEmpty()) {
                changed.add(Obj.of("sequence", o.get("sequence"), "name", other.get("name"), "changes", diff));
            }
        });
        return Obj.of("added", added, "removed", removed, "changed", changed);
    }

    private Map<String, Map<String, Object>> bySequence(String routingId) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> o : db.children(RoutingOperation.class, "routingId", routingId, "sequence ASC")) {
            m.put(String.valueOf(o.get("sequence")), o);
        }
        return m;
    }

    // ---- impact helpers --------------------------------------------------------------------------------------------

    private BigDecimal onHand(String productId) {
        return db.jdbc().sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ?").params(MfgDb.org(), productId).query(BigDecimal.class).single();
    }

    private long otherActiveUses(String componentId, String exceptBomId) {
        return db.jdbc().sql("SELECT count(DISTINCT b.id) FROM \"BomLine\" l JOIN \"Bom\" b ON b.id = l.\"bomId\""
                + " WHERE b.\"organizationId\" = ? AND l.\"componentProductId\" = ? AND b.status::text = 'active'"
                + " AND b.\"deletedAt\" IS NULL AND b.id <> ?").params(MfgDb.org(), componentId, exceptBomId)
                .query(Long.class).single();
    }

    private String lifecycleState(String productId) {
        Map<String, Object> item = lifecycle.byProduct(productId);
        return item == null ? null : str(item, "lifecycleState");
    }

    private Map<String, Object> costDelta(String oldBom, String newBom) {
        // Own transaction: a roll-up error (missing cost data, cycles) must not doom the caller's transaction.
        try {
            Map<String, Object> a = isolated.execute(s -> costing.rollup(oldBom));
            Map<String, Object> b = isolated.execute(s -> costing.rollup(newBom));
            BigDecimal ua = SqlRows.dec(a, "unitCost");
            BigDecimal ub = SqlRows.dec(b, "unitCost");
            BigDecimal delta = ua == null || ub == null ? null : ub.subtract(ua);
            return Obj.of("currentUnitCost", ua, "newUnitCost", ub, "delta", delta, "deltaPct", delta == null
                    || ua.signum() == 0 ? null : delta.multiply(BigDecimal.valueOf(100))
                            .divide(ua, 2, java.math.RoundingMode.HALF_UP));
        } catch (RuntimeException e) {
            return Obj.of("error", e.getMessage());
        }
    }
}
