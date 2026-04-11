package com.infomedia.abacox.telephonypricing.config;

import com.infomedia.abacox.telephonypricing.multitenancy.TenantFilter;
import com.infomedia.abacox.telephonypricing.security.cache.RolePermissionCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
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
    private RolePermissionCache rolePermissionCache;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers(publicPaths()).permitAll()
                .anyRequest().authenticated())
                .sessionManagement(manager -> manager.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(internalApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new UsernameAuthenticationFilter(rolePermissionCache), UsernamePasswordAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    public AuthenticationManager noopAuthenticationManager() {
        return authentication -> {
            throw new AuthenticationServiceException("Authentication is disabled");
        };
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

    public static class UsernameAuthenticationFilter extends OncePerRequestFilter {

        private final RolePermissionCache rolePermissionCache;

        public UsernameAuthenticationFilter(RolePermissionCache rolePermissionCache) {
            this.rolePermissionCache = rolePermissionCache;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain)
                throws ServletException, IOException {

            String headerUsername = request.getHeader("X-Username");
            String username = "anonymousUser";

            if (headerUsername != null) {
                username = headerUsername;
            }

            if (!username.equals("anonymousUser")) {
                String rolename = request.getHeader("X-Role");
                List<SimpleGrantedAuthority> authorities = loadAuthorities(rolename);
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(username, null,
                        authorities);
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                context.setAuthentication(authToken);
                SecurityContextHolder.setContext(context);
            }

            filterChain.doFilter(request, response);
        }

        private List<SimpleGrantedAuthority> loadAuthorities(String rolename) {
            if (rolename == null || rolename.isBlank()) {
                return List.of();
            }
            Set<String> permissions = rolePermissionCache.get(rolename);
            return permissions.stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
        }
    }
}