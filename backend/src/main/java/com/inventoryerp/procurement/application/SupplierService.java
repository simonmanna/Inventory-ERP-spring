package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.auth.EncryptionService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.procurement.application.ProcurementPolicyService.PolicyContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Supplier lifecycle on top of the core {@code Partner} master (ADR-PROCUREMENT-001 §1): a {@code SupplierProfile}
 * (qualification status, risk, categories, lead time), bank accounts under dual control (encrypted at rest, a second
 * person verifies), certificates with expiry, the supplier's catalogue and the price history. {@link #eligibility}
 * is the single answer to "may we buy from this supplier now?".
 */
@Service
public class SupplierService {

    /** Profile upsert. */
    public record ProfileInput(String riskClass, List<String> categories, String currencyCode, Integer leadTimeDays,
            String registrationNumber, String tradingName, String notes) {}

    /** New bank account; {@code accountNumber} is stored encrypted, only its last 4 digits stay readable. */
    public record BankAccountInput(String bankName, String branchName, String accountName, String accountNumber,
            String swiftCode, String currencyCode, Boolean isDefault) {}

    /** New certificate. */
    public record CertificateInput(String type, String number, String issuer, String validFrom, String validTo,
            String fileId, String notes) {}

    /** Catalogue entry upsert (by product). */
    public record ProductInput(String productId, String supplierSku, String purchaseUomId, BigDecimal minOrderQty,
            Integer leadTimeDays, Boolean preferred, String notes) {}

    /** Manual price observation. */
    public record PriceInput(String productId, String uomId, String currencyCode, BigDecimal unitPrice,
            BigDecimal quantity, String validFrom, String validTo) {}

    /** Can we buy from the supplier: {@code blocked} reasons refuse the order, {@code warnings} only inform. */
    public record Eligibility(String partnerId, String lifecycleStatus, List<String> blocked, List<String> warnings) {

        public boolean eligible() {
            return blocked.isEmpty();
        }

        public Map<String, Object> toMap() {
            return Obj.of("partnerId", partnerId, "lifecycleStatus", lifecycleStatus, "eligible", eligible(), "blocked",
                    blocked, "warnings", warnings);
        }
    }

    private static final List<String> RISK = List.of("low", "medium", "high");
    private static final Map<String, List<String>> TRANSITIONS = Map.of(
            "approve", List.of("prospect", "provisional", "suspended"),
            "provisional", List.of("prospect", "suspended"),
            "suspend", List.of("prospect", "provisional", "approved"),
            "blacklist", List.of("prospect", "provisional", "approved", "suspended"),
            "reinstate", List.of("suspended", "blacklisted"));
    private static final Map<String, String> TARGET = Map.of("approve", "approved", "provisional", "provisional",
            "suspend", "suspended", "blacklist", "blacklisted", "reinstate", "provisional");

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final EncryptionService crypto;
    private final ProcurementPolicyService policies;
    private final TransactionTemplate tx;

    public SupplierService(JdbcClient jdbc, AuditService audit, EventBus events, EncryptionService crypto,
            ProcurementPolicyService policies, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.crypto = crypto;
        this.policies = policies;
        this.tx = new TransactionTemplate(txm);
    }

    // ── list / detail ──────────────────────────────────────────────────────────────────────────────────────────

    /** Suppliers (partners flagged as supplier) with their qualification status. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String search, String status, int page, int pageSize) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" p.\"organizationId\" = ? AND p.\"isSupplier\" = true AND"
                + " p.\"deletedAt\" IS NULL");
        if (search != null && !search.isBlank()) {
            String like = "%" + BaseCrudService.escapeLike(search.trim()) + "%";
            where.append(" AND (p.name ILIKE ? OR p.code ILIKE ? OR p.\"taxNumber\" ILIKE ? OR p.email ILIKE ?)");
            params.addAll(List.of(like, like, like, like));
        }
        if (status != null && !status.isBlank()) {
            if ("unqualified".equals(status)) {
                where.append(" AND s.id IS NULL");
            } else {
                where.append(" AND s.\"lifecycleStatus\" = ?");
                params.add(status);
            }
        }
        String from = " FROM \"Partner\" p LEFT JOIN \"SupplierProfile\" s ON s.\"partnerId\" = p.id AND"
                + " s.\"organizationId\" = p.\"organizationId\" WHERE" + where;
        long total = jdbc.sql("SELECT count(*)" + from).params(params).query(Long.class).single();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT p.id, p.code, p.name, p.email, p.phone,"
                + " p.\"taxNumber\", p.status::text AS \"partnerStatus\", s.\"lifecycleStatus\", s.\"riskClass\","
                + " s.categories, s.\"leadTimeDays\", (SELECT count(*) FROM \"SupplierCertificate\" c WHERE"
                + " c.\"organizationId\" = p.\"organizationId\" AND c.\"partnerId\" = p.id AND c.\"deletedAt\" IS NULL"
                + " AND c.\"validTo\" < CURRENT_DATE + 30) AS \"certificatesExpiring\"" + from
                + " ORDER BY p.name ASC LIMIT " + pageSize + " OFFSET " + (long) (page - 1) * pageSize).params(params));
        return PageResult.of(rows, page, pageSize, total);
    }

    /** Supplier 360: partner, profile, bank accounts (masked), certificates, catalogue and eligibility. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String partnerId) {
        Map<String, Object> partner = requireSupplier(partnerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partner", partner);
        out.put("profile", profile(partnerId));
        out.put("bankAccounts", bankAccounts(partnerId));
        out.put("certificates", SqlRows.list(jdbc.sql("SELECT * FROM \"SupplierCertificate\" WHERE \"organizationId\" = ?"
                + " AND \"partnerId\" = ? AND \"deletedAt\" IS NULL ORDER BY \"validTo\" ASC NULLS LAST")
                .params(org(), partnerId)));
        out.put("products", SqlRows.list(jdbc.sql("SELECT sp.*, pr.code AS \"productCode\", pr.name AS \"productName\""
                + " FROM \"SupplierProduct\" sp LEFT JOIN \"Product\" pr ON pr.id = sp.\"productId\" AND"
                + " pr.\"organizationId\" = sp.\"organizationId\" WHERE sp.\"organizationId\" = ? AND sp.\"partnerId\" = ?"
                + " ORDER BY pr.name").params(org(), partnerId)));
        out.put("eligibility", eligibility(partnerId, PolicyContext.ORG, LocalDate.now()).toMap());
        return out;
    }

    private Map<String, Object> requireSupplier(String partnerId) {
        Map<String, Object> partner = SqlRows.one(jdbc.sql("SELECT id, code, name, email, phone, \"taxNumber\", website,"
                + " \"isSupplier\", status::text AS status, \"paymentTermId\" FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(partnerId, org()));
        if (partner == null) {
            throw ApiException.notFound("Supplier not found");
        }
        return partner;
    }

    Map<String, Object> profile(String partnerId) {
        return SqlRows.one(jdbc.sql("SELECT * FROM \"SupplierProfile\" WHERE \"organizationId\" = ? AND \"partnerId\" = ?")
                .params(org(), partnerId));
    }

    private List<Map<String, Object>> bankAccounts(String partnerId) {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT id, \"partnerId\", \"bankName\", \"branchName\","
                + " \"accountName\", \"accountNumberLast4\", \"swiftCode\", \"currencyCode\", \"isDefault\", status,"
                + " \"createdBy\", \"verifiedBy\", \"verifiedAt\", \"revokedAt\", \"createdAt\" FROM \"SupplierBankAccount\""
                + " WHERE \"organizationId\" = ? AND \"partnerId\" = ? ORDER BY \"createdAt\" DESC").params(org(), partnerId));
        rows.forEach(r -> r.put("accountNumberMasked", "•••• " + r.get("accountNumberLast4")));
        return rows;
    }

    // ── profile & lifecycle ────────────────────────────────────────────────────────────────────────────────────

    /** Create or update the profile; a new profile starts as {@code prospect}. */
    public Map<String, Object> upsertProfile(String partnerId, ProfileInput in) {
        requireSupplier(partnerId);
        if (in.riskClass() != null && !RISK.contains(in.riskClass())) {
            throw ApiException.badRequest("riskClass must be one of low, medium, high");
        }
        if (in.leadTimeDays() != null && in.leadTimeDays() < 0) {
            throw ApiException.badRequest("leadTimeDays must not be negative");
        }
        String[] cats = in.categories() == null ? null : in.categories().stream().filter(c -> c != null && !c.isBlank())
                .map(String::trim).distinct().toArray(String[]::new);
        tx.executeWithoutResult(st -> {
            Map<String, Object> before = profile(partnerId);
            if (before == null) {
                jdbc.sql("INSERT INTO \"SupplierProfile\" (id, \"organizationId\", \"partnerId\", \"riskClass\", categories,"
                        + " \"currencyCode\", \"leadTimeDays\", \"registrationNumber\", \"tradingName\", notes, \"createdBy\","
                        + " \"updatedBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, COALESCE(?::text[], '{}'), ?, ?,"
                        + " ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")")
                        .params(UUID.randomUUID().toString(), org(), partnerId,
                                in.riskClass() == null ? "medium" : in.riskClass(), cats, in.currencyCode(),
                                in.leadTimeDays(), in.registrationNumber(), in.tradingName(), in.notes(), user(), user())
                        .update();
                audit.recordInTx(AuditInput.of("SupplierProfile", partnerId, "create", null,
                        Obj.of("lifecycleStatus", "prospect")));
            } else {
                jdbc.sql("UPDATE \"SupplierProfile\" SET \"riskClass\" = COALESCE(?, \"riskClass\"), categories ="
                        + " COALESCE(?::text[], categories), \"currencyCode\" = COALESCE(?, \"currencyCode\"),"
                        + " \"leadTimeDays\" = COALESCE(?, \"leadTimeDays\"), \"registrationNumber\" = COALESCE(?,"
                        + " \"registrationNumber\"), \"tradingName\" = COALESCE(?, \"tradingName\"), notes = COALESCE(?,"
                        + " notes), \"updatedBy\" = ?, version = version + 1, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                        .params(in.riskClass(), cats, in.currencyCode(), in.leadTimeDays(), in.registrationNumber(),
                                in.tradingName(), in.notes(), user(), before.get("id"), org()).update();
                audit.recordInTx(AuditInput.of("SupplierProfile", partnerId, "update", Obj.of("riskClass",
                        before.get("riskClass")), Obj.of("riskClass", in.riskClass())));
            }
        });
        return get(partnerId);
    }

    /**
     * Lifecycle action: {@code approve | provisional | suspend | blacklist | reinstate}. Suspending or blacklisting
     * needs a reason; a supplier is created as a profile on first action if none exists.
     */
    public Map<String, Object> transition(String partnerId, String action, String reason) {
        requireSupplier(partnerId);
        List<String> from = TRANSITIONS.get(action);
        if (from == null) {
            throw ApiException.badRequest("Unknown supplier action: " + action);
        }
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        if (("suspend".equals(action) || "blacklist".equals(action)) && why == null) {
            throw ApiException.badRequest("A reason is required to " + action + " a supplier");
        }
        if (profile(partnerId) == null) {
            upsertProfile(partnerId, new ProfileInput(null, null, null, null, null, null, null));
        }
        String to = TARGET.get(action);
        String set = "approve".equals(action) ? ", \"approvedBy\" = ?, \"approvedAt\" = " + NOW : "";
        List<Object> params = new ArrayList<>(List.of(to, why == null ? "" : why));
        if (!set.isEmpty()) {
            params.add(user());
        }
        params.addAll(List.of(user(), org(), partnerId, from.toArray(String[]::new)));
        Map<String, Object> before = profile(partnerId);
        int n = jdbc.sql("UPDATE \"SupplierProfile\" SET \"lifecycleStatus\" = ?, \"statusReason\" = NULLIF(?, '')" + set
                + ", \"updatedBy\" = ?, version = version + 1, \"updatedAt\" = " + NOW + " WHERE \"organizationId\" = ?"
                + " AND \"partnerId\" = ? AND \"lifecycleStatus\" = ANY(?::text[])").params(params).update();
        if (n == 0) {
            throw ApiException.badRequest("Cannot " + action + " a supplier that is " + before.get("lifecycleStatus"));
        }
        audit.record(AuditInput.of("SupplierProfile", partnerId, "update", Obj.of("lifecycleStatus",
                before.get("lifecycleStatus")), Obj.of("lifecycleStatus", to, "reason", why)));
        events.publish("supplier.status_changed", Obj.of("organizationId", org(), "partnerId", partnerId, "from",
                before.get("lifecycleStatus"), "to", to, "reason", why == null ? "" : why));
        return get(partnerId);
    }

    // ── bank accounts (dual control) ───────────────────────────────────────────────────────────────────────────

    /** Register a bank account; it stays {@code pending_verification} until someone else verifies it. */
    public Map<String, Object> addBankAccount(String partnerId, BankAccountInput in) {
        requireSupplier(partnerId);
        String number = in.accountNumber() == null ? "" : in.accountNumber().replaceAll("\\s+", "");
        if (number.length() < 4) {
            throw ApiException.badRequest("accountNumber must have at least 4 characters");
        }
        if (in.bankName() == null || in.bankName().isBlank() || in.accountName() == null || in.accountName().isBlank()) {
            throw ApiException.badRequest("bankName and accountName are required");
        }
        EncryptionService.Encrypted enc = crypto.encrypt(number);
        String id = UUID.randomUUID().toString();
        String json = EntityJson.toJson(Obj.of("ciphertext", enc.ciphertext(), "iv", enc.iv(), "tag", enc.tag()));
        jdbc.sql("INSERT INTO \"SupplierBankAccount\" (id, \"organizationId\", \"partnerId\", \"bankName\", \"branchName\","
                + " \"accountName\", \"accountNumberLast4\", \"accountNumberEnc\", \"swiftCode\", \"currencyCode\","
                + " \"isDefault\", \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?,"
                + " ?, " + NOW + ", " + NOW + ")")
                .params(id, org(), partnerId, in.bankName().trim(), in.branchName(), in.accountName().trim(),
                        number.substring(number.length() - 4), json, in.swiftCode(), in.currencyCode(),
                        Boolean.TRUE.equals(in.isDefault()), user()).update();
        // Never log or audit the number itself.
        audit.record(AuditInput.of("SupplierBankAccount", id, "create", null, Obj.of("partnerId", partnerId,
                "bankName", in.bankName(), "last4", number.substring(number.length() - 4))));
        events.publish("supplier.bank_account_added", Obj.of("organizationId", org(), "partnerId", partnerId,
                "bankAccountId", id));
        return bankAccount(id);
    }

    /** Second-person verification; the person who entered the account cannot verify it. */
    public Map<String, Object> verifyBankAccount(String id) {
        Map<String, Object> acc = bankAccount(id);
        if (user() != null && user().equals(acc.get("createdBy"))) {
            throw ApiException.forbidden("A bank account must be verified by someone other than the person who entered"
                    + " it");
        }
        tx.executeWithoutResult(st -> {
            int n = jdbc.sql("UPDATE \"SupplierBankAccount\" SET status = 'verified', \"verifiedBy\" = ?, \"verifiedAt\" = "
                    + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND status ="
                    + " 'pending_verification'").params(user(), id, org()).update();
            if (n == 0) {
                throw ApiException.badRequest("Only a bank account pending verification can be verified");
            }
            if (Boolean.TRUE.equals(acc.get("isDefault"))) {
                jdbc.sql("UPDATE \"SupplierBankAccount\" SET \"isDefault\" = false, \"updatedAt\" = " + NOW + " WHERE"
                        + " \"organizationId\" = ? AND \"partnerId\" = ? AND id <> ?").params(org(), acc.get("partnerId"),
                        id).update();
            }
            audit.recordInTx(AuditInput.of("SupplierBankAccount", id, "update", Obj.of("status",
                    "pending_verification"), Obj.of("status", "verified")));
        });
        events.publish("supplier.bank_account_verified", Obj.of("organizationId", org(), "partnerId",
                acc.get("partnerId"), "bankAccountId", id));
        return bankAccount(id);
    }

    /** Revoke a bank account (no longer usable for payments). */
    public Map<String, Object> revokeBankAccount(String id) {
        bankAccount(id);
        int n = jdbc.sql("UPDATE \"SupplierBankAccount\" SET status = 'revoked', \"isDefault\" = false, \"revokedBy\" = ?,"
                + " \"revokedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                + " AND status <> 'revoked'").params(user(), id, org()).update();
        if (n == 0) {
            throw ApiException.badRequest("Bank account is already revoked");
        }
        audit.record(AuditInput.of("SupplierBankAccount", id, "update", null, Obj.of("status", "revoked")));
        return bankAccount(id);
    }

    private Map<String, Object> bankAccount(String id) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT id, \"partnerId\", \"bankName\", \"branchName\","
                + " \"accountName\", \"accountNumberLast4\", \"swiftCode\", \"currencyCode\", \"isDefault\", status,"
                + " \"createdBy\", \"verifiedBy\", \"verifiedAt\", \"revokedAt\", \"createdAt\" FROM \"SupplierBankAccount\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(id, org()));
        if (row == null) {
            throw ApiException.notFound("Bank account not found");
        }
        row.put("accountNumberMasked", "•••• " + row.get("accountNumberLast4"));
        return row;
    }

    // ── certificates ───────────────────────────────────────────────────────────────────────────────────────────

    public Map<String, Object> addCertificate(String partnerId, CertificateInput in) {
        requireSupplier(partnerId);
        if (in.type() == null || in.type().isBlank()) {
            throw ApiException.badRequest("type is required");
        }
        LocalDate from = date(in.validFrom());
        LocalDate to = date(in.validTo());
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("validTo must not be before validFrom");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"SupplierCertificate\" (id, \"organizationId\", \"partnerId\", type, number, issuer,"
                + " \"validFrom\", \"validTo\", \"fileId\", notes, \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?,"
                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")")
                .params(id, org(), partnerId, in.type().trim().toLowerCase(), in.number(), in.issuer(), from, to,
                        in.fileId(), in.notes(), user()).update();
        audit.record(AuditInput.of("SupplierCertificate", id, "create", null, Obj.of("partnerId", partnerId, "type",
                in.type(), "validTo", in.validTo())));
        return SqlRows.one(jdbc.sql("SELECT * FROM \"SupplierCertificate\" WHERE id = ?").param(id));
    }

    public Map<String, Object> removeCertificate(String id) {
        int n = jdbc.sql("UPDATE \"SupplierCertificate\" SET \"deletedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()).update();
        if (n == 0) {
            throw ApiException.notFound("Certificate not found");
        }
        audit.record(AuditInput.of("SupplierCertificate", id, "delete", null, null));
        return Obj.of("ok", true);
    }

    /** Certificates expiring within {@code days} (or already expired), soonest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> expiringCertificates(int days) {
        return SqlRows.list(jdbc.sql("SELECT c.*, p.name AS \"partnerName\" FROM \"SupplierCertificate\" c JOIN"
                + " \"Partner\" p ON p.id = c.\"partnerId\" WHERE c.\"organizationId\" = ? AND c.\"deletedAt\" IS NULL"
                + " AND c.\"validTo\" IS NOT NULL AND c.\"validTo\" <= CURRENT_DATE + ? ORDER BY c.\"validTo\" ASC LIMIT 500")
                .params(org(), Math.max(0, days)));
    }

    // ── catalogue & prices ─────────────────────────────────────────────────────────────────────────────────────

    public Map<String, Object> upsertProduct(String partnerId, ProductInput in) {
        requireSupplier(partnerId);
        long product = jdbc.sql("SELECT count(*) FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(in.productId(), org()).query(Long.class).single();
        if (product == 0) {
            throw ApiException.notFound("Product not found");
        }
        Map<String, Object> row = SqlRows.one(jdbc.sql("INSERT INTO \"SupplierProduct\" (id, \"organizationId\","
                + " \"partnerId\", \"productId\", \"supplierSku\", \"purchaseUomId\", \"minOrderQty\", \"leadTimeDays\","
                + " preferred, notes, \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW
                + ") ON CONFLICT (\"organizationId\", \"partnerId\", \"productId\") DO UPDATE SET \"supplierSku\" ="
                + " EXCLUDED.\"supplierSku\", \"purchaseUomId\" = EXCLUDED.\"purchaseUomId\", \"minOrderQty\" ="
                + " EXCLUDED.\"minOrderQty\", \"leadTimeDays\" = EXCLUDED.\"leadTimeDays\", preferred = EXCLUDED.preferred,"
                + " notes = EXCLUDED.notes, \"updatedAt\" = " + NOW + " RETURNING *")
                .params(UUID.randomUUID().toString(), org(), partnerId, in.productId(), in.supplierSku(),
                        in.purchaseUomId(), in.minOrderQty(), in.leadTimeDays(), Boolean.TRUE.equals(in.preferred()),
                        in.notes()));
        audit.record(AuditInput.of("SupplierProduct", (String) row.get("id"), "update", null, Obj.of("partnerId",
                partnerId, "productId", in.productId())));
        return row;
    }

    public Map<String, Object> removeProduct(String id) {
        int n = jdbc.sql("DELETE FROM \"SupplierProduct\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .update();
        if (n == 0) {
            throw ApiException.notFound("Supplier product not found");
        }
        return Obj.of("ok", true);
    }

    /** Manual price observation (a phoned-in quote, a market price). */
    public Map<String, Object> recordPrice(String partnerId, PriceInput in) {
        requireSupplier(partnerId);
        if (in.unitPrice() == null || in.unitPrice().signum() < 0) {
            throw ApiException.badRequest("unitPrice must not be negative");
        }
        return recordPrice(partnerId, in.productId(), in.uomId(), in.currencyCode(), in.unitPrice(), in.quantity(),
                date(in.validFrom()), date(in.validTo()), "manual", null);
    }

    /**
     * Append one observation to the price history (idempotent per source document + product). Used by purchase
     * orders, quotations and agreements.
     */
    public Map<String, Object> recordPrice(String partnerId, String productId, String uomId, String currencyCode,
            BigDecimal unitPrice, BigDecimal quantity, LocalDate validFrom, LocalDate validTo, String source,
            String sourceId) {
        if (partnerId == null || productId == null || unitPrice == null) {
            return null;
        }
        String currency = currencyCode != null ? currencyCode : jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\""
                + " WHERE id = ?").param(org()).query(String.class).single();
        return SqlRows.one(jdbc.sql("INSERT INTO \"SupplierPrice\" (id, \"organizationId\", \"partnerId\", \"productId\","
                + " \"uomId\", \"currencyCode\", \"unitPrice\", quantity, \"validFrom\", \"validTo\", source, \"sourceId\","
                + " \"createdBy\", \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ")"
                + " ON CONFLICT (\"organizationId\", source, \"sourceId\", \"productId\") WHERE \"sourceId\" IS NOT NULL"
                + " DO NOTHING RETURNING *")
                .params(UUID.randomUUID().toString(), org(), partnerId, productId, uomId, currency, unitPrice, quantity,
                        validFrom != null ? validFrom : LocalDate.now(), validTo, source, sourceId, user()));
    }

    /** Price history for a product (optionally one supplier), newest first, with per-supplier summary. */
    @Transactional(readOnly = true)
    public Map<String, Object> priceHistory(String productId, String partnerId) {
        if (productId == null || productId.isBlank()) {
            throw ApiException.badRequest("productId is required");
        }
        List<Object> params = new ArrayList<>(List.of(org(), productId));
        String extra = "";
        if (partnerId != null && !partnerId.isBlank()) {
            extra = " AND sp.\"partnerId\" = ?";
            params.add(partnerId);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT sp.*, p.name AS \"partnerName\" FROM"
                + " \"SupplierPrice\" sp JOIN \"Partner\" p ON p.id = sp.\"partnerId\" WHERE sp.\"organizationId\" = ?"
                + " AND sp.\"productId\" = ?" + extra + " ORDER BY sp.\"validFrom\" DESC, sp.\"createdAt\" DESC LIMIT 500")
                .params(params));
        List<Map<String, Object>> summary = SqlRows.list(jdbc.sql("SELECT sp.\"partnerId\", p.name AS \"partnerName\","
                + " sp.\"currencyCode\", count(*) AS observations, MIN(sp.\"unitPrice\") AS \"minPrice\","
                + " MAX(sp.\"unitPrice\") AS \"maxPrice\", ROUND(AVG(sp.\"unitPrice\"), 6) AS \"avgPrice\","
                + " (ARRAY_AGG(sp.\"unitPrice\" ORDER BY sp.\"validFrom\" DESC, sp.\"createdAt\" DESC))[1] AS \"lastPrice\","
                + " MAX(sp.\"validFrom\") AS \"lastDate\" FROM \"SupplierPrice\" sp JOIN \"Partner\" p ON p.id ="
                + " sp.\"partnerId\" WHERE sp.\"organizationId\" = ? AND sp.\"productId\" = ?" + extra
                + " GROUP BY sp.\"partnerId\", p.name, sp.\"currencyCode\" ORDER BY \"lastPrice\" ASC").params(params));
        return Obj.of("productId", productId, "history", rows, "suppliers", summary);
    }

    // ── eligibility ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * May we buy from {@code partnerId} on {@code date} in {@code ctx}? Suspended / blacklisted suppliers are always
     * blocked; {@code supplier.approvedRequired} decides about suppliers that are not approved;
     * {@code supplier.certificatesRequired} lists certificate types that must be valid on the date.
     */
    @Transactional(readOnly = true)
    public Eligibility eligibility(String partnerId, PolicyContext ctx, LocalDate date) {
        Map<String, Object> profile = partnerId == null ? null : profile(partnerId);
        String status = profile == null ? null : (String) profile.get("lifecycleStatus");
        List<String> blocked = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if ("blacklisted".equals(status) || "suspended".equals(status)) {
            blocked.add("Supplier is " + status + (profile.get("statusReason") != null ? ": " + profile.get("statusReason")
                    : ""));
        } else if (!"approved".equals(status)) {
            String mode = policies.text(ProcurementPolicyRegistry.SUPPLIER_APPROVED_REQUIRED, ctx);
            String msg = "Supplier is not approved for purchasing (status: " + (status == null ? "not qualified" : status)
                    + ")";
            if ("block".equals(mode)) {
                blocked.add(msg);
            } else if ("warn".equals(mode)) {
                warnings.add(msg);
            }
        }
        List<String> required = policies.list(ProcurementPolicyRegistry.SUPPLIER_CERTIFICATES_REQUIRED, ctx);
        if (!required.isEmpty() && partnerId != null) {
            LocalDate on = date == null ? LocalDate.now() : date;
            Set<String> valid = new LinkedHashSet<>(jdbc.sql("SELECT type FROM \"SupplierCertificate\" WHERE"
                    + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"deletedAt\" IS NULL AND (\"validFrom\" IS NULL OR"
                    + " \"validFrom\" <= ?) AND (\"validTo\" IS NULL OR \"validTo\" >= ?)").params(org(), partnerId, on, on)
                    .query(String.class).list());
            for (String type : required) {
                if (!valid.contains(type.toLowerCase())) {
                    blocked.add("Supplier has no valid " + type + " certificate");
                }
            }
        }
        return new Eligibility(partnerId, status, blocked, warnings);
    }

    /** Eligibility across the product categories of an order (each category may carry its own policy). */
    @Transactional(readOnly = true)
    public Eligibility eligibilityForProducts(String partnerId, String branchId, List<String> productIds,
            LocalDate date) {
        Set<String> blocked = new LinkedHashSet<>();
        Set<String> warnings = new LinkedHashSet<>();
        Set<String> categories = new LinkedHashSet<>();
        categories.add(null);
        List<String> ids = productIds == null ? List.of() : productIds.stream().filter(p -> p != null && !p.isBlank())
                .distinct().toList();
        if (!ids.isEmpty()) {
            categories.addAll(jdbc.sql("SELECT DISTINCT \"categoryId\" FROM \"Product\" WHERE \"organizationId\" = ? AND"
                    + " id = ANY(?::text[]) AND \"categoryId\" IS NOT NULL").params(org(), ids.toArray(String[]::new))
                    .query(String.class).list());
        }
        String status = null;
        for (String cat : categories) {
            Eligibility e = eligibility(partnerId, PolicyContext.branch(branchId).withCategory(cat), date);
            status = e.lifecycleStatus();
            blocked.addAll(e.blocked());
            warnings.addAll(e.warnings());
        }
        return new Eligibility(partnerId, status, List.copyOf(blocked), List.copyOf(warnings));
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Dates must be ISO dates (YYYY-MM-DD)");
        }
    }

    static String money(BigDecimal v) {
        return JsNumbers.dec(Money.dec(v));
    }
}
