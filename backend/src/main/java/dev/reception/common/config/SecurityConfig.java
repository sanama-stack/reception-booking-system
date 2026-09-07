package dev.reception.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder security configuration.
 *
 * <p>TODO(phase-02): replace {@code permitAll} with the real rules — resource-server JWT
 * validation over the {@code access_token} cookie, method security for role checks, and the
 * tenant-resolution filter. This is an explicit placeholder rather than an accidental hole:
 * phase 01 ships no authenticated endpoint, so there is nothing yet to protect.
 *
 * <p>CSRF is disabled deliberately and stays disabled: the cookie-authenticated surface is
 * defended by {@code SameSite=Lax}, the single origin, and a required JSON content type
 * (docs/06-security.md §13). No CORS configuration exists anywhere — the single origin makes the
 * safest configuration the absent one.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                // Security headers are set once, at the edge, by Caddy (infra/caddy/Caddyfile).
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
