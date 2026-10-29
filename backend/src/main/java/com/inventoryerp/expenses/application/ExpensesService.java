package com.inventoryerp.expenses.application;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.TreasuryGuards;
import com.inventoryerp.expenses.domain.Expense;
import com.inventoryerp.expenses.domain.ExpensePayment;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Standalone expenses (port of {@code expenses.service.ts}): create (CASH pays on the spot, CREDIT routes through the
 * approval engine), edit, approve / reject (SoD), pay (Dr expense / Cr cash-bank with treasury guards), void
 * (reverses every posted payment), soft delete, stats, audit trail and form lookups.
 */
@Service
public class ExpensesService {

    /** {@code CreateExpenseDto}. */
    public record CreateInput(String title, String description, BigDecimal amount, String categoryId, String expenseDate,
            String notes, String supplierId, String paymentType, String paymentMethod, String accountId,
            String paymentReference) {}

    /** {@code UpdateExpenseDto}. */
    public record UpdateInput(String title, String description, BigDecimal amount, String categoryId, String expenseDate,
            String notes, String supplierId, String paymentType) {}

    /** {@code PayExpenseDto}. */
    public record PayInput(String paymentMethod, String accountId, String reference, String paymentNotes) {}

    /**
     * Tags of the expense-side line of an expense's payment postings (additive, for expenses raised by other modules,
     * e.g. fleet fuel): cost centre, branch, analytic dimensions and an optional expense account overriding the
     * category / default resolution. {@code null} tags = the line posts exactly as before.
     */
    public record PostingTags(String costCenterId, String branchId, Map<String, Object> dimensions,
            String expenseAccountId) {}

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final EventBus events;
    private final SequenceService sequence;
    private final AuditService audit;
    private final ApprovalsService approvals;
    private final PostingService posting;
    private final TransactionTemplate newTx;
    private final ObjectProvider<ExpensePostingTagger> taggers;

    public ExpensesService(JdbcClient jdbc, EventBus events, SequenceService sequence, AuditService audit,
            ApprovalsService approvals, PostingService posting, PlatformTransactionManager txm,
            ObjectProvider<ExpensePostingTagger> taggers) {
        this.taggers = taggers;
        this.jdbc = jdbc;
        this.events = events;
        this.sequence = sequence;
        this.audit = audit;
        this.approvals = approvals;
        this.posting = posting;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    static String journalForMethod(String method) {
        return switch (method == null ? "" : method.toUpperCase()) {
            case "CASH" -> "CASH";
            case "BANK_TRANSFER", "MTN_MOBILE_MONEY", "AIRTEL_MONEY", "CHEQUE" -> "BANK";
            default -> "GEN";
        };
    }

    private void publish(String event, Map<String, Object> payload) {
        Map<String, Object> p = new HashMap<>(payload);
        p.put("organizationId", org());
        events.publish(event, p);
    }

    // ------------------------------------------------------------------ reads

    /** {@code {data, total, page, limit}} (limit default 15, max 200). */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> q) {
        int page = Math.max(1, JsNumbers.orDefault(q.get("page"), 1));
        int limit = Math.min(200, Math.max(1, JsNumbers.orDefault(q.get("limit"), 15)));
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"deletedAt\" IS NULL");
        for (String f : List.of("categoryId", "supplierId")) {
            if (notBlank(q.get(f))) {
                where.append(" AND \"").append(f).append("\" = ?");
                params.add(q.get(f));
            }
        }
        if (notBlank(q.get("status"))) {
            where.append(" AND status::text = ?");
            params.add(q.get("status"));
        }
        if (notBlank(q.get("paymentType"))) {
            where.append(" AND \"paymentType\"::text = ?");
            params.add(q.get("paymentType"));
        }
        if (notBlank(q.get("dateFrom"))) {
            where.append(" AND \"expenseDate\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(q.get("dateFrom"))));
        }
        if (notBlank(q.get("dateTo"))) {
            where.append(" AND \"expenseDate\" <= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(q.get("dateTo") + "T23:59:59.999Z")));
        }
        if (notBlank(q.get("search"))) {
            String like = "%" + BaseCrudService.escapeLike(q.get("search")) + "%";
            where.append(" AND (title ILIKE ? OR \"expenseCode\" ILIKE ? OR description ILIKE ? OR \"categoryName\" ILIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        List<Map<String, Object>> rows = SqlRows.list(Expense.class, jdbc.sql("SELECT * FROM \"Expense\" WHERE" + where
                + " ORDER BY \"createdAt\" DESC LIMIT " + limit + " OFFSET " + (page - 1) * limit).params(params));
        for (Map<String, Object> r : rows) {
            r.put("category", category(r.get("categoryId")));
        }
        long total = jdbc.sql("SELECT count(*) FROM \"Expense\" WHERE" + where).params(params).query(Long.class).single();
        return Obj.of("data", decorate(rows), "total", total, "page", page, "limit", limit);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isEmpty();
    }

    private Map<String, Object> category(Object id) {
        return id == null ? null : SqlRows.one(com.inventoryerp.expenses.domain.ExpenseCategory.class,
                jdbc.sql("SELECT * FROM \"ExpenseCategory\" WHERE id = ?").param(id));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> exp = SqlRows.one(Expense.class, jdbc.sql("SELECT * FROM \"Expense\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        if (exp == null) {
            throw ApiException.notFound("Expense not found");
        }
        return loaded(exp);
    }

    private Map<String, Object> loaded(Map<String, Object> exp) {
        exp.put("category", category(exp.get("categoryId")));
        exp.put("payments", SqlRows.list(ExpensePayment.class, jdbc.sql("SELECT * FROM \"ExpensePayment\" WHERE"
                + " \"expenseId\" = ? ORDER BY \"createdAt\" DESC").param(exp.get("id"))));
        return decorate(List.of(exp)).getFirst();
    }

    /** {@code loadDecorated}: no soft-delete filter (a voided expense still renders). */
    private Map<String, Object> loadDecorated(String id) {
        Map<String, Object> exp = SqlRows.one(Expense.class, jdbc.sql("SELECT * FROM \"Expense\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
        return exp == null ? null : loaded(exp);
    }

    /** Totals by payment state, category and supplier. */
    @Transactional(readOnly = true)
    public Map<String, Object> stats(String dateFrom, String dateTo) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND status NOT IN ('VOID', 'CANCELLED', 'REJECTED')");
        if (notBlank(dateFrom)) {
            where.append(" AND \"expenseDate\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(dateFrom)));
        }
        if (notBlank(dateTo)) {
            where.append(" AND \"expenseDate\" <= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(dateTo + "T23:59:59.999Z")));
        }
        double grand = 0;
        double unpaid = 0;
        long unpaidCount = 0;
        double partial = 0;
        long partialCount = 0;
        double totalPaid = 0;
        long paidCount = 0;
        double outstanding = 0;
        long outstandingCount = 0;
        Map<String, double[]> byCat = new LinkedHashMap<>();
        Map<String, double[]> bySup = new LinkedHashMap<>();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT amount, \"amountPaid\", \"paymentStatus\"::text AS ps,"
                + " \"categoryName\", \"supplierId\" FROM \"Expense\" WHERE" + where).params(params));
        for (Map<String, Object> e : rows) {
            double amount = Money.dec(e.get("amount")).doubleValue();
            double paid = Money.dec(e.get("amountPaid")).doubleValue();
            grand += amount;
            String ps = (String) e.get("ps");
            if ("UNPAID".equals(ps)) {
                unpaid += amount;
                unpaidCount++;
            } else if ("PARTIALLY_PAID".equals(ps)) {
                partial += amount;
                partialCount++;
            } else if ("PAID".equals(ps)) {
                paidCount++;
            }
            totalPaid += paid;
            if (!"PAID".equals(ps)) {
                outstanding += amount - paid;
                outstandingCount++;
            }
            String catKey = e.get("categoryName") != null ? (String) e.get("categoryName") : "—";
            double[] c = byCat.computeIfAbsent(catKey, k -> new double[2]);
            c[0] += amount;
            c[1] += 1;
            if (e.get("supplierId") != null) {
                double[] s = bySup.computeIfAbsent((String) e.get("supplierId"), k -> new double[2]);
                s[0] += amount;
                s[1] += 1;
            }
        }
        Map<String, String> names = new HashMap<>();
        if (!bySup.isEmpty()) {
            for (Map<String, Object> p : jdbc.sql("SELECT id, name FROM \"Partner\" WHERE id IN (:ids)"
                    + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(bySup.keySet()))
                    .param("org", org()).query().listOfRows()) {
                names.put((String) p.get("id"), (String) p.get("name"));
            }
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        byCat.forEach((k, v) -> categories.add(Obj.of("category", k, "_sum", Obj.of("amount", JsNumbers.js(v[0])),
                "_count", Obj.of("id", (long) v[1]))));
        List<Map<String, Object>> suppliers = new ArrayList<>();
        bySup.forEach((k, v) -> suppliers.add(Obj.of("supplierName", names.getOrDefault(k, "Unspecified"), "_sum",
                Obj.of("amount", JsNumbers.js(v[0])), "_count", Obj.of("id", (long) v[1]))));
        return Obj.of("count", rows.size(), "grandTotal", JsNumbers.js(grand), "totalUnpaid", JsNumbers.js(unpaid),
                "totalUnpaidCount", unpaidCount, "totalPartiallyPaid", JsNumbers.js(partial), "totalPartiallyPaidCount",
                partialCount, "totalPaid", JsNumbers.js(totalPaid), "totalPaidCount", paidCount, "byCategory", categories,
                "bySupplier", suppliers, "outstandingPayables", Obj.of("amount", JsNumbers.js(outstanding), "count",
                        outstandingCount));
    }

    /** Audit trail of an expense and its payments. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAudit(String id) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT id, action::text AS action, entity, \"actorId\","
                + " \"newValues\", \"createdAt\" FROM \"AuditLog\" WHERE \"organizationId\" = ? AND entity IN ('Expense',"
                + " 'ExpensePayment') AND \"entityId\" = ? ORDER BY \"createdAt\" DESC").params(org(), id));
        LinkedHashSet<String> actors = new LinkedHashSet<>();
        rows.forEach(r -> {
            if (r.get("actorId") != null) {
                actors.add((String) r.get("actorId"));
            }
        });
        Map<String, String> nameById = new HashMap<>();
        if (!actors.isEmpty()) {
            for (Map<String, Object> u : jdbc.sql("SELECT id, \"firstName\", \"lastName\", email FROM \"User\" WHERE id IN"
                    + " (:ids) AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(actors))
                    .param("org", org()).query().listOfRows()) {
                String n = ((u.get("firstName") == null ? "" : u.get("firstName")) + " "
                        + (u.get("lastName") == null ? "" : u.get("lastName"))).trim();
                nameById.put((String) u.get("id"), n.isEmpty() ? (String) u.get("email") : n);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object reason = null;
            if (r.get("newValues") instanceof tools.jackson.databind.JsonNode nv && nv.has("reason")
                    && !nv.get("reason").isNull()) {
                reason = nv.get("reason");
            }
            out.add(Obj.of("id", r.get("id"), "action", r.get("action"), "entityType", r.get("entity"), "userId",
                    r.get("actorId"), "userName", r.get("actorId") != null ? nameById.get((String) r.get("actorId")) : null,
                    "reason", reason, "createdAt", r.get("createdAt")));
        }
        return out;
    }

    /** Postable cash-equivalent accounts (register drawers excluded) with GL balances. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> paymentAccounts() {
        List<String> drawers = jdbc.sql("SELECT \"defaultAccountId\" FROM \"CashRegister\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND \"defaultAccountId\" IS NOT NULL").param(org()).query(String.class).list();
        List<Map<String, Object>> accounts = SqlRows.list(jdbc.sql("SELECT a.id, a.name FROM \"Account\" a JOIN"
                + " \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ? AND c.\"isCashEquivalent\" ="
                + " true AND a.\"isPostable\" = true AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL"
                + " AND a.\"deprecatedAt\" IS NULL AND NOT (a.id = ANY(?::text[])) ORDER BY a.\"sortOrder\" ASC, a.code ASC")
                .params(org(), drawers.toArray(String[]::new)));
        String currency = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org())
                .query(String.class).optional().orElse(null);
        String base = currency != null ? currency : "UGX";
        Map<String, Double> balances = new HashMap<>();
        if (!accounts.isEmpty()) {
            for (Map<String, Object> b : jdbc.sql("SELECT l.\"accountId\", SUM(l.\"baseDebit\") AS d, SUM(l.\"baseCredit\") AS c"
                    + " FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" WHERE l.\"organizationId\" ="
                    + " :org AND l.\"accountId\" IN (:ids) AND e.status IN ('posted', 'reversed') GROUP BY l.\"accountId\"")
                    .param("org", org()).param("ids", accounts.stream().map(a -> (String) a.get("id")).toList())
                    .query().listOfRows()) {
                balances.put((String) b.get("accountId"), Money.dec(b.get("d")).doubleValue()
                        - Money.dec(b.get("c")).doubleValue());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : accounts) {
            out.add(Obj.of("id", a.get("id"), "name", a.get("name"), "currency", base, "currentBalance",
                    JsNumbers.js(balances.getOrDefault((String) a.get("id"), 0.0))));
        }
        return out;
    }

    /** Partners flagged as suppliers ({@code phone} omitted when null, like {@code undefined}). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> suppliers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT id, name, phone FROM \"Partner\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isSupplier\" = true ORDER BY name ASC").param(org()))) {
            out.add(supplierShape(r));
        }
        return out;
    }

    private static Map<String, Object> supplierShape(Map<String, Object> r) {
        Map<String, Object> m = Obj.of("id", r.get("id"), "name", r.get("name"));
        if (r.get("phone") != null) {
            m.put("phone", r.get("phone"));
        }
        return m;
    }

    // ------------------------------------------------------------------ writes

    /** Create an expense; CASH pays immediately, CREDIT goes through the approval engine. */
    @Transactional
    public Map<String, Object> create(CreateInput dto) {
        return create(dto, null);
    }

    /**
     * {@link #create(CreateInput)} whose immediate (CASH) payment posting carries {@code tags} on the expense line;
     * later payments of a CREDIT expense take their tags from the {@link ExpensePostingTagger}s.
     */
    @Transactional
    public Map<String, Object> create(CreateInput dto, PostingTags tags) {
        String actorId = requireActor();
        boolean isCash = "CASH".equals(dto.paymentType());
        if (isCash && (dto.paymentMethod() == null || dto.paymentMethod().isEmpty() || dto.accountId() == null
                || dto.accountId().isEmpty())) {
            throw ApiException.badRequest("Cash expenses require paymentMethod and accountId");
        }
        Map<String, Object> category = dto.categoryId() != null && !dto.categoryId().isEmpty()
                ? SqlRows.one(jdbc.sql("SELECT * FROM \"ExpenseCategory\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(dto.categoryId(), org())) : null;
        if (dto.categoryId() != null && !dto.categoryId().isEmpty() && category == null) {
            throw ApiException.badRequest("Category not found");
        }
        int year = EntityPatch.toInstant(dto.expenseDate()).atOffset(ZoneOffset.UTC).getYear();
        String code = sequence.next("expense:" + year, "EXP-", 5);
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Expense\" (id, \"organizationId\", \"expenseCode\", title, description, amount, status,"
                        + " \"paymentStatus\", \"paymentType\", \"expenseDate\", notes, \"categoryId\", \"categoryName\","
                        + " \"supplierId\", \"createdById\", \"approvedById\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?,"
                        + " ?, ?, ?::\"ExpenseStatus\", 'UNPAID', ?::\"ExpensePaymentType\", ?, ?, ?, ?, ?, ?, ?, " + NOW
                        + ", " + NOW + ")")
                .params(id, org(), code, dto.title().trim(), dto.description(), dto.amount(), isCash ? "POSTED" : "DRAFT",
                        dto.paymentType(), SqlRows.ts(EntityPatch.toInstant(dto.expenseDate())), dto.notes(),
                        category == null ? null : category.get("id"), category == null ? null : category.get("name"),
                        dto.supplierId() == null || dto.supplierId().isEmpty() ? null : dto.supplierId(), actorId,
                        isCash ? actorId : null)
                .update();
        audit.recordInTx(AuditInput.of("Expense", id, "create", null, Obj.of("expenseCode", code, "title",
                dto.title().trim(), "amount", JsNumbers.js(dto.amount()), "paymentType", dto.paymentType())));
        if (isCash) {
            if (!TenantContext.has("expense:post")) {
                throw ApiException.forbidden("Paying an expense on creation requires expense:post; save it as a credit"
                        + " expense instead");
            }
            recordPayment(expense(id), actorId, dto.paymentMethod(), dto.accountId(), dto.paymentReference(), null,
                    category, tags);
        }
        publish("expense.created", Obj.of("expenseId", id, "expenseCode", code));
        BusinessOutcome.record(jdbc, Obj.of("id", id, "expenseCode", code), isCash);
        if (!isCash) {
            Map<String, Object> snapshot = Obj.of("amount", JsNumbers.js(dto.amount()), "categoryId",
                    category == null ? null : category.get("id"), "title", dto.title().trim());
            Map<String, Object> gate = newTx.execute(s -> approvals.checkOrRequestApproval("expense", id, snapshot));
            if (gate == null || !Boolean.TRUE.equals(gate.get("needsApproval"))) {
                jdbc.sql("UPDATE \"Expense\" SET status = 'APPROVED', \"approvedById\" = NULL, \"updatedAt\" = " + NOW
                        + " WHERE id = ?").param(id).update();
            }
        }
        return loadDecorated(id);
    }

    private Map<String, Object> expense(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"Expense\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(id, org()));
    }

    /** Edit an unpaid draft / approved expense. */
    @Transactional
    public Map<String, Object> update(String id, UpdateInput dto) {
        Map<String, Object> exp = expense(id);
        if (exp == null) {
            throw ApiException.notFound("Expense not found");
        }
        if (!List.of("DRAFT", "APPROVED").contains(String.valueOf(exp.get("status")))
                || !"UNPAID".equals(String.valueOf(exp.get("paymentStatus")))) {
            throw ApiException.badRequest("Only unpaid draft/approved expenses can be edited");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        List<Object> params = new ArrayList<>();
        StringBuilder set = new StringBuilder("\"updatedAt\" = " + NOW);
        if (EntityPatch.present("title", dto.title())) {
            data.put("title", dto.title().trim());
        }
        if (EntityPatch.present("description", dto.description())) {
            data.put("description", dto.description() == null || dto.description().isEmpty() ? null : dto.description());
        }
        if (EntityPatch.present("amount", dto.amount())) {
            data.put("amount", dto.amount());
        }
        if (EntityPatch.present("expenseDate", dto.expenseDate())) {
            data.put("expenseDate", EntityPatch.toInstant(dto.expenseDate()));
        }
        if (EntityPatch.present("notes", dto.notes())) {
            data.put("notes", dto.notes() == null || dto.notes().isEmpty() ? null : dto.notes());
        }
        if (EntityPatch.present("supplierId", dto.supplierId())) {
            data.put("supplierId", dto.supplierId() == null || dto.supplierId().isEmpty() ? null : dto.supplierId());
        }
        if (EntityPatch.present("paymentType", dto.paymentType())) {
            data.put("paymentType", dto.paymentType());
        }
        if (EntityPatch.present("categoryId", dto.categoryId())) {
            Map<String, Object> cat = dto.categoryId() != null && !dto.categoryId().isEmpty()
                    ? SqlRows.one(jdbc.sql("SELECT id, name FROM \"ExpenseCategory\" WHERE id = ? AND \"organizationId\" = ?"
                            + " AND \"deletedAt\" IS NULL").params(dto.categoryId(), org())) : null;
            if (dto.categoryId() != null && !dto.categoryId().isEmpty() && cat == null) {
                throw ApiException.badRequest("Category not found");
            }
            data.put("categoryId", cat == null ? null : cat.get("id"));
            data.put("categoryName", cat == null ? null : cat.get("name"));
        }
        for (Map.Entry<String, Object> e : data.entrySet()) {
            set.append(", \"").append(e.getKey()).append("\" = ?");
            if ("paymentType".equals(e.getKey())) {
                set.append("::\"ExpensePaymentType\"");
            }
            params.add(e.getValue() instanceof Instant i ? SqlRows.ts(i) : e.getValue());
        }
        params.add(id);
        jdbc.sql("UPDATE \"Expense\" SET " + set + " WHERE id = ?").params(params).update();
        Map<String, Object> auditValues = new LinkedHashMap<>(data);
        if (auditValues.get("amount") instanceof BigDecimal b) {
            auditValues.put("amount", JsNumbers.js(b));
        }
        audit.recordInTx(AuditInput.of("Expense", id, "update", null, auditValues));
        return loadDecorated(id);
    }

    /** Approve a draft expense (decides the pending approval request when one exists). */
    @Transactional
    public Map<String, Object> approve(String id, String approvalNotes) {
        Map<String, Object> exp = expense(id);
        if (exp == null) {
            throw ApiException.notFound("Expense not found");
        }
        if (!"DRAFT".equals(String.valueOf(exp.get("status")))) {
            throw ApiException.badRequest("Only draft expenses can be approved");
        }
        String approverId = requireActor();
        if (exp.get("createdById") != null && exp.get("createdById").equals(approverId)) {
            throw ApiException.forbidden("The person who raised an expense cannot approve it");
        }
        String pending = pendingRequest(id);
        if (pending != null) {
            approvals.decide(pending, "approved", approvalNotes);
            String status = jdbc.sql("SELECT status::text FROM \"ApprovalRequest\" WHERE id = ?").param(pending)
                    .query(String.class).single();
            if ("approved".equals(status)) {
                jdbc.sql("UPDATE \"Expense\" SET status = 'APPROVED', \"approvedById\" = ?, \"approvalNotes\" = ?,"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ?").params(approverId, approvalNotes, id).update();
            }
            return loadDecorated(id);
        }
        jdbc.sql("UPDATE \"Expense\" SET status = 'APPROVED', \"approvedById\" = ?, \"approvalNotes\" = ?, \"updatedAt\" = "
                + NOW + " WHERE id = ?").params(approverId, approvalNotes, id).update();
        audit.recordInTx(AuditInput.of("Expense", id, "approve", null, Obj.of("approvedBy", approverId, "reason",
                approvalNotes)));
        return loadDecorated(id);
    }

    private String pendingRequest(String id) {
        return jdbc.sql("SELECT id FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\" = 'expense'"
                + " AND \"entityId\" = ? AND status = 'pending' LIMIT 1").params(org(), id).query(String.class)
                .optional().orElse(null);
    }

    /** Reject a draft expense. */
    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> exp = expense(id);
        if (exp == null) {
            throw ApiException.notFound("Expense not found");
        }
        if (!"DRAFT".equals(String.valueOf(exp.get("status")))) {
            throw ApiException.badRequest("Only draft expenses can be rejected");
        }
        String pending = pendingRequest(id);
        if (pending != null) {
            approvals.decide(pending, "rejected", reason);
        }
        jdbc.sql("UPDATE \"Expense\" SET status = 'REJECTED', \"approvalNotes\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ?").params(reason, id).update();
        if (pending == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("reason", reason);
            audit.recordInTx(AuditInput.of("Expense", id, "reject", null, values));
        }
        return loadDecorated(id);
    }

    /** Pay the residual of an approved / posted expense. */
    @Transactional
    public Map<String, Object> pay(String id, PayInput dto) {
        Map<String, Object> exp0 = expense(id);
        if (exp0 == null) {
            throw ApiException.notFound("Expense not found");
        }
        Map<String, Object> gate = newTx.execute(s -> approvals.checkOrRequestApproval("expense", id,
                Obj.of("amount", JsNumbers.js(Money.dec(exp0.get("amount"))))));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.badRequest("Approval required before paying this expense. Pending approval request "
                    + gate.get("requestId") + ".");
        }
        String payerId = requireActor();
        jdbc.sql("SELECT id FROM \"Expense\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, org())
                .query().listOfRows();
        Map<String, Object> exp = expense(id);
        if (exp == null) {
            throw ApiException.notFound("Expense not found");
        }
        String status = String.valueOf(exp.get("status"));
        if ("DRAFT".equals(status)) {
            throw ApiException.badRequest("Approve this expense before paying it");
        }
        if (!List.of("APPROVED", "POSTED").contains(status)) {
            throw ApiException.badRequest("Cannot pay an expense in status " + status);
        }
        if ("PAID".equals(String.valueOf(exp.get("paymentStatus")))) {
            throw ApiException.badRequest("Expense is already fully paid");
        }
        Map<String, Object> category = exp.get("categoryId") == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM"
                + " \"ExpenseCategory\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(exp.get("categoryId"), org()));
        recordPayment(exp, payerId, dto.paymentMethod(), dto.accountId(), dto.reference(), dto.paymentNotes(), category,
                null);
        publish("expense.paid", Obj.of("expenseId", id));
        Map<String, Object> result = loadDecorated(id);
        BusinessOutcome.record(jdbc, Obj.of("id", id, "paid", true), true);
        return result;
    }

    /** Void: reverse every posted payment JE and mark VOID. */
    @Transactional
    public Map<String, Object> voidExpense(String id, String voidReason) {
        if (voidReason == null || voidReason.trim().isEmpty()) {
            throw ApiException.badRequest("A void reason is required");
        }
        String actorId = requireActor();
        jdbc.sql("SELECT id FROM \"Expense\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, org())
                .query().listOfRows();
        Map<String, Object> exp = expense(id);
        if (exp == null) {
            throw ApiException.notFound("Expense not found");
        }
        if (List.of("VOID", "CANCELLED").contains(String.valueOf(exp.get("status")))) {
            throw ApiException.badRequest("Expense is already void/cancelled");
        }
        List<Map<String, Object>> payments = SqlRows.list(jdbc.sql("SELECT id, \"journalEntryId\" FROM \"ExpensePayment\""
                + " WHERE \"expenseId\" = ? AND \"organizationId\" = ? AND status = 'posted'").params(id, org()));
        for (Map<String, Object> p : payments) {
            if (p.get("journalEntryId") != null) {
                posting.reverse((String) p.get("journalEntryId"), null,
                        "Void expense " + exp.get("expenseCode") + ": " + voidReason);
            }
            jdbc.sql("UPDATE \"ExpensePayment\" SET status = 'void', \"voidReason\" = ? WHERE id = ?")
                    .params(voidReason, p.get("id")).update();
        }
        jdbc.sql("UPDATE \"Expense\" SET status = 'VOID', \"paymentStatus\" = 'UNPAID', \"amountPaid\" = 0, \"paidAt\" = NULL,"
                + " \"updatedAt\" = " + NOW + " WHERE id = ?").param(id).update();
        audit.recordInTx(AuditInput.of("Expense", id, "cancel", null, Obj.of("reason", voidReason, "reversedPayments",
                payments.size(), "voidedBy", actorId)));
        BusinessOutcome.record(jdbc, Obj.of("id", id, "voided", true), true);
        publish("expense.voided", Obj.of("expenseId", id));
        return loadDecorated(id);
    }

    /** Soft delete an unpaid or voided expense. */
    @Transactional
    public Map<String, Object> remove(String id) {
        Map<String, Object> exp = expense(id);
        if (exp == null) {
            throw ApiException.notFound("Expense not found");
        }
        if (!"UNPAID".equals(String.valueOf(exp.get("paymentStatus"))) && !"VOID".equals(String.valueOf(exp.get("status")))) {
            throw ApiException.badRequest("Only unpaid or voided expenses can be deleted");
        }
        jdbc.sql("UPDATE \"Expense\" SET \"deletedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ?").param(id)
                .update();
        audit.recordInTx(AuditInput.of("Expense", id, "delete"));
        return Obj.of("id", id, "deleted", true);
    }

    // ------------------------------------------------------------------ internals

    private void recordPayment(Map<String, Object> expense, String paidBy, String paymentMethod, String accountId,
            String reference, String paymentNotes, Map<String, Object> category, PostingTags explicitTags) {
        BigDecimal residual = Money.dec(expense.get("amount")).subtract(Money.dec(expense.get("amountPaid")));
        if (residual.signum() <= 0) {
            throw ApiException.badRequest("Nothing left to pay");
        }
        String org = org();
        PostingTags tags = explicitTags != null ? explicitTags : tagsFor((String) expense.get("id"));
        String debit = tags != null && tags.expenseAccountId() != null ? tags.expenseAccountId()
                : resolveExpenseAccountId(category);
        if (debit == null) {
            throw ApiException.badRequest("Configure an expense account (category ledger or default_expense mapping)"
                    + " before paying expenses");
        }
        TreasuryGuards.lockAccounts(jdbc, org, List.of(accountId));
        Map<String, Object> credit = SqlRows.one(jdbc.sql("SELECT a.id, a.name, c.\"isCashEquivalent\" FROM \"Account\" a"
                + " LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ?"
                + " AND a.\"isPostable\" = true AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL").params(accountId, org));
        if (credit == null || !Boolean.TRUE.equals(credit.get("isCashEquivalent"))) {
            throw ApiException.badRequest("Pay expenses from an active cash, bank or mobile-money account");
        }
        TreasuryGuards.assertNotDrawerAccount(jdbc, org, (String) credit.get("id"), "An expense payment");
        TreasuryGuards.assertSufficientFunds(jdbc, org, (String) credit.get("id"), residual, (String) credit.get("name"));
        String paymentId = UUID.randomUUID().toString();
        String title = (String) expense.get("title");
        String value = JsNumbers.dec(residual);
        Map<String, Object> entry = posting.post(PostingRequest.of(journalForMethod(paymentMethod), Instant.now(),
                "Expense " + expense.get("expenseCode") + " - " + title, "expense_payment", (String) expense.get("id"),
                "expense_payment:" + paymentId, List.of(tags == null ? Line.debit(debit, value, title)
                        : new Line(debit, value, null, null, title, tags.branchId(), tags.costCenterId(),
                                tags.dimensions()),
                        Line.credit((String) credit.get("id"), value, "Paid: " + title))));
        jdbc.sql("INSERT INTO \"ExpensePayment\" (id, \"organizationId\", \"expenseId\", amount, \"paymentMethod\", reference,"
                        + " \"paymentNotes\", \"accountId\", \"paidById\", \"journalEntryId\", status, \"createdAt\") VALUES (?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?, ?, 'posted', " + NOW + ")")
                .params(paymentId, org, expense.get("id"), residual, paymentMethod, reference, paymentNotes, accountId,
                        paidBy, entry.get("id"))
                .update();
        BigDecimal newPaid = Money.dec(expense.get("amountPaid")).add(residual);
        String status = String.valueOf(expense.get("status"));
        jdbc.sql("UPDATE \"Expense\" SET \"amountPaid\" = ?, \"paymentStatus\" = ?::\"ExpensePaymentStatus\", \"paidAt\" = "
                        + NOW + ", status = ?::\"ExpenseStatus\", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(newPaid, newPaid.compareTo(Money.dec(expense.get("amount"))) >= 0 ? "PAID" : "PARTIALLY_PAID",
                        "APPROVED".equals(status) || "DRAFT".equals(status) ? "POSTED" : status, expense.get("id"))
                .update();
        Map<String, Object> values = Obj.of("paymentId", paymentId, "amount", value, "method", paymentMethod, "reason",
                paymentNotes, "journalEntryId", entry.get("id"));
        audit.recordInTx(AuditInput.of("ExpensePayment", (String) expense.get("id"), "post", null, values));
    }

    /** The first tagger claiming the expense, or null (no tagger registered / none owns it). */
    private PostingTags tagsFor(String expenseId) {
        for (ExpensePostingTagger t : taggers) {
            PostingTags tags = t.tagsFor(expenseId);
            if (tags != null) {
                return tags;
            }
        }
        return null;
    }

    private String resolveExpenseAccountId(Map<String, Object> category) {
        String org = org();
        if (category != null && category.get("ledgerAccountId") != null) {
            String acc = jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                            + " WHERE a.id = ? AND a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND a.\"isPostable\" = true"
                            + " AND a.\"isActive\" = true AND c.classification = 'expense'")
                    .params(category.get("ledgerAccountId"), org).query(String.class).optional().orElse(null);
            if (acc != null) {
                return acc;
            }
        }
        String mapping = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                + " 'default_expense' AND \"deletedAt\" IS NULL LIMIT 1").param(org).query(String.class).optional()
                .orElse(null);
        if (mapping != null) {
            return mapping;
        }
        return jdbc.sql("SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                        + " a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL AND c.classification = 'expense'"
                        + " AND a.\"isPostable\" = true AND a.\"isActive\" = true AND a.\"deprecatedAt\" IS NULL"
                        + " ORDER BY a.\"sortOrder\" ASC, a.code ASC LIMIT 1")
                .param(org).query(String.class).optional().orElse(null);
    }

    private static String requireActor() {
        String userId = TenantContext.userId();
        if (userId == null) {
            throw ApiException.forbidden("An authenticated user is required");
        }
        return userId;
    }

    /** Frontend shape: nested category / supplier / createdBy / approvedBy + latest payment method / reference. */
    private List<Map<String, Object>> decorate(List<Map<String, Object>> rows) {
        LinkedHashSet<String> supplierIds = new LinkedHashSet<>();
        LinkedHashSet<String> userIds = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            if (r.get("supplierId") != null) {
                supplierIds.add((String) r.get("supplierId"));
            }
            for (String k : List.of("createdById", "approvedById")) {
                if (r.get(k) != null) {
                    userIds.add((String) r.get(k));
                }
            }
        }
        Map<String, Map<String, Object>> suppliers = new HashMap<>();
        if (!supplierIds.isEmpty()) {
            for (Map<String, Object> s : jdbc.sql("SELECT id, name, phone FROM \"Partner\" WHERE id IN (:ids)"
                    + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(supplierIds))
                    .param("org", org()).query().listOfRows()) {
                suppliers.put((String) s.get("id"), s);
            }
        }
        Map<String, Map<String, Object>> users = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Map<String, Object> u : jdbc.sql("SELECT id, \"firstName\", \"lastName\" FROM \"User\" WHERE id IN (:ids)"
                    + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(userIds))
                    .param("org", org()).query().listOfRows()) {
                users.put((String) u.get("id"), u);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("amount", JsNumbers.js(Money.dec(r.get("amount"))));
            m.put("amountPaid", JsNumbers.js(Money.dec(r.get("amountPaid"))));
            @SuppressWarnings("unchecked")
            Map<String, Object> cat = (Map<String, Object>) r.get("category");
            m.put("category", cat == null ? null : Obj.of("id", cat.get("id"), "name", cat.get("name"), "icon",
                    cat.get("icon")));
            Map<String, Object> supplier = r.get("supplierId") == null ? null : suppliers.get((String) r.get("supplierId"));
            m.put("supplier", supplier == null ? null : supplierShape(supplier));
            m.put("createdBy", staff(users, r.get("createdById")));
            m.put("approvedBy", staff(users, r.get("approvedById")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> payments = (List<Map<String, Object>>) r.get("payments");
            Map<String, Object> latest = payments == null || payments.isEmpty() ? null : payments.getFirst();
            m.put("paymentMethod", latest == null ? null : latest.get("paymentMethod"));
            m.put("paymentReference", latest == null ? null : latest.get("reference"));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> staff(Map<String, Map<String, Object>> users, Object uid) {
        if (uid == null) {
            return null;
        }
        Map<String, Object> u = users.get((String) uid);
        if (u == null) {
            return null;
        }
        return Obj.of("staff", Obj.of("firstName", u.get("firstName") == null ? "" : u.get("firstName"), "lastName",
                u.get("lastName") == null ? "" : u.get("lastName")));
    }
}
