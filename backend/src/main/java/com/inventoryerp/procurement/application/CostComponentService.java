package com.inventoryerp.procurement.application;

import com.inventoryerp.accounting.application.AccountingPolicyService;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cost component master (ADR-S007 §2): the charge types a landed cost voucher uses, each with its default IAS 2
 * treatment ({@code capitalize} into inventory, {@code expense}, or {@code recoverable_tax} → input tax), default
 * allocation basis and expense account. Seeded with the standard import components on first use; organisations edit
 * or add their own.
 */
@Service
public class CostComponentService {

    static final Set<String> TREATMENTS = Set.of("capitalize", "expense", "recoverable_tax");
    static final Set<String> METHODS = Set.of("value", "quantity", "equal", "weight", "volume", "manual");
    static final Set<String> KINDS = Set.of("freight", "duty", "insurance", "handling", "other");

    /** Create / update body. */
    public record ComponentInput(String code, String name, String kind, String treatment, String defaultAllocationMethod,
            String expenseAccountId, String description, Boolean isActive) {}

    private static final String[][] DEFAULTS = {
        {"DUTY", "Import duty (non-recoverable)", "duty", "capitalize", "value"},
        {"FREIGHT", "Freight & transport to destination", "freight", "capitalize", "value"},
        {"CLEARING", "Clearing & agency fees", "handling", "capitalize", "value"},
        {"INSURANCE", "Insurance in transit", "insurance", "capitalize", "value"},
        {"HANDLING", "Loading & handling", "handling", "capitalize", "quantity"},
        {"IMPORT_VAT", "Import VAT (recoverable)", "other", "recoverable_tax", "value"},
        {"DEMURRAGE", "Demurrage, penalties & avoidable costs", "other", "expense", "value"},
        {"SELLING", "Selling & distribution after acquisition", "other", "expense", "value"},
        {"ADMIN", "General administration", "other", "expense", "value"},
    };

    private final JdbcClient jdbc;
    private final AccountingPolicyService policies;

    public CostComponentService(JdbcClient jdbc, AccountingPolicyService policies) {
        this.jdbc = jdbc;
        this.policies = policies;
    }

    /** Seed the standard components once per organisation. */
    void ensureDefaults() {
        long n = jdbc.sql("SELECT count(*) FROM \"CostComponent\" WHERE \"organizationId\" = ?").param(org())
                .query(Long.class).single();
        if (n > 0) {
            return;
        }
        for (String[] d : DEFAULTS) {
            jdbc.sql("INSERT INTO \"CostComponent\" (id, \"organizationId\", code, name, kind, treatment,"
                            + " \"defaultAllocationMethod\") VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
                    .params(UUID.randomUUID().toString(), org(), d[0], d[1], d[2], d[3], d[4]).update();
        }
    }

    @Transactional
    public List<Map<String, Object>> list() {
        ensureDefaults();
        policies.seedComponentDefaults();
        return SqlRows.list(jdbc.sql("SELECT c.*, a.code AS \"expenseAccountCode\", a.name AS \"expenseAccountName\""
                + " FROM \"CostComponent\" c LEFT JOIN \"Account\" a ON a.id = c.\"expenseAccountId\""
                + " WHERE c.\"organizationId\" = ? ORDER BY c.code").param(org()));
    }

    Map<String, Object> find(String id) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"CostComponent\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
    }

    @Transactional
    public Map<String, Object> create(ComponentInput in) {
        validate(in, true);
        String id = UUID.randomUUID().toString();
        long dup = jdbc.sql("SELECT count(*) FROM \"CostComponent\" WHERE \"organizationId\" = ? AND code = ?")
                .params(org(), in.code()).query(Long.class).single();
        if (dup > 0) {
            throw ApiException.conflict("Cost component " + in.code() + " already exists");
        }
        jdbc.sql("INSERT INTO \"CostComponent\" (id, \"organizationId\", code, name, kind, treatment,"
                        + " \"defaultAllocationMethod\", \"expenseAccountId\", description, \"isActive\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.code(), in.name(), in.kind() == null ? "other" : in.kind(), in.treatment(),
                        in.defaultAllocationMethod() == null ? "value" : in.defaultAllocationMethod(),
                        in.expenseAccountId(), in.description(), in.isActive() == null || in.isActive())
                .update();
        return find(id);
    }

    @Transactional
    public Map<String, Object> update(String id, ComponentInput in) {
        Map<String, Object> c = find(id);
        if (c == null) {
            throw ApiException.notFound("Cost component not found");
        }
        validate(in, false);
        if ("capitalize".equals(in.treatment())) {
            policies.assertCapitalisable("cost_component", id, "Cost component " + c.get("code"), false);
        }
        jdbc.sql("UPDATE \"CostComponent\" SET name = COALESCE(?, name), kind = COALESCE(?, kind), treatment ="
                        + " COALESCE(?, treatment), \"defaultAllocationMethod\" = COALESCE(?, \"defaultAllocationMethod\"),"
                        + " \"expenseAccountId\" = COALESCE(?, \"expenseAccountId\"), description = COALESCE(?, description),"
                        + " \"isActive\" = COALESCE(?, \"isActive\"), \"updatedAt\" = " + NOW + " WHERE id = ?"
                        + " AND \"organizationId\" = ?")
                .params(in.name(), in.kind(), in.treatment(), in.defaultAllocationMethod(), in.expenseAccountId(),
                        in.description(), in.isActive(), id, org())
                .update();
        return find(id);
    }

    private void validate(ComponentInput in, boolean create) {
        if (create && (in.code() == null || in.code().isBlank() || in.name() == null || in.name().isBlank())) {
            throw ApiException.badRequest("code and name are required");
        }
        if ((create || in.treatment() != null) && !TREATMENTS.contains(String.valueOf(in.treatment()))) {
            throw ApiException.badRequest("treatment must be one of the following values: capitalize, expense,"
                    + " recoverable_tax");
        }
        if (in.kind() != null && !KINDS.contains(in.kind())) {
            throw ApiException.badRequest("kind must be one of the following values: freight, duty, insurance, handling,"
                    + " other");
        }
        if (in.defaultAllocationMethod() != null && !METHODS.contains(in.defaultAllocationMethod())) {
            throw ApiException.badRequest("defaultAllocationMethod must be one of the following values: value, quantity,"
                    + " equal, weight, volume, manual");
        }
        if (in.expenseAccountId() != null) {
            long n = jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL").params(in.expenseAccountId(), org()).query(Long.class).single();
            if (n == 0) {
                throw ApiException.badRequest("Expense account not found");
            }
        }
    }
}
