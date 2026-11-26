package app.gitwatch.controller;

import app.gitwatch.api.ApiEnvelopes.NotificationView;
import app.gitwatch.api.ApiEnvelopes.PageResponse;
import app.gitwatch.service.CurrentUser;
import app.gitwatch.service.NotificationQuery;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    private final NotificationQuery notifications;
    private final CurrentUser currentUser;

    public NotificationController(NotificationQuery notifications, CurrentUser currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    @GetMapping("/v1/notifications")
    public PageResponse<NotificationView> list(
            Authentication authentication,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) String status) {
        return notifications.list(currentUser.require(authentication).getId().toString(), limit, offset, status);
    }
}
