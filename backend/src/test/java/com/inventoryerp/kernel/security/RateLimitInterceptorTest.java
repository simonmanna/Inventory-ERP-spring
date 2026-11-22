package com.inventoryerp.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.kernel.auth.AuthController;
import com.inventoryerp.shared.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/** Throttler parity: per-route, per-IP for anonymous traffic, ×5 for staff, route overrides not multiplied. */
class RateLimitInterceptorTest {

    static HandlerMethod handler(String name) throws Exception {
        for (var m : AuthController.class.getMethods()) {
            if (m.getName().equals(name)) {
                return new HandlerMethod(new Object(), m) {
                    @Override
                    public Class<?> getBeanType() {
                        return AuthController.class;
                    }
                };
            }
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    @DisplayName("login: 10 per 5 min per IP, then 429 ThrottlerException with Retry-After")
    void loginLimit() throws Exception {
        var limiter = new RateLimitInterceptor(true, 5);
        HandlerMethod login = handler("login");
        for (int i = 0; i < 10; i++) {
            var res = new MockHttpServletResponse();
            assertThat(limiter.preHandle(req("10.0.0.1", null), res, login)).isTrue();
            assertThat(res.getHeader("X-RateLimit-Limit")).isEqualTo("10");
        }
        var res = new MockHttpServletResponse();
        assertThatThrownBy(() -> limiter.preHandle(req("10.0.0.1", null), res, login))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.body()).isEqualTo("ThrottlerException: Too Many Requests"));
        assertThat(res.getHeader("Retry-After")).isEqualTo("300");
        assertThat(limiter.preHandle(req("10.0.0.2", null), new MockHttpServletResponse(), login)).isTrue();
    }

    @Test
    @DisplayName("staff get 5x the global default (500 / 60 s) keyed by user")
    void staffMultiplier() throws Exception {
        var limiter = new RateLimitInterceptor(true, 5);
        HandlerMethod me = handler("me");
        var res = new MockHttpServletResponse();
        limiter.preHandle(req("10.0.0.1", new AuthUser("u1", "o1", "e")), res, me);
        assertThat(res.getHeader("X-RateLimit-Limit")).isEqualTo("500");
        assertThat(res.getHeader("X-RateLimit-Limit-burst")).isEqualTo("3000");
        var anon = new MockHttpServletResponse();
        limiter.preHandle(req("10.0.0.1", null), anon, me);
        assertThat(anon.getHeader("X-RateLimit-Limit")).isEqualTo("100");
    }

    /** A public route keyed per form (CRM production-readiness P0-5). */
    static class PublicForm {
        @Throttle(limit = 2, ttlMs = 60_000, perPathVariable = "key")
        public void submit() {}
    }

    @Test
    @DisplayName("perPathVariable: an anonymous counter is per client address AND per form key")
    void perPathVariable() throws Exception {
        var limiter = new RateLimitInterceptor(true, 5);
        HandlerMethod submit = new HandlerMethod(new PublicForm(), PublicForm.class.getMethod("submit"));
        for (int i = 0; i < 2; i++) {
            assertThat(limiter.preHandle(form("10.0.0.9", "lf_a"), new MockHttpServletResponse(), submit)).isTrue();
        }
        assertThatThrownBy(() -> limiter.preHandle(form("10.0.0.9", "lf_a"), new MockHttpServletResponse(), submit))
                .isInstanceOf(ApiException.class);
        // the same visitor on another form, and another visitor on the same form, still have their own budget
        assertThat(limiter.preHandle(form("10.0.0.9", "lf_b"), new MockHttpServletResponse(), submit)).isTrue();
        assertThat(limiter.preHandle(form("10.0.0.10", "lf_a"), new MockHttpServletResponse(), submit)).isTrue();
    }

    private static MockHttpServletRequest form(String ip, String key) {
        var r = req(ip, null);
        r.setAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                java.util.Map.of("key", key));
        return r;
    }

    private static MockHttpServletRequest req(String ip, AuthUser user) {
        var r = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        r.setRemoteAddr(ip);
        if (user != null) {
            r.setAttribute(AuthUser.ATTRIBUTE, user);
        }
        return r;
    }
}
