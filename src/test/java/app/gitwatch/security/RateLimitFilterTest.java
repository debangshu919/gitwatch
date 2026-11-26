package app.gitwatch.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import app.gitwatch.api.ApiException;
import app.gitwatch.config.AppProperties;
import app.gitwatch.service.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    @Test
    void doesNotContinueWhenRedisCannotAnswer() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        doThrow(new ApiException(503, "RATE_LIMIT_UNAVAILABLE", "Rate limit store is unavailable"))
                .when(limiter)
                .consume(anyString(), anyString(), anyInt());
        RateLimitFilter filter = new RateLimitFilter(limiter, new AppProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
        request.setRemoteAddr("203.0.113.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("request continued without a rate limit answer");
        });

        assertEquals(503, response.getStatus());
        assertEquals(true, response.getContentAsString().contains("RATE_LIMIT_UNAVAILABLE"));
    }

    @Test
    void unexpectedRedisFailureAlsoFailsClosed() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        doThrow(new IllegalStateException("redis"))
                .when(limiter)
                .consume(anyString(), anyString(), anyInt());
        RateLimitFilter filter = new RateLimitFilter(limiter, new AppProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/register");
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("request continued without a rate limit answer");
        });
        assertEquals(503, response.getStatus());
    }
}
