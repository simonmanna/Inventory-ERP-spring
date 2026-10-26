package com.inventoryerp.license.web;

import com.inventoryerp.license.application.ActivationService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three routes an ERP install calls. No account: the licence key (or a token this server
 * signed) is the credential, which is why the whole controller is throttled per IP.
 */
@RestController
@RateLimited
public class ActivationController {

    private final ActivationService activations;

    public ActivationController(ActivationService activations) {
        this.activations = activations;
    }

    @PostMapping("/activate")
    public ClientDtos.TokenResponse activate(@Valid @RequestBody ClientDtos.ActivateRequest body,
            HttpServletRequest request) {
        ActivationService.Issued issued = activations.activate(body.licenseKey(), body.fingerprint(),
                body.instanceName(), body.hostname(), body.appVersion(),
                ClientRateLimitInterceptor.clientIp(request));
        return response(issued);
    }

    @PostMapping("/heartbeat")
    public ClientDtos.TokenResponse heartbeat(@Valid @RequestBody ClientDtos.HeartbeatRequest body,
            HttpServletRequest request) {
        ActivationService.Issued issued = activations.heartbeat(body.token(),
                new ActivationService.Report(body.deviceCount(), body.userCount(), body.orgCount(),
                        body.appVersion(), body.mode()),
                ClientRateLimitInterceptor.clientIp(request));
        return response(issued);
    }

    @PostMapping("/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@Valid @RequestBody ClientDtos.DeactivateRequest body) {
        activations.deactivate(body.licenseKey(), body.fingerprint(), "install");
    }

    private static ClientDtos.TokenResponse response(ActivationService.Issued issued) {
        return new ClientDtos.TokenResponse(issued.token(), issued.kid(), issued.refreshAfter(),
                issued.expiresAt(), issued.status());
    }
}
