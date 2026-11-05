package com.inventoryerp.core.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Commercial profile of a partner shown on the customer / supplier detail page: the sales configuration (salesperson,
 * payment terms, payment method, incoterm) and the accounting preferences (receivable / payable accounts, bank
 * accounts, bill auto-posting). Only keys present in the body are written; an explicit {@code null} clears a value.
 */
@Service
public class PartnerProfileService {

    /** Incoterms 2020. */
    public static final Set<String> INCOTERMS =
            Set.of("EXW", "FCA", "CPT", "CIP", "DAP", "DPU", "DDP", "FAS", "FOB", "CFR", "CIF");

    /** Accepted auto-post choices. */
    public static final Set<String> AUTO_POST = Set.of("ask", "always", "never");

    private static final List<String> BANK_KEYS = List.of("bankName", "accountNumber", "holderName", "iban", "swift");

    /** Writable key → column. */
    private static final Map<String, String> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("salespersonId", "salespersonId");
        COLUMNS.put("paymentTermId", "paymentTermId");
        COLUMNS.put("paymentModeId", "paymentModeId");
        COLUMNS.put("incoterm", "incoterm");
        COLUMNS.put("incotermLocation", "incotermLocation");
        COLUMNS.put("receivableAccountId", "receivableAccountId");
        COLUMNS.put("payableAccountId", "payableAccountId");
        COLUMNS.put("autoPostBills", "autoPostBills");
        COLUMNS.put("bankAccounts", "bankAccounts");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final AuditService audit;

    public PartnerProfileService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** The partner's sales and accounting configuration. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String partnerId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT id, "salespersonId", "paymentTermId", "paymentModeId", incoterm, "incotermLocation",
                       "receivableAccountId", "payableAccountId", "autoPostBills", "bankAccounts"::text AS "bankAccounts"
                  FROM "Partner" WHERE id = ? AND "organizationId" = ? AND "deletedAt" IS NULL""")
                .params(partnerId, org()).query().listOfRows();
        if (rows.isEmpty()) {
            throw ApiException.notFound("Partner " + partnerId + " not found");
        }
        Map<String, Object> out = new LinkedHashMap<>(rows.getFirst());
        out.put("bankAccounts", parseList((String) out.get("bankAccounts")));
        return out;
    }

    /** Patch the configuration; unknown keys are rejected like any whitelisted DTO. */
    @Transactional
    public Map<String, Object> update(String partnerId, Map<String, Object> body) {
        Map<String, Object> before = get(partnerId);
        Map<String, Object> in = body == null ? Map.of() : body;
        for (String k : in.keySet()) {
            if (!COLUMNS.containsKey(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String key = e.getKey();
            Object value = normalize(key, e.getValue());
            if ("bankAccounts".equals(key)) {
                sets.add("\"bankAccounts\" = ?::jsonb");
            } else {
                sets.add("\"" + COLUMNS.get(key) + "\" = ?");
            }
            params.add(value);
        }
        if (!sets.isEmpty()) {
            params.add(partnerId);
            params.add(org());
            jdbc.sql("UPDATE \"Partner\" SET " + String.join(", ", sets) + ", \"updatedAt\" = now() WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(params).update();
        }
        Map<String, Object> after = get(partnerId);
        audit.recordInTx(AuditInput.of("Partner", partnerId, "update", before, after));
        return after;
    }

    private Object normalize(String key, Object raw) {
        String s = raw == null ? null : raw instanceof String str ? (str.isBlank() ? null : str.trim()) : null;
        switch (key) {
            case "incoterm" -> {
                if (s != null && !INCOTERMS.contains(s)) {
                    throw ApiException.badRequest("incoterm must be one of the following values: "
                            + String.join(", ", INCOTERMS.stream().sorted().toList()));
                }
                return s;
            }
            case "autoPostBills" -> {
                if (s == null || !AUTO_POST.contains(s)) {
                    throw ApiException.badRequest("autoPostBills must be one of the following values: ask, always, never");
                }
                return s;
            }
            case "receivableAccountId", "payableAccountId" -> {
                requireRow("Account", s, "Account not found");
                return s;
            }
            case "paymentTermId" -> {
                requireRow("PaymentTerm", s, "Payment term not found");
                return s;
            }
            case "bankAccounts" -> {
                return bankAccounts(raw);
            }
            default -> {
                if (raw != null && !(raw instanceof String)) {
                    throw ApiException.badRequest(key + " must be a string");
                }
                return s;
            }
        }
    }

    private void requireRow(String table, String id, String message) {
        if (id == null) {
            return;
        }
        Long n = jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest(message);
        }
    }

    private static String bankAccounts(Object raw) {
        if (raw == null) {
            return "[]";
        }
        if (!(raw instanceof List<?> list)) {
            throw ApiException.badRequest("bankAccounts must be an array");
        }
        List<Map<String, Object>> clean = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw ApiException.badRequest("each bank account must be an object");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String k : BANK_KEYS) {
                Object v = m.get(k);
                row.put(k, v == null || v.toString().isBlank() ? null : v.toString().trim());
            }
            if (row.get("accountNumber") == null) {
                throw ApiException.badRequest("bank account number should not be empty");
            }
            clean.add(row);
        }
        return JSON.writeValueAsString(clean);
    }

    private static List<?> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return JSON.readValue(json, List.class);
    }
}
