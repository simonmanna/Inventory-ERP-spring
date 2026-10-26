package com.inventoryerp.license.web;

import com.inventoryerp.license.application.Entitlements;
import com.inventoryerp.license.application.LicenseService;
import com.inventoryerp.license.domain.AdminAudit;
import com.inventoryerp.license.domain.AdminUser;
import com.inventoryerp.license.domain.Customer;
import com.inventoryerp.license.domain.LicenseActivation;
import com.inventoryerp.license.domain.LicenseEvent;
import com.inventoryerp.license.domain.LicenseHeartbeat;
import com.inventoryerp.license.domain.Plan;
import com.inventoryerp.license.shared.Json;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request and response records for the admin API, with the mapping from entities.
 *
 * <p>Entities never leave a controller (the ERP's ArchUnit rule, kept here by hand), so every read
 * goes through one of the {@code of} methods below. They are also where fields are deliberately
 * withheld: no response anywhere carries a sealed secret, and a licence's own key is shown in full
 * only to an admin — logs and the dashboard see it masked.
 */
public final class AdminDtos {

    private AdminDtos() {}

    // ---- auth ----

    public record LoginRequest(
            @NotBlank(message = "is required") @Email(message = "must be an email address") String email,
            @NotBlank(message = "is required") String password,
            @Size(max = 10) String totpCode) {}

    public record RefreshRequest(@NotBlank(message = "is required") String refreshToken) {}

    public record SessionResponse(String accessToken, String refreshToken, long expiresIn, String email,
            String role, boolean mfaEnrolled) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "is required") String currentPassword,
            @NotBlank(message = "is required") @Size(min = 12, message = "must be at least 12 characters")
            String newPassword) {}

    public record CreateAdminRequest(
            @NotBlank(message = "is required") @Email(message = "must be an email address") String email,
            @NotBlank(message = "is required") @Size(min = 12, message = "must be at least 12 characters")
            String password,
            @Size(max = 120) String displayName,
            @Pattern(regexp = "owner|support", message = "must be owner or support") String role) {}

    public record MfaSecretResponse(String secret, String otpauthUri) {}

    public record MfaConfirmRequest(@NotBlank(message = "is required") @Size(max = 10) String code) {}

    public record AdminResponse(String id, String email, String displayName, String role, boolean isActive,
            boolean mfaEnrolled, Instant lastLoginAt, Instant createdAt) {

        public static AdminResponse of(AdminUser admin) {
            return new AdminResponse(admin.getId(), admin.getEmail(), admin.getDisplayName(), admin.getRole(),
                    Boolean.TRUE.equals(admin.getIsActive()), admin.hasMfa(), admin.getLastLoginAt(),
                    admin.getCreatedAt());
        }
    }

    // ---- customers ----

    public record CustomerRequest(
            @NotBlank(message = "is required") @Size(max = 200) String name,
            @Email(message = "must be an email address") @Size(max = 200) String email,
            @Size(max = 60) String phone,
            @Size(max = 80) String country,
            @Size(max = 60) String taxId,
            @Size(max = 4000) String notes) {}

    public record CustomerResponse(String id, String name, String email, String phone, String country,
            String taxId, String notes, Instant createdAt, Instant updatedAt) {

        public static CustomerResponse of(Customer customer) {
            return new CustomerResponse(customer.getId(), customer.getName(), customer.getEmail(),
                    customer.getPhone(), customer.getCountry(), customer.getTaxId(), customer.getNotes(),
                    customer.getCreatedAt(), customer.getUpdatedAt());
        }
    }

    // ---- plans ----

    public record PlanRequest(
            @NotBlank(message = "is required") @Size(max = 60) String code,
            @NotBlank(message = "is required") @Size(max = 120) String name,
            @Size(max = 60) String productCode,
            @Pattern(regexp = "DAY|MONTH|YEAR|TRIAL|PERPETUAL",
                    message = "must be DAY, MONTH, YEAR, TRIAL or PERPETUAL") String period,
            @Min(1) Integer periodCount,
            @Min(0) Long priceMinor,
            @Size(min = 3, max = 3, message = "must be a three-letter code") String currency,
            @Min(0) Integer maxDevices,
            @Min(0) Integer maxUsers,
            @Min(0) Integer maxOrganizations,
            @Min(1) Integer instanceLimit,
            Set<String> features,
            @Min(0) Integer graceDays,
            Boolean isTrial,
            Integer sortOrder) {}

    public record PlanPatchRequest(
            @Size(max = 120) String name,
            @Min(0) Long priceMinor,
            @Size(min = 3, max = 3, message = "must be a three-letter code") String currency,
            @Min(0) Integer maxDevices,
            @Min(0) Integer maxUsers,
            @Min(0) Integer maxOrganizations,
            @Min(1) Integer instanceLimit,
            Set<String> features,
            @Min(0) Integer graceDays,
            Boolean isActive,
            Integer sortOrder,
            @Min(1) Integer periodCount) {}

    public record PlanResponse(String id, String code, String name, String productCode, String period,
            int periodCount, long priceMinor, String currency, Integer maxDevices, Integer maxUsers,
            Integer maxOrganizations, int instanceLimit, List<String> features, int graceDays, boolean isTrial,
            boolean isActive, int sortOrder) {

        public static PlanResponse of(Plan plan) {
            return new PlanResponse(plan.getId(), plan.getCode(), plan.getName(), plan.getProductCode(),
                    plan.getPeriod(), plan.getPeriodCount(), plan.getPriceMinor(), plan.getCurrency(),
                    plan.getMaxDevices(), plan.getMaxUsers(), plan.getMaxOrganizations(), plan.getInstanceLimit(),
                    List.copyOf(Json.readStringSet(plan.getFeatures())), plan.getGraceDays(),
                    Boolean.TRUE.equals(plan.getIsTrial()), Boolean.TRUE.equals(plan.getIsActive()),
                    plan.getSortOrder());
        }
    }

    // ---- licences ----

    public record CreateLicenseRequest(
            @NotBlank(message = "is required") String customerId,
            @NotBlank(message = "is required") String planCode,
            @Min(0) Integer graceDays,
            @Min(1) Integer instanceLimit,
            @Min(0) Integer maxDevices,
            @Min(0) Integer maxUsers,
            @Min(0) Integer maxOrganizations,
            Set<String> features,
            Boolean autoRenew,
            @Size(max = 4000) String notes,
            /** Start the term immediately on creation. Omit to leave the licence pending. */
            Boolean issueNow,
            /** Start the term at this moment instead of now. Requires {@code issueNow}. */
            Instant startsAt) {}

    public record PatchLicenseRequest(
            String planCode,
            @Min(0) Integer graceDays,
            @Min(1) Integer instanceLimit,
            @Min(0) Integer maxDevices,
            @Min(0) Integer maxUsers,
            @Min(0) Integer maxOrganizations,
            Set<String> features,
            Boolean autoRenew,
            @Size(max = 4000) String notes) {}

    public record IssueRequest(Instant startsAt) {}

    public record RenewRequest(@Min(1) Integer periods) {}

    public record ReasonRequest(@Size(max = 500) String reason) {}

    public record OfflineTokenRequest(
            @NotBlank(message = "is required")
            @Pattern(regexp = "[0-9a-fA-F]{64}", message = "must be 64 hexadecimal characters") String fingerprint,
            @Size(max = 120) String instanceName) {}

    public record OfflineTokenResponse(String token, String kid, Instant expiresAt, String licenseKey,
            String customer, String filename) {}

    /** Everything an admin needs to understand one licence, including what it effectively grants. */
    public record LicenseResponse(String id, String key, String status, String customerId, String customer,
            String planCode, String planName, String period, int periodCount, Instant issuedAt, Instant startsAt,
            Instant expiresAt, Instant graceUntil, int graceDays, boolean autoRenew, Long daysRemaining,
            Map<String, Object> limits, List<String> features, boolean featuresOverridden, int instanceLimit,
            long activeInstances, String notes, Instant createdAt, Instant updatedAt) {

        public static LicenseResponse of(LicenseService.Loaded loaded, long activeInstances, Instant now) {
            var license = loaded.license();
            var plan = loaded.plan();
            Entitlements entitlements = loaded.entitlements();
            return new LicenseResponse(
                    license.getId(), license.getKey(), license.getStatus(),
                    loaded.customer().getId(), loaded.customer().getName(),
                    plan.getCode(), plan.getName(), plan.getPeriod(), plan.getPeriodCount(),
                    license.getIssuedAt(), license.getStartsAt(), license.getExpiresAt(), license.graceUntil(),
                    entitlements.graceDays(), Boolean.TRUE.equals(license.getAutoRenew()),
                    com.inventoryerp.license.application.LicensePeriods.daysRemaining(now, license.getExpiresAt()),
                    entitlements.limitsClaim(), List.copyOf(entitlements.features()),
                    license.getFeatureOverrides() != null, entitlements.instanceLimit(), activeInstances,
                    license.getNotes(), license.getCreatedAt(), license.getUpdatedAt());
        }
    }

    public record ActivationResponse(String id, String fingerprint, String instanceName, String hostname,
            String appVersion, String status, Instant firstSeenAt, Instant lastSeenAt, Instant deactivatedAt,
            Integer deviceCount, Integer userCount, Integer orgCount, String mode) {

        public static ActivationResponse of(LicenseActivation activation, LicenseHeartbeat latest) {
            return new ActivationResponse(activation.getId(), activation.getFingerprint(),
                    activation.getInstanceName(), activation.getHostname(), activation.getAppVersion(),
                    activation.getStatus(), activation.getFirstSeenAt(), activation.getLastSeenAt(),
                    activation.getDeactivatedAt(),
                    latest == null ? null : latest.getDeviceCount(),
                    latest == null ? null : latest.getUserCount(),
                    latest == null ? null : latest.getOrgCount(),
                    latest == null ? null : latest.getMode());
        }
    }

    public record EventResponse(long id, String type, Instant at, String actor, String activationId,
            Map<String, Object> detail) {

        public static EventResponse of(LicenseEvent event) {
            return new EventResponse(event.getId(), event.getType(), event.getAt(), event.getActor(),
                    event.getActivationId(), Json.readObject(event.getDetail()));
        }
    }

    public record HeartbeatResponse(long id, Instant at, Integer deviceCount, Integer userCount,
            Integer orgCount, String appVersion, String mode) {

        public static HeartbeatResponse of(LicenseHeartbeat beat) {
            return new HeartbeatResponse(beat.getId(), beat.getAt(), beat.getDeviceCount(), beat.getUserCount(),
                    beat.getOrgCount(), beat.getAppVersion(), beat.getMode());
        }
    }

    public record AuditResponse(long id, Instant at, String adminEmail, String action, String target, String ip,
            Map<String, Object> detail) {

        public static AuditResponse of(AdminAudit audit) {
            return new AuditResponse(audit.getId(), audit.getAt(), audit.getAdminEmail(), audit.getAction(),
                    audit.getTarget(), audit.getIp(), Json.readObject(audit.getDetail()));
        }
    }

    public record SigningKeyResponse(String kid, String algorithm, String status, Instant activeFrom,
            Instant retiredAt) {}
}
