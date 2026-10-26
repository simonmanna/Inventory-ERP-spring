package com.inventoryerp.license.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The key is read down phone lines and typed off invoices, so the codec has to be forgiving about
 * how it was written down and unforgiving about a genuine mistake.
 */
class LicenseKeyCodecTest {

    @Test
    void generatesTheDocumentedShape() {
        String key = LicenseKeyCodec.generate();
        assertThat(key).matches("ERP(-[0-9A-HJKMNP-TV-Z]{4}){4}");
        assertThat(LicenseKeyCodec.isValid(key)).isTrue();
    }

    @Test
    void generatesDistinctKeys() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            keys.add(LicenseKeyCodec.generate());
        }
        assertThat(keys).hasSize(500);
    }

    @Test
    void acceptsLowerCaseAndMissingDashes() {
        String key = LicenseKeyCodec.generate();
        String sloppy = key.toLowerCase().replace("-", "");
        assertThat(LicenseKeyCodec.isValid(sloppy)).isTrue();
        assertThat(LicenseKeyCodec.normalize(sloppy)).isEqualTo(key);
    }

    @Test
    void foldsTheVisualConfusionsTheAlphabetAvoids() {
        // The alphabet has no O, I, L or U, so a customer who wrote one meant the digit it resembles.
        String expected = LicenseKeyCodec.normalize("ERP-0000-1111-0000-ABCD");
        assertThat(LicenseKeyCodec.normalize("ERP-OOOO-IIII-OOOO-ABCD")).isEqualTo(expected);
        assertThat(LicenseKeyCodec.normalize("erp oooo llll oooo abcd")).isEqualTo(expected);
    }

    @Test
    void rejectsASingleMistypedCharacter() {
        String key = LicenseKeyCodec.generate();
        char[] chars = key.toCharArray();
        int index = 4; // first character of the payload
        chars[index] = chars[index] == '2' ? '3' : '2';
        assertThat(LicenseKeyCodec.isValid(new String(chars))).isFalse();
    }

    @Test
    void rejectsAWrongLength() {
        assertThat(LicenseKeyCodec.normalize("ERP-2345-6789-ABCD")).isNull();
        assertThat(LicenseKeyCodec.normalize("ERP-2345-6789-ABCD-0000-0000")).isNull();
    }

    @Test
    void rejectsRubbish() {
        assertThat(LicenseKeyCodec.isValid(null)).isFalse();
        assertThat(LicenseKeyCodec.isValid("")).isFalse();
        assertThat(LicenseKeyCodec.isValid("ERP")).isFalse();
        assertThat(LicenseKeyCodec.isValid("ERP-ZZZZ")).isFalse();
        assertThat(LicenseKeyCodec.isValid("not-a-licence-key-at-all")).isFalse();
        assertThat(LicenseKeyCodec.normalize("ERP-12")).isNull();
    }

    @Test
    void masksEverythingButTheLastGroup() {
        String key = LicenseKeyCodec.generate();
        String masked = LicenseKeyCodec.mask(key);
        assertThat(masked).isEqualTo("ERP-…-" + key.substring(key.length() - 4));
        assertThat(masked).doesNotContain(key.substring(4, 8));
    }

    @Test
    void masksSafelyWhenTheKeyIsNotOne() {
        assertThat(LicenseKeyCodec.mask(null)).isEqualTo("ERP-…");
        assertThat(LicenseKeyCodec.mask("garbage")).isEqualTo("ERP-…");
    }
}
