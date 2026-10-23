package com.example.wallet.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Real HTTP against the embedded Tomcat on a random port (never 8080/8081), because MockMvc does not do the
 * container's error dispatch or multipart parsing: F6 ({@code GET /error}) and F7 (malformed multipart).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContainerErrorIT extends ItBase {
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String contentType, String body, String token)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) b.header("Content-Type", contentType);
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ---- F6 ---------------------------------------------------------------------------------------------------

    @Test
    void directRequestToErrorIsNeverA500() throws Exception {
        assertThat(send("GET", "/error", null, null, null).statusCode()).isEqualTo(401);
        String alice = register("alice");
        HttpResponse<String> authed = send("GET", "/error", null, null, alice);
        assertThat(authed.statusCode()).isEqualTo(403);
        assertThat(authed.body()).doesNotContain("999");
        for (String m : new String[] {"POST", "PUT", "DELETE"}) {
            assertThat(send(m, "/error", "application/json", "{}", null).statusCode()).isBetween(400, 499);
        }
    }

    @Test
    void realContainerErrorsStillReachTheClientWithTheirOwnStatus() throws Exception {
        String alice = register("alice");
        assertThat(send("GET", "/api/does-not-exist", null, null, alice).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/admin/users", null, null, alice).statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/admin/users", null, null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/transactions", null, null, "garbage").statusCode()).isEqualTo(401);
        HttpResponse<String> badBody = send("POST", "/api/auth/login", "application/json", "{not json", null);
        assertThat(badBody.statusCode()).isEqualTo(400);
        assertThat(send("DELETE", "/api/auth/login", null, null, null).statusCode()).isEqualTo(405);
    }

    // ---- F7 ---------------------------------------------------------------------------------------------------

    @Test
    void malformedOrUnexpectedContentTypesGiveCleanClientErrors() throws Exception {
        String alice = register("alice");
        String admin = adminToken();
        String[] malformed = {"--x\r\n", "", "--x\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nv", "garbage"};
        String[][] targets = {{"/api/auth/login", null}, {"/api/auth/register", null},
                {"/api/transactions/transfer", alice}, {"/api/wallets/me/withdraw", alice},
                {"/api/deposit-requests", alice}, {"/api/admin/deposit-requests/1/reject", admin},
                {"/api/admin/wallets/1/deposit", admin}};
        for (String[] t : targets) {
            for (String body : malformed) {
                HttpResponse<String> r = send("POST", t[0], "multipart/form-data; boundary=x", body, t[1]);
                assertThat(r.statusCode()).as(t[0] + " multipart " + body.length()).isEqualTo(415);
                assertThat(r.body()).doesNotContain("Exception").doesNotContain("at org.");
            }
            for (String ct : new String[] {"text/plain", "application/xml", "application/x-www-form-urlencoded",
                    "multipart/form-data", "multipart/form-data; boundary="}) {
                assertThat(send("POST", t[0], ct, "a=b", t[1]).statusCode()).as(t[0] + " " + ct).isEqualTo(415);
            }
        }
    }
}
