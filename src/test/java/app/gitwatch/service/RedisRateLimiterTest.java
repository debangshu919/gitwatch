package app.gitwatch.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import app.gitwatch.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRateLimiterTest {

    @Test
    void failsClosedWhenRedisCannotAnswer() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        RedisRateLimiter limiter = new RedisRateLimiter(redis, Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC));

        ApiException ex = assertThrows(ApiException.class, () -> limiter.consume("auth", "1.2.3.4", 10));
        assertEquals(503, ex.status());
        assertEquals("RATE_LIMIT_UNAVAILABLE", ex.code());
    }

    @Test
    void failsClosedWhenExpiryCannotBeSet() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(false);
        RedisRateLimiter limiter = new RedisRateLimiter(redis, Clock.systemUTC());

        ApiException ex = assertThrows(ApiException.class, () -> limiter.consume("auth", "1.2.3.4", 10));
        assertEquals(503, ex.status());
    }

    @Test
    void rejectsTheRequestPastTheLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(11L);
        RedisRateLimiter limiter = new RedisRateLimiter(redis, Clock.systemUTC());

        ApiException ex = assertThrows(ApiException.class, () -> limiter.consume("auth", "1.2.3.4", 10));
        assertEquals(429, ex.status());
        assertEquals("RATE_LIMITED", ex.code());
    }

    @Test
    void allowsARequestUnderTheLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        RedisRateLimiter limiter = new RedisRateLimiter(redis, Clock.systemUTC());

        assertDoesNotThrow(() -> limiter.consume("user", "user-1", 100));
    }
}
