package com.inventoryerp.kernel.licensing;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@code /license} — what this installation's licence is, and how to change it.
 *
 * <p>New surface, additive to the frozen Nest contract (ADR-S002 §7): no ported route changes.
 *
 * <p>{@code GET /license/status} is authenticated but unprivileged, because every user's sidebar and
 * banner depend on it — the same reasoning as {@code GET /settings/features}. Everything that
 * changes the licence needs {@code setting:update}.
 *
 * <p>The whole controller is exempt from the licence gate (see
 * {@link LicenseEnforcementInterceptor}): the way out of read-only cannot itself be blocked by
 * being read-only.
 */
@RestController
@RequestMapping("/license")
public class LicenseController {

    /** {@code POST /license/activate}. */
    public record ActivateRequest(@NotBlank(message = "is required") @Size(max = 64) String licenseKey) {}

    /** {@code POST /license/offline} — the contents of a {@code .lic} file. */
    public record OfflineRequest(@NotBlank(message = "is required") String token) {}

    private final LicenseService licenses;
    private final LicenseEntitlements entitlements;
    private final LicenseProperties properties;

    public LicenseController(LicenseService licenses, LicenseEntitlements entitlements,
            LicenseProperties properties) {
        this.licenses = licenses;
        this.entitlements = entitlements;
        this.properties = properties;
    }

    /** Everything the licence page and the banner need, in one call. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return describe(licenses.current(), licenses.usage());
    }

    /**
     * The string a customer sends us to get an offline licence file. Shown on the licence page with
     * a copy button; not secret, but only useful to whoever holds the licence key.
     */
    @GetMapping("/fingerprint")
    @RequirePermissions(Permissions.SETTING__READ)
    public Map<String, Object> fingerprint() {
        return Map.of("fingerprint", licenses.fingerprint());
    }

    @PostMapping("/activate")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> activate(@Valid @RequestBody ActivateRequest body) {
        LicenseState state = licenses.activate(body.licenseKey());
        return describe(state, licenses.usage());
    }

    /**
     * Apply a licence file. Accepts the token pasted as JSON, or the {@code .lic} file uploaded —
     * whichever the operator finds easier, since this is the path used when there is no network to
     * look anything up with.
     */
    @PostMapping("/offline")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> offline(@RequestBody(required = false) OfflineRequest body,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        String token = body == null ? null : body.token();
        if ((token == null || token.isBlank()) && file != null && !file.isEmpty()) {
            token = tokenFromFile(file);
        }
        LicenseState state = licenses.applyOfflineToken(token);
        return describe(state, licenses.usage());
    }

    /** Check in with the authority now, rather than waiting for the scheduled attempt. */
    @PostMapping("/refresh")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> refresh() {
        LicenseState state = licenses.refresh(true);
        entitlements.reconcile(state);
        return describe(state, licenses.usage());
    }

    /** Release this installation's slot, so the licence can be used on other hardware. */
    @PostMapping("/deactivate")
    @RequirePermissions(Permissions.SETTING__UPDATE)
    public Map<String, Object> deactivate() {
        return describe(licenses.deactivate(), licenses.usage());
    }

    /**
     * The licence as JSON. Also folded into {@code /sync/pull} so an Android terminal learns its
     * mode without a second round trip.
     */
    static Map<String, Object> describe(LicenseState state, LicenseService.Usage usage) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", state.mode().name());
        out.put("reason", state.reason());
        out.put("writable", state.allowsWrites());
        out.put("licensed", state.licensed());
        out.put("plan", state.plan());
        out.put("planName", state.claims() == null ? null : state.claims().planName());
        out.put("customer", state.customer());
        out.put("status", state.claims() == null ? null : state.claims().status());
        out.put("period", state.claims() == null ? null : state.claims().period());
        out.put("offline", state.claims() != null && state.claims().offline());
        out.put("startsAt", iso(state.claims() == null ? null : state.claims().startsAt()));
        out.put("expiresAt", iso(state.expiresAt()));
        out.put("graceUntil", iso(state.graceUntil()));
        out.put("daysRemaining", state.daysRemaining());
        out.put("features", List.copyOf(state.features()));
        out.put("limits", limits(state));
        out.put("usage", Map.of(
                "devices", usage.devices(),
                "users", usage.users(),
                "organizations", usage.organizations()));
        out.put("fingerprint", state.fingerprint());
        out.put("lastCheckAt", iso(state.lastCheckAt()));
        out.put("lastCheckError", state.lastCheckError());
        out.put("refreshAfter", iso(state.refreshAfter()));
        out.put("tamperSuspected", state.tamperSuspected());
        return out;
    }

    /** Only the caps that exist; an absent one means unlimited, which the client renders as such. */
    private static Map<String, Object> limits(LicenseState state) {
        Map<String, Object> limits = new LinkedHashMap<>();
        if (state.maxDevices() != null) {
            limits.put("devices", state.maxDevices());
        }
        if (state.maxUsers() != null) {
            limits.put("users", state.maxUsers());
        }
        if (state.maxOrganizations() != null) {
            limits.put("organizations", state.maxOrganizations());
        }
        return limits;
    }

    /**
     * A {@code .lic} file is either the bare compact token or the JSON the authority returns with a
     * {@code token} member. Accept both: an operator forwarding a file should not have to know which.
     */
    private static String tokenFromFile(MultipartFile file) {
        String contents;
        try {
            contents = new String(file.getBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw ApiException.badRequest("That file could not be read.");
        }
        if (contents.startsWith("{")) {
            Object parsed = com.inventoryerp.shared.web.EntityJson.parseJson(contents);
            if (parsed instanceof Map<?, ?> map && map.get("token") != null) {
                return String.valueOf(map.get("token"));
            }
            throw ApiException.badRequest("That file does not contain a licence.");
        }
        return contents;
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
