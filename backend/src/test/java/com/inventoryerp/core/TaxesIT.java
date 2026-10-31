package com.inventoryerp.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** FIN-3: the {@code /taxes} configuration routes, which only the authorization matrix touched before. */
class TaxesIT extends ApiTestSupport {

    @Test
    @DisplayName("create, read, search, update and delete a tax rate; another organization never sees it")
    void crud() {
        String name = "VAT " + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = ok(201, "POST", "/taxes", Map.of("name", name, "rate", 18, "vatCategory", "standard"));
        String id = created.get("id").asString();
        assertThat(created.get("name").asString()).isEqualTo(name);

        JsonNode found = ok(200, "GET", "/taxes/" + id, null);
        assertThat(found.get("vatCategory").asString()).isEqualTo("standard");
        assertThat(ok(200, "GET", "/taxes?search=" + name.replace(" ", "%20"), null).get("data")).hasSize(1);

        JsonNode updated = ok(200, "PATCH", "/taxes/" + id, Map.of("rate", 16, "vatCategory", "zero_rated"));
        assertThat(Double.parseDouble(updated.get("rate").asString())).isEqualTo(16.0);
        assertThat(updated.get("vatCategory").asString()).isEqualTo("zero_rated");

        assertThat(call("GET", "/taxes?search=" + name.replace(" ", "%20"), null,
                otherToken).body().get("data")).isEmpty();

        assertThat(as("DELETE", "/taxes/" + id, null).status()).isEqualTo(204);
        assertThat(ok(200, "GET", "/taxes?search=" + name.replace(" ", "%20"), null).get("data")).isEmpty();
    }

    @Test
    @DisplayName("a VAT category outside the list is refused")
    void validation() {
        Res bad = as("POST", "/taxes", Map.of("name", "Bad", "rate", 1, "vatCategory", "nonsense"));
        assertThat(bad.status()).isEqualTo(400);
    }
}
