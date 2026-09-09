package dev.reception.publicapi;

import java.util.List;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * A stranger.
 *
 * <p>Deliberately not {@code AuthTestClient} with the cookies cleared: this client has no cookie jar
 * at all, so there is no way for a test to accidentally prove the public flow works while carrying a
 * session it forgot to drop. Everything phase 08 claims is claimed about a caller who has never
 * signed in, and this type is what makes that structural rather than careful.
 *
 * <p>The JDK request factory for the reason {@code AuthTestClient} documents: Apache HttpClient 5,
 * if it reaches the classpath, retries {@code 429} responses and honours {@code Retry-After} — which
 * would turn the rate-limit assertions into a sleep.
 */
public final class PublicTestClient {

    private final TestRestTemplate rest;
    private final String baseUrl;

    public PublicTestClient(TestRestTemplate rest, int port) {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        this.rest = rest;
        this.baseUrl = "http://localhost:" + port + "/api";
    }

    public ResponseEntity<String> get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    public ResponseEntity<String> post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
        return rest.exchange(baseUrl + path, method, new HttpEntity<>(body, headers), String.class);
    }
}
