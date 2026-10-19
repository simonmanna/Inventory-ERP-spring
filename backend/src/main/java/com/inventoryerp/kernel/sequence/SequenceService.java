package com.inventoryerp.kernel.sequence;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Native Postgres SEQUENCE numbering per (organization, key) (port of {@code sequence.service.ts}):
 * {@code seq_<first 8 hex of org id>_<sanitized key>}, created lazily, formatted
 * {@code prefix + padStart(padding, '0')}. Called inside the consuming transaction the number is
 * reserved atomically with it (a rollback leaves a gap, never a duplicate).
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
        int pad = padding == null ? DEFAULT_PADDING : padding;
        return (prefix == null ? "" : prefix) + "0".repeat(Math.max(0, pad - digits.length())) + digits;
    }

    private void ensure(String organizationId, String key) {
        String name = seqName(organizationId, key);
        if (ensured.contains(name)) {
            return;
        }
        try {
            jdbc.sql("CREATE SEQUENCE IF NOT EXISTS \"" + name + "\" INCREMENT BY 1 START WITH 1").update();
        } catch (RuntimeException err) {
            if (!String.valueOf(err.getMessage()).contains("already exists")) {
                throw err;
            }
        }
        ensured.add(name);
    }

    /** {@code seq_<orgShort>_<keySafe>}. */
    public static String seqName(String organizationId, String key) {
        String orgShort = organizationId.replace("-", "");
        orgShort = orgShort.substring(0, Math.min(8, orgShort.length()));
        return "seq_" + orgShort + "_" + key.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
