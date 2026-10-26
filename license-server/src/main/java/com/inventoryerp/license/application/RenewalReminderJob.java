package com.inventoryerp.license.application;

import com.inventoryerp.license.crypto.LicenseKeyCodec;
import com.inventoryerp.license.domain.License;
import com.inventoryerp.license.domain.LicenseEventRepository;
import com.inventoryerp.license.domain.LicenseEventType;
import com.inventoryerp.license.domain.LicenseRepository;
import com.inventoryerp.license.shared.Json;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Tells a customer their licence is about to lapse, on each of the days in
 * {@code app.reminders.days-before}.
 *
 * <p>Each (licence, milestone) pair is sent at most once: a {@code reminder_sent} event carrying the
 * milestone is written and checked, so a restart, a second instance or a re-run of the job cannot
 * mail the same customer twice. A missing email address is not an error — plenty of customers are
 * invoiced in person — but it is logged so the gap is visible.
 */
@Component
public class RenewalReminderJob {

    private static final Logger log = LoggerFactory.getLogger(RenewalReminderJob.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy")
            .withZone(ZoneOffset.UTC);

    private final LicenseRepository licenses;
    private final LicenseEventRepository events;
    private final LicenseService licenseService;
    private final LicenseEvents audit;
    private final JavaMailSender mail;
    /** The interface, not {@code SpringTemplateEngine}: its package moves with Spring's major version. */
    private final ITemplateEngine templates;
    private final LicenseProperties properties;

    public RenewalReminderJob(LicenseRepository licenses, LicenseEventRepository events,
            LicenseService licenseService, LicenseEvents audit, JavaMailSender mail,
            ITemplateEngine templates, LicenseProperties properties) {
        this.licenses = licenses;
        this.events = events;
        this.licenseService = licenseService;
        this.audit = audit;
        this.mail = mail;
        this.templates = templates;
        this.properties = properties;
    }

    /** Once an hour, so a milestone is never missed by more than that however the clock falls. */
    @Scheduled(cron = "0 7 * * * *")
    @SchedulerLock(name = "license-renewal-reminders", lockAtMostFor = "PT30M")
    public void run() {
        if (!properties.getReminders().isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        int sent = 0;
        for (Integer daysBefore : properties.getReminders().getDaysBefore()) {
            sent += sweep(now, daysBefore);
        }
        if (sent > 0) {
            log.info("Sent {} renewal reminder(s)", sent);
        }
    }

    /**
     * Licences lapsing in exactly {@code daysBefore} days, give or take the day they fall in. The
     * window is a whole day wide so an hourly job sees each licence several times and the
     * already-sent check — not the timing — is what makes it once.
     */
    public int sweep(Instant now, int daysBefore) {
        Instant from = now.plus(Duration.ofDays(daysBefore));
        Instant to = from.plus(Duration.ofDays(1));
        int sent = 0;
        for (License license : licenses.expiringBetween(from, to)) {
            if (alreadySent(license, daysBefore)) {
                continue;
            }
            if (send(license, daysBefore)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean alreadySent(License license, int daysBefore) {
        // One term is the longest a milestone could legitimately repeat within, so look back far
        // enough to cover a renewal having reset the clock.
        Instant since = license.getExpiresAt().minus(Duration.ofDays(daysBefore + 1L));
        return events.findTop200ByLicenseIdOrderByAtDesc(license.getId()).stream()
                .filter(e -> LicenseEventType.REMINDER_SENT.equals(e.getType()))
                .filter(e -> e.getAt().isAfter(since))
                // Parsed, not substring-matched: Postgres re-renders jsonb with its own spacing, so
                // looking for the literal text of a number silently never matched.
                .anyMatch(e -> milestoneOf(e.getDetail()) == daysBefore);
    }

    /** The {@code daysBefore} a stored reminder event was for, or -1 when it does not say. */
    private static int milestoneOf(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return -1;
        }
        Object value = Json.readObject(detailJson).get("daysBefore");
        return value instanceof Number number ? number.intValue() : -1;
    }

    @Transactional(readOnly = true)
    boolean send(License license, int daysBefore) {
        LicenseService.Loaded loaded = licenseService.load(license.getId());
        String to = loaded.customer().getEmail();
        if (to == null || to.isBlank()) {
            log.info("Licence {} lapses in {} day(s) but customer '{}' has no email address",
                    LicenseKeyCodec.mask(license.getKey()), daysBefore, loaded.customer().getName());
            return false;
        }
        try {
            Context context = new Context();
            context.setVariable("customer", loaded.customer().getName());
            context.setVariable("plan", loaded.plan().getName());
            context.setVariable("licenseKey", license.getKey());
            context.setVariable("daysBefore", daysBefore);
            context.setVariable("expiresOn", DATE.format(license.getExpiresAt()));
            context.setVariable("graceDays", loaded.entitlements().graceDays());

            MimeMessage message = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(properties.getReminders().getFrom());
            helper.setTo(to);
            helper.setSubject(subject(loaded.plan().getName(), daysBefore));
            helper.setText(templates.process("renewal-reminder", context), true);
            mail.send(message);
        } catch (Exception e) {
            // A mail outage must not stop the sweep, and must not mark the reminder as sent.
            log.warn("Could not send the {}-day reminder for licence {}: {}",
                    daysBefore, LicenseKeyCodec.mask(license.getKey()), e.getMessage());
            return false;
        }
        audit.record(license.getId(), LicenseEventType.REMINDER_SENT, "system",
                Map.of("daysBefore", daysBefore, "to", to));
        return true;
    }

    private static String subject(String planName, int daysBefore) {
        if (daysBefore <= 1) {
            return "Your " + planName + " licence expires tomorrow";
        }
        return "Your " + planName + " licence expires in " + daysBefore + " days";
    }

    /** Exposed for the admin API's "send it now" button. */
    @Transactional(readOnly = true)
    public boolean sendNow(String licenseId) {
        License license = licenses.findById(licenseId).orElseThrow();
        Long days = license.getExpiresAt() == null ? null
                : LicensePeriods.daysRemaining(Instant.now(), license.getExpiresAt());
        return send(license, days == null ? 0 : Math.max(0, days.intValue()));
    }

    /** The milestones this job will act on, for display. */
    public List<Integer> milestones() {
        return properties.getReminders().getDaysBefore();
    }
}
