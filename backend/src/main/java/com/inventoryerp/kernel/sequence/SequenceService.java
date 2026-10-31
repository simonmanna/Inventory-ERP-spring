package com.inventoryerp.kernel.sequence;

import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Savepoints;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Native Postgres SEQUENCE numbering per (organization, key) (port of {@code sequence.service.ts}):
 * {@code seq_<first 8 hex of org id>_<sanitized key>}, created lazily, formatted
 * {@code prefix + padStart(padding, '0')}. Called inside the consuming transaction the number is
 * reserved atomically with it (a rollback leaves a gap, never a duplicate). A tenant's row in
 * {@code DocumentNumberFormat} replaces the prefix and padding for keys of {@link DocumentNumberCatalog}; every other
 * key, POS included, does not even look.
 */
@Service
public class SequenceService {

    private static final Logger log = LoggerFactory.getLogger(SequenceService.class);
    private static final int DEFAULT_PADDING = 5;

    /** Logical sequence keys known to the platform. */
    public static final List<String> KNOWN_KEYS = List.of(
            "stock_move", "stock_out", "waste_doc", "stock_adj", "stock_transfer", "bom", "production_order",
            "production_request", "production_plan", "direct_stock_in", "direct_stock_out", "invoice", "creditnote",
            "vendorbill", "payment_inbound", "payment_outbound", "receipt", "cashsession", "journal_sales", "journal_purch",
            "journal_cash", "journal_bank", "journal_inv", "journal_adj", "asset", "repair_order", "repair_quotation",
            "repair_job", "repair_contract", "repair_technician", "hr_employee", "hr_timesheet", "hr_leave_request",
            "hr_payroll_period", "hr_payroll_run", "hr_payslip", "hr_advance", "hr_loan", "hr_bank_payment", "debitnote",
            "proforma", "posreceipt", "deliverynote", "quotation", "rentalagreement", "rentalreturn", "rentaldeposit",
            "rentalserviceorder", "feeinvoice", "feereceipt", "admission", "certificate", "reportcard");

    private final JdbcClient jdbc;
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    public SequenceService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Remove sequences left by deleted tenants. */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupOrphans() {
        try {
            jdbc.sql("""
                    DO $cleanup$
                    DECLARE seq record;
                    BEGIN
                      FOR seq IN
                        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = 'public' AND c.relkind = 'S' AND c.relname ~ '^seq_[0-9a-f]{8}_'
                          AND substring(c.relname from 5 for 8) NOT IN (
                            SELECT substring(replace(id::text, '-', '') from 1 for 8) FROM "Organization")
                      LOOP
                        EXECUTE format('DROP SEQUENCE IF EXISTS %I', seq.relname);
                      END LOOP;
                    END $cleanup$""").update();
        } catch (RuntimeException err) {
            log.warn("Orphan sequence cleanup failed (numbering remains available): {}", err.toString());
        }
    }

    /** Next number for {@code key} in the current organization. */
    public String next(String key, String prefix, Integer padding) {
        String organizationId = TenantContext.organizationId();
        ensure(organizationId, key);
        long reserved = jdbc.sql("SELECT nextval('\"" + seqName(organizationId, key) + "\"')").query(Long.class).single();
        String digits = String.valueOf(reserved);
        if (DocumentNumberCatalog.configurable(key)) {
            String custom = customNumber(organizationId, key, digits);
            if (custom != null) {
                return custom;
            }
        }
        int pad = padding == null ? DEFAULT_PADDING : padding;
        return (prefix == null ? "" : prefix) + "0".repeat(Math.max(0, pad - digits.length())) + digits;
    }

    private record Format(String prefix, String suffix, int padding) {}

    private String customNumber(String organizationId, String key, String digits) {
        return jdbc.sql("SELECT prefix, suffix, padding FROM \"DocumentNumberFormat\" WHERE \"organizationId\" = ? AND"
                        + " \"sequenceKey\" = ?")
                .params(organizationId, DocumentNumberCatalog.baseKey(key))
                .query((rs, n) -> new Format(rs.getString("prefix"), rs.getString("suffix"), rs.getInt("padding")))
                .optional()
                .map(f -> {
                    LocalDate today = LocalDate.now(BusinessZone.get());
                    return DocumentNumberCatalog.render(f.prefix(), f.suffix(), f.padding(), digits, keyYear(key, today),
                            today.getMonthValue());
                })
                .orElse(null);
    }

    /** The year in the key ({@code po:2026}) when there is one: it is what separates yearly counters. */
    private static int keyYear(String key, LocalDate today) {
        String last = key.substring(key.lastIndexOf(':') + 1);
        return last.matches("\\d{4}") ? Integer.parseInt(last) : today.getYear();
    }

    /** The number the next call would give, without taking it. */
    long peekNext(String key) {
        String name = seqName(TenantContext.organizationId(), key);
        boolean exists = Boolean.TRUE.equals(jdbc.sql("SELECT to_regclass(?) IS NOT NULL").param("\"" + name + "\"")
                .query(Boolean.class).single());
        if (!exists) {
            return 1;
        }
        return jdbc.sql("SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM \"" + name + "\"")
                .query(Long.class).single();
    }

    /** Make {@code next} the next number. The caller has checked that this moves forward. */
    void restart(String key, long next) {
        String organizationId = TenantContext.organizationId();
        ensure(organizationId, key);
        // setval, not ALTER SEQUENCE ... RESTART: the API's database role is not the owner of sequences created by
        // migrations or by an earlier owner-run instance, and setval only needs UPDATE on the sequence.
        jdbc.sql("SELECT setval(?::regclass, ?, false)").params("\"" + seqName(organizationId, key) + "\"", next)
                .query(Long.class).single();
    }

    private void ensure(String organizationId, String key) {
        String name = seqName(organizationId, key);
        if (ensured.contains(name)) {
            return;
        }
        // Already there: no write at all. This is every call after a restart. Inside a transaction the sequence may
        // be one this same transaction created under a savepoint (still uncommitted), so only cache it once the
        // transaction commits; a rollback drops it and the next call must create it again.
        if (Boolean.TRUE.equals(jdbc.sql("SELECT to_regclass(?) IS NOT NULL").param("\"" + name + "\"")
                .query(Boolean.class).single())) {
            cacheAfterCommit(name);
            return;
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            create(name);
            ensured.add(name);
            return;
        }
        // Nest creates it on the raw client, outside the caller's transaction. A REQUIRES_NEW transaction here would
        // hold the caller's pooled connection while waiting for a second one, and many tills taking the first number
        // of a fresh sequence (e.g. the first invoice of a new year) then deadlock the pool. Create it inside the
        // caller's transaction under a savepoint instead, and do not cache it: if the caller rolls back, the
        // sequence goes with it and the next call creates it again.
        try {
            // Raw CREATE: the duplicate error must reach Savepoints so it rolls back to the savepoint.
            Savepoints.run(jdbc, () -> jdbc.sql(createSql(name)).update());
        } catch (RuntimeException err) {
            if (!duplicate(err)) {
                throw err;
            }
        }
    }

    private void cacheAfterCommit(String name) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            ensured.add(name);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                ensured.add(name);
            }
        });
    }

    private void create(String name) {
        try {
            jdbc.sql(createSql(name)).update();
        } catch (RuntimeException err) {
            // Concurrent first use: Postgres can still raise "already exists" / a pg_type duplicate key.
            if (!duplicate(err)) {
                throw err;
            }
        }
    }

    private static String createSql(String name) {
        return "CREATE SEQUENCE IF NOT EXISTS \"" + name + "\" INCREMENT BY 1 START WITH 1";
    }

    private static boolean duplicate(RuntimeException err) {
        String cause = String.valueOf(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(err)
                .getMessage());
        return cause.contains("already exists") || cause.contains("duplicate key");
    }

    /** {@code seq_<orgShort>_<keySafe>}. */
    public static String seqName(String organizationId, String key) {
        String orgShort = organizationId.replace("-", "");
        orgShort = orgShort.substring(0, Math.min(8, orgShort.length()));
        return "seq_" + orgShort + "_" + key.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
