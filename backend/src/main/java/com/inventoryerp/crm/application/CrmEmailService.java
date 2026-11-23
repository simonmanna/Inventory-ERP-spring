package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.NOW;
import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * CRM email templates and sending an email from a lead or opportunity (CRM enhancements T3b). Templates use
 * {@code {{placeholders}}} ({@link #PLACEHOLDERS}); the rendered text is shown to the salesperson, who may edit it
 * before it is sent. Each email is logged as a done email activity on the record whose outcome says whether it was
 * really sent ({@link CrmEmailOutbox}).
 */
@Service
public class CrmEmailService {

    /** Supported placeholders, in the order the settings screen lists them. */
    public static final List<String> PLACEHOLDERS = List.of("contact_name", "first_name", "company", "deal_name",
            "amount", "salesperson", "my_name", "organization");
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]+$");

    private final CrmDb db;
    private final CrmPipelineService pipeline;
    private final AuditService audit;
    private final CrmEmailOutbox outbox;

    public CrmEmailService(CrmDb db, CrmPipelineService pipeline, AuditService audit, CrmEmailOutbox outbox) {
        this.db = db;
        this.pipeline = pipeline;
        this.audit = audit;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------ templates

    @Transactional(readOnly = true)
    public Map<String, Object> templates() {
        return Obj.of("data", db.rows("SELECT id, name, subject, body, \"isActive\", \"updatedAt\" FROM"
                + " \"CrmEmailTemplate\" WHERE \"organizationId\" = ? ORDER BY name", org()), "placeholders", PLACEHOLDERS);
    }

    @Transactional
    public Map<String, Object> saveTemplate(String id, Map<String, Object> in) {
        if (id != null) {
            db.require("CrmEmailTemplate", id, "Email template not found");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        text(in, d, "name", 120, id == null);
        text(in, d, "subject", 200, id == null);
        text(in, d, "body", 20_000, id == null);
        if (in.containsKey("isActive")) {
            d.put("isActive", Boolean.TRUE.equals(in.get("isActive")));
        }
        for (Object v : List.of(d.getOrDefault("subject", ""), d.getOrDefault("body", ""))) {
            Matcher m = TOKEN.matcher(v.toString());
            while (m.find()) {
                if (!PLACEHOLDERS.contains(m.group(1))) {
                    throw ApiException.badRequest("Unknown placeholder {{" + m.group(1) + "}}; use one of: "
                            + String.join(", ", PLACEHOLDERS));
                }
            }
        }
        if (d.containsKey("name") && db.count("SELECT count(*) FROM \"CrmEmailTemplate\" WHERE \"organizationId\" = ? AND"
                + " lower(name) = lower(?) AND id <> ?", List.of(org(), d.get("name"), id == null ? "" : id)) > 0) {
            throw ApiException.conflict("A template with this name already exists");
        }
        Map<String, Object> row;
        if (id == null) {
            d.put("createdById", user());
            row = db.insert("CrmEmailTemplate", d);
        } else {
            row = db.patch("CrmEmailTemplate", id, d, true);
        }
        audit.record(AuditInput.of("CrmEmailTemplate", str(row.get("id")), id == null ? "create" : "update", null,
                Obj.of("name", row.get("name"))));
        return row;
    }

    @Transactional
    public Map<String, Object> deleteTemplate(String id) {
        db.require("CrmEmailTemplate", id, "Email template not found");
        db.update("DELETE FROM \"CrmEmailTemplate\" WHERE id = ? AND \"organizationId\" = ?", id, org());
        audit.record(AuditInput.of("CrmEmailTemplate", id, "delete"));
        return Obj.of("ok", true);
    }

    private static void text(Map<String, Object> in, Map<String, Object> d, String key, int max, boolean required) {
        if (!in.containsKey(key) && !required) {
            return;
        }
        String v = str(in.get(key));
        if (blank(v)) {
            throw ApiException.badRequest(key + " should not be empty");
        }
        if (v.length() > max) {
            throw ApiException.badRequest(key + " must be at most " + max + " characters");
        }
        d.put(key, "body".equals(key) ? v : v.trim());
    }

    // ------------------------------------------------------------------ compose / send

    /** Recipient, subject and body for the deal: a template rendered for it, or blank with only the recipient. */
    @Transactional(readOnly = true)
    public Map<String, Object> compose(String dealId, String templateId) {
        Map<String, Object> deal = pipeline.visible(dealId);
        Map<String, String> values = values(deal);
        String subject = "";
        String body = "";
        if (!blank(templateId)) {
            Map<String, Object> t = db.row("SELECT subject, body FROM \"CrmEmailTemplate\" WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"isActive\"", templateId, org());
            if (t == null) {
                throw ApiException.notFound("Email template not found");
            }
            subject = render(str(t.get("subject")), values);
            body = render(str(t.get("body")), values);
        }
        return Obj.of("to", deal.get("email"), "subject", subject, "body", body);
    }

    /**
     * Send {@code subject}/{@code body} to {@code to} and log it on the record (placeholders still in it are filled).
     *
     * <p>Production-readiness fix P0-6: the request writes the email activity and a {@link CrmEmailOutbox} row in one
     * transaction and nothing else. After the commit the outbox sends it once straight away and the answer reports what
     * really happened: {@code status} {@code sent}, {@code retrying} (a transport error; the outbox retries with
     * backoff), {@code failed} or {@code not_configured} (no outgoing mail on the server), with {@code delivered}
     * true only for {@code sent}. The activity's outcome follows the same states. A rolled-back request sends nothing.
     */
    @Transactional
    public Map<String, Object> send(String dealId, Map<String, Object> in) {
        Map<String, Object> deal = pipeline.visible(dealId);
        String to = str(in.get("to"));
        if (blank(to) || !EMAIL.matcher(to.trim()).matches()) {
            throw ApiException.badRequest("Enter one valid email address");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        text(in, d, "subject", 200, true);
        text(in, d, "body", 20_000, true);
        Map<String, String> values = values(deal);
        String subject = render(str(d.get("subject")), values);
        String body = render(str(d.get("body")), values);
        String activityId = CrmDb.newId();
        db.update("INSERT INTO \"Activity\" (id, \"organizationId\", type, title, body, outcome, \"dealId\", \"partnerId\","
                + " status, completed, \"completedAt\", \"assignedToId\", \"createdById\", \"occurredAt\", \"updatedAt\")"
                + " VALUES (?, ?, 'email'::\"ActivityType\", ?, ?, 'Sending', ?, ?, 'done'::\"TaskStatus\", true, " + NOW
                + ", ?, ?, " + NOW + ", " + NOW + ")", activityId, org(), "Email: " + subject, "To " + to.trim() + "\n\n"
                        + body, dealId, deal.get("partnerId"), user(), user());
        String outboxId = CrmDb.newId();
        db.update("INSERT INTO \"CrmEmailOutbox\" (id, \"organizationId\", \"activityId\", \"dealId\", \"toAddress\","
                + " subject, body, \"createdById\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)", outboxId, org(), activityId, dealId,
                to.trim(), subject, body, user());
        db.update("UPDATE \"Deal\" SET \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?", dealId, org());
        audit.record(AuditInput.of("Activity", activityId, "create", null, Obj.of("type", "email", "dealId", dealId,
                "to", to.trim())));
        Map<String, Object> out = new LinkedHashMap<>(Obj.of("sentTo", to.trim(), "delivered", false, "status",
                "queued", "activityId", activityId));
        // After the commit (still before this call returns): one immediate attempt, reported in the answer.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                CrmEmailOutbox.Result r = outbox.deliver(outboxId);
                out.put("status", switch (r) {
                    case SENT -> "sent";
                    case RETRY -> "retrying";
                    case NOT_CONFIGURED -> "not_configured";
                    case FAILED -> "failed";
                    case SKIPPED -> "queued";
                });
                out.put("delivered", r == CrmEmailOutbox.Result.SENT);
            }
        });
        return out;
    }

    private Map<String, String> values(Map<String, Object> deal) {
        Map<String, String> v = new LinkedHashMap<>();
        String contact = str(deal.get("contactName"));
        v.put("contact_name", contact == null ? "" : contact);
        v.put("first_name", contact == null ? "" : contact.trim().split("\\s+")[0]);
        v.put("company", firstNonBlank(str(deal.get("partnerName")), str(deal.get("companyName"))));
        v.put("deal_name", str(deal.get("name")));
        BigDecimal amount = deal.get("amount") == null ? BigDecimal.ZERO : new BigDecimal(deal.get("amount").toString());
        v.put("amount", (deal.get("currencyCode") == null ? "" : deal.get("currencyCode") + " ")
                + new DecimalFormat("#,##0.##").format(amount));
        v.put("salesperson", firstNonBlank(str(deal.get("ownerName")), ""));
        Map<String, Object> me = user() == null ? null : db.row("SELECT COALESCE(NULLIF(trim(concat_ws(' ', \"firstName\","
                + " \"lastName\")), ''), email) AS name FROM \"User\" WHERE id = ?", user());
        v.put("my_name", me == null ? "" : str(me.get("name")));
        Map<String, Object> o = db.row("SELECT name FROM \"Organization\" WHERE id = ?", org());
        v.put("organization", o == null ? "" : str(o.get("name")));
        return v;
    }

    static String render(String text, Map<String, String> values) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return !blank(a) ? a : b == null ? "" : b;
    }
}
