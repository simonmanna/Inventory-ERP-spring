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
    static final String SUPERSEDED = "This quotation is being updated; ask us for a new link";
    /** States a customer may see (never a draft or one waiting for approval). */
    private static final Set<String> SHOWN = Set.of("approved", "sent", "accepted", "rejected", "expired", "converted");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The link a token resolved to. */
    private record Link(String id, String organizationId, String quotationId, int revision) {}

    /** Who answered: the client address (behind the trusted proxy) and user agent, kept with the decision. */
    public record Client(String ip, String userAgent) {

        public Client {
            userAgent = userAgent == null ? null : userAgent.length() > 500 ? userAgent.substring(0, 500) : userAgent;
        }
    }

    private final SalesDb db;
    private final SalesQuotationService quotations;
    private final NotificationsService notifications;
    private final AuditService audit;
    private final ObjectProvider<QuotationOpportunityPort> opportunities;
    private final TransactionTemplate tx;
    private SalesSettingsService settings;

    /** The sales setting {@code quoteOnlineAcceptAction}: what an online acceptance starts. */
    @org.springframework.beans.factory.annotation.Autowired
    void setSettings(SalesSettingsService settings) {
        this.settings = settings;
    }

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
                + " \"viewCount\", decision, \"decidedAt\", \"signerName\", \"signerNote\", revision, \"decidedRevision\""
                + " FROM \"SalesQuoteLink\" WHERE"
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
        int revision = ((Number) q.get("revision")).intValue();
        db.update("INSERT INTO \"SalesQuoteLink\" (id, \"organizationId\", \"quotationId\", \"tokenHash\", \"expiresAt\","
                + " \"createdById\", revision) VALUES (?, ?, ?, ?, ?, ?, ?)", id, org(), quotationId, sha256(token),
                java.sql.Timestamp.from(expires), user(), revision);
        audit.record(AuditInput.of("SalesQuotation", quotationId, "update", null, Obj.of("shareLink", "created",
                "expiresAt", expires, "revision", revision)));
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

    /**
     * Customer acceptance: {@code signerName} (required), an optional {@code note} and {@code optionalLineIds} (the
     * optional products taken). Committed first; then, when the sales setting {@code quoteOnlineAcceptAction} says so,
     * the quotation is converted (sales order, or a proforma whose advance and payment details the customer then sees)
     * in a transaction of its own: a conversion that fails leaves the acceptance standing and tells the owner.
     */
    public Map<String, Object> accept(String token, Map<String, Object> in, Client client) {
        String signer = str(in.get("signerName"));
        if (blank(signer) || signer.trim().length() < 2 || signer.trim().length() > 120) {
            throw ApiException.badRequest("Type your full name to accept");
        }
        String note = note(in.get("note"));
        List<String> options = SalesQuotationService.optionalLineIds(in);
        boolean[] decidedNow = {false};
        Map<String, Object> out = withLink(token, l -> {
            shown(l);
            // lock order quotation → link, as in revise() (which revokes the link under the quotation lock)
            Map<String, Object> terms = currentTerms(l);
            if (alreadyDecided(l, "accepted")) {
                return publicView(l);
            }
            quotations.accept(l.quotationId(), options);
            decidedNow[0] = true;
            // the decision snapshot records the terms as accepted, with the options taken
            return decided(l, "accepted", signer.trim(), note, client, currentTerms(l));
        });
        return decidedNow[0] ? afterAcceptance(token, out) : out;
    }

    /** The configured action after an online acceptance (see {@link #accept}); returns the customer's view. */
    private Map<String, Object> afterAcceptance(String token, Map<String, Object> accepted) {
        String action = withLink(token, l -> str(settings.settings().get("quoteOnlineAcceptAction")));
        if (blank(action) || "none".equals(action)) {
            return accepted;
        }
        try {
            return withLink(token, l -> {
                Map<String, Object> converted = quotations.convert(l.quotationId(), Obj.of("target", action));
                @SuppressWarnings("unchecked")
                Map<String, Object> doc = (Map<String, Object>) converted.get("converted");
                notifyOwner(l.quotationId(), "Quotation " + converted.get("number") + " converted after online acceptance",
                        ("proforma".equals(action) ? "Proforma invoice " : "Sales order ") + doc.get("number")
                                + " was created automatically.", "sales.quote_link.converted");
                return publicView(l);
            });
        } catch (RuntimeException e) {
            withLink(token, l -> {
                notifyOwner(l.quotationId(), "Quotation " + accepted.get("number") + " accepted online: convert it by hand",
                        "The automatic " + ("proforma".equals(action) ? "proforma invoice" : "sales order")
                                + " could not be created: " + e.getMessage(), "sales.quote_link.convert_failed");
                return null;
            });
            return accepted;
        }
    }

    /** Customer decline with an optional {@code reason} and {@code signerName}. */
    public Map<String, Object> decline(String token, Map<String, Object> in, Client client) {
        String signer = str(in.get("signerName"));
        if (!blank(signer) && signer.trim().length() > 120) {
            throw ApiException.badRequest("signerName must be at most 120 characters");
        }
        String reason = note(in.get("reason"));
        return withLink(token, l -> {
            shown(l);
            // lock order quotation → link, as in revise() (which revokes the link under the quotation lock)
            Map<String, Object> terms = currentTerms(l);
            if (alreadyDecided(l, "declined")) {
                return publicView(l);
            }
            quotations.decline(l.quotationId(), blank(reason) ? null : reason);
            return decided(l, "declined", blank(signer) ? null : signer.trim(), reason, client, terms);
        });
    }

    private static String note(Object raw) {
        String s = str(raw);
        if (s != null && s.length() > 1000) {
            throw ApiException.badRequest("The note must be at most 1000 characters");
        }
        return blank(s) ? null : s.trim();
    }

    /**
     * Lock the link row and check it may still be answered (production-readiness fix P0-4). The same answer again is
     * an idempotent replay ({@code true}: nothing changes, the caller returns the current view); a different answer
     * after one was recorded is a 409. A link issued for an earlier revision never answers the current one.
     */
    private boolean alreadyDecided(Link l, String decision) {
        Map<String, Object> r = db.row("SELECT decision FROM \"SalesQuoteLink\" WHERE id = ? FOR UPDATE", l.id());
        if (r.get("decision") != null) {
            if (decision.equals(r.get("decision"))) {
                return true;
            }
            throw ApiException.conflict("This quotation has already been answered");
        }
        return false;
    }

    /**
     * The exact commercial terms on the table, read under the quotation's row lock: header + lines of the revision
     * the link was issued for, or a 404 when the quotation has moved on to another revision since.
     */
    private Map<String, Object> currentTerms(Link l) {
        Map<String, Object> q = db.row("SELECT * FROM \"SalesQuotation\" WHERE id = ? AND \"deletedAt\" IS NULL FOR"
                + " UPDATE", l.quotationId());
        if (q == null || ((Number) q.get("revision")).intValue() != l.revision()) {
            throw ApiException.notFound(SUPERSEDED);
        }
        Map<String, Object> header = SalesDb.json(q);
        header.put("lines", db.rows("SELECT * FROM \"SalesQuotationLine\" WHERE \"quotationId\" = ? ORDER BY"
                + " \"sortOrder\"", l.quotationId()));
        return header;
    }

    private Map<String, Object> decided(Link l, String decision, String signer, String note, Client client,
            Map<String, Object> terms) {
        db.update("UPDATE \"SalesQuoteLink\" SET decision = ?, \"decidedAt\" = " + NOW + ", \"signerName\" = ?,"
                + " \"signerNote\" = ?, \"signerIp\" = ?, \"signerUserAgent\" = ?, \"decidedRevision\" = ?,"
                + " \"decisionSnapshot\" = ?::jsonb WHERE id = ?", decision, signer, note, client.ip(), client.userAgent(),
                l.revision(), SalesDb.toJson(terms), l.id());
        audit.record(AuditInput.of("SalesQuotation", l.quotationId(), "update", null, Obj.of("via", "quote_link",
                "decision", decision, "signerName", signer, "revision", l.revision(), "total", terms.get("total"),
                "signerIp", client.ip())));
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
        Map<String, Object> q = db.row("SELECT status, revision FROM \"SalesQuotation\" WHERE id = ? AND \"deletedAt\""
                + " IS NULL", l.quotationId());
        if (q == null || !SHOWN.contains(str(q.get("status"))) || ((Number) q.get("revision")).intValue() != l.revision()) {
            throw ApiException.notFound(SUPERSEDED);
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
        // After an automatic conversion the customer sees what was created; for a proforma, the advance to pay and
        // where to pay it (sales settings bank / mobile money details).
        out.remove("convertedId");
        if ("converted".equals(out.get("status")) && "proforma".equals(out.get("convertedType"))) {
            Map<String, Object> qrow = db.row("SELECT \"convertedId\" FROM \"SalesQuotation\" WHERE id = ?", l.quotationId());
            Map<String, Object> pf = qrow == null ? null : db.row("SELECT \"documentNumber\" AS number, \"totalAmount\" AS"
                    + " total, (data->>'advanceAmount')::numeric AS \"advanceAmount\", data->>'bankDetails' AS"
                    + " \"bankDetails\", data->>'mobileDetails' AS \"mobileDetails\", \"dueDate\" FROM \"Document\" WHERE id = ?"
                    + " AND \"organizationId\" = ?", qrow.get("convertedId"), org());
            out.put("payment", pf);
        }
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
                + " \"quotationId\", revision FROM \"SalesQuoteLink\" WHERE \"tokenHash\" = ? AND \"revokedAt\" IS NULL AND"
                + " \"expiresAt\" > now()").param(sha256(token)).query().listOfRows().stream().findFirst().orElse(null));
        if (r == null) {
            throw ApiException.notFound(NOT_FOUND);
        }
        Link l = new Link(str(r.get("id")), str(r.get("organizationId")), str(r.get("quotationId")),
                ((Number) r.get("revision")).intValue());
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
