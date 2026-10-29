package com.payshield.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payshield.config.CorrelationIdFilter;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ACCESS_COOKIE_NAME = "ps_access_token";
    public static final String REFRESH_COOKIE_NAME = "ps_refresh_token";
    public static final String CSRF_COOKIE_NAME = "ps_csrf_token";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";

    private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final JwtTokenService jwtTokenService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, ObjectMapper objectMapper) {
        this.jwtTokenService = jwtTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = null;
        boolean fromCookie = false;

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7).trim();
        } else {
            token = extractCookie(request, ACCESS_COOKIE_NAME);
            if (token != null && !token.isBlank()) {
                fromCookie = true;
            }
        }

        if (token != null && !token.isBlank()) {
            try {
                Claims claims = jwtTokenService.parseAndValidateAccessToken(token);
                AuthenticatedUser principal = jwtTokenService.toAuthenticatedUser(claims, fromCookie);

                // Enforce double-submit CSRF protection on state-changing requests authenticated via cookies
                if (fromCookie && STATE_CHANGING_METHODS.contains(request.getMethod().toUpperCase())) {
                    String csrfCookie = extractCookie(request, CSRF_COOKIE_NAME);
                    String csrfHeader = request.getHeader(CSRF_HEADER_NAME);
                    if (csrfCookie == null || csrfHeader == null || !csrfCookie.equals(csrfHeader)) {
                        writeError(response, request.getRequestURI(), HttpStatus.FORBIDDEN,
                                "CSRF_TOKEN_INVALID", "Missing or invalid X-CSRF-Token header for cookie-authenticated request.");
                        return;
                    }
                }

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException ex) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    public static String extractCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeError(HttpServletResponse response,
                            String path,
                            HttpStatus status,
                            String errorCode,
                            String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("errorCode", errorCode);
        body.put("message", message);
        body.put("path", path);
        body.put("correlationId", CorrelationIdFilter.currentCorrelationId());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
