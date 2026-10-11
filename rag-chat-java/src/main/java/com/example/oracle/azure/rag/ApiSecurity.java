package com.example.oracle.azure.rag;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class ApiSecurity extends OncePerRequestFilter {
    private final byte[] expected;
    ApiSecurity(DemoSettings settings) { expected = ("Bearer " + settings.apiToken()).getBytes(StandardCharsets.UTF_8); }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        res.setHeader("Cache-Control", "no-store");
        if (req.getRequestURI().startsWith("/api/")) {
            String auth = req.getHeader("Authorization");
            if (auth == null || !MessageDigest.isEqual(expected, auth.getBytes(StandardCharsets.UTF_8))) {
                res.sendError(401); return;
            }
        }
        chain.doFilter(req, res);
    }
}
