package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.core.application.ProductSizingService;
import com.inventoryerp.core.application.ProductSizingService.Sizing;
import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Size math of products sold by length / area / volume (ADR-S066). */
class ProductSizingServiceTest {

    static Sizing in(Object pieces, Object length, Object width, Object height) {
        return Sizing.raw(pieces, length, width, height);
    }

    @Test
    void lengthInMetres() {
        Sizing s = ProductSizingService.compute("length", "m", in(4, 3, null, null));
        assertThat(s.quantity()).isEqualByComparingTo("12");
        assertThat(s.width()).isNull();
        assertThat(ProductSizingService.label(s, "m")).isEqualTo("4 pcs × 3 m");
    }

    @Test
    void piecesDefaultToOneAndUnusedDimensionsAreDropped() {
        Sizing s = ProductSizingService.compute("length", "m", in(null, "2.5", "9", "9"));
        assertThat(s.pieces()).isEqualByComparingTo("1");
        assertThat(s.quantity()).isEqualByComparingTo("2.5");
        assertThat(s.width()).isNull();
        assertThat(s.height()).isNull();
    }

    @Test
    void areaInCentimetres() {
        Sizing s = ProductSizingService.compute("area", "cm", in(2, 240, 120, null));
        assertThat(s.quantity()).isEqualByComparingTo("5.76");
        assertThat(ProductSizingService.label(s, "cm")).isEqualTo("2 pcs × 240 × 120 cm");
    }

    @Test
    void volumeInMillimetres() {
        Sizing s = ProductSizingService.compute("volume", "mm", in(10, 1000, 500, 20));
        assertThat(s.quantity()).isEqualByComparingTo("0.1");
    }

    @Test
    void requiredAndPositiveDimensions() {
        assertThatThrownBy(() -> ProductSizingService.compute("area", "m", in(1, 2, null, null)))
                .isInstanceOf(ApiException.class).hasMessage("width is required for a product sold by size");
        assertThatThrownBy(() -> ProductSizingService.compute("length", "m", in(1, 0, null, null)))
                .isInstanceOf(ApiException.class).hasMessage("length must be greater than 0");
        assertThatThrownBy(() -> ProductSizingService.compute("length", "m", in(-1, 2, null, null)))
                .isInstanceOf(ApiException.class).hasMessage("pieces must be greater than 0");
        assertThatThrownBy(() -> Sizing.raw("x", 1, null, null)).isInstanceOf(ApiException.class)
                .hasMessage("pieces must be a number");
    }

    @Test
    void pdfPrintsTheSize() {
        Map<String, Object> l = new java.util.HashMap<>(Map.of("description", "Granite step", "quantity", 12,
                "unitPrice", 35000, "discountPercent", 0, "total", 420000, "pieces", 4, "length", 3, "sizingUnit", "m"));
        byte[] pdf = com.inventoryerp.sales.application.SalesDocumentPdf.render("Co", "QUOTATION", "Q-1", java.util.List.of(),
                java.util.List.of(l), "UGX", java.util.List.of(), java.util.List.of());
        assertThat(pdf).isNotEmpty();
    }

    @Test
    void plainLinesCarryNoSize() {
        assertThat(Sizing.of(Map.of("quantity", 3))).isNull();
        assertThat(Sizing.stored(Map.of("quantity", 3))).isNull();
        assertThat(ProductSizingService.label(null, "m")).isNull();
        assertThat(Sizing.columns(null)).containsOnlyKeys("pieces", "length", "width", "height");
    }
}
