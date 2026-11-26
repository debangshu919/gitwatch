package app.gitwatch.service;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiEnvelopes.FiltersView;
import app.gitwatch.api.ApiEnvelopes.SubscriptionView;
import app.gitwatch.api.ApiException;
import app.gitwatch.model.Channel;
import app.gitwatch.model.EventTypes;
import app.gitwatch.model.RepoWatch;
import app.gitwatch.model.Subscription;
import app.gitwatch.model.SubscriptionFilters;
import app.gitwatch.model.User;
import app.gitwatch.repository.ChannelRepository;
import app.gitwatch.repository.RepoWatchRepository;
import app.gitwatch.repository.SubscriptionRepository;
import app.gitwatch.repository.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionApiService {

    private final SubscriptionRepository subscriptions;
    private final RepoWatchRepository watches;
    private final ChannelRepository channels;
    private final UserRepository users;

    public SubscriptionApiService(
            SubscriptionRepository subscriptions,
            RepoWatchRepository watches,
            ChannelRepository channels,
            UserRepository users) {
        this.subscriptions = subscriptions;
        this.watches = watches;
        this.channels = channels;
        this.users = users;
    }

    @Transactional
    public SubscriptionView create(User user, UUID repoId, String channelId, String eventType, List<String> labels) {
        RepoWatch watch = requireWatch(user, repoId);
        if (!watch.isActive()) {
            throw new ApiException(409, ApiCodes.CONFLICT, "Watch is not active");
        }
        Channel channel = requireChannel(user, channelId);
        String type = eventType(eventType);
        Subscription subscription = new Subscription();
        subscription.setUser(users.getReferenceById(user.getId()));
        subscription.setRepo(watch.getRepo());
        subscription.setChannel(channel);
        subscription.setEventType(type);
        subscription.setFilters(filters(labels));
        subscription.setActive(true);
        try {
            subscription = subscriptions.saveAndFlush(subscription);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(409, ApiCodes.CONFLICT, "Subscription already exists");
        }
        return view(subscription);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionView> list(User user, UUID repoId) {
        requireWatch(user, repoId);
        return subscriptions.findByUser_IdAndRepo_Id(user.getId(), repoId).stream().map(SubscriptionApiService::view).toList();
    }

    @Transactional
    public SubscriptionView update(
            User user,
            UUID repoId,
            UUID subscriptionId,
            String eventType,
            List<String> labels,
            boolean labelsPresent,
            Boolean active) {
        Subscription subscription = owned(user, repoId, subscriptionId);
        if (eventType != null) {
            subscription.setEventType(eventType(eventType));
        }
        if (labelsPresent) {
            subscription.setFilters(filters(labels));
        }
        if (active != null) {
            if (!requireWatch(user, repoId).isActive() && active) {
                throw new ApiException(409, ApiCodes.CONFLICT, "Watch is not active");
            }
            subscription.setActive(active);
        }
        try {
            subscription = subscriptions.saveAndFlush(subscription);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(409, ApiCodes.CONFLICT, "Subscription already exists");
        }
        return view(subscription);
    }

    @Transactional
    public void delete(User user, UUID repoId, UUID subscriptionId) {
        subscriptions.delete(owned(user, repoId, subscriptionId));
    }

    private Subscription owned(User user, UUID repoId, UUID subscriptionId) {
        Subscription subscription = subscriptions.findByIdAndUser_Id(subscriptionId, user.getId())
                .orElseThrow(() -> new ApiException(404, ApiCodes.NOT_FOUND, "Subscription was not found"));
        if (!repoId.equals(subscription.getRepo().getId())) {
            throw new ApiException(404, ApiCodes.NOT_FOUND, "Subscription was not found");
        }
        return subscription;
    }

    private RepoWatch requireWatch(User user, UUID repoId) {
        return watches.findByUserIdAndRepoId(user.getId(), repoId)
                .orElseThrow(() -> new ApiException(404, ApiCodes.NOT_FOUND, "Repository is not watched"));
    }

    private Channel requireChannel(User user, String channelId) {
        UUID id;
        try {
            id = UUID.fromString(channelId == null ? "" : channelId);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "channel_id is not valid");
        }
        Channel channel = channels.findByIdAndUser_Id(id, user.getId())
                .orElseThrow(() -> new ApiException(404, ApiCodes.NOT_FOUND, "Channel was not found"));
        if (!ChannelPolicy.isDiscord(channel.getType())) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "type must be discord");
        }
        return channel;
    }

    static String eventType(String raw) {
        if (raw == null) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "event_type must be issue.opened or pr.opened");
        }
        String type = raw.trim();
        if (!EventTypes.ISSUE_OPENED.equals(type) && !EventTypes.PR_OPENED.equals(type)) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "event_type must be issue.opened or pr.opened");
        }
        return type;
    }

    static SubscriptionFilters filters(List<String> labels) {
        SubscriptionFilters filters = new SubscriptionFilters();
        if (labels == null) {
            return filters;
        }
        if (labels.size() > 20) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "filters.labels is too long");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String label : labels) {
            if (label == null || label.isBlank() || label.trim().length() > 100) {
                throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "filters.labels contains an invalid label");
            }
            unique.add(label.trim());
        }
        filters.setLabels(new ArrayList<>(unique));
        return filters;
    }

    static SubscriptionView view(Subscription subscription) {
        List<String> labels = subscription.getFilters() == null || subscription.getFilters().getLabels() == null
                ? List.of()
                : List.copyOf(subscription.getFilters().getLabels());
        return new SubscriptionView(
                subscription.getId().toString(),
                subscription.getRepo().getId().toString(),
                subscription.getChannel().getId().toString(),
                subscription.getEventType(),
                new FiltersView(labels),
                subscription.isActive());
    }
}
