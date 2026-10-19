package com.inventoryerp.kernel.security;

import com.inventoryerp.kernel.web.GlobalExceptionHandler;
import com.inventoryerp.kernel.web.JsonResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * READ_ONLY_MODE guard (port of {@code read-only.middleware.ts}). On a reporting replica every mutating
 * verb is refused before it reaches a handler, except login/refresh/logout/MFA, which only touch
 * node-local session state.
 */
public class ReadOnlyModeFilter extends OncePerRequestFilter {

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern ALLOWED = Pattern.compile("^/api/v1/auth/(login|refresh|logout|mfa)");

    private final boolean enabled;
    private final JsonMapper mapper;

    public ReadOnlyModeFilter(boolean enabled, JsonMapper mapper) {
        this.enabled = enabled;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || !MUTATING.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        if (ALLOWED.matcher(path).find()) {
            chain.doFilter(request, response);
            return;
        }
        JsonResponses.write(mapper, response, 403, GlobalExceptionHandler.nestBody(HttpStatus.FORBIDDEN,
                "This server is a read-only reporting replica. Sales and edits must be made on the cafe POS server."));
    }
}
