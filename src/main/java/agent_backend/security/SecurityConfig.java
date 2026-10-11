package agent_backend.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain taskSecurity(HttpSecurity http) throws Exception {
        return http
                // Only Authorization: Bearer is supported; no cookie/session authentication.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${agent.security.issuer:}") String issuer,
            @Value("${agent.security.jwk-set-uri:}") String jwks,
            @Value("${agent.security.audience:}") String audience) {
        if (issuer.isBlank() && jwks.isBlank() && audience.isBlank()) {
            // Development startup remains possible, but there is no anonymous or generated-password fallback.
            return token -> { throw new BadJwtException("Identity provider is not configured"); };
        }
        if (issuer.isBlank() || jwks.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("Configure issuer, jwk-set-uri and audience together");
        }
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), requiredClaims(audience)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> requiredClaims(String audience) {
        return jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank()
                && jwt.getExpiresAt() != null && jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required claims missing", null));
    }
}
