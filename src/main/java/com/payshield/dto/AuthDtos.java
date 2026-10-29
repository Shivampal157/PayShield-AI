package com.payshield.dto;

import com.payshield.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Must be a valid email address")
            String email,

            @NotBlank(message = "Full name is required")
            @Size(min = 2, max = 120, message = "Full name must be between 2 and 120 characters")
            String fullName,

            @NotBlank(message = "Password is required")
            @Size(min = 10, max = 128, message = "Password must be between 10 and 128 characters")
            @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
            )
            String password,

            String initialCurrency
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Must be a valid email address")
            String email,

            @NotBlank(message = "Password is required")
            String password
    ) {
    }

    public record RefreshTokenRequest(
            String refreshToken
    ) {
    }

    public record PasswordResetRequest(
            @NotBlank(message = "Email is required")
            @Email(message = "Must be a valid email address")
            String email
    ) {
    }

    public record PasswordResetConfirmRequest(
            @NotBlank(message = "Reset token is required")
            String resetToken,

            @NotBlank(message = "New password is required")
            @Size(min = 10, max = 128, message = "Password must be between 10 and 128 characters")
            @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "Password must contain at least one uppercase letter, one lowercase letter, and one digit"
            )
            String newPassword
    ) {
    }

    public record UserProfileResponse(
            UUID id,
            String email,
            String fullName,
            Role role,
            Instant createdAt,
            String environment
    ) {
    }

    public record AuthSessionResponse(
            UserProfileResponse user,
            String accessToken,
            String refreshToken,
            String csrfToken,
            long expiresInSeconds
    ) {
    }
}
