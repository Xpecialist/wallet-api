package com.example.wallet.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** Writes the project's JSON error shape ({@code timestamp}, {@code status}, {@code error}) from servlet filters. */
final class ApiErrors {
    private ApiErrors() {}

    /**
     * Writes an error response.
     *
     * @param response the response
     * @param mapper   writes the JSON
     * @param status   the HTTP status
     * @param message  the generic message for the client
     * @throws IOException if writing fails
     */
    static void write(HttpServletResponse response, JsonMapper mapper, HttpStatus status, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", message);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
