package dev.reception.common.config;

import dev.reception.auth.CookieBearerTokenResolver;
import dev.reception.auth.JwtService;
import dev.reception.auth.ProblemAccessDeniedHandler;
import dev.reception.auth.ProblemAuthenticationEntryPoint;
import dev.reception.business.BusinessRepository;
import dev.reception.common.error.ProblemJsonWriter;
import dev.reception.tenancy.SlugTenantContextFilter;
import dev.reception.tenancy.TenantContextFilter;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * The authenticated surface.
 *
 * <p>Configured as an OAuth2 <strong>resource server</strong> even though we are also the issuer
 * (ADR-0001). The token arrives in a cookie rather than a header — {@link CookieBearerTokenResolver}
 * is the whole of that adaptation — so introducing an external identity provider later replaces the
 * issuer and changes no controller.
 *
 * <p>CSRF stays disabled and that is a decision, not an omission: the cookie-authenticated surface
 * is defended by {@code SameSite=Lax}, the single origin, and a required JSON content type, which
 * together block the form-post shape CSRF tokens exist to stop (docs/06-security.md §13). No CORS
 * configuration exists anywhere — with one origin, the safest configuration is the absent one, and
 * {@code NoCorsConfigurationTest} is what keeps this line true: it probes the whole mapped surface
 * for a grant and reads {@link org.springframework.web.servlet.handler.AbstractHandlerMapping}'s
 * configuration source, so neither changing {@code cors.disable()} here nor adding a single
 * {@code @CrossOrigin} elsewhere can pass.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Cost 12 (docs/06-security.md §2). Deliberately slow: this is the one hash in the system whose
     * input a human chose, and therefore the one an attacker can guess at.
     */
    private static final int BCRYPT_COST = 12;

    private final String jwtSecret;

    public SecurityConfig(@Value("${app.security.jwt-secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CookieBearerTokenResolver bearerTokenResolver,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler,
            JwtDecoder jwtDecoder,
            BusinessRepository businesses,
            ProblemJsonWriter problems)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                // Set once at the edge by Caddy; this is the defence-in-depth copy of the one that
                // matters most if the application is ever reached directly.
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                .authorizeHttpRequests(auth -> auth
                        // The public surface is declared explicitly and exhaustively, so "what is
                        // reachable without authentication" is a list you can read rather than a
                        // property inferred from annotations.
                        .requestMatchers(
                                "/auth/register",
                                "/auth/login",
                                "/auth/refresh",
                                "/auth/logout",
                                "/health",
                                "/docs/**",
                                "/openapi/**",
                                "/swagger-ui/**",
                                // The booking page and the Manage Link. One pattern, because the
                                // whole of what it opens is one package — dev.reception.publicapi —
                                // and a reader can check that claim by listing a directory rather
                                // than by trusting this line.
                                "/public/**")
                        .permitAll()
                        // Preflight never reaches here on a single origin, but a rule that depends
                        // on that is a rule that breaks silently if it ever stops being true.
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver)
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // After authentication, so there is a principal to derive the tenant from.
                .addFilterAfter(new TenantContextFilter(), BearerTokenAuthenticationFilter.class)
                // After that one, so it runs inside it: TenantContextFilter's finally is what
                // clears the holder, and it must wrap every resolution rather than only its own.
                .addFilterAfter(new SlugTenantContextFilter(businesses, problems), TenantContextFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(BCRYPT_COST);
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey()));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        // Pinned to HS256. Without this the decoder accepts any MAC algorithm the header names,
        // and algorithm confusion is the classic way a JWT verifier is talked out of verifying.
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Maps the token's {@code role} claim to the {@code ROLE_} authority
     * {@code @PreAuthorize("hasRole('OWNER')")} expects. Nothing else in the token becomes an
     * authority, so a future claim cannot accidentally grant one.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtService.CLAIM_ROLE);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private SecretKeySpec secretKey() {
        // The JCA name, not the JWS one: this is a javax.crypto key, and Nimbus reads the
        // algorithm off it.
        return new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
