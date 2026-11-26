package app.gitwatch.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisDedupStore implements DedupStore {

    private final StringRedisTemplate redis;

    public RedisDedupStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryStart(String owner, String repo, String eventType, int number) {
        Boolean stored = redis.opsForValue().setIfAbsent(
                DedupKeys.key(owner, repo, eventType, number),
                DedupKeys.IN_FLIGHT_VALUE,
                DedupKeys.IN_FLIGHT);
        return Boolean.TRUE.equals(stored);
    }

    @Override
    public boolean isCompleted(String owner, String repo, String eventType, int number) {
        return DedupKeys.COMPLETED_VALUE.equals(redis.opsForValue().get(DedupKeys.key(owner, repo, eventType, number)));
    }

    @Override
    public void complete(String owner, String repo, String eventType, int number) {
        redis.opsForValue().set(
                DedupKeys.key(owner, repo, eventType, number),
                DedupKeys.COMPLETED_VALUE,
                DedupKeys.TTL);
    }

    @Override
    public void release(String owner, String repo, String eventType, int number) {
        String key = DedupKeys.key(owner, repo, eventType, number);
        if (DedupKeys.IN_FLIGHT_VALUE.equals(redis.opsForValue().get(key))) {
            redis.delete(key);
        }
    }
}
