package com.infomedia.abacox.telephonypricing.config;

import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantFilter;
import com.infomedia.abacox.telephonypricing.security.cache.RolePermissionCache;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Autowired
    private InternalApiKeyFilter internalApiKeyFilter;
    @Autowired
    private TenantFilter tenantFilter;
    @Autowired
    @Lazy
    private RolePermissionCache rolePermissionCache;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
    private String jwkSetUri;

    @Value("${spring.application.name}")
    private String applicationName;

    /** Usernames that are allowed to bypass audience checks (cross-tenant superusers). */
    private static final java.util.Set<String> AUDIENCE_BYPASS_USERS = java.util.Set.of("abacox-admin", "system");

    /**
     * Replaces Spring Boot's auto-configured JwtDecoder with one that treats
     * {@code audience == <this module>} OR {@code username == "abacox-admin"}
     * as valid. This lets the cross-tenant superuser reach any module even
     * when the tenant's license has produced a token with no {@code aud}
     * claim (expired / suspended / revoked / no license).
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> audienceOrSuperuser = jwt -> {
            if (AUDIENCE_BYPASS_USERS.contains(jwt.getClaimAsString("username"))) {
                return OAuth2TokenValidatorResult.success();
            }
            List<String> aud = jwt.getAudience();
            if (aud != null && aud.contains(applicationName)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The required audience '" + applicationName + "' is missing",
                    null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), audienceOrSuperuser));
        return decoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(request -> {
                    var corsConfig = new CorsConfiguration();
                    corsConfig.setAllowedOrigins(List.of("*"));
                    corsConfig.setAllowedMethods(List.of("*"));
                    corsConfig.setAllowedHeaders(List.of("*"));
                    return corsConfig;
                }))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .requestMatchers(publicPaths()).permitAll()
                        .anyRequest().authenticated())
                .sessionManagement(manager -> manager.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(internalApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /**
     * Custom JWT → Authentication converter. Reads the {@code tenant} claim
     * into the multi-tenant thread local so downstream DB queries target the
     * right schema, then uses the {@code rolenames} claim to look up the
     * effective permissions for every role the user holds (each role is
     * cached independently, with event-based invalidation) and unions them
     * into Spring Security authorities.
     * <p>
     * This is deliberately a private helper — not a {@code @Bean} — so
     * Spring Boot's {@code ApplicationConversionService} doesn't try to
     * register it as an MVC formatter (which fails because lambdas
     * lose their generic type parameters at runtime).
     */
    private Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            String tenant = jwt.getClaimAsString("tenant");
            if (tenant != null && !tenant.isBlank()) {
                TenantContext.setTenant(tenant);
            }

            List<String> rolenames = jwt.getClaimAsStringList("rolenames");
            Collection<GrantedAuthority> authorities = loadAuthorities(rolenames);

            String username = jwt.getClaimAsString("username");
            return new JwtAuthenticationToken(jwt, authorities, username);
        };
    }

    private Collection<GrantedAuthority> loadAuthorities(List<String> rolenames) {
        if (rolenames == null || rolenames.isEmpty()) {
            return List.of();
        }
        Set<String> union = new HashSet<>();
        for (String rolename : rolenames) {
            if (rolename == null || rolename.isBlank()) {
                continue;
            }
            union.addAll(rolePermissionCache.get(rolename));
        }
        return union.stream()
                .map(SimpleGrantedAuthority::new)
                .map(GrantedAuthority.class::cast)
                .toList();
    }

    @Bean
    public CorsFilter corsFilter() {
        var corsConfig = new CorsConfiguration();
        corsConfig.setAllowedOrigins(List.of("*"));
        corsConfig.setAllowedMethods(List.of("*"));
        corsConfig.setAllowedHeaders(List.of("*"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);
        return new CorsFilter(source);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private String[] publicPaths() {
        return new String[] { "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error",
                "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info",
                "/websocket/module", "/api/cdr/process", "/api/cdr/test" };
    }
}
