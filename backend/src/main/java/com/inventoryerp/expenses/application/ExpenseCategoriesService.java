package com.inventoryerp.expenses.application;

import com.inventoryerp.expenses.domain.ExpenseCategory;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User-managed expense categories (port of {@code expense-categories.service.ts}); each may point at a postable GL
 * expense account ({@code ledgerAccountId}) that its expenses debit when paid.
 */
@Service
public class ExpenseCategoriesService {

    /** Create / update body. */
    public record CategoryInput(String name, String icon, String description, String ledgerAccountId, Boolean isActive) {}

    private final JdbcClient jdbc;

    public ExpenseCategoriesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private List<Map<String, Object>> withLedger(List<Map<String, Object>> categories) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        categories.forEach(c -> {
            if (c.get("ledgerAccountId") != null) {
                ids.add((String) c.get("ledgerAccountId"));
            }
        });
        Map<String, Map<String, Object>> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> a : jdbc.sql("SELECT id, name, code FROM \"Account\" WHERE id IN (:ids)"
                    + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL").param("ids", List.copyOf(ids))
                    .param("org", org()).query().listOfRows()) {
                byId.put((String) a.get("id"), a);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : categories) {
            c.put("ledgerAccount", c.get("ledgerAccountId") != null ? byId.get((String) c.get("ledgerAccountId")) : null);
            out.add(c);
        }
        return out;
    }

    private Map<String, Object> row(String id) {
        return SqlRows.one(ExpenseCategory.class, jdbc.sql("SELECT * FROM \"ExpenseCategory\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
    }

    /** Live categories by name. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return withLedger(SqlRows.list(ExpenseCategory.class, jdbc.sql("SELECT * FROM \"ExpenseCategory\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL ORDER BY name ASC").param(org())));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> cat = row(id);
        if (cat == null) {
            throw ApiException.notFound("Expense category not found");
        }
        return withLedger(List.of(cat)).getFirst();
    }

    @Transactional
    public Map<String, Object> create(CategoryInput dto) {
        String name = dto.name() == null ? null : dto.name().trim();
        if (name == null || name.isEmpty()) {
            throw ApiException.badRequest("Name is required");
        }
        assertLedgerValid(dto.ledgerAccountId());
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ExpenseCategory\" (id, \"organizationId\", name, icon, description, \"ledgerAccountId\","
                        + " \"createdBy\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, now())")
                .params(id, org(), name, dto.icon(), dto.description(), dto.ledgerAccountId(), TenantContext.userId())
                .update();
        return withLedger(List.of(row(id))).getFirst();
    }

    @Transactional
    public Map<String, Object> update(String id, CategoryInput dto) {
        if (row(id) == null) {
            throw ApiException.notFound("Expense category not found");
        }
        if (EntityPatch.present("ledgerAccountId", dto.ledgerAccountId())) {
            assertLedgerValid(dto.ledgerAccountId());
        }
        List<Object> params = new ArrayList<>();
        StringBuilder set = new StringBuilder("\"updatedAt\" = now()");
        if (EntityPatch.present("name", dto.name())) {
            set.append(", name = ?");
            params.add(dto.name() == null ? null : dto.name().trim());
        }
        if (EntityPatch.present("icon", dto.icon())) {
            set.append(", icon = ?");
            params.add(emptyToNull(dto.icon()));
        }
        if (EntityPatch.present("description", dto.description())) {
            set.append(", description = ?");
            params.add(emptyToNull(dto.description()));
        }
        if (EntityPatch.present("ledgerAccountId", dto.ledgerAccountId())) {
            set.append(", \"ledgerAccountId\" = ?");
            params.add(emptyToNull(dto.ledgerAccountId()));
        }
        if (EntityPatch.present("isActive", dto.isActive())) {
            set.append(", \"isActive\" = ?");
            params.add(dto.isActive());
        }
        params.add(id);
        params.add(org());
        jdbc.sql("UPDATE \"ExpenseCategory\" SET " + set + " WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(params).update();
        return findOne(id);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Soft delete. */
    @Transactional
    public Map<String, Object> remove(String id) {
        if (row(id) == null) {
            throw ApiException.notFound("Expense category not found");
        }
        jdbc.sql("UPDATE \"ExpenseCategory\" SET \"deletedAt\" = now(), \"isActive\" = false, \"updatedAt\" = now()"
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()).update();
        return Obj.of("id", id, "deleted", true);
    }

    private void assertLedgerValid(String ledgerAccountId) {
        if (ledgerAccountId == null || ledgerAccountId.isEmpty()) {
            return;
        }
        Map<String, Object> acc = SqlRows.one(jdbc.sql("SELECT \"isPostable\", \"isActive\" FROM \"Account\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(ledgerAccountId, org()));
        if (acc == null) {
            throw ApiException.badRequest("Ledger account not found");
        }
        if (!Boolean.TRUE.equals(acc.get("isPostable")) || !Boolean.TRUE.equals(acc.get("isActive"))) {
            throw ApiException.badRequest("Ledger account must be a postable (non-group, active) account");
        }
    }
}
