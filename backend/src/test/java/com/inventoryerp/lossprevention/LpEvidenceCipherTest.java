package com.inventoryerp.lossprevention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.lossprevention.application.LpEvidenceCipher;
import com.inventoryerp.lossprevention.application.LpEvidenceCipher.IntegrityException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Evidence at rest: encrypted, bound to its record, and refused when altered. */
class LpEvidenceCipherTest {

    final LpEvidenceCipher cipher = new LpEvidenceCipher("evidence-test-key", "", "jwt-secret");
    final byte[] clip = "not really a video, but bytes all the same".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("a stored clip decrypts to the same bytes and never holds them in the clear")
    void roundTrip() throws Exception {
        byte[] stored = cipher.encrypt("ev-1", clip);
        assertThat(Arrays.copyOfRange(stored, 0, 4)).isEqualTo("LPE1".getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("bytes all the same");
        assertThat(cipher.decrypt("ev-1", stored)).isEqualTo(clip);
        // A fresh IV each time: the same clip stored twice differs.
        assertThat(cipher.encrypt("ev-1", clip)).isNotEqualTo(stored);
    }

    @Test
    @DisplayName("a stored clip moved to another record, altered, or not in the format is refused")
    void refused() {
        byte[] stored = cipher.encrypt("ev-1", clip);
        assertThatThrownBy(() -> cipher.decrypt("ev-2", stored)).isInstanceOf(IntegrityException.class);
        byte[] altered = stored.clone();
        altered[altered.length - 20] ^= 0x01;
        assertThatThrownBy(() -> cipher.decrypt("ev-1", altered)).isInstanceOf(IntegrityException.class);
        assertThatThrownBy(() -> cipher.decrypt("ev-1", "garbage".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IntegrityException.class);
        LpEvidenceCipher other = new LpEvidenceCipher("another-key", "", "jwt-secret");
        assertThatThrownBy(() -> other.decrypt("ev-1", stored)).isInstanceOf(IntegrityException.class);
    }

    @Test
    @DisplayName("the key: LP_EVIDENCE_KEY, else ENCRYPTION_KEY, else the JWT secret; none refuses to start")
    void keyChoice() {
        String jwtOnly = new LpEvidenceCipher("", "", "jwt-secret").keyId();
        assertThat(new LpEvidenceCipher("", "", "jwt-secret").keyId()).isEqualTo(jwtOnly);
        assertThat(new LpEvidenceCipher("", "enc-key", "jwt-secret").keyId()).isNotEqualTo(jwtOnly)
                .isEqualTo(new LpEvidenceCipher("", "enc-key", "other").keyId());
        assertThat(cipher.keyId()).hasSize(16).isNotEqualTo(jwtOnly);
        assertThatThrownBy(() -> new LpEvidenceCipher("", "", "")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sha256 is lowercase hex")
    void sha256() {
        assertThat(LpEvidenceCipher.sha256("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
