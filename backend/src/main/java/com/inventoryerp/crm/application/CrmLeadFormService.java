package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.strings;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Website lead forms (CRM enhancements T3a, ADR-S084). A form has a public key that sits in the website's HTML (it is
 * not a secret) and turns each submission into a lead with the form's source, team, tags and owner (or the normal
 * auto-assignment). Abuse controls: a per-address and per-form rate limit on the route (client address resolved
 * behind the trusted proxy), a signed submit token from the form page (submissions without one are refused; one sent
 * back faster than a person can type is dropped like a bot), a honeypot field, a rolling 24-hour cap per form
 * ({@code dailyLimit}) and per organization ({@code app.crm.web-lead-org-daily-limit}), an optional origin allow-list,
 * field length limits, and switching the form off or rotating its key (production-readiness fix P0-5).
 */
@Service
public class CrmLeadFormService {

    /** Honeypot field: hidden from people, filled by bots. A filled one is answered as success and dropped. */
    public static final String HONEYPOT = "_hp";
    static final String NOT_FOUND = "Form not found";
    static final String DEFAULT_THANKS = "Thank you, we will be in touch soon.";
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^[+0-9 ()./-]{5,40}$");
    private static final Pattern ORIGIN = Pattern.compile("^https?://[a-z0-9.-]+(:[0-9]{1,5})?$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SELECT = "SELECT f.*, s.name AS \"sourceName\", t.name AS \"teamName\","
            + " COALESCE(NULLIF(trim(concat_ws(' ', u.\"firstName\", u.\"lastName\")), ''), u.email) AS \"ownerName\""
            + " FROM \"CrmLeadForm\" f LEFT JOIN \"CrmSource\" s ON s.id = f.\"sourceId\" LEFT JOIN \"CrmTeam\" t ON"
            + " t.id = f.\"teamId\" LEFT JOIN \"User\" u ON u.id = f.\"ownerId\"";

    private final CrmDb db;
    private final CrmConfigService config;
    private final CrmPipelineService pipeline;
    private final NotificationsService notifications;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final byte[] tokenKey;
    private final int orgDailyLimit;

    /** Submit token field and its rules: a person needs a few seconds to fill the form; a page is good for a day. */
    public static final String TOKEN = "_t";
    static final long MIN_FILL_MS = 2_000;
    static final long TOKEN_TTL_MS = 24 * 3_600_000L;

    public CrmLeadFormService(CrmDb db, CrmConfigService config, CrmPipelineService pipeline,
            NotificationsService notifications, AuditService audit, PlatformTransactionManager transactions,
            @Value("${app.jwt.access-secret}") String secret,
            @Value("${app.crm.web-lead-org-daily-limit:1000}") int orgDailyLimit) {
        this.db = db;
        this.config = config;
        this.pipeline = pipeline;
        this.notifications = notifications;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        // a key of its own, derived from the server secret: a form token is never valid as anything else
        this.tokenKey = sha256Bytes("crm-lead-form-token:" + secret);
        this.orgDailyLimit = orgDailyLimit;
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return db.rows(SELECT + " WHERE f.\"organizationId\" = ? ORDER BY f.name", org());
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> in) {
        Map<String, Object> d = data(in, true);
        d.put("publicKey", newKey());
        d.put("createdById", user());
        String id = str(db.insert("CrmLeadForm", d).get("id"));
        audit.record(AuditInput.of("CrmLeadForm", id, "create", null, Obj.of("name", d.get("name"))));
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> in) {
        db.require("CrmLeadForm", id, NOT_FOUND);
        db.patch("CrmLeadForm", id, data(in, false), true);
        audit.record(AuditInput.of("CrmLeadForm", id, "update", null, in));
        return get(id);
    }

    /** New public key; websites using the old one stop working. */
    @Transactional
    public Map<String, Object> rotateKey(String id) {
        db.require("CrmLeadForm", id, NOT_FOUND);
        db.patch("CrmLeadForm", id, Obj.of("publicKey", newKey()), true);
        audit.record(AuditInput.of("CrmLeadForm", id, "update", null, Obj.of("publicKey", "rotated")));
        return get(id);
    }

    @Transactional
    public Map<String, Object> delete(String id) {
        db.require("CrmLeadForm", id, NOT_FOUND);
        db.update("DELETE FROM \"CrmLeadForm\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("CrmLeadForm", id, "delete"));
        return Obj.of("id", id, "deleted", true);
    }

    private Map<String, Object> get(String id) {
        return db.row(SELECT + " WHERE f.id = ? AND f.\"organizationId\" = ?", id, org());
    }

    private Map<String, Object> data(Map<String, Object> in, boolean create) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (create || in.containsKey("name")) {
            String name = str(in.get("name"));
            if (blank(name) || name.trim().length() > 120) {
                throw ApiException.badRequest("name must be 1 to 120 characters");
            }
            d.put("name", name.trim());
        }
        ref(in, d, "sourceId", "CrmSource", "Source not found");
        ref(in, d, "teamId", "CrmTeam", "Team not found");
        if (in.containsKey("ownerId")) {
            String owner = str(in.get("ownerId"));
            d.put("ownerId", blank(owner) ? null : config.requireUser(owner));
        }
        if (in.containsKey("tagIds")) {
            List<String> tags = strings(in.get("tagIds"));
            for (String t : tags) {
                if (!db.exists("CrmTag", t)) {
                    throw ApiException.notFound("Tag not found");
                }
            }
            d.put("tagIds", tags);
        }
        if (in.containsKey("allowedOrigins")) {
            List<String> origins = new ArrayList<>();
            for (String o : strings(in.get("allowedOrigins"))) {
                String n = o.trim().toLowerCase(Locale.ROOT).replaceAll("/+$", "");
                if (n.isEmpty()) {
                    continue;
                }
                if (!ORIGIN.matcher(n).matches()) {
                    throw ApiException.badRequest("allowedOrigins must look like https://www.example.com");
                }
                if (!origins.contains(n)) {
                    origins.add(n);
                }
            }
            if (origins.size() > 20) {
                throw ApiException.badRequest("At most 20 allowed origins");
            }
            d.put("allowedOrigins", origins);
        }
        if (in.containsKey("thankYouMessage")) {
            String t = str(in.get("thankYouMessage"));
            if (t != null && t.length() > 500) {
                throw ApiException.badRequest("thankYouMessage must be at most 500 characters");
            }
            d.put("thankYouMessage", blank(t) ? null : t.trim());
        }
        if (in.containsKey("isActive")) {
            d.put("isActive", Boolean.TRUE.equals(in.get("isActive")));
        }
        if (in.containsKey("dailyLimit")) {
            Integer limit = CrmDb.intOf(in.get("dailyLimit"), "dailyLimit");
            if (limit == null || limit < 1 || limit > 100_000) {
                throw ApiException.badRequest("dailyLimit must be between 1 and 100000");
            }
            d.put("dailyLimit", limit);
        }
        return d;
    }

    private void ref(Map<String, Object> in, Map<String, Object> d, String key, String table, String message) {
        if (in.containsKey(key)) {
            String v = str(in.get(key));
            if (!blank(v) && !db.exists(table, v)) {
                throw ApiException.notFound(message);
            }
            d.put(key, blank(v) ? null : v);
        }
    }

    private static String newKey() {
        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        return "lf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    // ------------------------------------------------------------------ public

    /** What the hosted form page shows: the form and company name and the thank-you text. */
    public Map<String, Object> publicInfo(String key) {
        Map<String, Object> f = resolve(key);
        return Obj.of("name", f.get("name"), "company", f.get("company"), "thankYouMessage", thanks(f), "submitToken",
                issueToken(str(f.get("id")), System.currentTimeMillis()));
    }

    /**
     * A website submission: {@code name} (required), {@code email} and/or {@code phone}, optional {@code company},
     * {@code subject}, {@code message}, {@code pageUrl}. {@code origin} is the browser's Origin header and
     * {@code host} the request host (the hosted form page is always allowed).
     */
    public Map<String, Object> submit(String key, Map<String, Object> in, String origin, String host) {
        Map<String, Object> f = resolve(key);
        List<String> allowed = strings(f.get("allowedOrigins"));
        if (!allowed.isEmpty() && !blank(origin) && !allowed.contains(origin.toLowerCase(Locale.ROOT))
                && !sameHost(origin, host)) {
            throw ApiException.forbidden("This website is not allowed to use the form");
        }
        Map<String, Object> ok = Obj.of("ok", true, "message", thanks(f));
        if (!blank(str(in.get(HONEYPOT)))) {
            return ok;
        }
        long age = tokenAge(str(f.get("id")), in.get(TOKEN), System.currentTimeMillis());
        if (age < 0 || age > TOKEN_TTL_MS) {
            throw ApiException.badRequest("This form has expired; reload the page and try again");
        }
        if (age < MIN_FILL_MS) {
            return ok; // filled faster than a person can: answered like the honeypot, nothing is stored
        }
        String name = text(in, "name", 120);
        String email = text(in, "email", 254);
        String phone = text(in, "phone", 40);
        String company = text(in, "company", 200);
        String subject = text(in, "subject", 200);
        String message = text(in, "message", 5000);
        String page = text(in, "pageUrl", 500);
        if (name == null) {
            throw ApiException.badRequest("Please enter your name");
        }
        if (email == null && phone == null) {
            throw ApiException.badRequest("Please enter an email address or a phone number");
        }
        if (email != null && !EMAIL.matcher(email).matches()) {
            throw ApiException.badRequest("Please enter a valid email address");
        }
        if (phone != null && !PHONE.matcher(phone).matches()) {
            throw ApiException.badRequest("Please enter a valid phone number");
        }
        String formId = str(f.get("id"));
        TenantContext.TenantStore store = TenantContext.TenantStore.organization(str(f.get("organizationId")));
        // no signed-in user: the lead is created by the form; view_all lets the create read back what it wrote
        store.setPermissions(List.of(Permissions.CRM__DEAL_VIEW_ALL));
        TenantContext.run(store, () -> tx.execute(s -> {
            withinDailyLimits(formId);
            Map<String, Object> lead = new LinkedHashMap<>();
            lead.put("kind", "lead");
            String title = subject != null ? subject : "Website enquiry: " + (company != null ? company : name);
            lead.put("name", title.length() > 200 ? title.substring(0, 200) : title);
            lead.put("contactName", name);
            lead.put("email", email);
            lead.put("phone", phone);
            lead.put("companyName", company);
            StringBuilder notes = new StringBuilder(message == null ? "" : message);
            notes.append(notes.isEmpty() ? "" : "\n\n").append("Sent from the website form \"").append(f.get("name"))
                    .append('"').append(page == null ? "" : " on " + page).append('.');
            lead.put("notes", notes.toString());
            lead.put("sourceId", f.get("sourceId"));
            lead.put("teamId", f.get("teamId"));
            lead.put("tagIds", strings(f.get("tagIds")));
            if (f.get("ownerId") != null) {
                lead.put("ownerId", f.get("ownerId"));
            }
            lead.values().removeIf(v -> v == null);
            Map<String, Object> made = pipeline.create(lead);
            db.update("UPDATE \"CrmLeadForm\" SET submissions = submissions + 1, \"lastSubmittedAt\" = " + CrmDb.NOW
                    + " WHERE id = ?", formId);
            String owner = str(made.get("ownerId"));
            if (owner != null) {
                try {
                    notifications.send(new SendInput(org(), owner, "in_app", "crm", "New website enquiry", made.get(
                            "name") + " — " + name, Obj.of("kind", "crm.lead.web_form", "dealId", made.get("id"),
                                    "link", "/crm/leads/" + made.get("id"))));
                } catch (RuntimeException e) {
                    // a notification never loses the enquiry
                }
            }
            return made;
        }));
        return ok;
    }

    /**
     * Count this submission in the form's rolling 24-hour window and refuse it (429) past the form's
     * {@code dailyLimit} or the organization-wide cap. Runs in the submission's transaction, so a refused or failed
     * submission is not counted.
     */
    private void withinDailyLimits(String formId) {
        Map<String, Object> w = db.row("UPDATE \"CrmLeadForm\" SET \"windowCount\" = CASE WHEN \"windowStart\" IS NULL OR"
                + " \"windowStart\" < " + CrmDb.NOW + " - interval '1 day' THEN 1 ELSE \"windowCount\" + 1 END,"
                + " \"windowStart\" = CASE WHEN \"windowStart\" IS NULL OR \"windowStart\" < " + CrmDb.NOW
                + " - interval '1 day' THEN " + CrmDb.NOW + " ELSE \"windowStart\" END WHERE id = ? AND"
                + " \"organizationId\" = ? RETURNING \"windowCount\", \"dailyLimit\"", formId, org());
        if (w == null) {
            throw ApiException.notFound(NOT_FOUND);
        }
        long orgCount = db.count("SELECT COALESCE(sum(\"windowCount\"), 0) FROM \"CrmLeadForm\" WHERE"
                + " \"organizationId\" = ? AND \"windowStart\" >= " + CrmDb.NOW + " - interval '1 day'", List.of(org()));
        if (((Number) w.get("windowCount")).intValue() > ((Number) w.get("dailyLimit")).intValue()
                || orgCount > orgDailyLimit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "We have received a lot of enquiries today; please try again tomorrow or contact us directly");
        }
    }

    /** {@code <issuedAtMillis>.<HMAC-SHA256(formId.issuedAt)>}, base64url (public for the integration tests). */
    public String issueToken(String formId, long issuedAt) {
        return issuedAt + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(formId + "." + issuedAt));
    }

    /** Milliseconds since the token was issued for this form, or -1 when it is missing, malformed or forged. */
    long tokenAge(String formId, Object raw, long now) {
        if (!(raw instanceof String t) || t.length() > 100) {
            return -1;
        }
        int dot = t.indexOf('.');
        if (dot <= 0) {
            return -1;
        }
        long issuedAt;
        byte[] sig;
        try {
            issuedAt = Long.parseLong(t.substring(0, dot));
            sig = Base64.getUrlDecoder().decode(t.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return -1;
        }
        if (!java.security.MessageDigest.isEqual(sig, mac(formId + "." + issuedAt)) || issuedAt > now + 60_000) {
            return -1;
        }
        return Math.max(0, now - issuedAt);
    }

    private byte[] mac(String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(tokenKey, "HmacSHA256"));
            return mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256Bytes(String s) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String thanks(Map<String, Object> f) {
        return blank(str(f.get("thankYouMessage"))) ? DEFAULT_THANKS : str(f.get("thankYouMessage"));
    }

    private static String text(Map<String, Object> in, String key, int max) {
        Object raw = in.get(key);
        if (raw != null && !(raw instanceof String)) {
            throw ApiException.badRequest(key + " must be a string");
        }
        String s = (String) raw;
        if (blank(s)) {
            return null;
        }
        s = s.trim();
        if (s.length() > max) {
            throw ApiException.badRequest(key + " must be at most " + max + " characters");
        }
        return s;
    }

    private static boolean sameHost(String origin, String host) {
        try {
            String o = URI.create(origin).getHost();
            String h = host == null ? null : host.replaceAll(":\\d+$", "");
            return o != null && o.equalsIgnoreCase(h);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Active form of a public key, with its company name; 404 otherwise. */
    private Map<String, Object> resolve(String key) {
        if (key == null || !key.startsWith("lf_") || key.length() > 60) {
            throw ApiException.notFound(NOT_FOUND);
        }
        Map<String, Object> f = TenantContext.unscoped(() -> db.jdbc().sql("SELECT f.id, f.\"organizationId\", f.name,"
                + " f.\"sourceId\", f.\"teamId\", f.\"ownerId\", f.\"tagIds\", f.\"allowedOrigins\","
                + " f.\"thankYouMessage\", o.name AS company FROM \"CrmLeadForm\" f JOIN \"Organization\" o ON o.id ="
                + " f.\"organizationId\" WHERE f.\"publicKey\" = ? AND f.\"isActive\"").param(key).query().listOfRows()
                .stream().findFirst().map(CrmDb::json).orElse(null));
        if (f == null) {
            throw ApiException.notFound(NOT_FOUND);
        }
        return f;
    }
}
