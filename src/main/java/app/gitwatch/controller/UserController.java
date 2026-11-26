package app.gitwatch.controller;

import app.gitwatch.api.ApiEnvelopes.DataResponse;
import app.gitwatch.api.ApiEnvelopes.Profile;
import app.gitwatch.api.ApiEnvelopes.StatusBody;
import app.gitwatch.api.ApiRequests.NameUpdate;
import app.gitwatch.model.User;
import app.gitwatch.service.AccountService;
import app.gitwatch.service.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

    private final AccountService accounts;
    private final CurrentUser currentUser;

    public UserController(AccountService accounts, CurrentUser currentUser) {
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @PatchMapping("/v1/users/me")
    public DataResponse<Profile> update(Authentication authentication, @RequestBody NameUpdate request) {
        User user = currentUser.require(authentication);
        return new DataResponse<>(accounts.updateName(user, request == null ? null : request.name()));
    }

    @DeleteMapping("/v1/users/me")
    public DataResponse<StatusBody> delete(Authentication authentication) {
        accounts.delete(currentUser.require(authentication));
        return new DataResponse<>(new StatusBody("deleted"));
    }
}
