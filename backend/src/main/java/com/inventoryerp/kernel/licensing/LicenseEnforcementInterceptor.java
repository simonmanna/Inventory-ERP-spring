package com.inventoryerp.kernel.licensing;

import com.inventoryerp.kernel.security.AccessGuardInterceptor;
import com.inventoryerp.kernel.web.ApiPathConfig;
import com.inventoryerp.shared.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The licence gate: reads always work, writes stop when the licence has run out.
 *
 * <p>Registered after the authentication and permission guards, so 401 and 403 still win and an
 * unknown route is still a 404 — a licence problem must not be what a probe learns first.
 *
 * <p><b>What stays writable in every mode, and why.</b> A licence is a commercial matter; a shop
 * mid-service is not. These are allowed even when the licence is spent:
 *
 * <ul>
 *   <li>{@code /auth/**} — somebody has to be able to sign in and see what is wrong.
 *   <li>{@code /license/**} — the way out of read-only cannot itself be blocked.
 *   <li>{@code /health/**}, {@code /actuator/**} — operations, not business.
 *   <li>{@code /sync/push} and {@code /sync/pull} — an Android terminal holding a day of offline
 *       sales must be able to drain them. Losing a shop's takings over a billing state is never
 *       acceptable, and the data is already on the till: refusing it destroys it.
 *   <li>Finishing what is already open: closing a shift, reconciling a session, taking payment for
 *       or voiding an order that exists. Starting a <em>new</em> order is blocked — that is the line
 *       between "let them finish" and "let them carry on trading unlicensed".
 *   <li>Draining the <b>web POS offline queue</b> — see {@link #isReplayOfEarlierWork}. The Android
 *       till gets this for free because {@code /sync/**} is allowed outright; the web terminal
 *       replays to the ordinary POS routes, so it has to say what it is doing.
 * </ul>
 *
 * <p>The refusal is <b>402 Payment Required</b> rather than 403: it is honest about the cause, and
 * it gives the web client one status to branch on for the renewal dialog.
 */
@Component
public class LicenseEnforcementInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LicenseEnforcementInterceptor.class);

    /** Advertises the licence state on every response, so clients can warn without polling. */
    public static final String WARNING_HEADER = "X-License-Warning";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** Path prefixes that stay writable in every mode. */
    private static final List<String> ALWAYS_ALLOWED_PREFIXES = List.of(
            "/auth/",
            "/license",
            "/health",
            "/actuator",
            "/metrics",
            "/sync/");

    /** Exact paths that let a shop finish what it had already started. */
    private static final List<String> FINISH_OPEN_WORK = List.of(
            "/pos/shifts/*/close",
            "/pos/sessions/*/reconcile",
            "/pos/orders/*/pay",
            "/pos/orders/*/void",
            "/pos/orders/*/close");

    /**
     * Header the web POS offline queue sets when replaying a sale it took while the server was
     * unreachable, carrying the moment the money actually changed hands.
     */
    public static final String RECORDED_AT_HEADER = "X-Offline-Recorded-At";

    /** Routes the offline queue replays against (see {@code web/src/features/pos/offline-queue.ts}). */
    private static final List<String> REPLAYABLE = List.of(
            "/pos/checkout",
            "/pos/operation-approval",
            "/pos/cash-operations",
            "/pos/sessions");

    private final LicenseService licenses;

    public LicenseEnforcementInterceptor(LicenseService licenses) {
        this.licenses = licenses;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        LicenseState state = licenses.current();
        if (state.mode().warrantsWarning()) {
            response.setHeader(WARNING_HEADER, state.reason());
        }

        // A feature the licence does not include is refused whatever the mode or the method: a
        // read of a module nobody paid for is still a use of it.
        RequiresFeature feature = AccessGuardInterceptor.find(method, RequiresFeature.class);
        if (feature != null && licenses.enforcing() && !state.grants(feature.value())) {
            throw refusal(LicenseMessages.featureNotLicensed(feature.value()), state);
        }

        if (state.allowsWrites() || !licenses.enforcing()) {
            return true;
        }
        if (SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        String path = pathWithinApi(request);
        if (isAllowedWhileUnlicensed(path)) {
            return true;
        }
        if (isReplayOfEarlierWork(path, request.getHeader(RECORDED_AT_HEADER), state)) {
            log.info("Allowing an offline replay of work recorded at {} on {} while licence mode is {}",
                    request.getHeader(RECORDED_AT_HEADER), path, state.mode());
            return true;
        }

        log.debug("Refusing {} {} in licence mode {}", request.getMethod(), path, state.mode());
        throw refusal(LicenseMessages.forMode(state.mode(), state.reason()), state);
    }

    /**
     * 402 with the licence attached, so one client handler can render every case: the dialog needs
     * to say which plan ran out and when, not just that something is wrong.
     */
    private ApiException refusal(String message, LicenseState state) {
        Map<String, Object> licence = new LinkedHashMap<>();
        licence.put("mode", state.mode().name());
        licence.put("reason", state.reason());
        licence.put("status", state.claims() == null ? null : state.claims().status());
        licence.put("plan", state.plan());
        licence.put("expiresAt", iso(state.expiresAt()));
        licence.put("graceUntil", iso(state.graceUntil()));
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, message, Map.of("license", licence));
    }

    /** The request path with the {@code /api/v1} prefix removed, so the lists read as the spec does. */
    static String pathWithinApi(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri.startsWith(ApiPathConfig.PREFIX) ? uri.substring(ApiPathConfig.PREFIX.length()) : uri;
    }

    static boolean isAllowedWhileUnlicensed(String path) {
        for (String prefix : ALWAYS_ALLOWED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        for (String pattern : FINISH_OPEN_WORK) {
            if (matches(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A web POS sale taken <em>before</em> writes stopped, now being drained from the browser's
     * offline queue.
     *
     * <p>The money was already taken; the only question is whether the record of it reaches the
     * books. Refusing it would destroy a real shop's takings to make a billing point, which is
     * never the right trade — the same reasoning that allows {@code /sync/**} outright for the
     * Android till.
     *
     * <p>The honest limitation: the timestamp comes from the client, so a modified client could
     * backdate one and keep selling. That is accepted deliberately. Anyone willing to patch the app
     * can already push sales through {@code /sync/push}, so this adds no attack that was not
     * already open, and the licence is enforced on everything else. What it buys is that an
     * ordinary shop never loses a day's trade to an expiry it did not notice.
     *
     * @param recordedAt the {@value #RECORDED_AT_HEADER} header, an ISO-8601 instant
     */
    static boolean isReplayOfEarlierWork(String path, String recordedAt, LicenseState state) {
        if (recordedAt == null || recordedAt.isBlank()) {
            return false;
        }
        boolean replayable = REPLAYABLE.stream().anyMatch(prefix -> path.equals(prefix)
                || path.startsWith(prefix + "/"));
        if (!replayable) {
            return false;
        }
        Instant recorded;
        try {
            recorded = Instant.parse(recordedAt.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
        // The moment writes stopped: the end of grace, or of the trial for an install that never
        // had a licence. Anything recorded after that was taken while already read-only.
        Instant stoppedAt = state.graceUntil() != null ? state.graceUntil() : state.expiresAt();
        return stoppedAt != null && recorded.isBefore(stoppedAt);
    }

    /** Segment-wise match where {@code *} stands for exactly one segment (an id). */
    private static boolean matches(String pattern, String path) {
        String[] wanted = pattern.split("/");
        String[] actual = path.split("/");
        if (wanted.length != actual.length) {
            return false;
        }
        for (int i = 0; i < wanted.length; i++) {
            if (!"*".equals(wanted[i]) && !wanted[i].equals(actual[i])) {
                return false;
            }
        }
        return true;
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
