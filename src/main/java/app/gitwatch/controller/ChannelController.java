package app.gitwatch.controller;

import app.gitwatch.api.ApiEnvelopes.ChannelView;
import app.gitwatch.api.ApiEnvelopes.DataResponse;
import app.gitwatch.api.ApiEnvelopes.StatusBody;
import app.gitwatch.api.ApiRequests.ChannelCreateRequest;
import app.gitwatch.model.User;
import app.gitwatch.service.ChannelApiService;
import app.gitwatch.service.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChannelController {

    private final ChannelApiService channels;
    private final CurrentUser currentUser;

    public ChannelController(ChannelApiService channels, CurrentUser currentUser) {
        this.channels = channels;
        this.currentUser = currentUser;
    }

    @PostMapping("/v1/channels")
    public ResponseEntity<DataResponse<ChannelView>> create(
            Authentication authentication,
            @RequestBody ChannelCreateRequest request) {
        User user = currentUser.require(authentication);
        String webhook = request == null || request.config() == null ? null : request.config().webhookUrl();
        ChannelView view = channels.create(
                user,
                request == null ? null : request.type(),
                request == null ? null : request.name(),
                webhook);
        return ResponseEntity.status(HttpStatus.CREATED).body(new DataResponse<>(view));
    }

    @GetMapping("/v1/channels")
    public DataResponse<List<ChannelView>> list(Authentication authentication) {
        return new DataResponse<>(channels.list(currentUser.require(authentication)));
    }

    @DeleteMapping("/v1/channels/{id}")
    public DataResponse<StatusBody> delete(Authentication authentication, @PathVariable UUID id) {
        channels.delete(currentUser.require(authentication), id);
        return new DataResponse<>(new StatusBody("removed"));
    }

    @PostMapping("/v1/channels/{id}/test")
    public DataResponse<StatusBody> test(Authentication authentication, @PathVariable UUID id) {
        channels.sendTest(currentUser.require(authentication), id);
        return new DataResponse<>(new StatusBody("sent"));
    }
}
