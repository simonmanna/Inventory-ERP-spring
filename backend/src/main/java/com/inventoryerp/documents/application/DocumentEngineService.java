package com.inventoryerp.documents.application;

import com.inventoryerp.documents.application.DmsRules.AmendVerdict;
import com.inventoryerp.documents.application.DmsRules.Eligibility;
import com.inventoryerp.documents.application.DmsRules.GuardContext;
import com.inventoryerp.documents.application.DmsRules.Transition;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DMS lifecycle engine (port of {@code document-engine.service.ts}): one generic command executor over the
 * declarative registry with DocumentAction-first idempotency, version-guarded writes, numbering, reversal /
 * reissue counterparts, snapshots, amendment history, audit and the {@code document.*} outbox fact — all in one
 * transaction.
 */
@Service
public class DocumentEngineService {

    /** {@code EngineActionInput}. */
    public record ActionInput(String orgId, String userId, List<String> userPermissions, String documentId,
            String action, String idempotencyKey, String reason, String note, Map<String, Object> data) {}

    private final JdbcClient jdbc;
    private final DmsStore store;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventOutboxService outbox;
    private final TransactionTemplate tx;

    public DocumentEngineService(JdbcClient jdbc, DmsStore store, SequenceService sequence, AuditService audit,
            EventOutboxService outbox, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.store = store;
        this.sequence = sequence;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txm);
    }

    /** Execute a lifecycle action with idempotency + optimistic locking. */
    public Map<String, Object> runAction(ActionInput input) {
        if (input.idempotencyKey() == null || input.idempotencyKey().trim().isEmpty()) {
            throw ApiException.badRequest("idempotencyKey is required");
        }
        String key = input.idempotencyKey().trim();
        Map<String, Object> doc = store.requireDoc(input.documentId(), input.orgId());
        Map<String, Object> replay = replay(input, (String) doc.get("id"), key);
        if (replay != null) {
            return replay;
        }
        Map<String, Object> def = store.rawDef(doc.get("documentTypeId"));
        String code = (String) def.get("code");
        Object lifecycleId = def.get("lifecycleId");
        if (lifecycleId == null || jdbc.sql("SELECT 1 FROM \"DocumentLifecycle\" WHERE id = ?").param(lifecycleId).query()
                .listOfRows().isEmpty()) {
            throw ApiException.badRequest("Document type '" + code + "' has no lifecycle registered");
        }
        String status = doc.get("status").toString();
        AmendVerdict amend = null;
        if ("amend".equals(input.action())) {
            if (!"VERSIONED".equals(def.get("editPolicy"))) {
                throw ApiException.badRequest("amend is only allowed for VERSIONED document types ('" + code + "' is "
                        + def.get("editPolicy") + ")");
            }
            amend = DmsRules.validateAmendData(store.jsonMap(doc.get("data")), input.data());
            if (!amend.ok()) {
                throw ApiException.badRequest(amend.reason());
            }
        }
        Transition transition = DmsRules.findTransition(transitions(lifecycleId), status, input.action());
        if (!"amend".equals(input.action()) && !"reissue".equals(input.action()) && transition == null) {
            throw ApiException.badRequest("Action '" + input.action() + "' is not allowed from status '" + status + "'");
        }
        if (store.resolveKey(input.userPermissions(), def, input.action()) == null) {
            throw ApiException.forbidden("Missing permission for document action '" + input.action() + "'");
        }
        AmendVerdict verdict = amend;
        try {
            return tx.execute(s -> execute(input, key, doc, def, transition, verdict));
        } catch (DuplicateKeyException err) {
            if (String.valueOf(err.getMessage()).contains("idempotency")) {
                return replayDuplicate(input, (String) doc.get("id"), key);
            }
            throw err;
        }
    }

    private Map<String, Object> execute(ActionInput input, String key, Map<String, Object> doc, Map<String, Object> def,
            Transition transition, AmendVerdict amend) {
        String docId = (String) doc.get("id");
        String org = input.orgId();
        String action = input.action();
        String code = (String) def.get("code");
        String status = doc.get("status").toString();
        Map<String, Object> prior = replay(input, docId, key);
        if (prior != null) {
            return prior;
        }

        // --- Declarative guards (G4)
        boolean hasEffects = doc.get("journalEntryId") != null;
        if (!"amend".equals(action) && !"reissue".equals(action)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> guard = transition.guardJson();
            boolean approved = !jdbc.sql("SELECT 1 FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND"
                    + " \"entityType\" = ? AND \"entityId\" = ? AND status::text = 'approved' LIMIT 1")
                    .params(org, "document:" + code, docId).query().listOfRows().isEmpty();
            boolean hasSnapshot = count("SELECT count(*) FROM \"DocumentSnapshot\" WHERE \"documentId\" = ?", docId) > 0;
            List<String> blocked = DmsRules.evaluateGuards(guard, new GuardContext(input.reason(),
                    "paid".equals(status) || "paid".equals(String.valueOf(doc.get("paymentStatus"))), approved,
                    hasSnapshot));
            if (!blocked.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, String.join("; ", blocked));
            }
        }

        // --- Cancel vs reverse (§3.6)
        if ("cancel".equals(action)) {
            Eligibility e = DmsRules.cancelEligibility(hasEffects);
            if (!e.allowed()) {
                throw ApiException.badRequest(e.reason());
            }
        }
        String counterpartCode = DmsRules.REVERSAL_COUNTERPARTS.get(code);
        if ("reverse".equals(action)) {
            Eligibility e = DmsRules.reversalEligibility(hasEffects, counterpartCode);
            if (!e.allowed()) {
                throw ApiException.badRequest(e.reason());
            }
            boolean alreadyReversed = doc.get("reversedDocumentId") != null || count("SELECT count(*) FROM \"Document\""
                    + " WHERE \"organizationId\" = ? AND \"reversedDocumentId\" = ?", org, docId) > 0;
            if (alreadyReversed) {
                throw ApiException.badRequest("Document has already been reversed");
            }
        }
        if ("reissue".equals(action) && count("SELECT count(*) FROM \"Document\" WHERE \"organizationId\" = ? AND"
                + " \"reversedDocumentId\" = ?", org, docId) == 0) {
            throw ApiException.badRequest("Document has not been reversed — reissue is not applicable");
        }

        // --- Phase 6 reissue
        String reissueCounterpartId = null;
        if ("reissue".equals(action)) {
            String reissueCode = DmsRules.REISSUE_COUNTERPARTS.get(code);
            if (reissueCode == null) {
                throw ApiException.badRequest("Type '" + code + "' has no reissue counterpart configured (Phase 6)");
            }
            reissueCounterpartId = createCounterpart(doc, reissueCode, input.userId());
            relate(org, docId, reissueCounterpartId, "REISSUES", Obj.of("reversedDocumentId",
                    doc.get("reversedDocumentId"), "engine", true), input.userId(), false);
        }

        // --- Numbering (placeholder / empty numbers only)
        int year = LocalDate.now(BusinessZone.get()).getYear();
        String documentNumber = (String) doc.get("documentNumber");
        boolean numberingApplied = false;
        String numberingKey = (String) def.get("numberingKey");
        if (numberingKey != null && !numberingKey.isEmpty()
                && (documentNumber == null || documentNumber.isEmpty()
                        || documentNumber.startsWith(DmsRules.DRAFT_NUMBER_PREFIX))) {
            documentNumber = sequence.next(DmsRules.composeSequenceKey(numberingKey, year),
                    DmsRules.composeSequencePrefix((String) def.get("numberingPrefix"), year),
                    def.get("numberingPadding") == null ? 5 : ((Number) def.get("numberingPadding")).intValue());
            numberingApplied = true;
        }

        // --- Version-guarded state write (G7)
        String toStatus = transition != null ? transition.toState() : status;
        Instant now = Instant.now();
        int version = ((Number) doc.get("version")).intValue();
        List<String> sets = new ArrayList<>(List.of("status = ?::\"DocumentStatus\"", "version = version + 1",
                "\"updatedBy\" = ?", "\"updatedAt\" = " + DmsStore.NOW));
        List<Object> params = new ArrayList<>(List.of(toStatus));
        params.add(input.userId());
        if (numberingApplied) {
            sets.add("\"documentNumber\" = ?");
            params.add(documentNumber);
        }
        if (!"amend".equals(action) && ("posted".equals(toStatus) || "post".equals(action))) {
            sets.add("\"postedAt\" = ?");
            params.add(SqlRows.ts(now));
            sets.add("\"postedBy\" = ?");
            params.add(input.userId());
        }
        if ("amend".equals(action) && amend != null) {
            sets.add("data = ?::jsonb");
            params.add(store.toJson(amend.mergedData()));
            if (amend.hasNotes()) {
                sets.add("notes = ?");
                params.add(amend.notes());
            }
        }
        params.addAll(List.of(docId, org, version));
        int updated = jdbc.sql("UPDATE \"Document\" SET " + String.join(", ", sets) + " WHERE id = ? AND"
                + " \"organizationId\" = ? AND version = ?").params(params).update();
        if (updated == 0) {
            throw ApiException.conflict("Document was modified concurrently (expected version " + version
                    + "); reload and retry");
        }

        // --- Reversal counterpart + CANCELS mirror
        String counterpartId = null;
        if ("reverse".equals(action) && counterpartCode != null) {
            counterpartId = createCounterpart(doc, counterpartCode, input.userId());
            relate(org, docId, counterpartId, "CANCELS", Obj.of("reversedDocumentId", docId, "engine", true),
                    input.userId(), true);
        }

        // --- Snapshots per policy (always on reverse)
        String snapshotId = null;
        if (DmsRules.shouldCaptureSnapshot(captureOn(def), action, toStatus)) {
            snapshotId = captureSnapshot(doc, code, (String) def.get("name"), toStatus,
                    "reverse".equals(action) ? "reverse" : action, input.userId());
        }

        // --- Amendment history (VERSIONED types only)
        if ("amend".equals(action) && amend != null) {
            jdbc.sql("INSERT INTO \"DocumentVersion\" (id, \"organizationId\", \"documentId\", \"versionNo\", data,"
                            + " \"changeNote\", \"changedById\") VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)")
                    .params(UUID.randomUUID().toString(), org, docId, version + 1, store.toJson(amend.mergedData()),
                            input.note(), input.userId())
                    .update();
        }

        // --- Transactional record
        String cp = counterpartId != null ? counterpartId : reissueCounterpartId;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("replayed", false);
        result.put("action", action);
        result.put("fromStatus", status);
        result.put("toStatus", toStatus);
        result.put("version", version + 1);
        result.put("documentNumber", documentNumber);
        result.put("numberingApplied", numberingApplied);
        result.put("counterpartId", cp);
        result.put("snapshotId", snapshotId);
        result.put("amended", "amend".equals(action));
        result.put("at", DmsSnapshot.jsIso(now));
        jdbc.sql("INSERT INTO \"DocumentAction\" (id, \"organizationId\", \"documentId\", action, \"idempotencyKey\","
                        + " \"requestedById\", status, result) VALUES (?, ?, ?, ?, ?, ?, 'completed', ?::jsonb)")
                .params(UUID.randomUUID().toString(), org, docId, action, key, input.userId(), store.toJson(result))
                .update();
        Map<String, Object> newValues = new LinkedHashMap<>();
        newValues.put("documentAction", action);
        newValues.put("status", toStatus);
        newValues.put("version", version + 1);
        newValues.put("documentNumber", documentNumber);
        newValues.put("numberingApplied", numberingApplied);
        newValues.put("counterpartId", cp);
        audit.recordInTx(AuditInput.of("Document", docId, DmsRules.AUDIT_ACTION.getOrDefault(action, "update"),
                Obj.of("documentAction", action, "status", status, "version", version), newValues));
        String eventName = DmsRules.DOMAIN_EVENT.get(action);
        if (eventName == null) {
            throw new IllegalStateException("No declared domain event for engine action '" + action + "'");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", org);
        payload.put("documentId", docId);
        payload.put("documentNumber", documentNumber);
        payload.put("documentTypeId", def.get("id"));
        payload.put("documentTypeCode", code);
        payload.put("action", action);
        payload.put("fromStatus", status);
        payload.put("toStatus", toStatus);
        payload.put("actorId", input.userId());
        payload.put("version", version + 1);
        if (input.reason() != null) {
            payload.put("reason", input.reason());
        }
        payload.put("counterpartId", cp);
        if (input.note() != null) {
            payload.put("note", input.note());
        }
        outbox.publish(eventName, payload);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> captureOn(Map<String, Object> def, DmsStore store) {
        Object c = store.jsonMap(def.get("snapshotPolicy")).get("captureOn");
        return c instanceof List<?> l ? (List<String>) l : null;
    }

    private List<String> captureOn(Map<String, Object> def) {
        return captureOn(def, store);
    }

    private List<Transition> transitions(Object lifecycleId) {
        List<Transition> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT * FROM \"DocumentTransition\" WHERE \"lifecycleId\" = ?")
                .param(lifecycleId).query().listOfRows()) {
            Object guard = store.json(r.get("guardJson"));
            @SuppressWarnings("unchecked")
            Map<String, Object> g = guard instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            out.add(new Transition((String) r.get("fromState"), (String) r.get("toState"), (String) r.get("action"), g));
        }
        return out;
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    /** Stored result replay for (documentId, action, key); a failed prior row is re-raised. */
    private Map<String, Object> replay(ActionInput input, String docId, String key) {
        Map<String, Object> prior = jdbc.sql("SELECT status, result, error FROM \"DocumentAction\" WHERE"
                        + " \"organizationId\" = ? AND \"documentId\" = ? AND action = ? AND \"idempotencyKey\" = ?")
                .params(input.orgId(), docId, input.action(), key).query().listOfRows().stream().findFirst()
                .orElse(null);
        if (prior == null) {
            return null;
        }
        if ("failed".equals(prior.get("status"))) {
            Object err = prior.get("error");
            throw ApiException.badRequest(err != null ? err.toString() : "Replayed a failed action (no-op)");
        }
        Map<String, Object> result = store.jsonMap(prior.get("result"));
        if (result.isEmpty() && prior.get("result") == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("replayed", true);
        return out;
    }

    private Map<String, Object> replayDuplicate(ActionInput input, String docId, String key) {
        Map<String, Object> winner = jdbc.sql("SELECT status, result FROM \"DocumentAction\" WHERE \"organizationId\" = ?"
                        + " AND \"documentId\" = ? AND action = ? AND \"idempotencyKey\" = ?")
                .params(input.orgId(), docId, input.action(), key).query().listOfRows().stream().findFirst().orElse(null);
        if (winner != null && "completed".equals(winner.get("status")) && winner.get("result") != null) {
            Map<String, Object> out = new LinkedHashMap<>(store.jsonMap(winner.get("result")));
            out.put("replayed", true);
            return out;
        }
        throw ApiException.conflict("A concurrent action with this Idempotency-Key is in progress; retry");
    }

    private void relate(String org, String from, String to, String relationCode, Map<String, Object> payload,
            String userId, boolean skipExisting) {
        String typeId = jdbc.sql("SELECT id FROM \"DocumentRelationType\" WHERE code = ?").param(relationCode)
                .query(String.class).optional().orElse(null);
        if (typeId == null) {
            return;
        }
        if (skipExisting && count("SELECT count(*) FROM \"DocumentRelation\" WHERE \"organizationId\" = ? AND"
                + " \"fromDocumentId\" = ? AND \"toDocumentId\" = ? AND \"relationTypeId\" = ?", org, from, to, typeId) > 0) {
            return;
        }
        jdbc.sql("INSERT INTO \"DocumentRelation\" (id, \"organizationId\", \"fromDocumentId\", \"toDocumentId\","
                        + " \"relationTypeId\", payload, \"createdById\") VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)")
                .params(UUID.randomUUID().toString(), org, from, to, typeId, store.toJson(payload), userId).update();
    }

    private static String neg(Object v) {
        return "-" + com.inventoryerp.kernel.common.JsNumbers.toJsString(DmsSnapshot.num(v));
    }

    /**
     * Reversal counterpart (§3.6): header mirrored, money negated, posted. Nest maps {@code doc.lines}, which the
     * engine never loads, so the counterpart carries no lines.
     */
    private String createCounterpart(Map<String, Object> doc, String counterpartCode, String userId) {
        int year = LocalDate.now(BusinessZone.get()).getYear();
        Map<String, Object> cpDef = store.rawDefByCode(counterpartCode);
        if (cpDef == null) {
            throw ApiException.badRequest("Counterpart type '" + counterpartCode + "' not registered");
        }
        String numberingKey = (String) cpDef.get("numberingKey");
        String number = sequence.next(DmsRules.composeSequenceKey(numberingKey != null ? numberingKey : counterpartCode,
                        year), DmsRules.composeSequencePrefix((String) cpDef.get("numberingPrefix"), year),
                cpDef.get("numberingPadding") == null ? 5 : ((Number) cpDef.get("numberingPadding")).intValue());
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        BigDecimal residual = new BigDecimal(String.valueOf(doc.get("amountResidual")));
        jdbc.sql("INSERT INTO \"Document\" (id, \"organizationId\", \"documentNumber\", \"documentType\","
                        + " \"documentTypeId\", \"partnerId\", \"currencyId\", \"exchangeRate\", \"issueDate\", \"dueDate\","
                        + " \"sourceDocument\", status, subtotal, \"discountTotal\", \"taxAmount\", \"totalAmount\","
                        + " \"amountPaid\", \"amountResidual\", \"reversedDocumentId\", \"sourceType\", \"sourceId\","
                        + " \"branchId\", \"postedAt\", \"createdBy\", \"updatedAt\") VALUES (?, ?, ?, ?::\"DocumentType\", ?,"
                        + " ?, ?, ?, ?, ?, ?, 'posted', ?::numeric, ?::numeric, ?::numeric, ?::numeric, 0, ?::numeric, ?, ?, ?,"
                        + " ?, ?, ?, " + DmsStore.NOW + ")")
                .params(id, doc.get("organizationId"), number, counterpartCode, cpDef.get("id"), doc.get("partnerId"),
                        doc.get("currencyId"), doc.get("exchangeRate"), SqlRows.ts(now), doc.get("dueDate"),
                        doc.get("documentNumber"), neg(doc.get("subtotal")), neg(doc.get("discountTotal")),
                        neg(doc.get("taxAmount")), neg(doc.get("totalAmount")),
                        residual.signum() > 0 ? neg(residual) : neg(doc.get("totalAmount")), doc.get("id"),
                        doc.get("sourceType"), doc.get("sourceId"), doc.get("branchId"), SqlRows.ts(now), userId)
                .update();
        if (DmsRules.shouldCaptureSnapshot(captureOn(cpDef), "post", "posted")) {
            captureSnapshot(store.rawDoc(id, (String) doc.get("organizationId")), (String) cpDef.get("code"),
                    (String) cpDef.get("name"), "posted", "reverse", userId);
        }
        return id;
    }

    /** §4.4 capture from Document + DocumentLine only, sealed with the active A4 template id. */
    String captureSnapshot(Map<String, Object> doc, String typeCode, String typeName, String state, String reason,
            String userId) {
        List<Map<String, Object>> lines = store.rawLines(doc.get("id"));
        String a4 = jdbc.sql("SELECT id FROM \"TemplateDefinition\" WHERE \"documentTypeId\" = ? AND format = 'a4' AND"
                + " \"isActive\" = true LIMIT 1").param(doc.get("documentTypeId")).query(String.class).optional()
                .orElse(null);
        Map<String, Object> data = DmsSnapshot.buildSnapshotData(doc, lines, typeCode, typeName);
        String seal = DmsSnapshot.seal(data, a4);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"DocumentSnapshot\" (id, \"organizationId\", \"documentId\", state, data,"
                        + " \"templateVersionId\", seal, reason, \"createdById\") VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)")
                .params(id, doc.get("organizationId"), doc.get("id"), state, store.toJson(data), a4, seal, reason, userId)
                .update();
        return id;
    }
}
