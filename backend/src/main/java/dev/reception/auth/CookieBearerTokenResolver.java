package dev.reception.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Feeds the resource server from the {@code access_token} cookie instead of an
 * {@code Authorization} header.
 *
 * <p>This one class is the whole adaptation. Everything above it — the JWT decoder, the authority
 * mapping, method security — is stock resource-server configuration, which is what keeps
 * ADR-0001's promise that swapping in an external issuer later changes no controller.
 *
 * <p>The header is deliberately <em>not</em> accepted as a fallback: a surface that takes a token
 * from either place is a surface where a cross-site request can supply one.
 */
@Component
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private final AuthCookies cookies;

    public CookieBearerTokenResolver(AuthCookies cookies) {
        this.cookies = cookies;
    }

    @Override
    public String resolve(HttpServletRequest request) {
        return cookies.read(request, AuthCookies.ACCESS_TOKEN).orElse(null);
    }
}
