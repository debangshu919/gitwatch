package app.gitwatch.service;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public void consume(String bucket, String id, int limit) {
        if (limit < 1) {
            throw unavailable();
        }
        String key = "ratelimit:" + bucket + ":" + id + ":" + clock.instant().getEpochSecond() / 60;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                throw unavailable();
            }
            if (count == 1L) {
                Boolean expired = redis.expire(key, Duration.ofMinutes(2));
                if (!Boolean.TRUE.equals(expired)) {
                    redis.delete(key);
                    throw unavailable();
                }
            }
            if (count > limit) {
                throw new ApiException(429, ApiCodes.RATE_LIMITED, "Too many requests");
            }
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(503, ApiCodes.RATE_LIMIT_UNAVAILABLE, "Rate limit store is unavailable");
    }
}
