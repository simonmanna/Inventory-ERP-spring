package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.invoicing.application.BranchSaleRecognitionService;
import com.inventoryerp.invoicing.application.BranchSaleRecognitionService.Tender;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Branch sales profiles (ADR-S056, cash-sales.md §4, §6): per branch the showroom stock location, the tender account
 * of every payment method, the walk-in customer, the numbering prefix, segregation and the fiscal trigger. Every
 * save validates the accounts (active, postable, same organization, expected report section and tender category)
 * and the mappings a cash sale posts to; {@link #requireValid} repeats the check at confirm.
 */
@Service
public class BranchSalesProfileService {

    static final Set<String> FIELDS = Set.of("branchId", "locationId", "cashAccountId", "momoMtnAccountId",
            "momoAirtelAccountId", "bankClearingAccountId", "walkInPartnerId", "numberPrefix", "requireSegregation",
            "fiscalTrigger", "fiscalProvider", "rejectedAlertDays", "isActive", "invoicingMode");

    private final SalesDb db;
    private final BranchSaleRecognitionService recognition;
    private final CashSaleFiscalService fiscal;
    private final AuditService audit;

    public BranchSalesProfileService(SalesDb db, BranchSaleRecognitionService recognition, CashSaleFiscalService fiscal,
            AuditService audit) {
        this.db = db;
        this.recognition = recognition;
        this.fiscal = fiscal;
        this.audit = audit;
    }

    private static final String SELECT = """
            SELECT p.*, b.name AS "branchName", b.code AS "branchCode", l.name AS "locationName",
                   w.name AS "walkInPartnerName"
              FROM "BranchSalesProfile" p
              LEFT JOIN "Branch" b ON b.id = p."branchId"
              LEFT JOIN "InventoryLocation" l ON l.id = p."locationId"
              LEFT JOIN "Partner" w ON w.id = p."walkInPartnerId"
            """;

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return db.rows(SELECT + " WHERE p.\"organizationId\" = ? ORDER BY b.name", org());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> p = db.row(SELECT + " WHERE p.id = ? AND p.\"organizationId\" = ?", id, org());
        if (p == null) {
            throw ApiException.notFound("Branch sales profile not found");
        }
        return p;
    }

    /** Active profile of a branch, or 400 when the branch has none. */
    @Transactional(readOnly = true)
    public Map<String, Object> forBranch(String branchId) {
        Map<String, Object> p = branchId == null ? null : db.row(SELECT + " WHERE p.\"organizationId\" = ? AND"
                + " p.\"branchId\" = ? AND p.\"isActive\"", org(), branchId);
        if (p == null) {
            throw ApiException.badRequest("This branch has no active branch sales profile");
        }
        return p;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        Map<String, Object> d = values(in, null);
        if (db.count("SELECT count(*) FROM \"BranchSalesProfile\" WHERE \"organizationId\" = ? AND \"branchId\" = ?",
                List.of(org(), str(d.get("branchId")))) > 0) {
            throw ApiException.conflict("This branch already has a sales profile");
        }
        validate(d);
        d.put("createdById", user());
        Map<String, Object> row = db.insert("BranchSalesProfile", d);
        audit.record(AuditInput.of("BranchSalesProfile", str(row.get("id")), "create", null, Obj.of("branchId",
                d.get("branchId"))));
        return get(str(row.get("id")));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        Map<String, Object> current = get(id);
        if (in.containsKey("branchId") && !str(in.get("branchId")).equals(str(current.get("branchId")))) {
            throw ApiException.badRequest("The branch of a profile cannot change");
        }
        Map<String, Object> d = values(in, current);
        Map<String, Object> merged = new LinkedHashMap<>(current);
        merged.putAll(d);
        validate(merged);
        db.patch("BranchSalesProfile", id, d, true);
        audit.record(AuditInput.of("BranchSalesProfile", id, "update", null, Obj.of("fields", d.keySet())));
        return get(id);
    }

    private Map<String, Object> values(Map<String, Object> in, Map<String, Object> current) {
        for (String k : in.keySet()) {
            if (!FIELDS.contains(k)) {
                throw ApiException.badRequest("property " + k + " should not exist");
            }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        for (String k : List.of("branchId", "locationId", "cashAccountId", "momoMtnAccountId", "momoAirtelAccountId",
                "bankClearingAccountId", "walkInPartnerId", "numberPrefix", "fiscalTrigger", "fiscalProvider")) {
            if (in.containsKey(k)) {
                d.put(k, blank(str(in.get(k))) ? null : str(in.get(k)).trim());
            }
        }
        if (in.containsKey("invoicingMode") && !"head_office".equals(str(in.get("invoicingMode")))) {
            throw ApiException.badRequest("invoicingMode must be head_office: branch self-invoicing is not available yet");
        }
        for (String k : List.of("requireSegregation", "isActive")) {
            if (in.containsKey(k)) {
                d.put(k, Boolean.TRUE.equals(in.get(k)));
            }
        }
        if (in.containsKey("rejectedAlertDays")) {
            int days = in.get("rejectedAlertDays") instanceof Number n ? n.intValue() : 0;
            if (days <= 0) {
                throw ApiException.badRequest("rejectedAlertDays must be greater than 0");
            }
            d.put("rejectedAlertDays", days);
        }
        if (current == null) {
            for (String k : List.of("branchId", "locationId", "numberPrefix")) {
                if (d.get(k) == null) {
                    throw ApiException.badRequest(k + " should not be empty");
                }
            }
        } else {
            for (String k : List.of("locationId", "numberPrefix")) {
                if (d.containsKey(k) && d.get(k) == null) {
                    throw ApiException.badRequest(k + " should not be empty");
                }
            }
        }
        if (d.get("numberPrefix") != null && !str(d.get("numberPrefix")).matches("[A-Z0-9]{2,8}")) {
            throw ApiException.badRequest("numberPrefix must be 2-8 upper-case letters or digits");
        }
        if (d.get("fiscalTrigger") != null && !Set.of("at_confirm", "at_invoice").contains(str(d.get("fiscalTrigger")))) {
            throw ApiException.badRequest("fiscalTrigger must be one of: at_confirm, at_invoice");
        }
        if (d.get("fiscalProvider") != null && !fiscal.providers().contains(str(d.get("fiscalProvider")))) {
            throw ApiException.badRequest("fiscalProvider must be one of: " + String.join(", ", fiscal.providers()));
        }
        return d;
    }

    /** 400 with every problem found (location, walk-in customer, accounts, mappings). */
    void validate(Map<String, Object> p) {
        List<String> problems = new ArrayList<>(problems(p));
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Branch sales profile is not valid: " + String.join("; ", problems));
        }
    }

    /** Re-validate a stored profile before a sale posts (mappings can drift after the profile was saved). */
    public void requireValid(Map<String, Object> p) {
        List<String> problems = problems(p);
        if (!problems.isEmpty()) {
            throw ApiException.badRequest("Branch sales profile is not valid: " + String.join("; ", problems));
        }
    }

    private List<String> problems(Map<String, Object> p) {
        List<String> out = new ArrayList<>();
        String branchId = str(p.get("branchId"));
        if (db.count("SELECT count(*) FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                List.of(branchId, org())) == 0) {
            out.add("branch not found");
        }
        if (db.count("SELECT count(*) FROM \"InventoryLocation\" WHERE id = ? AND \"organizationId\" = ? AND \"branchId\" = ?"
                + " AND \"isActive\" AND \"deletedAt\" IS NULL", List.of(str(p.get("locationId")), org(), branchId)) == 0) {
            out.add("location must be an active stock location of the branch");
        }
        if (p.get("walkInPartnerId") != null && db.count("SELECT count(*) FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL", List.of(str(p.get("walkInPartnerId")), org())) == 0) {
            out.add("walk-in customer not found");
        }
        List<Tender> tenders = new ArrayList<>();
        tenders.add(new Tender("cash account", "cash", str(p.get("cashAccountId"))));
        tenders.add(new Tender("MTN mobile money account", "mobile_money", str(p.get("momoMtnAccountId"))));
        tenders.add(new Tender("Airtel mobile money account", "mobile_money", str(p.get("momoAirtelAccountId"))));
        tenders.add(new Tender("bank clearing account", "bank", str(p.get("bankClearingAccountId"))));
        if (tenders.stream().allMatch(t -> t.accountId() == null)) {
            out.add("at least one tender account is required");
        }
        out.addAll(recognition.accountProblems(tenders));
        return out;
    }

    /** Tender account of a payment method (server-side; 400 when the branch does not accept it). */
    static String tenderAccount(Map<String, Object> profile, String method, String provider) {
        String key = switch (method == null ? "" : method) {
            case "cash" -> "cashAccountId";
            case "bank" -> "bankClearingAccountId";
            case "mobile_money" -> "mtn".equals(provider) ? "momoMtnAccountId" : "airtel".equals(provider)
                    ? "momoAirtelAccountId" : null;
            default -> null;
        };
        if (key == null) {
            throw ApiException.badRequest("method must be one of: cash, mobile_money (mtn | airtel), bank");
        }
        String account = str(profile.get(key));
        if (account == null) {
            throw ApiException.badRequest("This branch does not accept " + method.replace('_', ' ')
                    + (provider == null ? "" : " (" + provider + ")"));
        }
        return account;
    }
}
