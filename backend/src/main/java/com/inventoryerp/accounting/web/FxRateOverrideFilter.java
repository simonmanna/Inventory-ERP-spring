package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.FxRateOverride;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * FX-2 B: binds the optional {@code X-Fx-Rate-Override-Reason} header for the request, so a money route can state a
 * transaction rate outside the company's rate-policy tolerance without every request DTO gaining a field.
 */
@Component
public class FxRateOverrideFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Fx-Rate-Override-Reason";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String reason = request.getHeader(HEADER);
        if (reason == null || reason.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        IOException[] io = new IOException[1];
        ServletException[] se = new ServletException[1];
        FxRateOverride.with(reason.length() > 500 ? reason.substring(0, 500) : reason, () -> {
            try {
                chain.doFilter(request, response);
            } catch (IOException e) {
                io[0] = e;
            } catch (ServletException e) {
                se[0] = e;
            }
        });
        if (io[0] != null) {
            throw io[0];
        }
        if (se[0] != null) {
            throw se[0];
        }
    }
}
