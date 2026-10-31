package com.inventoryerp.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.documents.application.LabelRenderer.Label;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** INT-3: labels as PDF and ZPL, and ZXing symbols that hold any text. */
class LabelRendererTest {

    private static final List<Label> LABELS = List.of(new Label("Aggregate 20 mm", "Quarry 1", "ST-001"),
            new Label("Sand ^XZ", "~JA", "S^1"));

    @Test
    void pdfForBothSymbologies() {
        for (String symbology : List.of(LabelRenderer.QR, LabelRenderer.CODE128)) {
            byte[] pdf = LabelRenderer.pdf(LABELS, symbology, 2);
            assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        }
    }

    @Test
    void zplCarriesNoInjectedCommands() {
        String zpl = LabelRenderer.zpl(LABELS, LabelRenderer.CODE128, 3);
        assertThat(zpl).contains("^BCN").contains("^PQ3").contains("^FDST-001^FS");
        assertThat(zpl.split("\\^XZ", -1)).hasSize(3);
        assertThat(zpl).doesNotContain("~JA");
        assertThat(LabelRenderer.zpl(LABELS, LabelRenderer.QR, 1)).contains("^BQN");
    }

    @Test
    void code128TakesOnlyPrintableAscii() {
        assertThat(LabelRenderer.code128Matrix("Blöck").getWidth()).isGreaterThan(0);
        assertThat(LabelRenderer.qrMatrix("Blöck").getWidth()).isGreaterThan(0);
    }
}
