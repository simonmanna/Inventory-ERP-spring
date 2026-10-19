package com.inventoryerp.kernel.security;

import com.inventoryerp.kernel.web.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * P0 security baseline: stateless bearer JWT (HS256, JWT_ACCESS_SECRET), public health/docs,
 * Nest-shaped 401 body. Phase P1 replaces the decoder with the full token service (access, POS
 * token via X-Pos-User, device token for /sync, SSE query tokens) and the permission guard.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    static final String[] PUBLIC = {
        "/api/v1/health", "/api/v1/health/**", "/api/v1/metrics", "/api/docs", "/api/docs/**", "/v3/api-docs/**",
        "/swagger-ui/**", "/swagger-ui.html",
        "/api/v1/auth/login", "/api/v1/auth/mfa-login", "/api/v1/auth/refresh",
        "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/menu/public/**",
    };

    @Bean
    SecurityFilterChain api(HttpSecurity http, AuthenticationEntryPoint entryPoint) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers(PUBLIC).permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${app.jwt.access-secret}") String secret) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    AuthenticationEntryPoint nestUnauthorized(JsonMapper mapper) {
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.nestBody(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        };
    }

    /** CORS parity with apps/api/src/main.ts (allow-list from CORS_ORIGINS; LAN origins in P1). */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.origins:}") List<String> origins) {
        var cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(origins.isEmpty() ? List.of("http://localhost:*") : origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Idempotency-Key", "X-Pos-User", "X-Device-Token",
                "X-Device-Label", "X-Request-Id"));
        cfg.setExposedHeaders(List.of("X-Request-Id", "X-Total-Count", "x-pos-session"));
        cfg.setMaxAge(600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
