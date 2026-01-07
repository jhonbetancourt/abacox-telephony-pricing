package com.infomedia.abacox.telephonypricing.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
@Log4j2
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Internal-Api-Key";

    @Value("${abacox.internal-api-key}")
    private String internalApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!request.getRequestURI().startsWith("/api/internal/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(API_KEY_HEADER);

        if (providedKey != null && MessageDigest.isEqual(internalApiKey.getBytes(), providedKey.getBytes())) {
            // --- START OF FIX ---
            // Key is valid, create an authentication token for the security context.
            // We'll represent the authenticated principal as a generic "internal-service".
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "system", // The principal's name
                    null,               // No credentials needed after this point
                    null                // No authorities/roles
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            // Place the token in the security context
            SecurityContextHolder.getContext().setAuthentication(authentication);
            // --- END OF FIX ---

            filterChain.doFilter(request, response); // Now, continue the chain
        } else {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid or missing Internal API Key");
        }
    }
}