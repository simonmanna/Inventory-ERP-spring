package com.inventoryerp.license.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Signing keys are only as safe as this wrapper, so its failure modes are explicit. */
class SecretBoxTest {

    private final SecretBox box = new SecretBox("a passphrase that is long enough");

    @Test
    void sealsAndOpensARoundTrip() {
        String sealed = box.seal("the private key");
        assertThat(sealed).startsWith("v1.").doesNotContain("the private key");
        assertThat(box.open(sealed)).isEqualTo("the private key");
    }

    @Test
    void sealingTwiceProducesDifferentCiphertext() {
        // A fresh salt and IV each time: identical plaintexts must not be recognisable as such.
        assertThat(box.seal("same")).isNotEqualTo(box.seal("same"));
    }

    @Test
    void refusesToOpenUnderTheWrongPassphrase() {
        String sealed = box.seal("secret");
        assertThatThrownBy(() -> new SecretBox("a different passphrase entirely").open(sealed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong passphrase or corrupt value");
    }

    @Test
    void refusesToOpenTamperedCiphertext() {
        String sealed = box.seal("secret");
        String tampered = sealed.substring(0, sealed.length() - 4) + "AAAA";
        assertThatThrownBy(() -> box.open(tampered)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesAnUnrecognisedFormat() {
        assertThatThrownBy(() -> box.open("v2.a.b.c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> box.open("nonsense")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> box.open(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesToExistWithoutAPassphrase() {
        assertThatThrownBy(() -> new SecretBox("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretBox(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void comparesConstantTime() {
        assertThat(SecretBox.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(SecretBox.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(SecretBox.constantTimeEquals(null, "abc")).isFalse();
        assertThat(SecretBox.constantTimeEquals("abc", null)).isFalse();
    }
}
