package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.invoicing.application.MobileMoneyService;
import com.inventoryerp.invoicing.application.StatementService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer portal (ADR-S011 phase S10, licence {@code customerPortal}). Staff issue a magic link for a customer; the
 * link's token (only its SHA-256 is stored) identifies the organization and the customer, and every portal call runs
 * in that organization scoped to that customer: quotations (accept / decline), orders with delivery progress, invoices,
 * the account statement, mobile-money payment of an invoice and complaints. Nothing outside the customer's own
 * documents is reachable.
 */
@Service
public class SalesPortalService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Portal session: the organization and customer a token resolved to. */
    public record Session(String organizationId, String partnerId, String accessId) {}

    private final SalesDb db;
    private final JdbcClient jdbc;
    private final SalesQuotationService quotations;
    private final SalesComplaintService complaints;
    private final StatementService statements;
    private final MobileMoneyService mobileMoney;
    private final NotificationsService notifications;
    private final AuditService audit;
    private final String baseUrl;
    private final boolean allowTestPayments;

    public SalesPortalService(SalesDb db, JdbcClient jdbc, SalesQuotationService quotations,
            SalesComplaintService complaints, StatementService statements, MobileMoneyService mobileMoney,
            NotificationsService notifications, AuditService audit, @Value("${app.portal.base-url:}") String baseUrl,
            @Value("${app.portal.allow-test-payments:false}") boolean allowTestPayments) {
        this.db = db;
        this.jdbc = jdbc;
        this.quotations = quotations;
        this.complaints = complaints;
        this.statements = statements;
        this.mobileMoney = mobileMoney;
        this.notifications = notifications;
        this.audit = audit;
        this.baseUrl = baseUrl;
        this.allowTestPayments = allowTestPayments;
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(
                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ staff side

    /** Issue a link for {@code partnerId} valid {@code days} (default 30); e-mailed when an address is known. */
    @Transactional
    public Map<String, Object> issue(Map<String, Object> in) {
        String partnerId = str(in.get("partnerId"));
        Map<String, Object> partner = partnerId == null ? null : db.row("SELECT id, name, email FROM \"Partner\" WHERE"
                + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", partnerId, org());
        if (partner == null) {
            throw ApiException.notFound("Partner not found");
        }
        int days = in.get("days") instanceof Number n ? n.intValue() : 30;
        if (days < 1 || days > 365) {
            throw ApiException.badRequest("days must be between 1 and 365");
        }
        String email = blank(str(in.get("email"))) ? str(partner.get("email")) : str(in.get("email"));
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = Instant.now().plus(days, ChronoUnit.DAYS);
        Map<String, Object> row = db.insert("CustomerPortalAccess", Obj.of("partnerId", partnerId, "email", email,
                "tokenHash", hash(token), "expiresAt", expires, "createdById", user()));
        String base = blank(str(in.get("baseUrl"))) ? baseUrl : str(in.get("baseUrl"));
        String path = "/portal?token=" + token;
        String url = (base == null ? "" : base.replaceAll("/+$", "")) + path;
        boolean emailed = false;
        if (Boolean.TRUE.equals(in.get("sendEmail")) && !blank(email) && !blank(base)) {
            String orgName = jdbc.sql("SELECT name FROM \"Organization\" WHERE id = ?").param(org()).query(String.class)
                    .optional().orElse("");
            emailed = notifications.sendExternalEmail(email, orgName + " — your customer portal", "Dear "
                    + partner.get("name") + ",\n\nFollow this link to see your quotations, orders, deliveries, invoices"
                    + " and statement, and to pay online:\n\n" + url + "\n\nThe link is personal and valid until "
                    + LocalDate.ofInstant(expires, java.time.ZoneOffset.UTC) + ".\n", null);
        }
        audit.record(AuditInput.of("CustomerPortalAccess", str(row.get("id")), "create", null, Obj.of("partnerId",
                partnerId, "email", email, "expiresAt", expires, "emailed", emailed)));
        Map<String, Object> out = access(str(row.get("id")));
        out.put("token", token);
        out.put("path", path);
        out.put("url", url);
        out.put("emailed", emailed);
        return out;
    }

    private Map<String, Object> access(String id) {
        return db.row("SELECT a.id, a.\"partnerId\", p.name AS \"partnerName\", a.email, a.\"expiresAt\","
                + " a.\"revokedAt\", a.\"lastUsedAt\", a.\"createdAt\" FROM \"CustomerPortalAccess\" a LEFT JOIN \"Partner\""
                + " p ON p.id = a.\"partnerId\" WHERE a.id = ? AND a.\"organizationId\" = ?", id, org());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> accesses(String partnerId) {
        List<Object> params = new ArrayList<>(List.of(org()));
        String filter = "";
        if (!blank(partnerId)) {
            filter = " AND a.\"partnerId\" = ?";
            params.add(partnerId);
        }
        return db.rows("SELECT a.id, a.\"partnerId\", p.name AS \"partnerName\", a.email, a.\"expiresAt\","
                + " a.\"revokedAt\", a.\"lastUsedAt\", a.\"createdAt\" FROM \"CustomerPortalAccess\" a LEFT JOIN"
                + " \"Partner\" p ON p.id = a.\"partnerId\" WHERE a.\"organizationId\" = ?" + filter
                + " ORDER BY a.\"createdAt\" DESC", params);
    }

    @Transactional
    public Map<String, Object> revoke(String id) {
        db.require("CustomerPortalAccess", id, "Portal access not found");
        db.update("UPDATE \"CustomerPortalAccess\" SET \"revokedAt\" = " + SalesDb.NOW + " WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"revokedAt\" IS NULL", id, org());
        audit.record(AuditInput.of("CustomerPortalAccess", id, "update", null, Obj.of("revoked", true)));
        return access(id);
    }

    // ------------------------------------------------------------------ customer side

    /**
     * Resolve a portal token (before any organization is known) and run {@code body} in its organization. An
     * unknown, revoked or expired token is a 401.
     */
    public <T> T withToken(String token, Supplier<T> body) {
        if (blank(token) || token.length() > 200) {
            throw ApiException.unauthorized("Portal link is missing or invalid");
        }
        Map<String, Object> a = TenantContext.unscoped(() -> jdbc.sql("SELECT id, \"organizationId\", \"partnerId\","
                + " \"expiresAt\", \"revokedAt\" FROM \"CustomerPortalAccess\" WHERE \"tokenHash\" = ?").param(hash(token))
                .query().listOfRows().stream().findFirst().orElse(null));
        if (a == null || a.get("revokedAt") != null
                || ((java.sql.Timestamp) a.get("expiresAt")).toInstant().isBefore(Instant.now())) {
            throw ApiException.unauthorized("Portal link is invalid or has expired");
        }
        TenantContext.TenantStore store = TenantContext.TenantStore.organization(str(a.get("organizationId")));
        // the portal reads the customer's documents whoever owns them; every query below is scoped to the customer
        store.setPermissions(List.of(Permissions.SALES__ORDER_READ_ALL));
        Session s = new Session(str(a.get("organizationId")), str(a.get("partnerId")), str(a.get("id")));
        return TenantContext.run(store, () -> {
            jdbc.sql("UPDATE \"CustomerPortalAccess\" SET \"lastUsedAt\" = " + SalesDb.NOW + " WHERE id = ?")
                    .param(s.accessId()).update();
            CURRENT.set(s);
            try {
                return body.get();
            } finally {
                CURRENT.remove();
            }
        });
    }

    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();

    private static String partner() {
        Session s = CURRENT.get();
        if (s == null) {
            throw ApiException.unauthorized("Portal link is missing or invalid");
        }
        return s.partnerId();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> me() {
        Map<String, Object> p = db.row("SELECT p.id, p.name, p.email, p.phone, o.name AS \"companyName\","
                + " o.\"currencyCode\" FROM \"Partner\" p JOIN \"Organization\" o ON o.id = p.\"organizationId\" WHERE"
                + " p.id = ? AND p.\"organizationId\" = ?", partner(), org());
        Map<String, Object> bal = db.row("SELECT COALESCE(sum(\"amountResidual\"), 0) AS open, COALESCE(sum("
                + "\"amountResidual\") FILTER (WHERE \"dueDate\" < CURRENT_DATE), 0) AS overdue FROM \"Document\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\" = 'sales_invoice' AND status ="
                + " 'posted'", org(), partner());
        p.put("openBalance", bal.get("open"));
        p.put("overdue", bal.get("overdue"));
        List<Map<String, Object>> providers = new ArrayList<>();
        for (Map<String, Object> g : mobileMoney.providers()) {
            if (Boolean.TRUE.equals(g.get("configured")) && portalProvider(str(g.get("code")))) {
                String code = str(g.get("code"));
                providers.add(Obj.of("code", code, "name", switch (code) {
                    case "mtn_momo" -> "MTN Mobile Money";
                    case "airtel_money" -> "Airtel Money";
                    case "mock" -> "Test payment";
                    default -> code;
                }));
            }
        }
        p.put("mobileMoneyProviders", providers);
        return p;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> quotations() {
        return db.rows("SELECT id, number, revision, status, \"issueDate\", \"validUntil\", \"currencyCode\", subtotal,"
                + " \"taxAmount\", total FROM \"SalesQuotation\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND"
                + " \"deletedAt\" IS NULL AND status IN ('sent', 'accepted', 'rejected', 'expired', 'converted') ORDER BY"
                + " \"issueDate\" DESC", org(), partner());
    }

    private void ownQuotation(String id) {
        if (db.count("SELECT count(*) FROM \"SalesQuotation\" WHERE id = ? AND \"organizationId\" = ? AND \"partnerId\""
                + " = ? AND status IN ('sent', 'accepted', 'rejected', 'expired', 'converted') AND \"deletedAt\" IS NULL",
                List.of(id, org(), partner())) == 0) {
            throw ApiException.notFound("Quotation not found");
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> quotation(String id) {
        ownQuotation(id);
        return strip(quotations.get(id), "approvalReasons", "approvalRequestId", "internalNotes", "ownerId",
                "createdById", "credit", "dealId", "margin");
    }

    @Transactional
    public Map<String, Object> acceptQuotation(String id) {
        ownQuotation(id);
        quotations.accept(id);
        audit.record(AuditInput.of("SalesQuotation", id, "update", null, Obj.of("via", "customer_portal")));
        return quotation(id);
    }

    @Transactional
    public Map<String, Object> declineQuotation(String id, String reason) {
        ownQuotation(id);
        quotations.decline(id, reason);
        return quotation(id);
    }

    private static Map<String, Object> strip(Map<String, Object> m, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>(m);
        for (String k : keys) {
            out.remove(k);
        }
        if (out.get("lines") instanceof List<?> lines) {
            List<Object> clean = new ArrayList<>();
            for (Object o : lines) {
                if (o instanceof Map<?, ?> l) {
                    Map<Object, Object> c = new LinkedHashMap<>(l);
                    for (String k : List.of("cost", "costSnapshot", "stock", "marginPercent", "listPrice", "priceRuleId")) {
                        c.remove(k);
                    }
                    clean.add(c);
                }
            }
            out.put("lines", clean);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> orders() {
        return db.rows("SELECT id, number, status, \"deliveryStatus\", \"invoiceStatus\", \"customerRef\","
                + " \"requestedDate\", \"commitmentDate\", \"currencyCode\", total, \"confirmedAt\" FROM \"SalesOrder\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND status <> 'draft' AND \"deletedAt\" IS NULL ORDER BY"
                + " \"createdAt\" DESC", org(), partner());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> order(String id) {
        Map<String, Object> o = db.row("SELECT id, number, status, \"deliveryStatus\", \"invoiceStatus\", \"customerRef\","
                + " \"requestedDate\", \"commitmentDate\", \"deliveryAddress\", \"currencyCode\", subtotal, \"taxAmount\","
                + " total, \"confirmedAt\" FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? AND \"partnerId\" = ?"
                + " AND status <> 'draft' AND \"deletedAt\" IS NULL", id, org(), partner());
        if (o == null) {
            throw ApiException.notFound("Sales order not found");
        }
        o.put("lines", db.rows("SELECT l.description, l.quantity, u.code AS \"uomCode\", l.\"unitPrice\","
                + " l.\"discountPercent\", l.\"lineTotal\", l.\"qtyDelivered\", l.\"qtyAccepted\", l.\"qtyInvoiced\","
                + " l.\"qtyCancelled\", l.\"promisedDate\" FROM \"SalesOrderLine\" l LEFT JOIN \"UnitOfMeasure\" u ON u.id ="
                + " l.\"uomId\" WHERE l.\"orderId\" = ? ORDER BY l.\"sortOrder\"", id));
        o.put("deliveries", db.rows("SELECT d.id, d.code AS number, d.status, (SELECT json_agg(json_build_object("
                + "'number', t.code, 'status', t.status, 'loadedTonnes', t.\"loadedTonnes\", 'deliveredTonnes',"
                + " t.\"deliveredTonnes\", 'dispatchedAt', t.\"dispatchedAt\", 'deliveredAt', t.\"deliveredAt\") ORDER BY"
                + " t.sequence) FROM \"FleetLoad\" t WHERE t.\"deliveryOrderId\" = d.id) AS"
                + " trips FROM \"FleetDeliveryOrder\" d WHERE d.\"organizationId\" = ? AND d.\"sourceType\" = 'sales_order'"
                + " AND d.\"sourceId\" = ? ORDER BY d.\"createdAt\"", org(), id));
        return o;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> invoices() {
        return db.rows("SELECT id, \"documentNumber\" AS number, \"documentType\"::text AS type, status::text AS status,"
                + " \"issueDate\", \"dueDate\", \"totalAmount\" AS total, \"amountResidual\" AS residual FROM \"Document\""
                + " WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\" IN ('sales_invoice',"
                + " 'credit_note') AND status IN ('posted', 'paid') ORDER BY \"issueDate\" DESC", org(), partner());
    }

    @Transactional(readOnly = true)
    public byte[] statementPdf(String from, String to) {
        return statements.pdf("ar", partner(), from, to);
    }

    /** The mock gateway marks money received without any; customers only see it when explicitly allowed (tests). */
    private boolean portalProvider(String code) {
        return !"mock".equals(code) || allowTestPayments;
    }

    /** Start a mobile-money collection of an open invoice of the customer. */
    @Transactional
    public Map<String, Object> pay(String invoiceId, Map<String, Object> in) {
        Map<String, Object> inv = db.row("SELECT id, \"amountResidual\" FROM \"Document\" WHERE id = ? AND"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND \"documentType\" = 'sales_invoice' AND status ="
                + " 'posted'", invoiceId, org(), partner());
        if (inv == null) {
            throw ApiException.notFound("Invoice not found");
        }
        java.math.BigDecimal residual = SalesDb.dec(inv.get("amountResidual"));
        java.math.BigDecimal amount = in.get("amount") == null ? residual : SalesDb.dec(in.get("amount"));
        if (!portalProvider(str(in.get("provider")))) {
            throw ApiException.badRequest("Unknown payment provider");
        }
        if (amount.signum() <= 0 || amount.compareTo(residual) > 0) {
            throw ApiException.badRequest("Amount must be positive and not more than the open balance");
        }
        // a double click / retry while the customer confirms on the phone returns the request already running
        Map<String, Object> running = db.row("SELECT id FROM \"MobileMoneyRequest\" WHERE \"organizationId\" = ? AND"
                + " \"documentId\" = ? AND \"partnerId\" = ? AND status = 'pending' AND amount = ? AND \"createdAt\" >"
                + " now() - interval '10 minutes' ORDER BY \"createdAt\" DESC LIMIT 1", org(), invoiceId, partner(), amount);
        if (running != null) {
            return mobileMoney.get(str(running.get("id")));
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("provider", in.get("provider"));
        req.put("phone", in.get("phone"));
        req.put("amount", amount);
        req.put("purpose", "invoice");
        req.put("partnerId", partner());
        req.put("documentId", invoiceId);
        return mobileMoney.initiate(req);
    }

    /** Status of a payment the customer started (polls the provider). */
    @Transactional
    public Map<String, Object> payment(String id) {
        if (db.count("SELECT count(*) FROM \"MobileMoneyRequest\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"partnerId\" = ?", List.of(id, org(), partner())) == 0) {
            throw ApiException.notFound("Payment not found");
        }
        return mobileMoney.refresh(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> complaints() {
        return db.rows("SELECT id, number, category, status, description, resolution, \"createdAt\" FROM"
                + " \"CustomerComplaint\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? ORDER BY \"createdAt\" DESC",
                org(), partner());
    }

    @Transactional
    public Map<String, Object> complain(Map<String, Object> in) {
        String orderId = blank(str(in.get("orderId"))) ? null : str(in.get("orderId"));
        if (orderId != null && db.count("SELECT count(*) FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"partnerId\" = ?", List.of(orderId, org(), partner())) == 0) {
            throw ApiException.notFound("Sales order not found");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partner());
        body.put("orderId", orderId);
        body.put("category", in.get("category"));
        body.put("description", in.get("description"));
        return complaints.create(body);
    }
}
