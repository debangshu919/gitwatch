package app.gitwatch.notifier;

import app.gitwatch.config.AppProperties;
import app.gitwatch.model.NotificationLog;
import app.gitwatch.repository.NotificationLogRepository;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class MongoNotificationLogStore implements NotificationLogStore {

    private final NotificationLogRepository repository;
    private final MongoTemplate mongoTemplate;
    private final int ttlDays;
    private final AtomicBoolean indexReady = new AtomicBoolean(false);

    public MongoNotificationLogStore(
            NotificationLogRepository repository, MongoTemplate mongoTemplate, AppProperties properties) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.ttlDays = properties.getMongo().getLogTtlDays();
    }

    @Override
    public void append(NotificationLog log) {
        ensureTtl();
        repository.save(log);
    }

    private void ensureTtl() {
        if (!indexReady.compareAndSet(false, true)) {
            return;
        }
        mongoTemplate
                .indexOps(NotificationLog.class)
                .createIndex(new Index()
                        .on("created_at", Sort.Direction.ASC)
                        .expire(Duration.ofDays(ttlDays))
                        .named("notification_logs_ttl"));
    }
}
