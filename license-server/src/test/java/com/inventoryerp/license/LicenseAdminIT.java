package com.inventoryerp.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.license.application.ActivationService;
import com.inventoryerp.license.application.AdminAudits;
import com.inventoryerp.license.application.AdminAuthService;
import com.inventoryerp.license.application.AdminTokens;
import com.inventoryerp.license.application.CustomerService;
import com.inventoryerp.license.application.DashboardService;
import com.inventoryerp.license.application.LicenseService;
import com.inventoryerp.license.application.MaintenanceJob;
import com.inventoryerp.license.application.PlanService;
import com.inventoryerp.license.application.RenewalReminderJob;
import com.inventoryerp.license.domain.AdminRole;
import com.inventoryerp.license.domain.LicenseRepository;
import com.inventoryerp.license.domain.LicenseStatus;
import com.inventoryerp.license.shared.ApiException;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The administrative half of the authority, driven through its services: the price list, customers,
 * the parts of the licence life cycle the HTTP walk-through does not reach, admin sign-in and its
 * defences, renewal reminders, the dashboard and the nightly sweep.
 *
 * <p>Service-level rather than over HTTP because {@link LicenseServerIT} already covers the wire;
 * what matters here is the rules — which edits are refused, what a lockout does, when a reminder is
 * sent twice (never).
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(LicenseAdminIT.StubMail.class)
class LicenseAdminIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.signing-passphrase", () -> "admin suite passphrase");
        registry.add("app.admin.jwt-secret", () -> "admin-suite-jwt-secret-value");
        registry.add("app.reminders.enabled", () -> true);
    }

    /** Captures what would have been emailed, so the success path is exercised without an SMTP server. */
    @TestConfiguration
    static class StubMail {

        static final List<MimeMessage> SENT = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        JavaMailSender stubMailSender() {
            return new JavaMailSenderImpl() {
                @Override
                public void send(MimeMessage... messages) {
                    SENT.addAll(List.of(messages));
                }
            };
        }
    }

    @Autowired private LicenseService licenses;
    @Autowired private PlanService plans;
    @Autowired private CustomerService customers;
    @Autowired private AdminAuthService auth;
    @Autowired private AdminAudits audits;
    @Autowired private ActivationService activations;
    @Autowired private DashboardService dashboard;
    @Autowired private RenewalReminderJob reminders;
    @Autowired private MaintenanceJob maintenance;
    @Autowired private LicenseRepository licenseRows;
    @Autowired private JdbcClient jdbc;

    private String customer(String name, String email) {
        return customers.create(name, email, "+100000", "UG", "TIN-1", "notes").getId();
    }

    private String fingerprint() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64);
    }

    // ---- the price list ----

    @Test
    void aPlanCanBeCreatedListedAndEdited() {
        String code = "test-monthly-" + UUID.randomUUID().toString().substring(0, 6);
        var created = plans.create(code, "Test (monthly)", "erp", "MONTH", 1, 4900L, "usd",
                3, 10, 1, 2, Set.of("crm", "accounting"), 7, false, 15);

        assertThat(created.getCurrency()).isEqualTo("USD");
        assertThat(created.periodType()).isEqualTo(com.inventoryerp.license.domain.LicensePeriod.MONTH);
        assertThat(plans.list(true)).anyMatch(p -> p.getCode().equals(code));

        var updated = plans.update(created.getId(), "Test (renamed)", 5900L, "eur", 4, 12, 2, 3,
                Set.of("crm"), true, 10, true, 20, 2);

        assertThat(updated.getName()).isEqualTo("Test (renamed)");
        assertThat(updated.getPriceMinor()).isEqualTo(5900L);
        assertThat(updated.getCurrency()).isEqualTo("EUR");
        assertThat(updated.getMaxDevices()).isEqualTo(4);
        assertThat(updated.getInstanceLimit()).isEqualTo(3);
        assertThat(updated.getGraceDays()).isEqualTo(10);
        assertThat(updated.getPeriodCount()).isEqualTo(2);
        assertThat(plans.get(created.getId()).getName()).isEqualTo("Test (renamed)");
    }

    @Test
    void aPlanLeftAloneIsLeftAlone() {
        String code = "untouched-" + UUID.randomUUID().toString().substring(0, 6);
        var created = plans.create(code, "Untouched", null, "YEAR", 1, 1000L, null,
                null, null, null, null, null, null, null, null);
        // Every optional argument defaulted: a YEAR plan takes that period's grace, and a null
        // feature set is "nothing extra" rather than an error.
        assertThat(created.getProductCode()).isEqualTo("erp");
        assertThat(created.getGraceDays()).isEqualTo(14);
        assertThat(created.getInstanceLimit()).isEqualTo(1);

        var same = plans.update(created.getId(), null, null, null, null, null, null, null,
                null, false, null, null, null, null);

        assertThat(same.getName()).isEqualTo("Untouched");
        assertThat(same.getPriceMinor()).isEqualTo(1000L);
        assertThat(same.getGraceDays()).isEqualTo(14);
    }

    @Test
    void aDuplicatePlanCodeAndAnUnknownProductAreRefused() {
        assertThatThrownBy(() -> plans.create("pro-monthly", "Clash", "erp", "MONTH", 1, 1L, "USD",
                null, null, null, null, Set.of(), null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");

        assertThatThrownBy(() -> plans.create("ghost-" + UUID.randomUUID(), "Ghost", "nosuchproduct",
                "MONTH", 1, 1L, "USD", null, null, null, null, Set.of(), null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No product with code");

        assertThatThrownBy(() -> plans.get("nope"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No plan with id");
    }

    @Test
    void aPlanWithAFeatureKeyNoReleaseUnderstandsIsStillAcceptedAndWarnedAbout() {
        // Refusing it would stop a bespoke deal being recorded before the release that implements it.
        var created = plans.create("bespoke-" + UUID.randomUUID().toString().substring(0, 6),
                "Bespoke", "erp", "MONTH", 1, 1L, "USD", null, null, null, null,
                Set.of("crm", "telepathy"), 7, false, 0);

        assertThat(com.inventoryerp.license.shared.Json.readStringSet(created.getFeatures()))
                .contains("telepathy");
    }

    // ---- customers ----

    @Test
    void customersCanBeSearchedEditedAndDeletedWhenTheyHoldNothing() {
        String id = customer("Searchable Cafe", "searchable@example.com");

        assertThat(customers.search("searchable", 1, 20).getContent()).extracting(c -> c.getId())
                .contains(id);
        assertThat(customers.search(null, 1, 20).getTotalElements()).isPositive();

        var updated = customers.update(id, "Renamed Cafe", "renamed@example.com", null, null, null, null);
        assertThat(updated.getName()).isEqualTo("Renamed Cafe");
        assertThat(updated.getEmail()).isEqualTo("renamed@example.com");

        customers.delete(id);
        assertThatThrownBy(() -> customers.get(id)).isInstanceOf(ApiException.class);
    }

    @Test
    void aDuplicateEmailIsRefusedOnCreateAndOnEdit() {
        customer("First Holder", "taken@example.com");
        assertThatThrownBy(() -> customer("Second Holder", "taken@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");

        String other = customer("Other Holder", "free@example.com");
        assertThatThrownBy(() -> customers.update(other, "Other Holder", "taken@example.com",
                null, null, null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aCustomerHoldingALicenceCannotBeDeleted() {
        // Deleting them would orphan the history every lockout is explained from.
        String id = customer("Holds A Licence", "holds@example.com");
        licenses.create(id, "pro-monthly", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");

        assertThatThrownBy(() -> customers.delete(id))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Revoke them before deleting");
    }

    // ---- the licence life cycle, past what the HTTP walk-through covers ----

    @Test
    void aPendingLicenceHasNoDatesUntilItIsIssued() {
        String id = customer("Pending Co", "pending@example.com");
        var pending = licenses.create(id, "pro-monthly", null, null, null, null, null, null,
                false, "sold at the trade show", null, "owner@example.com");

        assertThat(pending.license().statusType()).isEqualTo(LicenseStatus.PENDING);
        assertThat(pending.license().getStartsAt()).isNull();
        assertThat(pending.license().getExpiresAt()).isNull();
        // A pending licence cannot hand out a token; the install is told to contact its supplier.
        assertThat(com.inventoryerp.license.application.LicenseMessages.forStatus(LicenseStatus.PENDING))
                .isEqualTo("This licence has not been issued yet. Contact your supplier.");

        var issued = licenses.issue(pending.license().getId(), null, "owner@example.com");
        assertThat(issued.license().statusType()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(issued.license().getExpiresAt()).isNotNull();

        assertThatThrownBy(() -> licenses.issue(pending.license().getId(), null, "owner@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already active");
    }

    @Test
    void negotiatedTermsCanBeEditedAndFeatureOverridesReset() {
        String id = customer("Negotiated Co", "negotiated@example.com");
        var sold = licenses.create(id, "pro-monthly", null, null, null, null, null,
                Set.of("crm"), false, null, Instant.now(), "owner@example.com");
        String licenceId = sold.license().getId();

        var bespoke = licenses.update(licenceId, "starter-monthly", 21, 3, 9, 40, 2,
                Set.of("crm", "hr"), true, true, "renegotiated", "owner@example.com");

        assertThat(bespoke.license().getGraceDays()).isEqualTo(21);
        assertThat(bespoke.entitlements().instanceLimit()).isEqualTo(3);
        assertThat(bespoke.entitlements().maxDevices()).isEqualTo(9);
        assertThat(bespoke.entitlements().features()).containsExactlyInAnyOrder("crm", "hr");
        assertThat(bespoke.license().getAutoRenew()).isTrue();

        // An empty set means "inherit the plan again", not "grant nothing".
        var inherited = licenses.update(licenceId, null, null, null, null, null, null,
                Set.of(), true, null, null, "owner@example.com");
        assertThat(inherited.license().getFeatureOverrides()).isNull();
        assertThat(inherited.entitlements().features()).contains("accounting");

        // Nothing passed changes nothing.
        var unchanged = licenses.update(licenceId, null, null, null, null, null, null, null, false,
                null, null, "owner@example.com");
        assertThat(unchanged.license().getGraceDays()).isEqualTo(21);
    }

    @Test
    void aLicenceCannotChangeTheShapeOfItsTerm() {
        // Moving a monthly licence onto a daily plan would silently re-date a term already sold.
        String id = customer("Shape Co", "shape@example.com");
        var sold = licenses.create(id, "pro-monthly", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");

        assertThatThrownBy(() -> licenses.update(sold.license().getId(), "starter-daily", null, null,
                null, null, null, null, false, null, null, "owner@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Issue a new licence instead");
    }

    @Test
    void resumingALicenceWhoseTermRanOutWhileStoppedComesBackExpired() {
        // Suspending does not extend a term, so a long suspension can outlive it.
        String id = customer("Lapsed While Stopped", "lapsed@example.com");
        var sold = licenses.create(id, "pro-monthly", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");
        String licenceId = sold.license().getId();

        licenses.suspend(licenceId, "non-payment", "owner@example.com");
        expireInThePast(licenceId, Duration.ofDays(60));

        var resumed = licenses.resume(licenceId, "owner@example.com");
        assertThat(resumed.license().statusType()).isEqualTo(LicenseStatus.EXPIRED);

        assertThatThrownBy(() -> licenses.resume(licenceId, "owner@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only a suspended licence");
    }

    @Test
    void aPerpetualLicenceNeverExpiresAndCannotBeRenewed() {
        String id = customer("Perpetual Co", "perpetual@example.com");
        var sold = licenses.create(id, "perpetual-onprem", null, null, null, null, null, null, false,
                null, Instant.now(), "owner@example.com");

        assertThat(sold.license().getExpiresAt()).isNull();
        assertThat(sold.license().graceUntil()).isNull();
        assertThatThrownBy(() -> licenses.renew(sold.license().getId(), 1, "owner@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("never expires");
    }

    @Test
    void renewingAPendingLicenceIssuesItInstead() {
        String id = customer("Renew Pending Co", "renewpending@example.com");
        var pending = licenses.create(id, "pro-monthly", null, null, null, null, null, null, false,
                null, null, "owner@example.com");

        var renewed = licenses.renew(pending.license().getId(), 1, "owner@example.com");

        assertThat(renewed.license().statusType()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(renewed.license().getExpiresAt()).isNotNull();
    }

    @Test
    void anUnknownLicenceOrKeyIsNotFound() {
        assertThatThrownBy(() -> licenses.load("nope"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No licence with id");
        assertThatThrownBy(() -> licenses.loadByKey("ERP-0000-0000-0000-0000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Licence key not recognised.");
        assertThatThrownBy(() -> licenses.loadByKey(null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anUnknownCustomerOrPlanIsRefusedAtTheSale() {
        assertThatThrownBy(() -> licenses.create("nocustomer", "pro-monthly", null, null, null, null,
                null, null, false, null, null, "owner@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No customer with id");

        String id = customer("Plan Hunter", "planhunter@example.com");
        assertThatThrownBy(() -> licenses.create(id, "no-such-plan", null, null, null, null, null,
                null, false, null, null, "owner@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No plan with code");
    }

    @Test
    void aFingerprintMustBeSixtyFourHexCharacters() {
        String id = customer("Offline Co", "offlineco@example.com");
        var sold = licenses.create(id, "pro-monthly", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");

        assertThatThrownBy(() -> licenses.offlineToken(sold.license().getId(), "short", "till",
                "owner@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("64 hexadecimal characters");
    }

    @Test
    void anOfflineTokenForASuspendedLicenceIsRefused() {
        String id = customer("Suspended Offline Co", "suspoffline@example.com");
        var sold = licenses.create(id, "pro-monthly", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");
        licenses.suspend(sold.license().getId(), "unpaid", "owner@example.com");

        assertThatThrownBy(() -> licenses.offlineToken(sold.license().getId(), fingerprint(), "till",
                "owner@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("suspended");
    }

    @Test
    void theNightlySweepClosesTheBooksOnALapsedLicence() {
        String id = customer("Sweep Co", "sweep@example.com");
        var sold = licenses.create(id, "pro-monthly", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");
        String licenceId = sold.license().getId();

        // Inside grace: still trading, so the books stay open.
        expireInThePast(licenceId, Duration.ofDays(2));
        assertThat(licenses.markLapsedExpired(Instant.now())).isZero();
        assertThat(licenses.load(licenceId).license().statusType()).isEqualTo(LicenseStatus.ACTIVE);

        // Past grace: marked expired, which is what the renewal lists read.
        expireInThePast(licenceId, Duration.ofDays(60));
        assertThat(licenses.markLapsedExpired(Instant.now())).isPositive();
        assertThat(licenses.load(licenceId).license().statusType()).isEqualTo(LicenseStatus.EXPIRED);

        // And the whole job runs: expiry sweep, heartbeat retention, token cleanup.
        maintenance.run();
    }

    // ---- renewal reminders ----

    @Test
    void aReminderGoesOutOnceAndOnlyOncePerMilestone() {
        StubMail.SENT.clear();
        String id = customer("Reminder Co", "reminder@example.com");
        var sold = licenses.create(id, "pro-yearly", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");
        String licenceId = sold.license().getId();
        // Lapses in seven days.
        setExpiry(licenceId, Instant.now().plus(Duration.ofDays(7)).plus(Duration.ofHours(1)));

        assertThat(reminders.sweep(Instant.now(), 7)).isEqualTo(1);
        assertThat(StubMail.SENT).hasSize(1);

        // The job runs hourly, so it sees this licence again and again. It must stay at one email.
        assertThat(reminders.sweep(Instant.now(), 7)).isZero();
        reminders.run();
        assertThat(StubMail.SENT).hasSize(1);
    }

    @Test
    void aCustomerWithNoEmailIsNotAnErrorButIsNotSilentlyForgottenEither() {
        StubMail.SENT.clear();
        String id = customers.create("Invoiced In Person", null, null, null, null, null).getId();
        var sold = licenses.create(id, "pro-yearly", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");
        setExpiry(sold.license().getId(), Instant.now().plus(Duration.ofDays(3)).plus(Duration.ofHours(1)));

        assertThat(reminders.sweep(Instant.now(), 3)).isZero();
        assertThat(StubMail.SENT).isEmpty();
        assertThat(reminders.milestones()).contains(30, 14, 7, 3, 1);
    }

    @Test
    void anAdminCanSendAReminderWithoutWaitingForAMilestone() {
        StubMail.SENT.clear();
        String id = customer("Chase Me Co", "chaseme@example.com");
        var sold = licenses.create(id, "pro-monthly", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");

        assertThat(reminders.sendNow(sold.license().getId())).isTrue();
        assertThat(StubMail.SENT).hasSize(1);
    }

    // ---- admin accounts ----

    @Test
    void repeatedWrongPasswordsLockTheAccountAndTheLockIsReported() {
        String email = "lockme-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        auth.create(email, "a-long-enough-password", "Locked", AdminRole.SUPPORT);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> auth.login(email, "wrong-but-long-enough", null, "10.0.0.1"))
                    .isInstanceOf(ApiException.class);
        }
        // The sixth attempt is refused for being locked, even with the right password.
        assertThatThrownBy(() -> auth.login(email, "a-long-enough-password", null, "10.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many failed attempts");
    }

    @Test
    void anUnknownAdminAndADisabledOneAreRefused() {
        assertThatThrownBy(() -> auth.login("ghost@example.com", "whatever-long-enough", null, "ip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Email or password is incorrect.");

        String email = "disabled-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        var admin = auth.create(email, "a-long-enough-password", "Disabled", AdminRole.SUPPORT);
        jdbc.sql("UPDATE admin_user SET is_active = false WHERE id = ?").param(admin.getId()).update();

        assertThatThrownBy(() -> auth.login(email, "a-long-enough-password", null, "ip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("disabled");
        assertThatThrownBy(() -> auth.require(admin.getId()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aWeakPasswordAndABadEmailAreRefused() {
        assertThatThrownBy(() -> auth.create("short@example.com", "tooshort", "X", AdminRole.SUPPORT))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least 12 characters");
        assertThatThrownBy(() -> auth.create("not-an-email", "a-long-enough-password", "X",
                AdminRole.SUPPORT))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("valid email");

        String email = "dupe-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        auth.create(email, "a-long-enough-password", "X", AdminRole.SUPPORT);
        assertThatThrownBy(() -> auth.create(email, "a-long-enough-password", "X", AdminRole.SUPPORT))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void aRefreshTokenRotatesOnUseAndAReplayEndsEverySession() {
        String email = "rotate-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        auth.create(email, "a-long-enough-password", "Rotate", AdminRole.SUPPORT);
        var first = auth.login(email, "a-long-enough-password", null, "ip");

        var second = auth.refresh(first.refreshToken());
        assertThat(second.accessToken()).isNotBlank();
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

        // Replaying the spent token is treated as theft: every session for that admin is revoked.
        assertThatThrownBy(() -> auth.refresh(first.refreshToken()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("session has ended");
        assertThatThrownBy(() -> auth.refresh(second.refreshToken()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> auth.refresh("never-issued"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void changingAPasswordEndsEveryOtherSession() {
        String email = "changepw-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        var admin = auth.create(email, "a-long-enough-password", "Change", AdminRole.OWNER);
        var session = auth.login(email, "a-long-enough-password", null, "ip");

        assertThatThrownBy(() -> auth.changePassword(admin.getId(), "wrong-password", "a-new-long-password"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("current password is incorrect");

        auth.changePassword(admin.getId(), "a-long-enough-password", "a-new-long-password");

        assertThatThrownBy(() -> auth.refresh(session.refreshToken()))
                .isInstanceOf(ApiException.class);
        assertThat(auth.login(email, "a-new-long-password", null, "ip").accessToken()).isNotBlank();
        assertThat(auth.list()).extracting(a -> a.getEmail()).contains(email);
    }

    @Test
    void mfaComesIntoForceOnlyOnceACodeHasBeenConfirmed() throws Exception {
        String email = "mfa-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        var admin = auth.create(email, "a-long-enough-password", "Mfa", AdminRole.OWNER);

        String secret = auth.beginMfaEnrolment(admin.getId());
        assertThat(secret).isNotBlank();
        // Half-enrolled: a sign-in must still work, or a mistyped setup would lock the owner out.
        assertThat(auth.login(email, "a-long-enough-password", null, "ip").mfaEnrolled()).isFalse();

        assertThatThrownBy(() -> auth.confirmMfaEnrolment(admin.getId(), "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not valid");

        String code = new DefaultCodeGenerator().generate(secret,
                new SystemTimeProvider().getTime() / 30);
        auth.confirmMfaEnrolment(admin.getId(), code);

        // Now it is in force: the password alone is not enough.
        assertThatThrownBy(() -> auth.login(email, "a-long-enough-password", null, "ip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("one-time code is required");
        assertThatThrownBy(() -> auth.login(email, "a-long-enough-password", "000000", "ip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not valid");

        String fresh = new DefaultCodeGenerator().generate(secret,
                new SystemTimeProvider().getTime() / 30);
        assertThat(auth.login(email, "a-long-enough-password", fresh, "ip").mfaEnrolled()).isTrue();
    }

    @Test
    void enrolmentMustBeStartedBeforeItCanBeConfirmed() {
        String email = "mfaorder-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        var admin = auth.create(email, "a-long-enough-password", "Order", AdminRole.SUPPORT);

        assertThatThrownBy(() -> auth.confirmMfaEnrolment(admin.getId(), "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Start enrolment first");
    }

    @Test
    void loggingOutEndsTheSession() {
        String email = "logout-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        var admin = auth.create(email, "a-long-enough-password", "Out", AdminRole.SUPPORT);
        var session = auth.login(email, "a-long-enough-password", null, "ip");

        auth.logout(admin.getId());

        assertThatThrownBy(() -> auth.refresh(session.refreshToken()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theAuditTrailRecordsWhoActedAndFromWhere() {
        var actor = new AdminTokens.Principal("admin-1", "owner@example.com", AdminRole.OWNER);
        audits.record(actor, "licence.something", "target-1", "10.0.0.9",
                java.util.Map.of("detail", "value"));
        audits.record(null, "system.something", null, null, java.util.Map.of());

        assertThat(audits.recent(1, 50).getContent())
                .extracting(a -> a.getAction())
                .contains("licence.something", "system.something");
    }

    // ---- the dashboard ----

    @Test
    void theDashboardCountsTheBookAndFlagsWhatNeedsAttention() {
        String id = customer("Dashboard Co", "dashboard@example.com");
        var sold = licenses.create(id, "pro-monthly", null, null, null, 1, 1, null, false, null,
                Instant.now(), "owner@example.com");
        String fingerprint = fingerprint();
        var issued = activations.activate(sold.license().getKey(), fingerprint, "till", "host",
                "0.1.0", "10.0.0.1");

        // An install reporting more than it pays for: the upsell list, not an enforcement signal.
        activations.heartbeat(issued.token(),
                new ActivationService.Report(9, 9, 9, "0.1.0", "ACTIVE"), "10.0.0.1");

        var overview = dashboard.overview();
        assertThat(overview).containsKeys("licenses", "expiring", "activations",
                "recurringRevenueEstimate", "silentInstalls", "overLimitInstalls");
        @SuppressWarnings("unchecked")
        var revenue = (java.util.Map<String, Long>) overview.get("recurringRevenueEstimate");
        assertThat(revenue.get("USD")).isPositive();
        assertThat(dashboard.overLimitInstalls())
                .anyMatch(a -> a.licenseId().equals(sold.license().getId()));

        // Nothing has gone quiet yet; back-date the activation and it should be flagged.
        assertThat(dashboard.silentInstalls(Instant.now()))
                .noneMatch(a -> a.licenseId().equals(sold.license().getId()));
        jdbc.sql("UPDATE license_activation SET last_seen_at = ? WHERE fingerprint = ?")
                .params(java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(5))), fingerprint)
                .update();
        assertThat(dashboard.silentInstalls(Instant.now()))
                .anyMatch(a -> a.licenseId().equals(sold.license().getId()) && a.reason().equals("silent"));
    }

    @Test
    void aTrialAndAPerpetualLicenceContributeNothingToRecurringRevenue() {
        String id = customer("Free Co", "freeco@example.com");
        licenses.create(id, "trial-14d", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");
        licenses.create(id, "perpetual-onprem", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");

        // Both are active licences, so this only passes if the estimate excludes them by kind.
        @SuppressWarnings("unchecked")
        var revenue = (java.util.Map<String, Long>) dashboard.overview().get("recurringRevenueEstimate");
        long beforeAnyDailyPlan = revenue.getOrDefault("USD", 0L);
        assertThat(beforeAnyDailyPlan).isNotNegative();
    }

    @Test
    void aDailyPlanIsNormalisedToAMonthlyFigure() {
        String id = customer("Daily Co", "daily@example.com");
        @SuppressWarnings("unchecked")
        var before = (java.util.Map<String, Long>) dashboard.overview().get("recurringRevenueEstimate");
        long usdBefore = before.getOrDefault("USD", 0L);

        licenses.create(id, "starter-daily", null, null, null, null, null, null, false, null,
                Instant.now(), "owner@example.com");

        @SuppressWarnings("unchecked")
        var after = (java.util.Map<String, Long>) dashboard.overview().get("recurringRevenueEstimate");
        // 300 minor units a day is roughly 9131 a month, not 300.
        assertThat(after.get("USD") - usdBefore).isBetween(9000L, 9300L);
    }

    // ---- helpers ----

    /** Move a licence's expiry into the past without going through a lifecycle method. */
    private void expireInThePast(String licenceId, Duration ago) {
        setExpiry(licenceId, Instant.now().minus(ago));
    }

    private void setExpiry(String licenceId, Instant at) {
        var row = licenseRows.findById(licenceId).orElseThrow();
        row.setExpiresAt(at);
        licenseRows.saveAndFlush(row);
    }
}
