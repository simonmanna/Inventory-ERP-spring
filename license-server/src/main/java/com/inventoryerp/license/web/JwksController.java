package com.inventoryerp.license.web;

import com.inventoryerp.license.application.SigningKeyService;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public half of the signing keys, in RFC 8037 JWKS form.
 *
 * <p>Public by design and cacheable for an hour: an install uses it to learn a key id it does not
 * recognise yet, which is how a rotation reaches the field. It contains no private material, so
 * serving it widely costs nothing.
 *
 * <p>Note that this is a convenience, not the root of trust. Installs ship with the keys compiled in
 * and pin by {@code kid}; a client that cannot reach this document keeps working on the keys it has.
 */
@RestController
public class JwksController {

    private final SigningKeyService signingKeys;

    public JwksController(SigningKeyService signingKeys) {
        this.signingKeys = signingKeys;
    }

    @GetMapping("/.well-known/license-keys.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(signingKeys.jwks());
    }
}
