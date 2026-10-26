package com.inventoryerp.license.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * The wire shapes an ERP install uses. Kept small on purpose: an install sends what identifies it
 * and counts of what it is running, and nothing else ever leaves a customer's premises.
 */
public final class ClientDtos {

    private ClientDtos() {}

    /** {@code POST /activate}. */
    public record ActivateRequest(
            @NotBlank(message = "is required") @Size(max = 64) String licenseKey,
            @NotBlank(message = "is required")
            @Pattern(regexp = "[0-9a-fA-F]{64}", message = "must be 64 hexadecimal characters") String fingerprint,
            @Size(max = 120) String instanceName,
            @Size(max = 255) String hostname,
            @Size(max = 40) String appVersion) {}

    /**
     * {@code POST /heartbeat}. The token authenticates the call, so no key is sent; counts are
     * optional because an install that cannot compute one must still be able to check in.
     */
    public record HeartbeatRequest(
            @NotBlank(message = "is required") String token,
            @Min(0) @Max(100_000) Integer deviceCount,
            @Min(0) @Max(1_000_000) Integer userCount,
            @Min(0) @Max(100_000) Integer orgCount,
            @Size(max = 40) String appVersion,
            @Size(max = 20) String mode) {}

    /** {@code POST /deactivate}. */
    public record DeactivateRequest(
            @NotBlank(message = "is required") @Size(max = 64) String licenseKey,
            @NotBlank(message = "is required")
            @Pattern(regexp = "[0-9a-fA-F]{64}", message = "must be 64 hexadecimal characters") String fingerprint) {}

    /**
     * What activation and heartbeat return.
     *
     * @param token        the signed licence the install stores and verifies offline
     * @param kid          which signing key it was signed with
     * @param refreshAfter when the install should check in again
     * @param expiresAt    when the token stops verifying (the end of grace, not of the term)
     * @param status       the licence's server-side status, for the install's own logs
     */
    public record TokenResponse(String token, String kid, Instant refreshAfter, Instant expiresAt, String status) {}
}
