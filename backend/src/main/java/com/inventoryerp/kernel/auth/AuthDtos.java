package com.inventoryerp.kernel.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request bodies of {@code /auth/*} (class-validator rules mirrored; messages as class-validator renders them). */
public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank(message = "should not be empty") String organizationCode,
            @NotNull(message = "must be an email") @Email(message = "must be an email") String email,
            @NotBlank(message = "should not be empty") String password) {}

    public record MfaLoginRequest(
            @NotNull(message = "must be a string") String mfaToken,
            @NotNull(message = "must be a string") String code) {}

    public record MfaCodeRequest(@NotNull(message = "must be a string") String code) {}

    public record RefreshRequest(@NotBlank(message = "should not be empty") String refreshToken) {}

    public record ForgotPasswordRequest(
            @NotNull(message = "must be an email") @Email(message = "must be an email") String email,
            @NotNull(message = "must be a string") String organizationCode) {}

    public record ResetPasswordRequest(
            @NotNull(message = "must be a string") String token,
            @NotNull(message = "must be a string")
            @Size(min = 8, message = "must be longer than or equal to 8 characters") String newPassword) {}

    public record ChangePasswordRequest(
            @NotNull(message = "must be a string") String currentPassword,
            @NotNull(message = "must be a string")
            @Size(min = 8, message = "must be longer than or equal to 8 characters") String newPassword) {}
}
