package com.inventoryerp.communication.application.providers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** {@link ProviderHttp} over {@code java.net.http} (Node {@code fetch} in Nest). */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class JdkProviderHttp implements ProviderHttp {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public Response postJson(String url, Map<String, String> headers, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        headers.forEach(b::header);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, String> h = new LinkedHashMap<>();
        r.headers().map().forEach((k, v) -> {
            if (!v.isEmpty()) {
                h.put(k, v.getFirst());
            }
        });
        return new Response(r.statusCode(), r.body(), h);
    }
}
