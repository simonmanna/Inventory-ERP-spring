package com.inventoryerp.kernel.auth;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * TOTP (RFC 6238) MFA: SHA1, 6 digits, 30 s step, ±1 step window — compatible with Google
 * Authenticator / Authy / 1Password and with secrets enrolled by the Nest {@code otplib} service.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    private final String issuer;
    private final CodeVerifier verifier;

    public MfaService(@Value("${MFA_ISSUER:ERP Platform}") String issuer) {
        this.issuer = issuer;
        DefaultCodeVerifier v = new DefaultCodeVerifier(new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6),
                new SystemTimeProvider());
        v.setTimePeriod(30);
        v.setAllowedTimePeriodDiscrepancy(1);
        this.verifier = v;
    }

    /** Fresh base32 secret (160 bits). */
    public String generateSecret() {
        return new DefaultSecretGenerator(32).generate();
    }

    /** otpauth:// URI + a PNG data-URL QR code for enrollment. */
    public Map<String, String> buildEnrollmentQr(String userEmail, String secret) {
        String label = enc(issuer) + ":" + enc(userEmail);
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&period=30&digits=6&algorithm=SHA1&issuer="
                + enc(issuer);
        return Map.of("uri", uri, "qrDataUrl", qrDataUrl(uri));
    }

    /** Verify a 6-digit code against the secret. */
    public boolean verify(String secret, String code) {
        if (secret == null || secret.isEmpty() || code == null || code.isEmpty()) {
            return false;
        }
        try {
            return verifier.isValidCode(secret, code.replaceAll("\\s+", ""));
        } catch (RuntimeException err) {
            log.warn("TOTP verify failed: {}", err.toString());
            return false;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String qrDataUrl(String text) {
        try {
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 4));
            int scale = 4;
            BufferedImage img = new BufferedImage(m.getWidth() * scale, m.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    int rgb = m.get(x, y) ? 0x000000 : 0xFFFFFF;
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            img.setRGB(x * scale + dx, y * scale + dy, rgb);
                        }
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (WriterException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
