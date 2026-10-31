package com.inventoryerp.invoicing.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The registered {@link MobileMoneyProvider} beans by code, the configured default and the callback secret (INT-1):
 * {@code MOBILE_MONEY_PROVIDER}, {@code MOBILE_MONEY_CALLBACK_SECRET}. Without a secret every callback is refused.
 */
@Component
public class MobileMoneyProviders {

    private final Map<String, MobileMoneyProvider> byCode = new LinkedHashMap<>();
    private final String configured;
    private final String callbackSecret;

    public MobileMoneyProviders(List<MobileMoneyProvider> providers,
            @Value("${app.mobile-money.provider:${MOBILE_MONEY_PROVIDER:mock}}") String configured,
            @Value("${app.mobile-money.callback-secret:${MOBILE_MONEY_CALLBACK_SECRET:}}") String callbackSecret) {
        providers.forEach(p -> byCode.put(p.code(), p));
        this.configured = configured == null || configured.isBlank() ? MockMobileMoneyProvider.CODE : configured.trim();
        this.callbackSecret = callbackSecret;
    }

    public Optional<MobileMoneyProvider> get(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    /** The provider new collections use. */
    public String configured() {
        return configured;
    }

    public List<String> available() {
        return List.copyOf(byCode.keySet());
    }

    public boolean callbackSecretConfigured() {
        return callbackSecret != null && !callbackSecret.isBlank();
    }

    /** Constant-time check of {@code signature}: hex HMAC-SHA256 of {@code body} with the callback secret. */
    public boolean validSignature(String body, String signature) {
        if (!callbackSecretConfigured() || signature == null || body == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(callbackSecret, body).getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
