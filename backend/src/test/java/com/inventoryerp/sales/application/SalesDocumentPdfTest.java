package com.inventoryerp.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/** The customer-document PDF prints an optional Image column with a thumbnail per line. */
class SalesDocumentPdfTest {

    static byte[] png() throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 64, 48);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static List<Map<String, Object>> lines(byte[] picture) {
        Map<String, Object> withPicture = new HashMap<>(Map.of("description", "Granite slab", "quantity",
                new BigDecimal("2"), "unitPrice", new BigDecimal("100"), "discountPercent", BigDecimal.ZERO, "total",
                new BigDecimal("200")));
        withPicture.put("imageBytes", picture);
        Map<String, Object> damaged = new HashMap<>(Map.of("description", "Kerb", "quantity", BigDecimal.ONE,
                "unitPrice", new BigDecimal("50"), "discountPercent", BigDecimal.ZERO, "total", new BigDecimal("50")));
        damaged.put("imageBytes", new byte[] {1, 2, 3});
        Map<String, Object> none = new HashMap<>(Map.of("description", "Transport", "quantity", BigDecimal.ONE,
                "unitPrice", new BigDecimal("10"), "total", new BigDecimal("10")));
        return List.of(withPicture, damaged, none);
    }

    static byte[] render(byte[] picture, boolean images) {
        return SalesDocumentPdf.render("Acme", "QUOTATION", "Q-1", List.of("Customer: Franc"), lines(picture), "UGX",
                List.of(new SalesDocumentPdf.Total("Total", new BigDecimal("260"))), List.of(), images);
    }

    @Test
    void imageColumnCarriesThumbnailsAndSkipsUnreadablePictures() throws Exception {
        byte[] picture = png();
        byte[] with = render(picture, true);
        byte[] without = render(picture, false);

        PdfReader r = new PdfReader(with);
        String text = new PdfTextExtractor(r).getTextFromPage(1);
        assertThat(text).contains("Image", "Description", "Granite slab", "Kerb", "Transport", "200.00");
        assertThat(new String(r.getPageContent(1), java.nio.charset.StandardCharsets.ISO_8859_1)).contains(" Do");
        r.close();

        PdfReader plain = new PdfReader(without);
        assertThat(new PdfTextExtractor(plain).getTextFromPage(1)).contains("Description", "Granite slab")
                .doesNotContain("Image");
        assertThat(new String(plain.getPageContent(1), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(" Do");
        plain.close();
        assertThat(with.length).isGreaterThan(without.length);
    }
}
