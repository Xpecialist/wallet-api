package com.example.wallet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Caps request bodies under {@code /api/**} ({@code app.max-body-bytes}); a bigger body gets 413 in the project's JSON
 * error shape before authentication, validation or JSON parsing. The API takes small JSON objects only, but without a
 * cap Jackson would parse up to 20 million characters per string. A declared {@code Content-Length} over the cap is
 * refused without reading anything; a body without a declared length (chunked) is read, at most cap+1 bytes, and
 * refused as soon as it is over. A body within the cap is buffered and replayed to the controller.
 * <p>
 * "Under /api" is decided with the same path semantics as Spring MVC and the authorization rules
 * ({@link PathPatternRequestMatcher}: decoded path), not by a prefix of the raw URI, so {@code /%61pi/...} is covered.
 */
public class BodyLimitFilter extends OncePerRequestFilter {
    private static final RequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    private final int maxBytes;
    private final JsonMapper mapper;

    /**
     * Creates the filter.
     *
     * @param maxBytes largest body accepted, in bytes (at least 1)
     * @param mapper   writes the JSON error body
     */
    public BodyLimitFilter(long maxBytes, JsonMapper mapper) {
        this.maxBytes = (int) Math.max(1, Math.min(maxBytes, Integer.MAX_VALUE - 8));
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !API.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            tooLarge(response);
            return;
        }
        boolean hasBody = declared > 0 || (declared < 0 && request.getHeader("Transfer-Encoding") != null);
        if (!hasBody) {
            chain.doFilter(request, response);
            return;
        }
        ReplayableRequest buffered = new ReplayableRequest(request, maxBytes + 1);
        if (buffered.prefix.length > maxBytes) {
            tooLarge(response);
            return;
        }
        chain.doFilter(buffered, response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        ApiErrors.write(response, mapper, HttpStatus.CONTENT_TOO_LARGE, "Request body too large");
    }
}
