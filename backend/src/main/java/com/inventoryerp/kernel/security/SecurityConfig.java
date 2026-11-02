package com.inventoryerp.kernel.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP security baseline. Spring Security only provides CORS, security headers and statelessness;
 * identity is resolved by {@link AuthenticationFilter} and access is enforced after routing by
 * {@link AccessGuardInterceptor} so the error precedence matches Nest (unknown route 404, then 401,
 * then 403). Read-only mode runs right after authentication, like the Nest middleware.
 */
@Configuration
public class SecurityConfig {

    private static final Pattern PRIVATE_ORIGIN = Pattern.compile(
            "^https?://(localhost|127\\.0\\.0\\.1|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[01])\\.\\d+\\.\\d+)(:\\d+)?$");

    @Bean
    AuthenticationFilter authenticationFilter(JwtTokenService jwt, PosSessionCheck posSessionCheck, JdbcClient jdbc,
            JsonMapper mapper) {
        return new AuthenticationFilter(jwt, posSessionCheck, jdbc, mapper);
    }

    @Bean
    ReadOnlyModeFilter readOnlyModeFilter(@Value("${app.read-only:false}") boolean readOnly, JsonMapper mapper) {
        return new ReadOnlyModeFilter(readOnly, mapper);
    }

    /** Both filters run inside the security chain only (no duplicate servlet registration). */
    @Bean
    FilterRegistrationBean<AuthenticationFilter> authenticationFilterRegistration(AuthenticationFilter filter) {
        var reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    FilterRegistrationBean<ReadOnlyModeFilter> readOnlyModeFilterRegistration(ReadOnlyModeFilter filter) {
        var reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, AuthenticationFilter authenticationFilter,
            ReadOnlyModeFilter readOnlyModeFilter) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .securityContext(s -> s.disable())
                .anonymous(a -> a.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.sameOrigin())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .addFilterBefore(authenticationFilter, AuthorizationFilter.class)
                .addFilterAfter(readOnlyModeFilter, AuthenticationFilter.class);
        return http.build();
    }

    /**
     * CORS parity with Nest {@code main.ts}: allow-list from CORS_ORIGINS (default
     * {@code http://localhost:5173}); any private-LAN origin too when CORS_ALLOW_LAN=true, or by default
     * outside the {@code prod} profile.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.origins:}") String origins,
            @Value("${app.cors.allow-lan:}") String allowLan, Environment env) {
        List<String> allowList = origins == null || origins.isBlank()
                ? List.of("http://localhost:5173")
                : Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        boolean allowPrivateLan = "true".equals(allowLan) || (!"false".equals(allowLan) && !prod);
        CorsConfiguration cfg = new CorsConfiguration() {
            @Override
            public String checkOrigin(String origin) {
                if (origin == null) {
                    return null;
                }
                if (allowList.contains(origin) || (allowPrivateLan && PRIVATE_ORIGIN.matcher(origin).matches())) {
                    return origin;
                }
                return null;
            }
        };
        cfg.setAllowCredentials(true);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Request-Id", "Idempotency-Key",
                "X-Device-Label", "X-Pos-User", "X-Device-Token"));
        cfg.setExposedHeaders(List.of("X-Request-Id", "X-Total-Count", PosSessionCheck.POS_SESSION_HEADER));
        cfg.setMaxAge(86_400L);
        return (HttpServletRequest request) -> cfg;
    }
}
