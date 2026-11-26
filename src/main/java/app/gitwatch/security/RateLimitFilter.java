package app.gitwatch.security;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiException;
import app.gitwatch.api.ApiResponses;
import app.gitwatch.config.AppProperties;
import app.gitwatch.service.RedisRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {

    private final RedisRateLimiter limiter;
    private final AppProperties properties;

    public RateLimitFilter(RedisRateLimiter limiter, AppProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (publicAuth(request)) {
                limiter.consume("auth", clientIp(request), properties.getRateLimit().getAuthPerMinute());
            } else if (request.getRequestURI() != null && request.getRequestURI().startsWith("/v1/")) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null && authentication.isAuthenticated() && userId(authentication.getName())) {
                    limiter.consume("user", authentication.getName(), properties.getRateLimit().getUserPerMinute());
                }
            }
        } catch (ApiException ex) {
            ApiResponses.write(response, ex.status(), ex.code(), ex.getMessage());
            return;
        } catch (RuntimeException ex) {
            ApiResponses.write(response, 503, ApiCodes.RATE_LIMIT_UNAVAILABLE, "Rate limit store is unavailable");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean publicAuth(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("POST".equals(method) && ("/v1/auth/register".equals(path) || "/v1/auth/login".equals(path))) {
            return true;
        }
        return "GET".equals(method) && ("/v1/auth/github".equals(path) || "/v1/auth/github/callback".equals(path));
    }

    private static boolean userId(String name) {
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return false;
        }
        try {
            java.util.UUID.fromString(name);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
