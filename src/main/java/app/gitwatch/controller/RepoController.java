package app.gitwatch.controller;

import app.gitwatch.api.ApiEnvelopes.DataResponse;
import app.gitwatch.api.ApiEnvelopes.RepoView;
import app.gitwatch.api.ApiEnvelopes.StatusBody;
import app.gitwatch.api.ApiRequests.RepoCreateRequest;
import app.gitwatch.api.ApiRequests.WatchUpdate;
import app.gitwatch.model.User;
import app.gitwatch.service.CurrentUser;
import app.gitwatch.service.WatchService;
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
public class RepoController {

    private final WatchService watches;
    private final CurrentUser currentUser;

    public RepoController(WatchService watches, CurrentUser currentUser) {
        this.watches = watches;
        this.currentUser = currentUser;
    }

    @PostMapping("/v1/repos")
    public ResponseEntity<DataResponse<RepoView>> create(Authentication authentication, @RequestBody RepoCreateRequest request) {
        User user = currentUser.require(authentication);
        RepoView view = watches.add(user, request == null ? null : request.url());
        return ResponseEntity.status(HttpStatus.CREATED).body(new DataResponse<>(view));
    }

    @GetMapping("/v1/repos")
    public DataResponse<List<RepoView>> list(Authentication authentication) {
        return new DataResponse<>(watches.list(currentUser.require(authentication)));
    }

    @PatchMapping("/v1/repos/{id}")
    public DataResponse<RepoView> update(
            Authentication authentication,
            @PathVariable UUID id,
            @RequestBody WatchUpdate request) {
        if (request == null || request.active() == null) {
            throw new app.gitwatch.api.ApiException(400, app.gitwatch.api.ApiCodes.VALIDATION_ERROR, "is_active is required");
        }
        return new DataResponse<>(watches.setActive(currentUser.require(authentication), id, request.active()));
    }

    @DeleteMapping("/v1/repos/{id}")
    public DataResponse<StatusBody> delete(Authentication authentication, @PathVariable UUID id) {
        watches.remove(currentUser.require(authentication), id);
        return new DataResponse<>(new StatusBody("removed"));
    }
}
