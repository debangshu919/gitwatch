package app.gitwatch.controller;

import app.gitwatch.api.ApiEnvelopes.DataResponse;
import app.gitwatch.api.ApiEnvelopes.StatusBody;
import app.gitwatch.api.ApiEnvelopes.SubscriptionView;
import app.gitwatch.api.ApiRequests.SubscriptionCreateRequest;
import app.gitwatch.api.ApiRequests.SubscriptionUpdateRequest;
import app.gitwatch.model.User;
import app.gitwatch.service.CurrentUser;
import app.gitwatch.service.SubscriptionApiService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SubscriptionController {

    private final SubscriptionApiService subscriptions;
    private final CurrentUser currentUser;

    public SubscriptionController(SubscriptionApiService subscriptions, CurrentUser currentUser) {
        this.subscriptions = subscriptions;
        this.currentUser = currentUser;
    }

    @PostMapping("/v1/repos/{repoId}/subscriptions")
    public ResponseEntity<DataResponse<SubscriptionView>> create(
            Authentication authentication,
            @PathVariable UUID repoId,
            @RequestBody SubscriptionCreateRequest request) {
        User user = currentUser.require(authentication);
        SubscriptionView view = subscriptions.create(
                user,
                repoId,
                request == null ? null : request.channelId(),
                request == null ? null : request.eventType(),
                request == null || request.filters() == null ? null : request.filters().labels());
        return ResponseEntity.status(HttpStatus.CREATED).body(new DataResponse<>(view));
    }

    @GetMapping("/v1/repos/{repoId}/subscriptions")
    public DataResponse<List<SubscriptionView>> list(Authentication authentication, @PathVariable UUID repoId) {
        return new DataResponse<>(subscriptions.list(currentUser.require(authentication), repoId));
    }

    @PatchMapping("/v1/repos/{repoId}/subscriptions/{id}")
    public DataResponse<SubscriptionView> update(
            Authentication authentication,
            @PathVariable UUID repoId,
            @PathVariable UUID id,
            @RequestBody SubscriptionUpdateRequest request) {
        boolean labelsPresent = request != null && request.filters() != null;
        return new DataResponse<>(subscriptions.update(
                currentUser.require(authentication),
                repoId,
                id,
                request == null ? null : request.eventType(),
                labelsPresent ? request.filters().labels() : null,
                labelsPresent,
                request == null ? null : request.active()));
    }

    @DeleteMapping("/v1/repos/{repoId}/subscriptions/{id}")
    public DataResponse<StatusBody> delete(
            Authentication authentication,
            @PathVariable UUID repoId,
            @PathVariable UUID id) {
        subscriptions.delete(currentUser.require(authentication), repoId, id);
        return new DataResponse<>(new StatusBody("removed"));
    }
}
