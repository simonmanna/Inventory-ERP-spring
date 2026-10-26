package com.inventoryerp.license.web;

import com.inventoryerp.license.application.ActivationService;
import com.inventoryerp.license.application.AdminAudits;
import com.inventoryerp.license.application.AdminTokens;
import com.inventoryerp.license.application.LicenseService;
import com.inventoryerp.license.application.RenewalReminderJob;
import com.inventoryerp.license.domain.ActivationStatus;
import com.inventoryerp.license.domain.AdminRole;
import com.inventoryerp.license.domain.LicenseActivationRepository;
import com.inventoryerp.license.domain.LicenseEventRepository;
import com.inventoryerp.license.domain.LicenseHeartbeatRepository;
import com.inventoryerp.license.shared.ApiException;
import com.inventoryerp.license.shared.PageResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Licences: the commercial surface.
 *
 * <p>Role split (see {@link AdminRole}): {@code support} reads everything and may move an
 * installation — the day-to-day of getting a customer onto new hardware — while issuing, renewing,
 * suspending, revoking and minting offline files are {@code owner} decisions.
 */
@RestController
@RequestMapping("/admin/licenses")
@RequireAdmin
public class AdminLicenseController {

    private final LicenseService licenses;
    private final ActivationService activations;
    private final LicenseActivationRepository activationRows;
    private final LicenseHeartbeatRepository heartbeats;
    private final LicenseEventRepository events;
    private final RenewalReminderJob reminders;
    private final AdminAudits audits;

    public AdminLicenseController(LicenseService licenses, ActivationService activations,
            LicenseActivationRepository activationRows, LicenseHeartbeatRepository heartbeats,
            LicenseEventRepository events, RenewalReminderJob reminders, AdminAudits audits) {
        this.licenses = licenses;
        this.activations = activations;
        this.activationRows = activationRows;
        this.heartbeats = heartbeats;
        this.events = events;
        this.reminders = reminders;
        this.audits = audits;
    }

    @GetMapping
    public PageResult<AdminDtos.LicenseResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        Instant now = Instant.now();
        var found = licenses.search(status, customerId, q, page, pageSize);
        return PageResult.of(found, license -> {
            var loaded = licenses.load(license.getId());
            return AdminDtos.LicenseResponse.of(loaded, activeInstances(license.getId()), now);
        });
    }

    @GetMapping("/{id}")
    public AdminDtos.LicenseResponse get(@PathVariable String id) {
        return AdminDtos.LicenseResponse.of(licenses.load(id), activeInstances(id), Instant.now());
    }

    @PostMapping
    @RequireAdmin(AdminRole.OWNER)
    @ResponseStatus(HttpStatus.CREATED)
    public AdminDtos.LicenseResponse create(@Valid @RequestBody AdminDtos.CreateLicenseRequest body,
            AdminTokens.Principal actor, HttpServletRequest request) {
        if (body.startsAt() != null && !Boolean.TRUE.equals(body.issueNow())) {
            throw ApiException.badRequest("startsAt only applies when issueNow is true.");
        }
        Instant start = Boolean.TRUE.equals(body.issueNow())
                ? (body.startsAt() == null ? Instant.now() : body.startsAt())
                : null;
        var loaded = licenses.create(body.customerId(), body.planCode(), body.graceDays(), body.instanceLimit(),
                body.maxDevices(), body.maxUsers(), body.maxOrganizations(), body.features(),
                Boolean.TRUE.equals(body.autoRenew()), body.notes(), start, actor.email());
        audits.record(actor, "license.create", loaded.license().getId(),
                ClientRateLimitInterceptor.clientIp(request),
                Map.of("plan", body.planCode(), "customerId", body.customerId(),
                        "issued", start != null));
        return AdminDtos.LicenseResponse.of(loaded, activeInstances(loaded.license().getId()), Instant.now());
    }

    @PutMapping("/{id}")
    @RequireAdmin(AdminRole.OWNER)
    public AdminDtos.LicenseResponse update(@PathVariable String id,
            @Valid @RequestBody AdminDtos.PatchLicenseRequest body, AdminTokens.Principal actor,
            HttpServletRequest request) {
        var loaded = licenses.update(id, body.planCode(), body.graceDays(), body.instanceLimit(),
                body.maxDevices(), body.maxUsers(), body.maxOrganizations(), body.features(),
                body.features() != null, body.autoRenew(), body.notes(), actor.email());
        audits.record(actor, "license.update", id, ClientRateLimitInterceptor.clientIp(request), Map.of());
        return AdminDtos.LicenseResponse.of(loaded, activeInstances(id), Instant.now());
    }

    @PostMapping("/{id}/issue")
    @RequireAdmin(AdminRole.OWNER)
    public AdminDtos.LicenseResponse issue(@PathVariable String id,
            @RequestBody(required = false) AdminDtos.IssueRequest body, AdminTokens.Principal actor,
            HttpServletRequest request) {
        var loaded = licenses.issue(id, body == null ? null : body.startsAt(), actor.email());
        audits.record(actor, "license.issue", id, ClientRateLimitInterceptor.clientIp(request),
                Map.of("expiresAt", String.valueOf(loaded.license().getExpiresAt())));
        return AdminDtos.LicenseResponse.of(loaded, activeInstances(id), Instant.now());
    }

    @PostMapping("/{id}/renew")
    @RequireAdmin(AdminRole.OWNER)
    public AdminDtos.LicenseResponse renew(@PathVariable String id,
            @RequestBody(required = false) AdminDtos.RenewRequest body, AdminTokens.Principal actor,
            HttpServletRequest request) {
        var loaded = licenses.renew(id, body == null ? null : body.periods(), actor.email());
        audits.record(actor, "license.renew", id, ClientRateLimitInterceptor.clientIp(request),
                Map.of("expiresAt", String.valueOf(loaded.license().getExpiresAt())));
        return AdminDtos.LicenseResponse.of(loaded, activeInstances(id), Instant.now());
    }

    @PostMapping("/{id}/suspend")
    @RequireAdmin(AdminRole.OWNER)
    public AdminDtos.LicenseResponse suspend(@PathVariable String id,
            @RequestBody(required = false) AdminDtos.ReasonRequest body, AdminTokens.Principal actor,
            HttpServletRequest request) {
        var loaded = licenses.suspend(id, body == null ? null : body.reason(), actor.email());
        audits.record(actor, "license.suspend", id, ClientRateLimitInterceptor.clientIp(request),
                Map.of("reason", body == null || body.reason() == null ? "" : body.reason()));
        return AdminDtos.LicenseResponse.of(loaded, activeInstances(id), Instant.now());
    }

    @PostMapping("/{id}/resume")
    @RequireAdmin(AdminRole.OWNER)
    public AdminDtos.LicenseResponse resume(@PathVariable String id, AdminTokens.Principal actor,
            HttpServletRequest request) {
        var loaded = licenses.resume(id, actor.email());
        audits.record(actor, "license.resume", id, ClientRateLimitInterceptor.clientIp(request), Map.of());
        return AdminDtos.LicenseResponse.of(loaded, activeInstances(id), Instant.now());
    }

    @PostMapping("/{id}/revoke")
    @RequireAdmin(AdminRole.OWNER)
    public AdminDtos.LicenseResponse revoke(@PathVariable String id,
            @RequestBody(required = false) AdminDtos.ReasonRequest body, AdminTokens.Principal actor,
            HttpServletRequest request) {
        var loaded = licenses.revoke(id, body == null ? null : body.reason(), actor.email());
        audits.record(actor, "license.revoke", id, ClientRateLimitInterceptor.clientIp(request),
                Map.of("reason", body == null || body.reason() == null ? "" : body.reason()));
        return AdminDtos.LicenseResponse.of(loaded, activeInstances(id), Instant.now());
    }

    /**
     * A {@code .lic} file for an install that cannot reach this server. Returned as an attachment so
     * it can be emailed on, and also carried in the JSON body for an operator who prefers to paste.
     */
    @PostMapping("/{id}/offline-token")
    @RequireAdmin(AdminRole.OWNER)
    public ResponseEntity<AdminDtos.OfflineTokenResponse> offlineToken(@PathVariable String id,
            @Valid @RequestBody AdminDtos.OfflineTokenRequest body, AdminTokens.Principal actor,
            HttpServletRequest request) {
        var issued = licenses.offlineToken(id, body.fingerprint(), body.instanceName(), actor.email());
        audits.record(actor, "license.offline-token", id, ClientRateLimitInterceptor.clientIp(request),
                Map.of("fingerprint", body.fingerprint()));
        String filename = issued.licenseKey().replace("-", "_") + ".lic";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AdminDtos.OfflineTokenResponse(issued.token(), issued.kid(), issued.expiresAt(),
                        issued.licenseKey(), issued.customer(), filename));
    }

    @GetMapping("/{id}/activations")
    public List<AdminDtos.ActivationResponse> activations(@PathVariable String id) {
        return licenses.activationsOf(id).stream()
                .map(activation -> AdminDtos.ActivationResponse.of(activation,
                        heartbeats.findFirstByActivationIdOrderByAtDesc(activation.getId()).orElse(null)))
                .toList();
    }

    /** Free a slot from this side — the customer's hardware died, or they cannot reach the server. */
    @PostMapping("/{id}/activations/{activationId}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateActivation(@PathVariable String id, @PathVariable String activationId,
            AdminTokens.Principal actor, HttpServletRequest request) {
        var activation = activationRows.findById(activationId)
                .filter(row -> row.getLicenseId().equals(id))
                .orElseThrow(() -> ApiException.notFound("No such activation on this licence."));
        activations.deactivate(licenses.load(id).license().getKey(), activation.getFingerprint(), actor.email());
        audits.record(actor, "license.activation.deactivate", activationId,
                ClientRateLimitInterceptor.clientIp(request), Map.of("licenseId", id));
    }

    @GetMapping("/{id}/heartbeats")
    public List<AdminDtos.HeartbeatResponse> heartbeats(@PathVariable String id,
            @RequestParam(required = false) String activationId) {
        List<String> ids = activationId != null ? List.of(activationId)
                : licenses.activationsOf(id).stream().map(a -> a.getId()).toList();
        return ids.stream()
                .flatMap(a -> heartbeats.findTop100ByActivationIdOrderByAtDesc(a).stream())
                .sorted((a, b) -> b.getAt().compareTo(a.getAt()))
                .map(AdminDtos.HeartbeatResponse::of)
                .toList();
    }

    @GetMapping("/{id}/events")
    public List<AdminDtos.EventResponse> events(@PathVariable String id) {
        return events.findTop200ByLicenseIdOrderByAtDesc(id).stream()
                .map(AdminDtos.EventResponse::of)
                .toList();
    }

    /** Send the renewal reminder now rather than waiting for its milestone. */
    @PostMapping("/{id}/remind")
    @RequireAdmin(AdminRole.OWNER)
    public Map<String, Object> remind(@PathVariable String id, AdminTokens.Principal actor,
            HttpServletRequest request) {
        boolean sent = reminders.sendNow(id);
        audits.record(actor, "license.remind", id, ClientRateLimitInterceptor.clientIp(request),
                Map.of("sent", sent));
        return Map.of("sent", sent);
    }

    private long activeInstances(String licenseId) {
        return activationRows.countByLicenseIdAndStatus(licenseId, ActivationStatus.ACTIVE.value());
    }
}
