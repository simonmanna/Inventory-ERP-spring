package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;

import com.inventoryerp.core.application.PartnerService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merge a duplicate customer into another (CRM enhancements T3b). Only a customer that lives in CRM alone can be merged
 * away: its deals, activities, cases, contacts and open quotations move to the kept customer, and it is archived. A
 * customer with orders, invoices, payments, stock or any other record is refused, because those carry ledger history
 * that must not change hands silently.
 */
@Service
public class CrmCustomerMergeService {

    /** References re-pointed by a merge: table → partner column. Anything else blocks the merge. */
    private static final Map<String, String> MOVABLE = Map.of("Deal", "partnerId", "Activity", "partnerId", "CrmCase",
            "partnerId", "Contact", "partnerId", "SalesQuotation", "partnerId");
    /** Partner-like column names checked even where no foreign key is declared. */
    private static final Set<String> PARTNER_COLUMNS = Set.of("partnerId", "customerId", "supplierId", "vendorId",
            "payerId", "billToId", "shipToId");

    private final CrmDb db;
    private final PartnerService partners;
    private final AuditService audit;

    public CrmCustomerMergeService(CrmDb db, PartnerService partners, AuditService audit) {
        this.db = db;
        this.partners = partners;
        this.audit = audit;
    }

    /** What a merge of {@code sourceId} into {@code targetId} would move, and what blocks it (nothing is changed). */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String targetId, String sourceId) {
        check(targetId, sourceId);
        return Obj.of("moves", counts(sourceId), "blockedBy", blockers(sourceId));
    }

    /** Merge {@code sourceId} into {@code targetId}; refused when the source has records outside CRM. */
    @Transactional
    public Map<String, Object> merge(String targetId, Map<String, Object> in) {
        String sourceId = str(in.get("sourceId"));
        Map<String, Object> source = check(targetId, sourceId);
        db.row("SELECT id FROM \"Partner\" WHERE id IN (?, ?) ORDER BY id FOR UPDATE", targetId, sourceId);
        List<String> blocked = blockers(sourceId);
        if (!blocked.isEmpty()) {
            throw ApiException.conflict("This customer also has records in " + String.join(", ", blocked)
                    + "; only customers used in CRM alone can be merged");
        }
        Map<String, Object> moved = new LinkedHashMap<>();
        boolean targetHasPrimary = db.row("SELECT id FROM \"Contact\" WHERE \"partnerId\" = ? AND \"isPrimary\" AND"
                + " \"deletedAt\" IS NULL LIMIT 1", targetId) != null;
        if (targetHasPrimary) {
            db.update("UPDATE \"Contact\" SET \"isPrimary\" = false WHERE \"partnerId\" = ? AND \"organizationId\" = ?",
                    sourceId, org());
        }
        for (Map.Entry<String, String> e : MOVABLE.entrySet()) {
            moved.put(e.getKey(), db.update("UPDATE \"" + e.getKey() + "\" SET \"" + e.getValue() + "\" = ? WHERE \""
                    + e.getValue() + "\" = ? AND \"organizationId\" = ?", targetId, sourceId, org()));
        }
        moved.put("childCompanies", db.update("UPDATE \"Partner\" SET \"parentId\" = ? WHERE \"parentId\" = ? AND"
                + " \"organizationId\" = ?", targetId, sourceId, org()));
        partners.remove(sourceId);
        audit.record(AuditInput.of("Partner", targetId, "update", null, Obj.of("mergedFrom", sourceId, "mergedName",
                source.get("name"), "moved", moved)));
        return Obj.of("targetId", targetId, "sourceId", sourceId, "moved", moved);
    }

    private Map<String, Object> check(String targetId, String sourceId) {
        if (blank(sourceId)) {
            throw ApiException.badRequest("sourceId should not be empty");
        }
        if (sourceId.equals(targetId)) {
            throw ApiException.badRequest("Choose a different customer to merge");
        }
        String sql = "SELECT id, name FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL";
        if (db.row(sql, targetId, org()) == null) {
            throw ApiException.notFound("Customer not found");
        }
        Map<String, Object> source = db.row(sql, sourceId, org());
        if (source == null) {
            throw ApiException.notFound("Customer to merge not found");
        }
        return source;
    }

    private Map<String, Object> counts(String sourceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : MOVABLE.entrySet()) {
            out.put(e.getKey(), db.count("SELECT count(*) FROM \"" + e.getKey() + "\" WHERE \"" + e.getValue() + "\" = ?"
                    + " AND \"organizationId\" = ?", List.of(sourceId, org())));
        }
        return out;
    }

    /**
     * Tables outside {@link #MOVABLE} holding the partner: every foreign key to {@code Partner} plus every partner-like
     * column, so a reference without a declared constraint is not missed. A converted quotation blocks too (its order
     * or invoice belongs to this customer).
     */
    private List<String> blockers(String sourceId) {
        List<Map<String, Object>> refs = db.rows("SELECT cl.relname AS t, a.attname AS c FROM pg_constraint co JOIN pg_class"
                + " cl ON cl.oid = co.conrelid JOIN pg_attribute a ON a.attrelid = co.conrelid AND a.attnum = ANY(co.conkey)"
                + " WHERE co.contype = 'f' AND co.confrelid = '\"Partner\"'::regclass UNION SELECT c.table_name, c.column_name"
                + " FROM information_schema.columns c JOIN information_schema.tables t ON t.table_schema = c.table_schema"
                + " AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE' WHERE c.table_schema = current_schema()"
                + " AND c.data_type = 'text' AND c.column_name = ANY(?::text[])", (Object) PARTNER_COLUMNS.toArray(
                        String[]::new));
        Set<String> blocked = new TreeSet<>();
        for (Map<String, Object> r : refs) {
            String table = str(r.get("t"));
            String column = str(r.get("c"));
            if (column.equals(MOVABLE.get(table)) || ("Partner".equals(table) && "parentId".equals(column))) {
                continue;
            }
            if (db.row("SELECT 1 AS x FROM \"" + table.replace("\"", "") + "\" WHERE \"" + column.replace("\"", "")
                    + "\" = ? LIMIT 1", sourceId) != null) {
                blocked.add(table);
            }
        }
        if (db.row("SELECT 1 AS x FROM \"SalesQuotation\" WHERE \"partnerId\" = ? AND \"convertedId\" IS NOT NULL LIMIT 1",
                sourceId) != null) {
            blocked.add("SalesQuotation (converted)");
        }
        return new ArrayList<>(blocked);
    }
}
