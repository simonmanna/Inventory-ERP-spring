package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.NOW;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal ICS calendar feed of a user's CRM activities (CRM enhancements T2b), so planned calls and meetings show up
 * in Google Calendar, Outlook or a phone. The secret token is shown once; only its SHA-256 is stored, one live feed per
 * user, and creating a new one revokes the old. The public feed covers 30 days back to 180 days ahead.
 */
@Service
public class CrmCalendarFeedService {

    static final String PUBLIC_PATH = "/public/crm/calendar/";
    private static final DateTimeFormatter ICS_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final CrmDb db;
    private final SecureRandom random = new SecureRandom();

    public CrmCalendarFeedService(CrmDb db) {
        this.db = db;
    }

    /** The caller's live feed ({@code active}, {@code createdAt}, {@code lastUsedAt}); the token is never returned. */
    @Transactional(readOnly = true)
    public Map<String, Object> status() {
        Map<String, Object> r = db.row("SELECT \"createdAt\", \"lastUsedAt\" FROM \"CrmCalendarFeed\" WHERE"
                + " \"organizationId\" = ? AND \"userId\" = ? AND \"revokedAt\" IS NULL", org(), user());
        return r == null ? Obj.of("active", false) : Obj.of("active", true, "createdAt", r.get("createdAt"),
                "lastUsedAt", r.get("lastUsedAt"));
    }

    /** New feed for the caller (the old one stops working). Returns the path with the token, once. */
    @Transactional
    public Map<String, Object> create() {
        if (user() == null) {
            throw ApiException.forbidden("A user is required");
        }
        revokeLive();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        db.update("INSERT INTO \"CrmCalendarFeed\" (id, \"organizationId\", \"userId\", \"tokenHash\") VALUES (?, ?, ?, ?)",
                CrmDb.newId(), org(), user(), sha256(token));
        return Obj.of("active", true, "path", "/api/v1" + PUBLIC_PATH + token + ".ics");
    }

    /** Stop the caller's feed. */
    @Transactional
    public Map<String, Object> revoke() {
        revokeLive();
        return Obj.of("active", false);
    }

    private void revokeLive() {
        db.update("UPDATE \"CrmCalendarFeed\" SET \"revokedAt\" = " + NOW + " WHERE \"organizationId\" = ? AND"
                + " \"userId\" = ? AND \"revokedAt\" IS NULL", org(), user());
    }

    /** The ICS document of a feed token (404 for an unknown or revoked token, or an inactive user). */
    @Transactional
    public String ics(String token) {
        if (token == null || token.length() < 30 || token.length() > 100) {
            throw ApiException.notFound("Calendar feed not found");
        }
        Map<String, Object> feed = SqlRows.one(db.jdbc().sql("SELECT f.id, f.\"organizationId\", f.\"userId\" FROM"
                + " \"CrmCalendarFeed\" f JOIN \"User\" u ON u.id = f.\"userId\" WHERE f.\"tokenHash\" = ? AND"
                + " f.\"revokedAt\" IS NULL AND u.\"isActive\" AND u.\"deletedAt\" IS NULL").param(sha256(token)));
        if (feed == null) {
            throw ApiException.notFound("Calendar feed not found");
        }
        String orgId = str(feed.get("organizationId"));
        String userId = str(feed.get("userId"));
        db.jdbc().sql("UPDATE \"CrmCalendarFeed\" SET \"lastUsedAt\" = " + NOW + " WHERE id = ?").param(feed.get("id"))
                .update();
        Instant now = Instant.now();
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT a.id, a.type::text AS type, a.title,"
                + " a.body, a.status::text AS status, a.\"dueAt\", a.duration, a.\"updatedAt\", d.name AS \"dealName\","
                + " d.kind AS \"dealKind\", a.\"dealId\", COALESCE(p.name, d.\"companyName\") AS \"partnerName\" FROM"
                + " \"Activity\" a LEFT JOIN \"Deal\" d ON d.id = a.\"dealId\" LEFT JOIN \"Partner\" p ON p.id ="
                + " COALESCE(a.\"partnerId\", d.\"partnerId\") WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS NULL"
                + " AND COALESCE(a.\"assignedToId\", a.\"createdById\") = ? AND a.\"dueAt\" IS NOT NULL AND a.\"dueAt\""
                + " >= ? AND a.\"dueAt\" < ? AND a.status::text <> 'cancelled' AND a.type::text NOT IN"
                + " ('deal_stage_change', 'note') ORDER BY a.\"dueAt\" LIMIT 2000").params(orgId, userId, SqlRows.ts(now
                        .minus(Duration.ofDays(30))), SqlRows.ts(now.plus(Duration.ofDays(180)))));
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//Inventory-ERP//CRM activities//EN");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:PUBLISH");
        line(out, "X-WR-CALNAME:CRM activities");
        line(out, "REFRESH-INTERVAL;VALUE=DURATION:PT1H");
        for (Map<String, Object> a : rows) {
            Instant start = (Instant) a.get("dueAt");
            int minutes = a.get("duration") instanceof Number n && n.intValue() > 0 ? n.intValue() : 30;
            boolean done = "done".equals(a.get("status"));
            String type = str(a.get("type"));
            String summary = (done ? "✓ " : "") + Character.toUpperCase(type.charAt(0)) + type.substring(1) + ": "
                    + a.get("title");
            StringBuilder desc = new StringBuilder();
            if (a.get("dealName") != null) {
                desc.append("lead".equals(a.get("dealKind")) ? "Lead: " : "Opportunity: ").append(a.get("dealName"));
            }
            if (a.get("partnerName") != null) {
                desc.append(desc.isEmpty() ? "" : "\n").append("Customer: ").append(a.get("partnerName"));
            }
            if (a.get("body") != null) {
                desc.append(desc.isEmpty() ? "" : "\n\n").append(a.get("body"));
            }
            line(out, "BEGIN:VEVENT");
            line(out, "UID:" + a.get("id") + "@crm.inventory-erp");
            line(out, "DTSTAMP:" + ICS_TIME.format(a.get("updatedAt") instanceof Instant u ? u : now));
            line(out, "DTSTART:" + ICS_TIME.format(start));
            line(out, "DTEND:" + ICS_TIME.format(start.plus(Duration.ofMinutes(minutes))));
            line(out, "SUMMARY:" + text(summary));
            if (!desc.isEmpty()) {
                line(out, "DESCRIPTION:" + text(desc.toString()));
            }
            line(out, "CATEGORIES:" + text(type));
            line(out, "STATUS:CONFIRMED");
            line(out, "TRANSP:" + (done ? "TRANSPARENT" : "OPAQUE"));
            line(out, "END:VEVENT");
        }
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    /** RFC 5545 TEXT escaping. */
    public static String text(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n",
                "\\n").replace("\r", "");
    }

    /** Append a content line folded at 75 octets (continuation lines start with a space), CRLF-terminated. */
    public static void line(StringBuilder out, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        int limit = 75;
        int start = 0;
        while (bytes.length - start > limit) {
            int end = start + limit;
            while (end > start && (bytes[end] & 0xC0) == 0x80) {
                end--; // never split a UTF-8 sequence
            }
            out.append(new String(bytes, start, end - start, StandardCharsets.UTF_8)).append("\r\n ");
            start = end;
            limit = 74; // the leading space counts
        }
        out.append(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8)).append("\r\n");
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
