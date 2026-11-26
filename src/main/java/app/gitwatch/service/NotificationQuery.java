package app.gitwatch.service;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiEnvelopes.NotificationView;
import app.gitwatch.api.ApiEnvelopes.PageResponse;
import app.gitwatch.api.ApiEnvelopes.Pagination;
import app.gitwatch.api.ApiException;
import app.gitwatch.model.NotificationLog;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class NotificationQuery {

    private final MongoTemplate mongo;

    public NotificationQuery(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public PageResponse<NotificationView> list(String userId, Integer limit, Integer offset, String status) {
        int pageLimit = limit == null ? 20 : limit;
        int pageOffset = offset == null ? 0 : offset;
        if (pageLimit < 1 || pageLimit > 100) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "limit must be between 1 and 100");
        }
        if (pageOffset < 0) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "offset must be zero or greater");
        }
        if (status != null && !status.isBlank()
                && !NotificationLog.SUCCESS.equals(status) && !NotificationLog.FAILED.equals(status)) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "status must be success or failed");
        }
        Criteria criteria = Criteria.where("user_id").is(userId);
        if (status != null && !status.isBlank()) {
            criteria = criteria.and("status").is(status);
        }
        Query query = new Query(criteria);
        long total = mongo.count(query, NotificationLog.class);
        query.with(Sort.by(Sort.Direction.DESC, "created_at")).skip(pageOffset).limit(pageLimit);
        List<NotificationView> rows = mongo.find(query, NotificationLog.class).stream().map(NotificationQuery::view).toList();
        return new PageResponse<>(rows, new Pagination(pageLimit, pageOffset, total));
    }

    private static NotificationView view(NotificationLog log) {
        String createdAt = log.getCreatedAt() == null ? null : log.getCreatedAt().toString();
        return new NotificationView(
                log.getId(),
                log.getEventType(),
                log.getTitle(),
                log.getGithubUrl(),
                log.getStatus(),
                createdAt);
    }
}
