package com.inventoryerp.kernel.sequence;

import com.inventoryerp.kernel.approvals.ConfigChangeApplier;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.DocumentNumberCatalog.Entry;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document numbering configuration (CTRL-2): per organization, the prefix, suffix and padding of the sequences in
 * {@link DocumentNumberCatalog}, and moving a counter forward. A counter can never go back, so no number repeats.
 */
@Service
public class DocumentNumberingService implements ConfigChangeApplier {

    static final String SET = "number_format.set";
    static final String RESET = "number_format.reset";
    static final String ADVANCE = "number_format.advance";

    /** Letters, digits, separators and the date tokens. */
    private static final Pattern TEXT = Pattern.compile("^(?:[A-Za-z0-9/_.\\-]|\\{YYYY}|\\{YY}|\\{MM})*$");

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final SequenceService sequence;
    private final AuditService audit;

    public DocumentNumberingService(JdbcClient jdbc, SequenceService sequence, AuditService audit) {
        this.jdbc = jdbc;
        this.sequence = sequence;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Every configurable sequence with its format, the next number and a preview. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> rows = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM \"DocumentNumberFormat\" WHERE"
                + " \"organizationId\" = ?").param(org()))) {
            rows.put((String) r.get("sequenceKey"), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : DocumentNumberCatalog.entries()) {
            out.add(view(e, rows.get(e.key())));
        }
        return out;
    }

    private Map<String, Object> view(Entry e, Map<String, Object> row) {
        String prefix = row == null ? e.defaultPrefix() : (String) row.get("prefix");
        String suffix = row == null ? "" : (String) row.get("suffix");
        int padding = row == null ? e.defaultPadding() : ((Number) row.get("padding")).intValue();
        long next = sequence.peekNext(fullKey(e));
        LocalDate today = LocalDate.now(BusinessZone.get());
        return Obj.of("key", e.key(), "label", e.label(), "group", e.group(), "yearly", e.yearly(), "customized",
                row != null, "prefix", prefix, "suffix", suffix, "padding", padding, "nextNumber", next, "preview",
                DocumentNumberCatalog.render(prefix, suffix, padding, String.valueOf(next), today.getYear(),
                        today.getMonthValue()));
    }

    /** The counter behind a catalogue key today: yearly keys use the UTC year, like their callers. */
    private static String fullKey(Entry e) {
        return e.yearly() ? e.key() + ":" + Instant.now().atOffset(ZoneOffset.UTC).getYear() : e.key();
    }

    /** Refuse an unknown key. */
    public void assertKnown(String key) {
        entry(key);
    }

    /**
     * Refuse a bad format before anything is held for approval. A counter that restarts every year needs the year in
     * the number, or next year's numbers would repeat this year's.
     */
    public void assertValid(String key, String prefix, String suffix) {
        Entry e = entry(key);
        String both = (prefix == null ? "" : prefix) + (suffix == null ? "" : suffix);
        for (String text : new String[] {prefix, suffix}) {
            if (text != null && (text.length() > 24 || !TEXT.matcher(text).matches())) {
                throw ApiException.badRequest("Use letters, digits, / _ . - and {YYYY}, {YY}, {MM}, up to 24 characters");
            }
        }
        if (e.defaultPrefix().contains("{YYYY}") && !both.contains("{YYYY}") && !both.contains("{YY}")) {
            throw ApiException.badRequest("This number restarts every year, so its format must contain {YYYY} or {YY}");
        }
    }

    /** Refuse moving a counter back, before anything is held for approval. */
    public void assertForward(String key, long nextNumber) {
        long current = sequence.peekNext(fullKey(entry(key)));
        if (nextNumber <= current) {
            throw ApiException.badRequest("The next number must be above " + current);
        }
    }

    @Transactional
    public Map<String, Object> set(String key, String prefix, String suffix, int padding) {
        assertValid(key, prefix, suffix);
        jdbc.sql("INSERT INTO \"DocumentNumberFormat\" (id, \"organizationId\", \"sequenceKey\", prefix, suffix, padding,"
                        + " \"updatedBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW
                        + ") ON CONFLICT (\"organizationId\", \"sequenceKey\") DO UPDATE SET prefix = EXCLUDED.prefix,"
                        + " suffix = EXCLUDED.suffix, padding = EXCLUDED.padding, \"updatedBy\" = EXCLUDED.\"updatedBy\","
                        + " \"updatedAt\" = EXCLUDED.\"updatedAt\"")
                .params(UUID.randomUUID().toString(), org(), key, prefix == null ? "" : prefix,
                        suffix == null ? "" : suffix, padding, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("DocumentNumberFormat", key, "update", null, Obj.of("prefix", prefix, "suffix",
                suffix, "padding", padding)));
        return view(entry(key), row(key));
    }

    /** Back to the format in the code. The counter is not touched. */
    @Transactional
    public Map<String, Object> reset(String key) {
        Entry e = entry(key);
        jdbc.sql("DELETE FROM \"DocumentNumberFormat\" WHERE \"organizationId\" = ? AND \"sequenceKey\" = ?")
                .params(org(), key).update();
        audit.recordInTx(AuditInput.of("DocumentNumberFormat", key, "delete"));
        return view(e, null);
    }

    /** Move the counter forward, for example to continue from the numbers of an old system. */
    @Transactional
    public Map<String, Object> advance(String key, long nextNumber) {
        assertForward(key, nextNumber);
        Entry e = entry(key);
        sequence.restart(fullKey(e), nextNumber);
        audit.recordInTx(AuditInput.of("DocumentNumberFormat", key, "update", null, Obj.of("nextNumber", nextNumber)));
        return view(e, row(key));
    }

    private Map<String, Object> row(String key) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"DocumentNumberFormat\" WHERE \"organizationId\" = ? AND"
                + " \"sequenceKey\" = ?").params(org(), key));
    }

    private static Entry entry(String key) {
        Entry e = DocumentNumberCatalog.entry(key);
        if (e == null) {
            throw ApiException.notFound("This document number cannot be configured");
        }
        return e;
    }

    @Override
    public boolean supports(String changeType) {
        return changeType.startsWith("number_format.");
    }

    @Override
    public void apply(String changeType, String targetId, Map<String, Object> payload) {
        switch (changeType) {
            case SET -> set(targetId, (String) payload.get("prefix"), (String) payload.get("suffix"),
                    ((Number) payload.get("padding")).intValue());
            case RESET -> reset(targetId);
            case ADVANCE -> advance(targetId, ((Number) payload.get("nextNumber")).longValue());
            default -> throw ApiException.badRequest("Unknown change type " + changeType);
        }
    }
}
