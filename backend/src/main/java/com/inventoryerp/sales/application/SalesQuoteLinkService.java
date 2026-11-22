package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;
import static com.inventoryerp.sales.application.SalesDb.user;

import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
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
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Online quotation link (CRM enhancements T3a, ADR-S084, licence {@code customerPortal}): staff share one quotation by
 * a secret link; the customer views it, downloads the PDF and accepts (typing their name) or declines, without a portal
 * account. Only the token's SHA-256 is stored, one live link per quotation, valid {@value #DAYS} days. The answer runs
 * the same {@link SalesQuotationService#accept}/{@link SalesQuotationService#decline} rules as staff, is kept with the
 * signer name, note and client address, and is reported to the quote owner and on the CRM timeline.
 */
@Service
public class SalesQuoteLinkService {

    static final int DAYS = 60;
    static final String NOT_FOUND = "Quotation link not found";
    /** States a customer may see (never a draft or one waiting for approval). */
    private static final Set<String> SHOWN = Set.of("approved", "sent", "accepted", "rejected", "expired", "converted");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The link a token resolved to. */
    private record Link(String id, String organizationId, String quotationId) {}

    private final SalesDb db;
    private final SalesQuotationService quotations;
    private final NotificationsService notifications;
    private final AuditService audit;
    private final ObjectProvider<QuotationOpportunityPort> opportunities;
    private final TransactionTemplate tx;

    public SalesQuoteLinkService(SalesDb db, SalesQuotationService quotations, NotificationsService notifications,
            AuditService audit, ObjectProvider<QuotationOpportunityPort> opportunities,
            PlatformTransactionManager transactions) {
        this.tx = new TransactionTemplate(transactions);
        this.db = db;
        this.quotations = quotations;
        this.notifications = notifications;
        this.audit = audit;
        this.opportunities = opportunities;
    }

    // ------------------------------------------------------------------ staff side

    /** The live link of a quotation the caller can see ({@code active}, views, answer); never the token. */
    @Transactional(readOnly = true)
    public Map<String, Object> status(String quotationId) {
        quotations.get(quotationId);
        Map<String, Object> r = db.row("SELECT \"createdAt\", \"expiresAt\", \"firstViewedAt\", \"lastViewedAt\","
                + " \"viewCount\", decision, \"decidedAt\", \"signerName\", \"signerNote\" FROM \"SalesQuoteLink\" WHERE"
                + " \"quotationId\" = ? AND \"organizationId\" = ? AND \"revokedAt\" IS NULL", quotationId, org());
        if (r == null) {
            return Obj.of("active", false);
        }
        r.put("active", ((Instant) r.get("expiresAt")).isAfter(Instant.now()));
        return r;
    }

    /** New link for an approved or sent quotation (the previous one stops working); the path is returned once. */
    @Transactional
    public Map<String, Object> create(String quotationId) {
        Map<String, Object> q = quotations.get(quotationId);
        if (!Set.of("approved", "sent").contains(q.get("status"))) {
            throw ApiException.badRequest("Only approved or sent quotations can be shared");
        }
        revokeLive(quotationId);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = Instant.now().plus(DAYS, ChronoUnit.DAYS);
        String id = SalesDb.newId();
        db.update("INSERT INTO \"SalesQuoteLink\" (id, \"organizationId\", \"quotationId\", \"tokenHash\", \"expiresAt\","
                + " \"createdById\") VALUES (?, ?, ?, ?, ?, ?)", id, org(), quotationId, sha256(token),
                java.sql.Timestamp.from(expires), user());
        audit.record(AuditInput.of("SalesQuotation", quotationId, "update", null, Obj.of("shareLink", "created",
                "expiresAt", expires)));
        return Obj.of("active", true, "path", "/q/" + token, "expiresAt", expires);
    }

    /** Stop the quotation's link. */
    @Transactional
    public Map<String, Object> revoke(String quotationId) {
        quotations.get(quotationId);
        if (revokeLive(quotationId) > 0) {
            audit.record(AuditInput.of("SalesQuotation", quotationId, "update", null, Obj.of("shareLink", "revoked")));
        }
        return Obj.of("active", false);
    }

    private int revokeLive(String quotationId) {
        return db.update("UPDATE \"SalesQuoteLink\" SET \"revokedAt\" = " + NOW + " WHERE \"quotationId\" = ? AND"
                + " \"organizationId\" = ? AND \"revokedAt\" IS NULL", quotationId, org());
    }

    // ------------------------------------------------------------------ customer side

    /** The quotation as the customer sees it; counts the view and tells the owner the first time. */
    public Map<String, Object> view(String token) {
        return withLink(token, l -> {
            Map<String, Object> link = db.row("SELECT \"viewCount\" FROM \"SalesQuoteLink\" WHERE id = ?", l.id());
            db.update("UPDATE \"SalesQuoteLink\" SET \"viewCount\" = \"viewCount\" + 1, \"firstViewedAt\" ="
                    + " COALESCE(\"firstViewedAt\", " + NOW + "), \"lastViewedAt\" = " + NOW + " WHERE id = ?", l.id());
            Map<String, Object> out = publicView(l);
            if (((Number) link.get("viewCount")).intValue() == 0) {
                notifyOwner(l.quotationId(), "Customer opened quotation " + out.get("number"),
                        out.get("partnerName") + " opened the online link.", "sales.quote_link.viewed");
            }
            return out;
        });
    }

    /** The quotation PDF. */
    public byte[] pdf(String token) {
        return withLink(token, l -> {
            shown(l);
            return quotations.pdf(l.quotationId(), null);
        });
    }

    /** Customer acceptance: {@code signerName} (required) and an optional {@code note}. */
    public Map<String, Object> accept(String token, Map<String, Object> in, String ip) {
        String signer = str(in.get("signerName"));
        if (blank(signer) || signer.trim().length() < 2 || signer.trim().length() > 120) {
            throw ApiException.badRequest("Type your full name to accept");
        }
        String note = note(in.get("note"));
        return withLink(token, l -> {
            shown(l);
            undecided(l);
            quotations.accept(l.quotationId());
            return decided(l, "accepted", signer.trim(), note, ip);
        });
    }

    /** Customer decline with an optional {@code reason} and {@code signerName}. */
    public Map<String, Object> decline(String token, Map<String, Object> in, String ip) {
        String signer = str(in.get("signerName"));
        if (!blank(signer) && signer.trim().length() > 120) {
            throw ApiException.badRequest("signerName must be at most 120 characters");
        }
        String reason = note(in.get("reason"));
        return withLink(token, l -> {
            shown(l);
            undecided(l);
            quotations.decline(l.quotationId(), blank(reason) ? null : reason);
            return decided(l, "declined", blank(signer) ? null : signer.trim(), reason, ip);
        });
    }

    private static String note(Object raw) {
        String s = str(raw);
        if (s != null && s.length() > 1000) {
            throw ApiException.badRequest("The note must be at most 1000 characters");
        }
        return blank(s) ? null : s.trim();
    }

    private void undecided(Link l) {
        Map<String, Object> r = db.row("SELECT decision FROM \"SalesQuoteLink\" WHERE id = ? FOR UPDATE", l.id());
        if (r.get("decision") != null) {
            throw ApiException.conflict("This quotation has already been answered");
        }
    }

    private Map<String, Object> decided(Link l, String decision, String signer, String note, String ip) {
        db.update("UPDATE \"SalesQuoteLink\" SET decision = ?, \"decidedAt\" = " + NOW + ", \"signerName\" = ?,"
                + " \"signerNote\" = ?, \"signerIp\" = ? WHERE id = ?", decision, signer, note, ip, l.id());
        audit.record(AuditInput.of("SalesQuotation", l.quotationId(), "update", null, Obj.of("via", "quote_link",
                "decision", decision, "signerName", signer)));
        Map<String, Object> out = publicView(l);
        String who = signer == null ? str(out.get("partnerName")) : signer;
        String title = "Quotation " + out.get("number") + " " + decision + " online by " + who;
        String body = note == null ? "Answered through the online quotation link." : (decision.equals("accepted")
                ? "Note: " : "Reason: ") + note;
        notifyOwner(l.quotationId(), title, body, "sales.quote_link." + decision);
        Map<String, Object> q = db.row("SELECT \"dealId\", \"partnerId\", COALESCE(\"ownerId\", \"createdById\") AS"
                + " owner FROM \"SalesQuotation\" WHERE id = ?", l.quotationId());
        QuotationOpportunityPort port = opportunities.getIfAvailable();
        if (port != null) {
            port.quotationAnsweredOnline(str(q.get("dealId")), str(q.get("partnerId")), title, body, str(q.get("owner")));
        }
        return out;
    }

    private void notifyOwner(String quotationId, String title, String body, String kind) {
        Map<String, Object> q = db.row("SELECT COALESCE(\"ownerId\", \"createdById\") AS owner FROM \"SalesQuotation\""
                + " WHERE id = ?", quotationId);
        if (q == null || q.get("owner") == null) {
            return;
        }
        try {
            notifications.send(new SendInput(org(), str(q.get("owner")), "in_app", "sales", title, body, Obj.of("kind",
                    kind, "quotationId", quotationId, "link", "/crm/quotations/" + quotationId)));
        } catch (RuntimeException e) {
            // a notification never undoes the customer's answer
        }
    }

    private void shown(Link l) {
        Map<String, Object> q = db.row("SELECT status FROM \"SalesQuotation\" WHERE id = ? AND \"deletedAt\" IS NULL",
                l.quotationId());
        if (q == null || !SHOWN.contains(str(q.get("status")))) {
            throw ApiException.notFound("This quotation is being updated; ask us for a new link");
        }
    }

    private Map<String, Object> publicView(Link l) {
        shown(l);
        Map<String, Object> out = SalesPortalService.strip(quotations.get(l.quotationId()), "approvalReasons",
                "approvalRequestId", "internalNotes", "ownerId", "createdById", "credit", "dealId", "margin",
                "canApprove", "approvedById", "rejectedById", "migratedFrom", "pricelistId", "agreementId",
                "lostReasonId", "teamId", "branchId", "siteId", "dealName", "recordedOffline", "occurredAt");
        Map<String, Object> link = db.row("SELECT decision, \"decidedAt\", \"signerName\", \"expiresAt\" FROM"
                + " \"SalesQuoteLink\" WHERE id = ?", l.id());
        Map<String, Object> o = db.row("SELECT name FROM \"Organization\" WHERE id = ?", org());
        out.put("company", o);
        out.put("link", link);
        boolean open = Set.of("approved", "sent").contains(out.get("status")) && link.get("decision") == null
                && !(out.get("validUntil") instanceof java.time.LocalDate v && v.isBefore(SalesQuotationService.today()));
        out.put("canAnswer", open);
        return out;
    }

    /**
     * Resolve a link token (before any organization is known) and run {@code body} in its organization with read
     * access to that one quotation, in one transaction opened inside that organization (so RLS sees it). An unknown,
     * revoked or expired token is a 404.
     */
    private <T> T withLink(String token, Function<Link, T> body) {
        if (token == null || token.length() < 30 || token.length() > 100) {
            throw ApiException.notFound(NOT_FOUND);
        }
        Map<String, Object> r = TenantContext.unscoped(() -> db.jdbc().sql("SELECT id, \"organizationId\","
                + " \"quotationId\" FROM \"SalesQuoteLink\" WHERE \"tokenHash\" = ? AND \"revokedAt\" IS NULL AND"
                + " \"expiresAt\" > now()").param(sha256(token)).query().listOfRows().stream().findFirst().orElse(null));
        if (r == null) {
            throw ApiException.notFound(NOT_FOUND);
        }
        Link l = new Link(str(r.get("id")), str(r.get("organizationId")), str(r.get("quotationId")));
        TenantContext.TenantStore store = TenantContext.TenantStore.organization(l.organizationId());
        // reads the one quotation whoever owns it or whichever branch it is in; nothing else is reachable
        store.setPermissions(List.of(Permissions.SALES__ORDER_READ_ALL, Permissions.SALES__BRANCH_OVERRIDE,
                Permissions.CRM__DEAL_VIEW_ALL));
        return TenantContext.run(store, () -> tx.execute(status -> body.apply(l)));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(
                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
