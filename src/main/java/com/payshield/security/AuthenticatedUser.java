package com.payshield.security;

import com.payshield.domain.Role;

import java.util.UUID;

public record AuthenticatedUser(
        UUID userId,
        String email,
        String fullName,
        Role role,
        boolean cookieAuthenticated
) {
}
