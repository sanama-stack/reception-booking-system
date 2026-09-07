package dev.reception.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * A browser's worth of cookie handling, for tests that need a session.
 *
 * <p>{@code TestRestTemplate} does not keep cookies between calls, and the whole point of this
 * phase is that the client never touches a token — so a test that reads a token out of a body and
 * puts it in a header would be testing something the real client does not do.
 */
public final class AuthTestClient {

    private final TestRestTemplate rest;
    private final String baseUrl;
    private final List<String> cookies = new ArrayList<>();

    public AuthTestClient(TestRestTemplate rest, int port) {
        // TestRestTemplate defaults to HttpURLConnection, which cannot send PATCH — half of the
        // phase 03 endpoints are PATCH, and it fails with "Invalid HTTP method", which reads like a
        // routing bug and is not one.
        //
        // The JDK's own client sends PATCH and, crucially, does nothing else. Putting Apache
        // HttpClient 5 on the classpath fixes PATCH too, and Spring picks it up automatically —
        // which is the trap: its default retry strategy treats 429 as retryable and honours the
        // Retry-After we set ourselves, so RateLimitTest, whose whole purpose is to collect 429s,
        // stops being a test and becomes a sleep. Choose the factory here rather than letting the
        // classpath choose it.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        this.rest = rest;
        this.baseUrl = "http://localhost:" + port + "/api";
    }

    public ResponseEntity<String> post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body);
    }

    public ResponseEntity<String> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    public ResponseEntity<String> put(String path, Object body) {
        return exchange(HttpMethod.PUT, path, body);
    }

    /** See the constructor for why this works at all. */
    public ResponseEntity<String> patch(String path, Object body) {
        return exchange(HttpMethod.PATCH, path, body);
    }

    public ResponseEntity<String> delete(String path) {
        return exchange(HttpMethod.DELETE, path, null);
    }

    /** Sends a request without the stored cookies, for asserting what an anonymous caller sees. */
    public ResponseEntity<String> getAnonymously(String path) {
        return rest.exchange(baseUrl + path, HttpMethod.GET, new HttpEntity<>(jsonHeaders()), String.class);
    }

    /** Registers an owner and keeps the resulting session. */
    public ResponseEntity<String> register(String email, String password, String businessName) {
        return post(
                "/auth/register",
                Map.of("email", email, "password", password, "fullName", "Test Owner", "businessName", businessName));
    }

    public ResponseEntity<String> login(String email, String password) {
        return post("/auth/login", Map.of("email", email, "password", password));
    }

    /** The value the browser currently holds for a cookie, or empty once it has been cleared. */
    public Optional<String> cookieValue(String name) {
        return cookies.stream()
                .filter(cookie -> cookie.startsWith(name + "="))
                .map(cookie -> cookie.substring(name.length() + 1))
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    /** Replaces a stored cookie, so a test can present a token the server has already rotated away. */
    public void overwriteCookie(String name, String value) {
        cookies.removeIf(cookie -> cookie.startsWith(name + "="));
        cookies.add(name + "=" + value);
    }

    public void forgetCookies() {
        cookies.clear();
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body) {
        HttpHeaders headers = jsonHeaders();
        if (!cookies.isEmpty()) {
            headers.add(HttpHeaders.COOKIE, String.join("; ", cookies));
        }
        ResponseEntity<String> response =
                rest.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), String.class);
        storeCookies(response.getHeaders());
        return response;
    }

    /** Mirrors a browser: a {@code Set-Cookie} with an empty value removes what was there. */
    private void storeCookies(HttpHeaders headers) {
        for (String setCookie : headers.getOrDefault(HttpHeaders.SET_COOKIE, List.of())) {
            String pair = setCookie.split(";", 2)[0];
            String name = pair.split("=", 2)[0];
            cookies.removeIf(cookie -> cookie.startsWith(name + "="));
            cookies.add(pair);
        }
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        return headers;
    }
}
