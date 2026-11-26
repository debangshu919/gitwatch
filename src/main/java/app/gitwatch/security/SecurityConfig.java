package app.gitwatch.security;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiResponses;
import app.gitwatch.config.AppProperties;
import app.gitwatch.service.RedisRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    public JwtDecoder jwtDecoder(JwtTokens tokens) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(tokens.key())
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> typ = new JwtClaimValidator<>("typ", JwtTokens.ACCESS::equals);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), typ));
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            RedisRateLimiter limiter,
            AppProperties properties) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/register", "/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/auth/github", "/v1/auth/github/callback").permitAll()
                        .requestMatchers("/v1/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(SecurityConfig::bearer)
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint((request, response, ex) -> unauthorized(response)))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authEx) -> unauthorized(response)))
                .addFilterAfter(new RateLimitFilter(limiter, properties), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private static String bearer(HttpServletRequest request) {
        if (RateLimitFilter.publicAuth(request) || "/health".equals(request.getRequestURI())) {
            return null;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static void unauthorized(HttpServletResponse response) throws java.io.IOException {
        ApiResponses.write(response, 401, ApiCodes.UNAUTHORIZED, "Authentication required");
    }
}
