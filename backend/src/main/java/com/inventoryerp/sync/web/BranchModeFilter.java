package com.inventoryerp.sync.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR-S083 O7: a branch node serves only the sync protocol to its tills (plus health). Its database holds no business
 * documents — HQ is the system of record — so every back-office route answers 503 and points users to HQ.
 */
@Component
@ConditionalOnProperty(name = "erp.sync.mode", havingValue = "branch")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BranchModeFilter extends OncePerRequestFilter {

    static final List<String> ALLOWED = List.of("/sync/pull", "/sync/push", "/sync/snapshot", "/sync/branch/");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean allowed = path.startsWith("/actuator") || path.contains("/health")
                || ALLOWED.stream().anyMatch(p -> path.contains(p));
        if (allowed) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write("{\"statusCode\":503,\"message\":\"This server is a branch relay node; use HQ for"
                + " back-office work\",\"error\":\"Service Unavailable\"}");
    }
}
