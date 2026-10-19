package com.inventoryerp.kernel.notifications;

import com.inventoryerp.shared.web.EntityJson;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

/**
 * Multi-channel notifications (port of {@code notifications.service.ts}). A {@code Notification} row is
 * always written (durable record); email goes out over SMTP (SMTP_HOST...), SMS over Twilio
 * (TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN / TWILIO_FROM), push over VAPID. Without credentials the
 * message is logged as "would-send". Per-user opt-out ({@code NotificationPreference}) is honoured.
 * All access is unscoped: callers pass the organization explicitly (outbox workers have no tenant).
 */
@Service
public class NotificationsService {

    private static final Logger log = LoggerFactory.getLogger(NotificationsService.class);

    /** One message to send. {@code channel}: in_app | email | sms | push. */
    public record SendInput(String organizationId, String userId, String channel, String category, String title,
            String body, Map<String, Object> payload) {}

    private final JdbcClient jdbc;
    private final PushService push;
    private final JavaMailSenderImpl smtp;
    private final String smtpFrom;
    private final String twilioSid;
    private final String twilioToken;
    private final String twilioFrom;

    public NotificationsService(JdbcClient jdbc, PushService push,
            @Value("${SMTP_HOST:}") String smtpHost,
            @Value("${SMTP_PORT:587}") int smtpPort,
            @Value("${SMTP_SECURE:false}") boolean smtpSecure,
            @Value("${SMTP_USER:}") String smtpUser,
            @Value("${SMTP_PASS:}") String smtpPass,
            @Value("${SMTP_FROM:no-reply@cafe-pos.local}") String smtpFrom,
            @Value("${TWILIO_ACCOUNT_SID:}") String twilioSid,
            @Value("${TWILIO_AUTH_TOKEN:}") String twilioToken,
            @Value("${TWILIO_FROM:}") String twilioFrom) {
        this.jdbc = jdbc;
        this.push = push;
        this.smtpFrom = smtpFrom;
        this.twilioSid = twilioSid;
        this.twilioToken = twilioToken;
        this.twilioFrom = twilioFrom;
        if (!smtpHost.isEmpty()) {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(smtpHost);
            sender.setPort(smtpPort);
            if (!smtpUser.isEmpty()) {
                sender.setUsername(smtpUser);
                sender.setPassword(smtpPass);
            }
            Properties props = sender.getJavaMailProperties();
            props.put("mail.smtp.auth", String.valueOf(!smtpUser.isEmpty()));
            if (smtpSecure) {
                props.put("mail.smtp.ssl.enable", "true");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }
            this.smtp = sender;
            log.info("SMTP transport ready ({})", smtpHost);
        } else {
            this.smtp = null;
            log.warn("SMTP_HOST not set — email notifications will be logged but not sent");
        }
    }

    /** Write the durable row, then best-effort deliver. Returns {@code {id, delivered}}. */
    public Map<String, Object> send(SendInput input) {
        String category = input.category() == null ? "general" : input.category();
        boolean enabled = true;
        if (input.userId() != null) {
            Boolean pref = jdbc.sql("""
                            SELECT enabled FROM "NotificationPreference" WHERE "organizationId" = ? AND "userId" = ?
                              AND channel = ?::"NotificationChannel" AND category = ?""")
                    .params(input.organizationId(), input.userId(), input.channel(), category)
                    .query(Boolean.class)
                    .optional()
                    .orElse(null);
            if (pref != null && !pref) {
                enabled = false;
            }
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO "Notification" (id, "organizationId", "userId", channel, category, title, body, payload,
                          status, "createdAt")
                        VALUES (?, ?, ?, ?::"NotificationChannel", ?, ?, ?, ?::jsonb, ?::"NotificationStatus", now())""")
                .params(id, input.organizationId(), input.userId(), input.channel(), category, input.title(),
                        input.body(), EntityJson.toJson(input.payload() == null ? Map.of() : input.payload()),
                        enabled ? "pending" : "failed")
                .update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        if (!enabled) {
            out.put("delivered", false);
            return out;
        }
        try {
            switch (input.channel()) {
                case "email" -> sendEmail(input);
                case "sms" -> sendSms(input);
                case "push" -> {
                    if (input.userId() != null) {
                        Map<String, Object> p = input.payload() == null ? Map.of() : input.payload();
                        push.sendToUser(input.userId(), input.title(), input.body(),
                                p.get("href") == null ? null : String.valueOf(p.get("href")),
                                p.get("tag") == null ? null : String.valueOf(p.get("tag")));
                    }
                }
                default -> {
                    // in_app only: mark sent so the UI badge counts it.
                }
            }
            jdbc.sql("UPDATE \"Notification\" SET status = 'sent', \"sentAt\" = now() WHERE id = ?").param(id).update();
            out.put("delivered", true);
        } catch (Exception err) {
            String msg = err.toString();
            jdbc.sql("UPDATE \"Notification\" SET status = 'failed', error = ? WHERE id = ?")
                    .params(msg.length() > 500 ? msg.substring(0, 500) : msg, id)
                    .update();
            log.warn("Notification {} failed: {}", id, msg);
            out.put("delivered", false);
        }
        return out;
    }

    private void sendEmail(SendInput input) throws Exception {
        if (input.userId() == null) {
            throw new IllegalStateException("email channel requires userId");
        }
        String email = jdbc.sql("SELECT email FROM \"User\" WHERE id = ?").param(input.userId())
                .query(String.class).optional().orElse(null);
        if (email == null || email.isEmpty()) {
            throw new IllegalStateException("User has no email address on file");
        }
        if (smtp == null) {
            log.warn("[DEV-EMAIL] to={} subject={} body={}", email, input.title(), input.body());
            return;
        }
        var message = smtp.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(smtpFrom);
        helper.setTo(email);
        helper.setSubject(input.title());
        helper.setText(input.body(), "<p>" + HtmlUtils.htmlEscape(input.body()) + "</p>");
        smtp.send(message);
    }

    private void sendSms(SendInput input) {
        if (input.userId() == null) {
            throw new IllegalStateException("sms channel requires userId");
        }
        if (twilioSid.isEmpty() || twilioToken.isEmpty()) {
            log.warn("[DEV-SMS] user={} body={}", input.userId(), input.body());
            return;
        }
        String phone = jdbc.sql("""
                        SELECT p.phone FROM "User" u JOIN "Partner" p ON p."organizationId" = ? AND p.email = u.email
                        WHERE u.id = ? LIMIT 1""")
                .params(input.organizationId(), input.userId())
                .query(String.class)
                .optional()
                .orElse(null);
        if (phone == null || phone.isEmpty()) {
            throw new IllegalStateException("No phone number on file");
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("From", twilioFrom);
        form.add("To", phone);
        form.add("Body", input.title() + "\n" + input.body());
        RestClient.create()
                .post()
                .uri("https://api.twilio.com/2010-04-01/Accounts/{sid}/Messages.json", twilioSid)
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((twilioSid + ":" + twilioToken).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }
}
