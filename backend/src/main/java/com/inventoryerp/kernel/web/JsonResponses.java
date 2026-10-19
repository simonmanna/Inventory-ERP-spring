package com.inventoryerp.kernel.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** Writes JSON bodies from servlet filters / interceptors (outside message conversion). */
public final class JsonResponses {

    private JsonResponses() {}

    public static void write(JsonMapper mapper, HttpServletResponse response, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getOutputStream().write(mapper.writeValueAsBytes(body));
        response.flushBuffer();
    }
}
