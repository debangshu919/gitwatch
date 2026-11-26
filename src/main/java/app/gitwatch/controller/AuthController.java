package app.gitwatch.controller;

import app.gitwatch.api.ApiEnvelopes.AuthResponse;
import app.gitwatch.api.ApiEnvelopes.DataResponse;
import app.gitwatch.api.ApiEnvelopes.Profile;
import app.gitwatch.api.ApiRequests.LoginRequest;
import app.gitwatch.api.ApiRequests.RegisterRequest;
import app.gitwatch.service.AccountService;
import app.gitwatch.service.AuthService;
import app.gitwatch.service.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService auth;
    private final CurrentUser currentUser;

    public AuthController(AuthService auth, CurrentUser currentUser) {
        this.auth = auth;
        this.currentUser = currentUser;
    }

    @PostMapping("/v1/auth/register")
    public ResponseEntity<DataResponse<AuthResponse>> register(@RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new DataResponse<>(auth.register(
                request == null ? null : request.email(),
                request == null ? null : request.password(),
                request == null ? null : request.name())));
    }

    @PostMapping("/v1/auth/login")
    public DataResponse<AuthResponse> login(@RequestBody LoginRequest request) {
        return new DataResponse<>(auth.login(
                request == null ? null : request.email(),
                request == null ? null : request.password()));
    }

    @GetMapping("/v1/auth/me")
    public DataResponse<Profile> me(Authentication authentication) {
        return new DataResponse<>(AccountService.profile(currentUser.require(authentication)));
    }

    @GetMapping("/v1/auth/github")
    public ResponseEntity<Void> github(HttpServletRequest request) {
        String redirect = auth.redirectUri(callback(request));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(auth.authorizeUrl(redirect, auth.optionalUser(request.getHeader("Authorization")))))
                .build();
    }

    @GetMapping("/v1/auth/github/callback")
    public DataResponse<AuthResponse> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state) {
        return new DataResponse<>(auth.completeGitHub(code, state));
    }

    static String callback(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        String host = scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
        return host + "/v1/auth/github/callback";
    }
}
