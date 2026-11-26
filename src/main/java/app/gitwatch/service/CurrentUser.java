package app.gitwatch.service;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiException;
import app.gitwatch.model.User;
import app.gitwatch.repository.UserRepository;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public User require(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw unauthorized();
        }
        UUID id;
        try {
            id = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ex) {
            throw unauthorized();
        }
        return users.findById(id).orElseThrow(CurrentUser::unauthorized);
    }

    private static ApiException unauthorized() {
        return new ApiException(401, ApiCodes.UNAUTHORIZED, "Authentication required");
    }
}
