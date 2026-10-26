package com.inventoryerp.license.web;

import com.inventoryerp.license.application.AdminAudits;
import com.inventoryerp.license.application.AdminTokens;
import com.inventoryerp.license.application.SigningKeyService;
import com.inventoryerp.license.domain.AdminRole;
import com.inventoryerp.license.domain.SigningKeyRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing keys. Nothing here ever returns private material — the only thing that leaves is the
 * public set, in the form an ERP release pins.
 *
 * <p>Rotation is {@code owner} only and is safe to do at any time: tokens already in the field keep
 * verifying against the retired key, which stays published until they have all expired.
 */
@RestController
@RequestMapping("/admin/keys")
@RequireAdmin(AdminRole.OWNER)
public class AdminKeyController {

    private final SigningKeyService signingKeys;
    private final SigningKeyRepository keys;
    private final AdminAudits audits;

    public AdminKeyController(SigningKeyService signingKeys, SigningKeyRepository keys, AdminAudits audits) {
        this.signingKeys = signingKeys;
        this.keys = keys;
        this.audits = audits;
    }

    @GetMapping
    public List<AdminDtos.SigningKeyResponse> list() {
        return keys.findAllByOrderByActiveFromDesc().stream()
                .map(key -> new AdminDtos.SigningKeyResponse(key.getKid(), key.getAlgorithm(),
                        key.isCurrent() ? "current" : "retired", key.getActiveFrom(), key.getRetiredAt()))
                .toList();
    }

    /**
     * The file an ERP release ships as {@code license-keys.json}, so cutting a release never means
     * copying key material out of the database by hand.
     */
    @GetMapping("/pinned")
    public ResponseEntity<String> pinned() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("license-keys.json").build().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(signingKeys.pinnedKeysJson());
    }

    @PostMapping("/rotate")
    public AdminDtos.SigningKeyResponse rotate(AdminTokens.Principal actor, HttpServletRequest request) {
        var created = signingKeys.rotate(actor.email());
        audits.record(actor, "key.rotate", created.getKid(), ClientRateLimitInterceptor.clientIp(request),
                Map.of("kid", created.getKid()));
        return new AdminDtos.SigningKeyResponse(created.getKid(), created.getAlgorithm(), "current",
                created.getActiveFrom(), null);
    }
}
