package com.infomedia.abacox.telephonypricing.config;

import com.infomedia.abacox.telephonypricing.multitenancy.TenantContext;
import com.infomedia.abacox.telephonypricing.multitenancy.TenantFilter;
import com.infomedia.abacox.telephonypricing.security.cache.RolePermissionCache;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.util.AntPathMatcher;

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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers(publicPaths()).permitAll()
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
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private String[] publicPaths() {
        return new String[] { "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error", "/api/module/*",
                "/api/health/**", "/websocket/module", "/api/cdr/process", "/api/cdr/test" };
    }

    public boolean isPublicPath(String path) {
        AntPathMatcher matcher = new AntPathMatcher();
        for (String url : publicPaths()) {
            if (matcher.match(url, path)) {
                return true;
            }
        }
        return false;
    }
}
