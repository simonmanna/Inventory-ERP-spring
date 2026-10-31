package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Supplier portal by secure link (ADR-PROCUREMENT-001 wave 7; owner decision: no supplier accounts). A buyer issues a
 * link for one document — an RFQ invitation or a purchase order — and one supplier. The link carries a random 256-bit
 * token; only its SHA-256 hash is stored, it expires (default 14 days, max 90), can be revoked, and every use is counted
 * and audited. Through it the supplier can only see that document and answer it: submit or revise a quotation,
 * decline the RFQ, or confirm the order with a reference and promised date. Nothing else is reachable.
 */
@Service
public class SupplierPortalService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String INVALID = "This link is invalid or has expired — ask the buyer for a new one";

    /** The resolved link: tenant and document it opens. */
    record Link(String id, String organizationId, String documentType, String documentId, String partnerId) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final RfqService rfqs;
    private final RequisitionToOrderService orders;

    public SupplierPortalService(JdbcClient jdbc, AuditService audit, EventBus events, RfqService rfqs,
            RequisitionToOrderService orders) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.rfqs = rfqs;
        this.orders = orders;
    }

    // ── buyer side (authenticated) ───────────────────────────────────────────────────────────────────────────────

    /** Issue a link; the plain token is returned once and never stored. */
    public Map<String, Object> issue(String documentType, String documentId, String partnerId, Integer days) {
        String orgId = org();
        int validDays = days == null ? 14 : days;
        if (validDays < 1 || validDays > 90) {
            throw ApiException.badRequest("A link can be valid for 1 to 90 days");
        }
        if ("rfq".equals(documentType)) {
            String status = jdbc.sql("SELECT status FROM \"Rfq\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(documentId, orgId).query(String.class).optional().orElse(null);
            if (status == null) {
                throw ApiException.notFound("RFQ not found");
            }
            if (!List.of("draft", "sent").contains(status)) {
                throw ApiException.badRequest("Quotations are closed on this RFQ");
            }
            if (jdbc.sql("SELECT count(*) FROM \"RfqSupplier\" WHERE \"rfqId\" = ? AND \"partnerId\" = ?")
                    .params(documentId, partnerId).query(Long.class).single() == 0) {
                throw ApiException.badRequest("This supplier was not invited to the RFQ");
            }
        } else {
            Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT \"partnerId\", status::text AS status FROM"
                    + " \"PurchaseOrder\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                    .params(documentId, orgId));
            if (po == null) {
                throw ApiException.notFound("Purchase order not found");
            }
            partnerId = (String) po.get("partnerId");
            if (partnerId == null) {
                throw ApiException.badRequest("This order has no supplier");
            }
            if (!List.of("active", "partially_received").contains(po.get("status"))) {
                throw ApiException.badRequest("Only an approved, open order can be shared with the supplier");
            }
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String id = UUID.randomUUID().toString();
        Instant expires = Instant.now().plus(validDays, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        jdbc.sql("INSERT INTO \"SupplierPortalToken\" (id, \"organizationId\", \"tokenHash\", \"documentType\","
                        + " \"documentId\", \"partnerId\", \"expiresAt\", \"createdBy\", \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?,"
                        + " ?, " + NOW + ")")
                .params(id, orgId, hash(token), documentType, documentId, partnerId, SqlRows.ts(expires), user())
                .update();
        audit.record(AuditInput.of("SupplierPortalToken", id, "create", null, Obj.of("documentType", documentType,
                "documentId", documentId, "partnerId", partnerId, "expiresAt", expires.toString())));
        return Obj.of("id", id, "token", token, "path", "/supplier-portal/" + token, "expiresAt", expires.toString(),
                "partnerId", partnerId);
    }

    /** Links issued for a document (never the token). */
    public List<Map<String, Object>> links(String documentType, String documentId) {
        return SqlRows.list(jdbc.sql("SELECT t.id, t.\"partnerId\", p.name AS \"partnerName\", t.\"expiresAt\","
                + " t.\"revokedAt\", t.\"lastUsedAt\", t.\"useCount\", t.\"createdAt\" FROM \"SupplierPortalToken\" t JOIN"
                + " \"Partner\" p ON p.id = t.\"partnerId\" WHERE t.\"organizationId\" = ? AND t.\"documentType\" = ? AND"
                + " t.\"documentId\" = ? ORDER BY t.\"createdAt\" DESC").params(org(), documentType, documentId));
    }

    /** Revoke a link at once. */
    public Map<String, Object> revoke(String id) {
        if (jdbc.sql("UPDATE \"SupplierPortalToken\" SET \"revokedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\""
                + " = ? AND \"revokedAt\" IS NULL").params(id, org()).update() == 0) {
            throw ApiException.notFound("Link not found or already revoked");
        }
        audit.record(AuditInput.of("SupplierPortalToken", id, "update", null, Obj.of("revoked", true)));
        return Obj.of("id", id, "revoked", true);
    }

    // ── supplier side (public, token only) ───────────────────────────────────────────────────────────────────────

    /** What the supplier sees. */
    public Map<String, Object> view(String token) {
        return within(token, "view", link -> {
            Map<String, Object> org = SqlRows.one(jdbc.sql("SELECT name FROM \"Organization\" WHERE id = ?")
                    .param(link.organizationId()));
            String supplier = jdbc.sql("SELECT name FROM \"Partner\" WHERE id = ?").param(link.partnerId())
                    .query(String.class).single();
            if ("rfq".equals(link.documentType())) {
                Map<String, Object> rfq = SqlRows.one(jdbc.sql("SELECT id, \"rfqNumber\", title, status, deadline,"
                        + " \"deliveryTerms\", notes FROM \"Rfq\" WHERE id = ?").param(link.documentId()));
                List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.id, l.\"lineNumber\", l.description,"
                        + " l.quantity::text AS quantity, u.name AS uom, l.\"requiredDate\" FROM \"RfqLine\" l LEFT JOIN"
                        + " \"UnitOfMeasure\" u ON u.id = l.\"uomId\" WHERE l.\"rfqId\" = ? ORDER BY l.\"lineNumber\"")
                        .param(link.documentId()));
                Map<String, Object> mine = SqlRows.one(jdbc.sql("SELECT id, revision, \"supplierReference\","
                        + " \"createdAt\" AS \"submittedAt\" FROM \"SupplierQuotation\" WHERE \"rfqId\" = ? AND \"partnerId\" = ? ORDER BY"
                        + " revision DESC LIMIT 1").params(link.documentId(), link.partnerId()));
                return Obj.of("documentType", "rfq", "buyer", org.get("name"), "supplier", supplier, "rfq", rfq,
                        "lines", lines, "lastQuotation", mine, "canQuote", "sent".equals(rfq.get("status")));
            }
            Map<String, Object> po = SqlRows.one(jdbc.sql("SELECT id, \"orderNumber\", status::text AS status,"
                    + " \"orderDate\", \"expectedDeliveryDate\", \"currencyCode\", subtotal::text AS subtotal,"
                    + " \"taxAmount\"::text AS \"taxAmount\", \"totalAmount\"::text AS \"totalAmount\", terms,"
                    + " \"acknowledgedAt\", \"supplierReference\", \"promisedDate\" FROM \"PurchaseOrder\" WHERE id = ?")
                    .param(link.documentId()));
            List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT \"lineNumber\", description,"
                    + " quantity::text AS quantity, \"receivedQuantity\"::text AS \"receivedQuantity\", \"unitPrice\"::text"
                    + " AS \"unitPrice\", subtotal::text AS subtotal FROM \"PurchaseOrderLine\" WHERE \"purchaseOrderId\" = ?"
                    + " ORDER BY \"lineNumber\"").param(link.documentId()));
            return Obj.of("documentType", "purchase_order", "buyer", org.get("name"), "supplier", supplier, "order", po,
                    "lines", lines, "canConfirm", po.get("acknowledgedAt") == null
                            && List.of("active", "partially_received").contains(po.get("status")));
        });
    }

    /** Submit (or revise) the supplier's quotation for the RFQ behind the link. */
    public Map<String, Object> quote(String token, RfqService.QuoteInput in) {
        return within(token, "quote", link -> {
            requireType(link, "rfq");
            RfqService.QuoteInput forced = new RfqService.QuoteInput(link.partnerId(), in.supplierReference(),
                    in.validUntil(), in.currencyCode(), in.exchangeRate(), in.leadTimeDays(), in.paymentTermsDays(),
                    in.freightAmount(), null, in.notes(), in.lines());
            rfqs.recordQuotation(link.documentId(), forced, "portal");
            return Obj.of("ok", true, "message", "Thank you — your quotation was received");
        });
    }

    /** Decline the RFQ. */
    public Map<String, Object> decline(String token, String reason) {
        return within(token, "decline", link -> {
            requireType(link, "rfq");
            rfqs.decline(link.documentId(), link.partnerId(), reason);
            return Obj.of("ok", true, "message", "Noted — you declined this request for quotation");
        });
    }

    /** Confirm the order with the supplier's reference and promised delivery date. */
    public Map<String, Object> confirm(String token, String supplierReference, String promisedDate) {
        return within(token, "confirm", link -> {
            requireType(link, "purchase_order");
            orders.acknowledge(link.documentId(), supplierReference, promisedDate);
            return Obj.of("ok", true, "message", "Thank you — the order is confirmed");
        });
    }

    private <T> T within(String token, String action, Function<Link, T> body) {
        Link link = resolve(token);
        return TenantContext.run(TenantStore.organization(link.organizationId()), () -> {
            T result = body.apply(link);
            jdbc.sql("UPDATE \"SupplierPortalToken\" SET \"lastUsedAt\" = " + NOW + ", \"useCount\" = \"useCount\" + 1"
                    + " WHERE id = ?").param(link.id()).update();
            if (!"view".equals(action)) {
                audit.record(AuditInput.of("SupplierPortalToken", link.id(), "update", null, Obj.of("action", action,
                        "documentType", link.documentType(), "documentId", link.documentId())));
                events.publish("supplier_portal.used", Obj.of("organizationId", link.organizationId(), "tokenId",
                        link.id(), "documentType", link.documentType(), "documentId", link.documentId(), "action", action));
            }
            return result;
        });
    }

    private Link resolve(String token) {
        if (token == null || token.length() < 40 || token.length() > 64) {
            throw ApiException.notFound(INVALID);
        }
        Map<String, Object> r = TenantContext.unscoped(() -> SqlRows.one(jdbc.sql("SELECT id, \"organizationId\","
                + " \"documentType\", \"documentId\", \"partnerId\" FROM \"SupplierPortalToken\" WHERE \"tokenHash\" = ? AND"
                + " \"revokedAt\" IS NULL AND \"expiresAt\" > " + NOW).param(hash(token))));
        if (r == null) {
            throw ApiException.notFound(INVALID);
        }
        return new Link((String) r.get("id"), (String) r.get("organizationId"), (String) r.get("documentType"),
                (String) r.get("documentId"), (String) r.get("partnerId"));
    }

    private static void requireType(Link link, String type) {
        if (!type.equals(link.documentType())) {
            throw ApiException.badRequest("This link does not allow that action");
        }
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
