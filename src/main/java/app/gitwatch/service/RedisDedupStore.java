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
    public boolean claim(String owner, String repo, String eventType, int number) {
        Boolean stored = redis.opsForValue().setIfAbsent(DedupKeys.key(owner, repo, eventType, number), "1", DedupKeys.TTL);
        return Boolean.TRUE.equals(stored);
    }

    @Override
    public void release(String owner, String repo, String eventType, int number) {
        redis.delete(DedupKeys.key(owner, repo, eventType, number));
    }
}
