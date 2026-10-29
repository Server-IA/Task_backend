package com.taskflow.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Lee el header {@code Authorization: Bearer <token>} y, si el token es válido, autentica la petición. Un token
 * emitido antes del último cambio de contraseña, o de una cuenta desactivada o eliminada, no vale
 * ({@link CredentialsGuard}).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Atributo que indica al entry point que el token venía pero no era válido. */
    public static final String INVALID_TOKEN_ATTRIBUTE = "taskflow.invalidToken";

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final CredentialsGuard credentialsGuard;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            Optional<UserPrincipal> principal = jwtService.parse(token)
                    .filter(parsed -> credentialsGuard.accepts(parsed.principal().getId(), parsed.issuedAt()))
                    .map(AccessToken::principal);
            if (principal.isPresent()) {
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal.get(), null, principal.get().getAuthorities());
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                request.setAttribute(INVALID_TOKEN_ATTRIBUTE, true);
            }
        }
        chain.doFilter(request, response);
    }
}
