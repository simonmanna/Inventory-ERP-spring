package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer master extras (SG-5): profile fields, suspension, duplicate detection, import and export, and merge.
 *
 * <p>Merge is non-destructive. Posted journals and payments are immutable, so nothing is re-pointed: the old record gets
 * {@code mergedIntoPartnerId}, becomes inactive and accepts no new documents, and the survivor's figures roll up every
 * record merged into it.
 */
@Service
public class SalesCustomerService {

    static final Set<String> TYPES = Set.of("retail", "wholesale", "contractor", "distributor", "government", "corporate",
            "other");
    private static final List<String> COLUMNS = List.of("code", "name", "email", "phone", "altPhone", "district", "taxNumber",
            "customerType", "defaultCurrencyCode", "creditLimit", "status");

    private final SalesDb db;
    private final AuditService audit;

    public SalesCustomerService(SalesDb db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    private Map<String, Object> require(String id) {
        Map<String, Object> p = db.row("SELECT * , status::text AS status FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL", id, org());
        if (p == null) {
            throw ApiException.notFound("Partner not found");
        }
        return p;
    }

    /** The record and everything merged into it, directly or through a chain. */
    public List<String> mergedSet(String id) {
        return db.rows("""
                WITH RECURSIVE m AS (SELECT id FROM "Partner" WHERE id = ? AND "organizationId" = ?
                    UNION SELECT p.id FROM "Partner" p JOIN m ON p."mergedIntoPartnerId" = m.id)
                SELECT id FROM m""", id, org()).stream().map(r -> str(r.get("id"))).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile(String id) {
        Map<String, Object> p = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("id", "code", "name", "status", "customerType", "homeBranchId", "defaultCurrencyCode", "altPhone",
                "district", "suspendedAt", "suspendedReason", "mergedIntoPartnerId", "mergedAt", "mergeReason")) {
            out.put(k, p.get(k));
        }
        out.put("mergedFrom", db.rows("SELECT id, code, name, \"mergedAt\" FROM \"Partner\" WHERE \"mergedIntoPartnerId\" = ? AND"
                + " \"organizationId\" = ?", id, org()));
        out.put("kpis", kpis(id));
        return out;
    }

    /** Lifetime sales, open balance, overdue, orders and last activity, including merged records. */
    Map<String, Object> kpis(String id) {
        String[] set = mergedSet(id).toArray(String[]::new);
        Map<String, Object> ar = db.row("SELECT COALESCE(sum(\"amountResidual\" * \"exchangeRate\") FILTER (WHERE \"documentType\"::text"
                + " IN ('sales_invoice', 'debit_note')), 0) - COALESCE(sum(\"amountResidual\" * \"exchangeRate\") FILTER (WHERE"
                + " \"documentType\"::text = 'credit_note'), 0) AS outstanding, COALESCE(sum(\"amountResidual\" * \"exchangeRate\") FILTER"
                + " (WHERE \"documentType\"::text IN ('sales_invoice', 'debit_note') AND \"dueDate\" < now()), 0) AS overdue,"
                + " COALESCE(sum(\"totalAmount\" * \"exchangeRate\") FILTER (WHERE \"documentType\"::text = 'sales_invoice'), 0) AS"
                + " \"lifetimeSales\", count(*) FILTER (WHERE \"documentType\"::text = 'sales_invoice') AS invoices, max(\"issueDate\") AS"
                + " \"lastInvoiceDate\" FROM \"Document\" WHERE \"organizationId\" = ? AND \"partnerId\" = ANY(?::text[]) AND"
                + " status::text = 'posted'", org(), set);
        Map<String, Object> so = db.row("SELECT count(*) AS orders, count(*) FILTER (WHERE status IN ('confirmed', 'in_fulfilment'))"
                + " AS \"openOrders\" FROM \"SalesOrder\" WHERE \"organizationId\" = ? AND \"partnerId\" = ANY(?::text[]) AND"
                + " \"deletedAt\" IS NULL", org(), set);
        Map<String, Object> out = new LinkedHashMap<>(ar);
        out.putAll(so);
        return out;
    }

    @Transactional
    public Map<String, Object> saveProfile(String id, Map<String, Object> in) {
        Map<String, Object> before = require(id);
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : List.of("homeBranchId", "defaultCurrencyCode", "altPhone", "district", "customerType")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)));
            }
        }
        if (d.get("customerType") != null && !TYPES.contains(d.get("customerType"))) {
            throw ApiException.badRequest("customerType must be one of: " + String.join(", ", TYPES.stream().sorted().toList()));
        }
        if (d.get("homeBranchId") != null && !db.exists("Branch", str(d.get("homeBranchId")))) {
            throw ApiException.notFound("Branch not found");
        }
        if (d.get("defaultCurrencyCode") != null && db.count("SELECT count(*) FROM \"Currency\" WHERE code = ?", List.of(d.get(
                "defaultCurrencyCode"))) == 0) {
            throw ApiException.badRequest("Currency not found");
        }
        if (!d.isEmpty()) {
            db.patch("Partner", id, d, true);
            audit.record(AuditInput.of("Partner", id, "update", before.get("customerType"), d));
        }
        return profile(id);
    }

    @Transactional
    public Map<String, Object> suspend(String id, String reason) {
        require(id);
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        db.update("UPDATE \"Partner\" SET \"suspendedAt\" = " + NOW + ", \"suspendedReason\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?", reason, id, org());
        audit.record(AuditInput.of("Partner", id, "update", null, Obj.of("suspended", true, "reason", reason)));
        return profile(id);
    }

    @Transactional
    public Map<String, Object> reinstate(String id) {
        require(id);
        db.update("UPDATE \"Partner\" SET \"suspendedAt\" = NULL, \"suspendedReason\" = NULL, \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("Partner", id, "update", null, Obj.of("suspended", false)));
        return profile(id);
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    /** Possible duplicates by same name, email, tax number or the last 9 phone digits. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> duplicates(Map<String, String> q) {
        List<String> ors = new ArrayList<>();
        List<Object> params = new ArrayList<>(List.of(org()));
        if (!blank(q.get("name"))) {
            ors.add("lower(trim(name)) = lower(trim(?))");
            params.add(q.get("name"));
        }
        if (!blank(q.get("email"))) {
            ors.add("lower(email) = lower(trim(?))");
            params.add(q.get("email"));
        }
        if (!blank(q.get("taxNumber"))) {
            ors.add("\"taxNumber\" = trim(?)");
            params.add(q.get("taxNumber"));
        }
        String ph = digits(q.get("phone"));
        if (ph.length() >= 7) {
            String tail = ph.substring(Math.max(0, ph.length() - 9));
            ors.add("(regexp_replace(COALESCE(phone, ''), '[^0-9]', '', 'g') LIKE ? OR regexp_replace(COALESCE(\"altPhone\", ''),"
                    + " '[^0-9]', '', 'g') LIKE ?)");
            params.add("%" + tail);
            params.add("%" + tail);
        }
        if (ors.isEmpty()) {
            return List.of();
        }
        String exclude = "";
        if (!blank(q.get("excludeId"))) {
            exclude = " AND id <> ?";
            params.add(q.get("excludeId"));
        }
        return db.rows("SELECT id, code, name, email, phone, \"taxNumber\" FROM \"Partner\" WHERE \"organizationId\" = ? AND"
                + " \"isCustomer\" AND \"deletedAt\" IS NULL AND \"mergedIntoPartnerId\" IS NULL AND (" + String.join(" OR ", ors) + ")"
                + exclude + " ORDER BY name LIMIT 20", params);
    }

    /** Merge {@code id} into {@code intoId}: nothing posted changes, the old record just stops accepting documents. */
    @Transactional
    public Map<String, Object> merge(String id, String intoId, String reason) {
        if (blank(intoId)) {
            throw ApiException.badRequest("intoPartnerId should not be empty");
        }
        if (id.equals(intoId)) {
            throw ApiException.badRequest("A customer cannot be merged into itself");
        }
        if (blank(reason)) {
            throw ApiException.badRequest("reason should not be empty");
        }
        Map<String, Object> from = require(id);
        Map<String, Object> into = require(intoId);
        if (from.get("mergedIntoPartnerId") != null) {
            throw ApiException.badRequest("This customer was already merged");
        }
        if (into.get("mergedIntoPartnerId") != null) {
            throw ApiException.badRequest("The target customer was itself merged; merge into the final record");
        }
        long open = db.count("SELECT (SELECT count(*) FROM \"SalesOrder\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND"
                + " \"deletedAt\" IS NULL AND status IN ('draft', 'pending_approval', 'confirmed', 'in_fulfilment')) + (SELECT"
                + " count(*) FROM \"SalesQuotation\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND \"deletedAt\" IS NULL AND"
                + " status IN ('draft', 'pending_approval', 'approved', 'sent', 'accepted')) + (SELECT count(*) FROM \"Document\""
                + " WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND status::text = 'draft')",
                List.of(org(), id, org(), id, org(), id));
        if (open > 0) {
            throw ApiException.badRequest("Finish or cancel this customer's open orders, quotations and draft invoices first");
        }
        db.update("UPDATE \"Partner\" SET \"mergedIntoPartnerId\" = ?, \"mergedAt\" = " + NOW + ", \"mergedById\" = ?, \"mergeReason\""
                + " = ?, status = 'inactive', \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", intoId, user(),
                reason, id, org());
        audit.record(AuditInput.of("Partner", id, "update", null, Obj.of("mergedInto", intoId, "reason", reason)));
        return profile(intoId);
    }

    // ------------------------------------------------------------------ import / export

    /** Customers as CSV, with a BOM for Excel; formula-leading cells are neutralised. */
    @Transactional(readOnly = true)
    public byte[] export() {
        StringBuilder sb = new StringBuilder("\uFEFF").append(String.join(",", COLUMNS)).append('\n');
        for (Map<String, Object> r : db.rows("SELECT code, name, email, phone, \"altPhone\", district, \"taxNumber\", \"customerType\","
                + " \"defaultCurrencyCode\", \"creditLimit\", status::text AS status FROM \"Partner\" WHERE \"organizationId\" = ? AND"
                + " \"isCustomer\" AND \"deletedAt\" IS NULL ORDER BY name", org())) {
            sb.append(String.join(",", COLUMNS.stream().map(c -> csv(r.get(c))).toList())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(Object v) {
        String s = v == null ? "" : v.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?[0-9.]+")) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /**
     * Import rows {@code {code?, name, email?, phone?, altPhone?, district?, taxNumber?, customerType?, creditLimit?}}. Bad
     * rows are returned with their errors and never written; {@code dryRun} (the default) writes nothing at all.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> importRows(Map<String, Object> in) {
        List<Map<String, Object>> rows = in.get("rows") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        if (rows.isEmpty()) {
            throw ApiException.badRequest("rows should not be empty");
        }
        if (rows.size() > 5000) {
            throw ApiException.badRequest("Import at most 5000 rows at a time");
        }
        boolean dryRun = !Boolean.FALSE.equals(in.get("dryRun"));
        boolean allowDuplicates = Boolean.TRUE.equals(in.get("allowDuplicates"));
        List<Map<String, Object>> errors = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        int good = 0;
        int rowNumber = 0;
        for (Map<String, Object> r : rows) {
            rowNumber++;
            List<String> e = validate(r, seen, allowDuplicates);
            if (!e.isEmpty()) {
                errors.add(Obj.of("row", rowNumber, "name", r.get("name"), "errors", e));
                continue;
            }
            good++;
            if (!dryRun) {
                insert(r);
            }
        }
        if (!dryRun && good > 0) {
            audit.record(AuditInput.of("Partner", org(), "create", null, Obj.of("imported", good, "rejected", errors.size())));
        }
        return Obj.of("dryRun", dryRun, "total", rows.size(), "valid", good, "imported", dryRun ? 0 : good, "rejected", errors.size(),
                "errors", errors);
    }

    private List<String> validate(Map<String, Object> r, Set<String> seen, boolean allowDuplicates) {
        List<String> e = new ArrayList<>();
        String name = str(r.get("name"));
        if (blank(name)) {
            e.add("name is required");
        }
        String email = str(r.get("email"));
        if (!blank(email) && !email.matches("^\\S+@\\S+\\.\\S+$")) {
            e.add("email is not valid");
        }
        String phone = str(r.get("phone"));
        if (!blank(phone) && digits(phone).length() < 7) {
            e.add("phone is too short");
        }
        String type = str(r.get("customerType"));
        if (!blank(type) && !TYPES.contains(type)) {
            e.add("customerType is not valid");
        }
        if (r.get("creditLimit") != null && !blank(str(r.get("creditLimit")))) {
            try {
                if (new java.math.BigDecimal(str(r.get("creditLimit"))).signum() < 0) {
                    e.add("creditLimit must not be negative");
                }
            } catch (NumberFormatException x) {
                e.add("creditLimit must be a number");
            }
        }
        String code = str(r.get("code"));
        if (!blank(code)) {
            if (!seen.add("code:" + code) || db.count("SELECT count(*) FROM \"Partner\" WHERE \"organizationId\" = ? AND code = ?",
                    List.of(org(), code)) > 0) {
                e.add("code already exists");
            }
        }
        if (!blank(name) && !allowDuplicates) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("name", name);
            q.put("email", email);
            q.put("phone", phone);
            q.put("taxNumber", str(r.get("taxNumber")));
            if (!seen.add("name:" + name.trim().toLowerCase(java.util.Locale.ROOT)) || !duplicates(q).isEmpty()) {
                e.add("possible duplicate of an existing customer");
            }
        }
        return e;
    }

    private void insert(Map<String, Object> r) {
        String code = blank(str(r.get("code"))) ? "IMP-" + SalesDb.newId().substring(0, 8).toUpperCase(java.util.Locale.ROOT)
                : str(r.get("code"));
        db.update("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, phone, \"altPhone\", district, \"taxNumber\","
                + " \"customerType\", \"creditLimit\", \"isCustomer\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, " + NOW
                + ")", SalesDb.newId(), org(), code, str(r.get("name")).trim(), blankNull(r.get("email")), blankNull(r.get("phone")),
                blankNull(r.get("altPhone")), blankNull(r.get("district")), blankNull(r.get("taxNumber")),
                blankNull(r.get("customerType")), r.get("creditLimit") == null || blank(str(r.get("creditLimit")))
                        ? java.math.BigDecimal.ZERO : dec(r.get("creditLimit")));
    }

    private static String blankNull(Object v) {
        return blank(str(v)) ? null : str(v).trim();
    }
}
