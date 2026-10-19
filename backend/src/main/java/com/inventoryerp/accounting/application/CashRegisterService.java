package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.CashRegister;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash registers (port of {@code cash-register.service.ts}): creation provisions a distinct drawer cash
 * account {@code DRW-<code>}; custody changes (drawer, location, branch, deactivation) are refused while a
 * shift is open; drawer GL balance for the open-shift dialog.
 */
@Service
public class CashRegisterService extends BaseCrudService<CashRegister> {

    private final JdbcClient jdbc;

    public CashRegisterService(EntityManager em, JdbcClient jdbc) {
        super(em, CashRegister.class);
        this.jdbc = jdbc;
    }

    @Override
    protected String entityName() {
        return "CashRegister";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("code", "name");
    }

    @Override
    protected List<Map<String, Object>> render(List<CashRegister> rows) {
        return rows.stream().map(r -> {
            Map<String, Object> m = EntityJson.of(r);
            m.put("defaultAccount", one(em, Account.class, r.getDefaultAccountId()));
            m.put("location", r.getLocationId() == null ? null : SqlRows.one(
                    jdbc.sql("SELECT * FROM \"InventoryLocation\" WHERE id = ?").param(r.getLocationId())));
            return m;
        }).toList();
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> data) {
        String orgId = TenantContext.organizationId();
        String locationId = (String) data.get("locationId");
        if (locationId != null && !locationId.isEmpty()) {
            assertLocation(locationId);
        }
        String branchId = (String) data.get("branchId");
        if (branchId != null && !branchId.isEmpty()) {
            assertBranch(branchId);
        }
        String code = (String) data.get("code");
        String drawerCode = "DRW-" + code;
        record Existing(String id, boolean isActive, Instant deletedAt, String categoryKey) {}
        Existing existing = jdbc.sql("SELECT a.id, a.\"isActive\", a.\"deletedAt\", c.key FROM \"Account\" a"
                        + " LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ? AND a.code = ?")
                .params(orgId, drawerCode)
                .query((rs, n) -> new Existing(rs.getString(1), rs.getBoolean(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4)))
                .optional().orElse(null);
        String drawerAccountId;
        if (existing == null) {
            record Cat(String id, String normalBalance) {}
            Cat cash = jdbc.sql("SELECT id, \"normalBalance\"::text FROM \"AccountCategory\" WHERE key = 'cash' LIMIT 1")
                    .query((rs, n) -> new Cat(rs.getString(1), rs.getString(2))).optional().orElse(null);
            if (cash == null) {
                throw ApiException.badRequest("Account category 'cash' is missing for this organization. Run the"
                        + " accounting backfill (prisma/backfill-account-category.ts).");
            }
            Account drawer = new Account();
            drawer.setOrganizationId(orgId);
            drawer.setCode(drawerCode);
            drawer.setName("Cash Drawer - " + data.get("name"));
            drawer.setCategoryId(cash.id());
            EntityPatch.set(drawer, "normalBalance", cash.normalBalance());
            drawer.setIsDefault(false);
            em.persist(drawer);
            em.flush();
            drawerAccountId = drawer.getId();
        } else {
            if (!existing.isActive() || existing.deletedAt() != null
                    || !List.of("cash", "petty_cash").contains(existing.categoryKey() == null ? "" : existing.categoryKey())) {
                throw ApiException.badRequest(drawerCode + " exists but is not an active drawer cash account");
            }
            long used = jdbc.sql("SELECT count(*) FROM \"CashRegister\" WHERE \"organizationId\" = ? AND \"defaultAccountId\" = ?"
                            + " AND \"isActive\" = true AND \"deletedAt\" IS NULL")
                    .params(orgId, existing.id()).query(Long.class).single();
            if (used > 0) {
                throw ApiException.badRequest("Each active register must use a distinct drawer account");
            }
            drawerAccountId = existing.id();
        }
        CashRegister row = EntityPatch.apply(newInstance(), data);
        row.setOrganizationId(orgId);
        row.setDefaultAccountId(drawerAccountId);
        return render(persist(row));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> data) {
        String orgId = TenantContext.organizationId();
        String defaultAccountId = (String) data.get("defaultAccountId");
        if (defaultAccountId != null && !defaultAccountId.isEmpty()) {
            assertAvailableDrawerAccount(defaultAccountId, id);
        }
        String locationId = (String) data.get("locationId");
        if (locationId != null && !locationId.isEmpty()) {
            assertLocation(locationId);
        }
        String branchId = (String) data.get("branchId");
        if (branchId != null && !branchId.isEmpty()) {
            assertBranch(branchId);
        }
        List<String> locked = jdbc.sql("SELECT id FROM \"CashRegister\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, orgId).query(String.class).list();
        if (locked.isEmpty()) {
            throw ApiException.notFound("CashRegister not found");
        }
        CashRegister current = find(id);
        if (current == null) {
            throw ApiException.notFound("CashRegister not found");
        }
        boolean changesCustody = (defaultAccountId != null && !defaultAccountId.isEmpty()
                && !defaultAccountId.equals(current.getDefaultAccountId()))
                || (data.containsKey("locationId") && !Objects.equals(data.get("locationId"), current.getLocationId()))
                || (data.containsKey("branchId") && !Objects.equals(data.get("branchId"), current.getBranchId()))
                || Boolean.FALSE.equals(data.get("isActive"));
        if (changesCustody && openSessions(id) > 0) {
            throw ApiException.conflict("Close or hand over the active shift before changing its account, location, branch"
                    + " or status");
        }
        EntityPatch.apply(current, data);
        em.flush();
        return render(current);
    }

    /** What the GL says is in this register's drawer. */
    @Transactional(readOnly = true)
    public Map<String, Object> drawerBalance(String id) {
        CashRegister register = find(id);
        if (register == null) {
            throw ApiException.notFound("Cash register not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registerId", id);
        out.put("drawerAccountId", register.getDefaultAccountId());
        out.put("ledger", register.getDefaultAccountId() == null ? 0
                : SessionReconciliation.accountLedgerBalance(jdbc, TenantContext.organizationId(),
                        register.getDefaultAccountId()));
        return out;
    }

    @Override
    @Transactional
    public void remove(String id) {
        String orgId = TenantContext.organizationId();
        jdbc.sql("SELECT id FROM \"CashRegister\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE").params(id, orgId)
                .query(String.class).list();
        if (openSessions(id) > 0) {
            throw ApiException.conflict("An open register cannot be deleted");
        }
        int n = jdbc.sql("UPDATE \"CashRegister\" SET \"deletedAt\" = now(), \"isActive\" = false, \"updatedAt\" = now()"
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(id, orgId).update();
        if (n == 0) {
            throw ApiException.notFound("CashRegister not found");
        }
    }

    private long openSessions(String registerId) {
        return jdbc.sql("SELECT count(*) FROM \"CashSession\" WHERE \"organizationId\" = ? AND \"cashRegisterId\" = ?"
                        + " AND status = 'open'")
                .params(TenantContext.organizationId(), registerId).query(Long.class).single();
    }

    private void assertAvailableDrawerAccount(String accountId, String registerId) {
        String orgId = TenantContext.organizationId();
        String category = jdbc.sql("SELECT c.key FROM \"Account\" a LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE a.id = ? AND a.\"organizationId\" = ? AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL")
                .params(accountId, orgId).query(String.class).optional().orElse("");
        if (!List.of("cash", "petty_cash").contains(category == null ? "" : category)) {
            throw ApiException.badRequest("Select an active cash or petty-cash account");
        }
        long used = jdbc.sql("SELECT count(*) FROM \"CashRegister\" WHERE \"organizationId\" = ? AND id <> ?"
                        + " AND \"defaultAccountId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL")
                .params(orgId, registerId, accountId).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Each active register must use a distinct drawer account");
        }
    }

    private void assertLocation(String locationId) {
        long n = jdbc.sql("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL")
                .params(locationId, TenantContext.organizationId()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest("Select an active inventory location in this organization");
        }
    }

    private void assertBranch(String branchId) {
        long n = jdbc.sql("SELECT count(*) FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ? AND \"isActive\" = true"
                        + " AND \"deletedAt\" IS NULL")
                .params(branchId, TenantContext.organizationId()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest("Select an active branch in this organization");
        }
    }
}
