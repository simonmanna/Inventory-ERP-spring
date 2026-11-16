package com.inventoryerp.projects.application;

import static com.inventoryerp.projects.application.ProjectService.blank;
import static com.inventoryerp.projects.application.ProjectService.day;
import static com.inventoryerp.projects.application.ProjectService.org;

import com.inventoryerp.invoicing.application.SupplierPaymentGuard;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subcontractor compliance (ADR-S074 §2).
 *
 * <ul>
 *   <li><b>Documents</b> per supplier, optionally narrowed to one subcontract: insurance certificate, tax clearance,
 *       social security (NSSF) clearance, trade licence — number, issuer, validity, amount, a DMS attachment — and
 *       <b>lien waivers</b> (conditional / unconditional, for a valuation or for a payment, amount and through-date).
 *       A document counts once a user has verified it.</li>
 *   <li><b>Gating</b> per document type ({@code off} by default, {@code warn} or {@code block}) is applied when a
 *       subcontract valuation is certified and when a supplier payment settles a vendor bill of a subcontract (the
 *       valuation's net bill or a retention release bill). Insurance, tax, social security and trade licence need a
 *       verified document valid on the day. Lien waivers: every gate needs an unconditional waiver for the previous
 *       payment on the subcontract; paying a valuation's bill also needs a waiver for that valuation.</li>
 *   <li>A block passes once through an <b>override</b> recorded by a holder of {@code compliance:override} with a
 *       reason (audited, consumed by the next gate it matches). Every warned, blocked and overridden gate is logged.</li>
 * </ul>
 */
@Service
public class SubcontractComplianceService implements SupplierPaymentGuard {

    public static final List<String> TYPES = List.of("insurance_certificate", "tax_clearance",
            "social_security_clearance", "trade_licence", "lien_waiver");
    static final Set<String> MODES = Set.of("off", "warn", "block");
    static final Map<String, String> LABELS = Map.of("insurance_certificate", "Insurance certificate",
            "tax_clearance", "Tax clearance certificate", "social_security_clearance", "Social security (NSSF)"
                    + " clearance", "trade_licence", "Trade licence", "lien_waiver", "Lien waiver");

    public record DocumentInput(String partnerId, String subcontractId, String documentType, String documentNumber,
            String issuer, String validFrom, String validTo, BigDecimal amount, String waiverType, String waiverScope,
            String valuationId, String paymentId, String throughDate, String documentId, String notes) {}

    public record RequirementInput(String documentType, String mode) {}

    /** One reason a gate is not clear. */
    public record Issue(String documentType, String mode, String message) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TransactionTemplate newTx;
    private final JsonMapper json = JsonMapper.builder().build();

    public SubcontractComplianceService(JdbcClient jdbc, AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---- documents ----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> documents(String partnerId, String subcontractId, String documentType) {
        return SqlRows.list(jdbc.sql("SELECT d.*, p.name AS \"partnerName\", s.number AS \"subcontractNumber\","
                        + " v.number AS \"valuationNumber\", pay.\"paymentNumber\" FROM \"ComplianceDocument\" d"
                        + " JOIN \"Partner\" p ON p.id = d.\"partnerId\" LEFT JOIN \"Subcontract\" s ON s.id ="
                        + " d.\"subcontractId\" LEFT JOIN \"SubcontractValuation\" v ON v.id = d.\"valuationId\""
                        + " LEFT JOIN \"Payment\" pay ON pay.id = d.\"paymentId\" WHERE d.\"organizationId\" = :org"
                        + " AND (CAST(:partner AS text) IS NULL OR d.\"partnerId\" = :partner)"
                        + " AND (CAST(:sub AS text) IS NULL OR d.\"subcontractId\" = :sub OR (d.\"subcontractId\" IS NULL"
                        + " AND d.\"partnerId\" = (SELECT \"supplierId\" FROM \"Subcontract\" WHERE id = :sub)))"
                        + " AND (CAST(:type AS text) IS NULL OR d.\"documentType\" = :type)"
                        + " ORDER BY d.\"documentType\", d.\"createdAt\" DESC")
                .param("org", org()).param("partner", blank(partnerId)).param("sub", blank(subcontractId))
                .param("type", blank(documentType)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> document(String id) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT * FROM \"ComplianceDocument\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (d == null) {
            throw ApiException.notFound("Compliance document not found");
        }
        return d;
    }

    @Transactional
    public Map<String, Object> createDocument(DocumentInput in) {
        String type = in.documentType();
        if (type == null || !TYPES.contains(type)) {
            throw ApiException.badRequest("documentType must be one of the following values: " + String.join(", ",
                    TYPES));
        }
        if (in.partnerId() == null || jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" ="
                + " ?").params(in.partnerId(), org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Supplier not found");
        }
        String sub = blank(in.subcontractId());
        if (sub != null) {
            Map<String, Object> s = subcontract(sub);
            if (!in.partnerId().equals(s.get("supplierId"))) {
                throw ApiException.badRequest("The subcontract belongs to another supplier");
            }
        }
        String validFrom = day(in.validFrom());
        String validTo = day(in.validTo());
        String waiverType = null;
        String scope = null;
        String valuation = null;
        String payment = null;
        String through = null;
        BigDecimal amount = in.amount() == null ? null : in.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount != null && amount.signum() < 0) {
            throw ApiException.badRequest("amount must not be negative");
        }
        if ("lien_waiver".equals(type)) {
            if (sub == null) {
                throw ApiException.badRequest("A lien waiver needs the subcontract it waives");
            }
            waiverType = in.waiverType();
            if (!"conditional".equals(waiverType) && !"unconditional".equals(waiverType)) {
                throw ApiException.badRequest("waiverType must be one of the following values: conditional,"
                        + " unconditional");
            }
            scope = in.waiverScope();
            if ("valuation".equals(scope)) {
                valuation = blank(in.valuationId());
                if (valuation == null || jdbc.sql("SELECT count(*) FROM \"SubcontractValuation\" WHERE id = ? AND"
                        + " \"subcontractId\" = ?").params(valuation, sub).query(Long.class).single() == 0) {
                    throw ApiException.badRequest("valuationId must be a valuation of this subcontract");
                }
            } else if ("payment".equals(scope)) {
                payment = blank(in.paymentId());
                if (payment == null || jdbc.sql("SELECT count(*) FROM \"Payment\" WHERE id = ? AND \"organizationId\""
                        + " = ? AND \"partnerId\" = ? AND direction::text = 'outbound'").params(payment, org(),
                        in.partnerId()).query(Long.class).single() == 0) {
                    throw ApiException.badRequest("paymentId must be a payment to this supplier");
                }
            } else {
                throw ApiException.badRequest("waiverScope must be one of the following values: valuation, payment");
            }
            through = day(in.throughDate());
            if (through == null) {
                throw ApiException.badRequest("throughDate should not be empty");
            }
            if (amount == null) {
                throw ApiException.badRequest("amount should not be empty");
            }
        } else if (validTo == null) {
            throw ApiException.badRequest("validTo should not be empty");
        }
        if (validFrom != null && validTo != null && validTo.compareTo(validFrom) < 0) {
            throw ApiException.badRequest("validTo must not be before validFrom");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ComplianceDocument\" (id, \"organizationId\", \"partnerId\", \"subcontractId\","
                        + " \"documentType\", \"documentNumber\", issuer, \"validFrom\", \"validTo\", amount, \"waiverType\","
                        + " \"waiverScope\", \"valuationId\", \"paymentId\", \"throughDate\", \"documentId\", notes,"
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?, ?, ?, ?, ?, ?::date, ?, ?, ?)")
                .params(id, org(), in.partnerId(), sub, type, blank(in.documentNumber()), blank(in.issuer()), validFrom,
                        validTo, amount, waiverType, scope, valuation, payment, through, blank(in.documentId()),
                        blank(in.notes()), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("ComplianceDocument", id, "create", null, Obj.of("documentType", type,
                "partnerId", in.partnerId(), "subcontractId", sub)));
        return document(id);
    }

    /** Edit number, issuer, validity, amount, attachment and notes of a document not yet verified. */
    @Transactional
    public Map<String, Object> updateDocument(String id, DocumentInput in) {
        Map<String, Object> d = lockedDocument(id);
        if ("verified".equals(d.get("status"))) {
            throw ApiException.badRequest("A verified document cannot be changed; record a new one");
        }
        String validFrom = in.validFrom() == null ? str(d.get("validFrom")) : day(in.validFrom());
        String validTo = in.validTo() == null ? str(d.get("validTo")) : day(in.validTo());
        if (validFrom != null && validTo != null && validTo.compareTo(validFrom) < 0) {
            throw ApiException.badRequest("validTo must not be before validFrom");
        }
        if (validTo == null && !"lien_waiver".equals(d.get("documentType"))) {
            throw ApiException.badRequest("validTo should not be empty");
        }
        BigDecimal amount = in.amount() == null ? (BigDecimal) d.get("amount")
                : in.amount().setScale(2, RoundingMode.HALF_UP);
        jdbc.sql("UPDATE \"ComplianceDocument\" SET \"documentNumber\" = ?, issuer = ?, \"validFrom\" = ?::date,"
                        + " \"validTo\" = ?::date, amount = ?, \"throughDate\" = ?::date, \"documentId\" = ?, notes = ?,"
                        + " status = 'pending', \"updatedAt\" = now() WHERE id = ?")
                .params(in.documentNumber() == null ? d.get("documentNumber") : blank(in.documentNumber()),
                        in.issuer() == null ? d.get("issuer") : blank(in.issuer()), validFrom, validTo, amount,
                        in.throughDate() == null ? str(d.get("throughDate")) : day(in.throughDate()),
                        in.documentId() == null ? d.get("documentId") : blank(in.documentId()),
                        in.notes() == null ? d.get("notes") : blank(in.notes()), id)
                .update();
        audit.recordInTx(AuditInput.of("ComplianceDocument", id, "update", null, null));
        return document(id);
    }

    /** A user checked the original: the document now counts for gating. */
    @Transactional
    public Map<String, Object> verify(String id) {
        Map<String, Object> d = lockedDocument(id);
        if ("verified".equals(d.get("status"))) {
            throw ApiException.badRequest("This document is already verified");
        }
        jdbc.sql("UPDATE \"ComplianceDocument\" SET status = 'verified', \"verifiedBy\" = ?, \"verifiedAt\" = now(),"
                + " \"updatedAt\" = now() WHERE id = ?").params(TenantContext.userId(), id).update();
        audit.recordInTx(AuditInput.of("ComplianceDocument", id, "approve", Obj.of("status", d.get("status")),
                Obj.of("status", "verified")));
        return document(id);
    }

    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> d = lockedDocument(id);
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason should not be empty");
        }
        jdbc.sql("UPDATE \"ComplianceDocument\" SET status = 'rejected', \"verifiedBy\" = ?, \"verifiedAt\" = now(),"
                        + " notes = trim(coalesce(notes, '') || ' Rejected: ' || ?), \"updatedAt\" = now() WHERE id = ?")
                .params(TenantContext.userId(), reason.trim(), id).update();
        audit.recordInTx(AuditInput.of("ComplianceDocument", id, "reject", Obj.of("status", d.get("status")),
                Obj.of("status", "rejected", "reason", reason.trim())));
        return document(id);
    }

    private Map<String, Object> lockedDocument(String id) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT * FROM \"ComplianceDocument\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (d == null) {
            throw ApiException.notFound("Compliance document not found");
        }
        return d;
    }

    // ---- requirements -------------------------------------------------------------------------------------------------

    /** Every document type with its gating mode ({@code off} until set). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> requirements() {
        Map<String, String> modes = modes();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : TYPES) {
            out.add(Obj.of("documentType", t, "label", LABELS.get(t), "mode", modes.getOrDefault(t, "off")));
        }
        return out;
    }

    @Transactional
    public List<Map<String, Object>> setRequirements(List<RequirementInput> in) {
        if (in == null || in.isEmpty()) {
            throw ApiException.badRequest("requirements should not be empty");
        }
        Map<String, String> before = modes();
        for (RequirementInput r : in) {
            if (r == null || r.documentType() == null || !TYPES.contains(r.documentType())) {
                throw ApiException.badRequest("documentType must be one of the following values: "
                        + String.join(", ", TYPES));
            }
            if (r.mode() == null || !MODES.contains(r.mode())) {
                throw ApiException.badRequest("mode must be one of the following values: off, warn, block");
            }
            jdbc.sql("INSERT INTO \"ComplianceRequirement\" (id, \"organizationId\", \"documentType\", mode,"
                            + " \"updatedBy\") VALUES (?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"documentType\") DO"
                            + " UPDATE SET mode = EXCLUDED.mode, \"updatedBy\" = EXCLUDED.\"updatedBy\", \"updatedAt\" = now()")
                    .params(UUID.randomUUID().toString(), org(), r.documentType(), r.mode(), TenantContext.userId())
                    .update();
        }
        audit.recordInTx(AuditInput.of("ComplianceRequirement", org(), "update", before, modes()));
        return requirements();
    }

    private Map<String, String> modes() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"documentType\", mode FROM"
                + " \"ComplianceRequirement\" WHERE \"organizationId\" = ?").param(org()))) {
            m.put((String) r.get("documentType"), (String) r.get("mode"));
        }
        return m;
    }

    // ---- gates --------------------------------------------------------------------------------------------------------

    /**
     * What stands in the way of a gate on the subcontract today: {@code valuation} (certifying the next valuation) or
     * {@code payment} (paying a bill; {@code valuationId} when the bill is a valuation's net bill).
     */
    @Transactional(readOnly = true)
    public List<Issue> evaluate(String subcontractId, String gate, String valuationId) {
        Map<String, String> modes = modes();
        modes.values().removeIf("off"::equals);
        if (modes.isEmpty()) {
            return List.of();
        }
        Map<String, Object> s = subcontract(subcontractId);
        String supplier = (String) s.get("supplierId");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Issue> issues = new ArrayList<>();
        for (String type : TYPES) {
            String mode = modes.get(type);
            if (mode == null || "lien_waiver".equals(type)) {
                continue;
            }
            List<Map<String, Object>> docs = SqlRows.list(jdbc.sql("SELECT status, \"validFrom\", \"validTo\" FROM"
                    + " \"ComplianceDocument\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\" = ?"
                    + " AND (\"subcontractId\" IS NULL OR \"subcontractId\" = ?) AND status <> 'rejected' ORDER BY"
                    + " \"validTo\" DESC NULLS LAST").params(org(), supplier, type, subcontractId));
            String label = LABELS.get(type);
            Map<String, Object> verified = docs.stream().filter(d -> "verified".equals(d.get("status"))).findFirst()
                    .orElse(null);
            if (verified == null) {
                issues.add(new Issue(type, mode, label + (docs.isEmpty() ? " is missing" : " is not verified yet")));
                continue;
            }
            LocalDate from = (LocalDate) verified.get("validFrom");
            LocalDate to = (LocalDate) verified.get("validTo");
            if (to != null && to.isBefore(today)) {
                issues.add(new Issue(type, mode, label + " expired on " + to));
            } else if (from != null && from.isAfter(today)) {
                issues.add(new Issue(type, mode, label + " is only valid from " + from));
            }
        }
        String waiverMode = modes.get("lien_waiver");
        if (waiverMode != null) {
            Map<String, Object> previous = previousPayment(subcontractId);
            if (previous != null) {
                LocalDate paid = previous.get("paymentDate") instanceof java.time.Instant i
                        ? i.atZone(ZoneOffset.UTC).toLocalDate() : LocalDate.parse(previous.get("paymentDate").toString()
                                .substring(0, 10));
                long ok = jdbc.sql("SELECT count(*) FROM \"ComplianceDocument\" WHERE \"organizationId\" = ? AND"
                        + " \"subcontractId\" = ? AND \"documentType\" = 'lien_waiver' AND status = 'verified' AND"
                        + " \"waiverType\" = 'unconditional' AND (\"paymentId\" = ? OR \"throughDate\" >= ?::date)")
                        .params(org(), subcontractId, previous.get("id"), paid.toString()).query(Long.class).single();
                if (ok == 0) {
                    issues.add(new Issue("lien_waiver", waiverMode, "Unconditional lien waiver for payment "
                            + previous.get("paymentNumber") + " is missing"));
                }
            }
            if ("payment".equals(gate) && valuationId != null) {
                long ok = jdbc.sql("SELECT count(*) FROM \"ComplianceDocument\" WHERE \"organizationId\" = ? AND"
                        + " \"subcontractId\" = ? AND \"documentType\" = 'lien_waiver' AND status = 'verified' AND"
                        + " \"valuationId\" = ?").params(org(), subcontractId, valuationId).query(Long.class).single();
                if (ok == 0) {
                    String number = jdbc.sql("SELECT number FROM \"SubcontractValuation\" WHERE id = ?")
                            .param(valuationId).query(String.class).optional().orElse(valuationId);
                    issues.add(new Issue("lien_waiver", waiverMode, "Lien waiver for valuation " + number
                            + " is missing"));
                }
            }
        }
        return issues;
    }

    /** Gate of {@code ContractService.valuation}: throws on a block without an override; returns the warnings. */
    @Transactional
    public List<String> checkValuation(String subcontractId) {
        return apply(subcontractId, "valuation", null, evaluate(subcontractId, "valuation", null));
    }

    /** Gate of a supplier payment: every settled bill that belongs to a subcontract is checked. */
    @Override
    @Transactional
    public void beforeSupplierPayment(String partnerId, List<String> documentIds) {
        for (String billId : new LinkedHashSet<>(documentIds)) {
            Map<String, Object> link = billLink(billId);
            if (link == null) {
                continue;
            }
            String sub = (String) link.get("subcontractId");
            String valuation = (String) link.get("valuationId");
            apply(sub, "payment", billId, evaluate(sub, "payment", valuation));
        }
    }

    private List<String> apply(String subcontractId, String gate, String billId, List<Issue> issues) {
        if (issues.isEmpty()) {
            return List.of();
        }
        List<String> blocking = issues.stream().filter(i -> "block".equals(i.mode())).map(Issue::message).toList();
        List<String> warnings = issues.stream().filter(i -> !"block".equals(i.mode())).map(Issue::message).toList();
        if (blocking.isEmpty()) {
            log(subcontractId, gate, billId, "warned", issues, null);
            return warnings;
        }
        Map<String, Object> override = SqlRows.one(jdbc.sql("SELECT * FROM \"ComplianceOverride\" WHERE"
                + " \"organizationId\" = ? AND \"subcontractId\" = ? AND gate = ? AND \"consumedAt\" IS NULL AND"
                + " \"billId\" IS NOT DISTINCT FROM ? ORDER BY \"createdAt\" LIMIT 1 FOR UPDATE").params(org(),
                subcontractId, gate, billId));
        if (override == null) {
            String org = org();
            String user = TenantContext.userId();
            newTx.executeWithoutResult(t -> insertLog(org, user, subcontractId, gate, billId, "blocked", issues, null));
            throw ApiException.badRequest("Compliance check failed: " + String.join("; ", blocking) + ". Record the"
                    + " documents or ask a user with compliance:override to record an override");
        }
        String overrideId = (String) override.get("id");
        jdbc.sql("UPDATE \"ComplianceOverride\" SET \"consumedAt\" = now(), \"consumedBy\" = ? WHERE id = ?")
                .params(TenantContext.userId(), overrideId).update();
        log(subcontractId, gate, billId, "overridden", issues, overrideId);
        audit.recordInTx(AuditInput.of("ComplianceOverride", overrideId, "update", null, Obj.of("consumed", true,
                "gate", gate, "issues", blocking)));
        List<String> out = new ArrayList<>(warnings);
        out.add("Override: " + override.get("reason"));
        return out;
    }

    private void log(String subcontractId, String gate, String billId, String outcome, List<Issue> issues,
            String overrideId) {
        insertLog(org(), TenantContext.userId(), subcontractId, gate, billId, outcome, issues, overrideId);
    }

    private void insertLog(String org, String user, String subcontractId, String gate, String billId, String outcome,
            List<Issue> issues, String overrideId) {
        jdbc.sql("INSERT INTO \"ComplianceGateLog\" (id, \"organizationId\", \"subcontractId\", gate, \"billId\", outcome,"
                        + " issues, \"overrideId\", \"userId\") VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)")
                .params(UUID.randomUUID().toString(), org, subcontractId, gate, billId, outcome,
                        json.writeValueAsString(issues), overrideId, user)
                .update();
    }

    /** The subcontract (and valuation) a vendor bill belongs to, or null for any other bill. */
    private Map<String, Object> billLink(String billId) {
        Map<String, Object> v = SqlRows.one(jdbc.sql("SELECT id AS \"valuationId\", \"subcontractId\" FROM"
                + " \"SubcontractValuation\" WHERE \"organizationId\" = ? AND \"billId\" = ? LIMIT 1").params(org(),
                billId));
        if (v != null) {
            return v;
        }
        return SqlRows.one(jdbc.sql("SELECT CAST(NULL AS text) AS \"valuationId\", v.\"subcontractId\" FROM"
                + " \"RetentionLedger\" r JOIN \"SubcontractValuation\" v ON v.id = r.\"valuationId\" WHERE"
                + " r.\"organizationId\" = ? AND r.side = 'subcontractor' AND r.\"releaseDocumentId\" = ? LIMIT 1")
                .params(org(), billId));
    }

    /** The latest payment (not cancelled) settling a bill of the subcontract's valuations. */
    private Map<String, Object> previousPayment(String subcontractId) {
        return SqlRows.one(jdbc.sql("SELECT p.id, p.\"paymentNumber\", p.\"paymentDate\" FROM \"Payment\" p WHERE"
                + " p.\"organizationId\" = ? AND p.status::text <> 'cancelled' AND EXISTS (SELECT 1 FROM"
                + " \"PaymentAllocation\" a JOIN \"SubcontractValuation\" v ON v.\"billId\" = a.\"documentId\" WHERE"
                + " a.\"paymentId\" = p.id AND v.\"subcontractId\" = ?) ORDER BY p.\"paymentDate\" DESC, p.\"createdAt\""
                + " DESC LIMIT 1").params(org(), subcontractId));
    }

    // ---- overrides ----------------------------------------------------------------------------------------------------

    /** A one-time pass through the next blocking {@code gate} of the subcontract (payment: of that bill). */
    @Transactional
    public Map<String, Object> createOverride(String subcontractId, String gate, String billId, String reason) {
        subcontract(subcontractId);
        if (!"valuation".equals(gate) && !"payment".equals(gate)) {
            throw ApiException.badRequest("gate must be one of the following values: valuation, payment");
        }
        if (reason == null || reason.trim().length() < 10) {
            throw ApiException.badRequest("Give the reason for the override (at least 10 characters)");
        }
        String bill = blank(billId);
        if ("payment".equals(gate)) {
            Map<String, Object> link = bill == null ? null : billLink(bill);
            if (link == null || !subcontractId.equals(link.get("subcontractId"))) {
                throw ApiException.badRequest("billId must be a vendor bill of this subcontract");
            }
        } else {
            bill = null;
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"ComplianceOverride\" (id, \"organizationId\", \"subcontractId\", gate, \"billId\", reason,"
                + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?)").params(id, org(), subcontractId, gate, bill,
                reason.trim(), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("ComplianceOverride", id, "create", null, Obj.of("subcontractId",
                subcontractId, "gate", gate, "billId", bill, "reason", reason.trim())));
        return SqlRows.one(jdbc.sql("SELECT * FROM \"ComplianceOverride\" WHERE id = ?").param(id));
    }

    // ---- views --------------------------------------------------------------------------------------------------------

    /**
     * Compliance of one subcontract: its documents (supplier-wide ones included), lien waivers, the bills and payments
     * a waiver can refer to, what the next valuation gate would say, overrides and the gate log.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> subcontractView(String subcontractId) {
        Map<String, Object> s = subcontract(subcontractId);
        List<Map<String, Object>> docs = documents(null, subcontractId, null);
        List<Map<String, Object>> valuations = SqlRows.list(jdbc.sql("SELECT v.id, v.number, v.\"valuationDate\","
                + " v.\"thisValuation\", v.\"netAmount\", v.\"billId\", d.\"documentNumber\" AS \"billNumber\","
                + " d.\"amountResidual\" AS \"billResidual\" FROM \"SubcontractValuation\" v LEFT JOIN \"Document\" d ON"
                + " d.id = v.\"billId\" WHERE v.\"subcontractId\" = ? ORDER BY v.\"valuationDate\", v.\"createdAt\"")
                .param(subcontractId));
        List<Map<String, Object>> payments = SqlRows.list(jdbc.sql("SELECT p.id, p.\"paymentNumber\","
                + " p.\"paymentDate\", sum(a.amount) AS amount FROM \"Payment\" p JOIN \"PaymentAllocation\" a ON"
                + " a.\"paymentId\" = p.id JOIN \"SubcontractValuation\" v ON v.\"billId\" = a.\"documentId\" WHERE"
                + " v.\"subcontractId\" = ? AND p.status::text <> 'cancelled' GROUP BY p.id ORDER BY p.\"paymentDate\"")
                .param(subcontractId));
        List<Map<String, Object>> overrides = SqlRows.list(jdbc.sql("SELECT * FROM \"ComplianceOverride\" WHERE"
                + " \"subcontractId\" = ? ORDER BY \"createdAt\" DESC").param(subcontractId));
        List<Map<String, Object>> log = SqlRows.list(jdbc.sql("SELECT * FROM \"ComplianceGateLog\" WHERE"
                + " \"subcontractId\" = ? ORDER BY \"createdAt\" DESC LIMIT 100").param(subcontractId));
        String supplierName = jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ?").param(s.get("supplierId"))
                .query(String.class).optional().orElse(null);
        return Obj.of("subcontract", s, "supplierName", supplierName, "requirements", requirements(), "documents",
                docs.stream().filter(d -> !"lien_waiver".equals(d.get("documentType"))).toList(), "lienWaivers",
                docs.stream().filter(d -> "lien_waiver".equals(d.get("documentType"))).toList(), "valuations",
                valuations, "payments", payments, "valuationGate", evaluate(subcontractId, "valuation", null),
                "overrides", overrides, "log", log);
    }

    /** Compliance of a supplier: documents per type with the status gating would see today, and its subcontracts. */
    @Transactional(readOnly = true)
    public Map<String, Object> supplierView(String partnerId) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT id, code, name FROM \"Partner\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(partnerId, org()));
        if (p == null) {
            throw ApiException.notFound("Supplier not found");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> docs = documents(partnerId, null, null);
        List<Map<String, Object>> summary = new ArrayList<>();
        for (String t : TYPES) {
            if ("lien_waiver".equals(t)) {
                continue;
            }
            Map<String, Object> best = docs.stream().filter(d -> t.equals(d.get("documentType"))
                    && d.get("subcontractId") == null && "verified".equals(d.get("status"))).max((a, b) ->
                    String.valueOf(a.get("validTo")).compareTo(String.valueOf(b.get("validTo")))).orElse(null);
            String state = best == null ? "missing" : ((LocalDate) best.get("validTo")).isBefore(today) ? "expired"
                    : "valid";
            summary.add(Obj.of("documentType", t, "label", LABELS.get(t), "state", state, "validTo", best == null
                    ? null : best.get("validTo")));
        }
        List<Map<String, Object>> subs = SqlRows.list(jdbc.sql("SELECT s.id, s.number, s.status, s.value,"
                + " pr.code AS \"projectCode\", pr.name AS \"projectName\" FROM \"Subcontract\" s JOIN \"Project\" pr ON"
                + " pr.id = s.\"projectId\" WHERE s.\"organizationId\" = ? AND s.\"supplierId\" = ? ORDER BY"
                + " s.\"createdAt\" DESC").params(org(), partnerId));
        return Obj.of("partner", p, "requirements", requirements(), "summary", summary, "documents", docs,
                "subcontracts", subs);
    }

    private Map<String, Object> subcontract(String id) {
        Map<String, Object> s = id == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"Subcontract\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(id, org()));
        if (s == null) {
            throw ApiException.notFound("Subcontract not found");
        }
        return s;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
