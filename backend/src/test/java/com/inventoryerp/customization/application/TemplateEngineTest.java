package com.inventoryerp.customization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.customization.application.TemplateEngine.TemplateException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateEngineTest {

    @Test
    void variablesAreEscapedUnlessTripleOrAmpersand() {
        Map<String, Object> ctx = Map.of("name", "<b>A & B</b>", "doc", Map.of("total", new BigDecimal("10.50")));
        assertThat(TemplateEngine.render("Hi {{name}} {{doc.total}}", ctx))
                .isEqualTo("Hi &lt;b&gt;A &amp; B&lt;/b&gt; 10.50");
        assertThat(TemplateEngine.render("{{{name}}}|{{& name}}", ctx)).isEqualTo("<b>A & B</b>|<b>A & B</b>");
        assertThat(TemplateEngine.render("[{{missing}}][{{doc.none.deeper}}]", ctx)).isEqualTo("[][]");
    }

    @Test
    void sectionsIterateListsAndResolveOuterNames() {
        Map<String, Object> ctx = Map.of("currency", "USD", "lines", List.of(Map.of("d", "Cement", "q", 2),
                Map.of("d", "Sand", "q", 5)), "tags", List.of("a", "b"));
        assertThat(TemplateEngine.render("{{#lines}}{{d}} x{{q}} {{currency}};{{/lines}}", ctx))
                .isEqualTo("Cement x2 USD;Sand x5 USD;");
        assertThat(TemplateEngine.render("{{#tags}}<{{.}}>{{/tags}} {{tags}}", ctx)).isEqualTo("<a><b> a, b");
    }

    @Test
    void invertedSectionsAndTruthiness() {
        Map<String, Object> ctx = Map.of("empty", List.of(), "flag", true, "off", false, "blank", "",
                "partner", Map.of("name", "Acme"));
        assertThat(TemplateEngine.render("{{^empty}}none{{/empty}}{{#flag}}+{{/flag}}{{#off}}x{{/off}}"
                + "{{^blank}}b{{/blank}}{{#partner}}{{name}}{{/partner}}{{! a comment }}", ctx)).isEqualTo("none+bAcme");
    }

    @Test
    void malformedTemplatesAreRefusedWithAReadableMessage() {
        assertThatThrownBy(() -> TemplateEngine.validate("{{#a}}x")).isInstanceOf(TemplateException.class)
                .hasMessage("Unclosed section {{#a}}");
        assertThatThrownBy(() -> TemplateEngine.validate("{{#a}}{{/b}}")).hasMessage(
                "Section {{#a}} is closed by {{/b}}");
        assertThatThrownBy(() -> TemplateEngine.validate("{{/a}}")).hasMessage("Unexpected closing tag {{/a}}");
        assertThatThrownBy(() -> TemplateEngine.validate("{{a")).hasMessage("Unclosed tag at position 0");
        assertThatThrownBy(() -> TemplateEngine.validate("{{> partial}}"))
                .hasMessage("Partials and delimiter changes are not supported");
        assertThatThrownBy(() -> TemplateEngine.validate("{{a b}}")).hasMessageStartingWith("Invalid tag name");
        assertThatThrownBy(() -> TemplateEngine.validate("{{#a}}".repeat(11) + "{{/a}}".repeat(11)))
                .hasMessage("Sections are nested deeper than 10 levels");
    }

    @Test
    void outputIsCapped() {
        List<Integer> many = java.util.stream.IntStream.range(0, 3000).boxed().toList();
        String block = "x".repeat(1000);
        assertThatThrownBy(() -> TemplateEngine.render("{{#n}}" + block + "{{/n}}", Map.of("n", many)))
                .hasMessageStartingWith("The rendered output is larger than");
    }
}
