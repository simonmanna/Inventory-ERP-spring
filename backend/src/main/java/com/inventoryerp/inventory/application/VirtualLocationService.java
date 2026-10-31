package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * System (virtual) locations other modules need by a fixed code, e.g. {@code QC-QUARANTINE} (ADR-S006 / ADR-S013):
 * found by code, else created and committed in its own transaction (so concurrent first uses converge on one row and
 * a later rollback of the caller does not lose it). Additive API; no existing behaviour changes.
 */
@Service
public class VirtualLocationService {

    /** The quarantine location shared by manufacturing QC (ADR-S006) and quality lots (ADR-S013). */
    public static final String QC_QUARANTINE = "QC-QUARANTINE";

    private final JdbcClient jdbc;
    private final TransactionTemplate newTx;

    public VirtualLocationService(JdbcClient jdbc, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Id of the live location {@code code} of the current organization, created as a virtual location if absent. */
    public String ensure(String code, String name) {
        String org = TenantContext.organizationId();
        String existing = find(org, code);
        if (existing != null) {
            return existing;
        }
        return newTx.execute(s -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").param("vloc:" + org + ":" + code)
                    .query().listOfRows();
            String again = find(org, code);
            if (again != null) {
                return again;
            }
            String id = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code, name, type, \"isActive\","
                            + " \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, 'virtual'::\"LocationType\","
                            + " true, ?, now(), now())")
                    .params(id, org, code, name, TenantContext.userId()).update();
            return id;
        });
    }

    private String find(String org, String code) {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND code = ? ORDER BY \"createdAt\" LIMIT 1").params(org, code).query(String.class).optional()
                .orElse(null);
    }
}
