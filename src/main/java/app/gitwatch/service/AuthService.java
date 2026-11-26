package app.gitwatch.service;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiEnvelopes.AuthResponse;
import app.gitwatch.api.ApiEnvelopes.AuthUser;
import app.gitwatch.api.ApiException;
import app.gitwatch.config.AppProperties;
import app.gitwatch.github.GitHubIdentity;
import app.gitwatch.github.GitHubOAuthClient;
import app.gitwatch.model.OauthAccount;
import app.gitwatch.model.User;
import app.gitwatch.repository.OauthAccountRepository;
import app.gitwatch.repository.UserRepository;
import app.gitwatch.security.JwtTokens;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String LOGIN_FAILURE = "Invalid email or password";
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final OauthAccountRepository oauthAccounts;
    private final PasswordEncoder passwords;
    private final JwtTokens tokens;
    private final GitHubOAuthClient github;
    private final AppProperties properties;
    private final String dummyHash;

    public AuthService(
            UserRepository users,
            OauthAccountRepository oauthAccounts,
            PasswordEncoder passwords,
            JwtTokens tokens,
            GitHubOAuthClient github,
            AppProperties properties) {
        this.users = users;
        this.oauthAccounts = oauthAccounts;
        this.passwords = passwords;
        this.tokens = tokens;
        this.github = github;
        this.properties = properties;
        this.dummyHash = passwords.encode("gitwatch-dummy-password");
    }

    @Transactional
    public AuthResponse register(String email, String password, String name) {
        String normalized = email(email);
        String displayName = requiredName(name);
        requirePassword(password);
        if (users.findByEmail(normalized).isPresent()) {
            throw new ApiException(409, ApiCodes.CONFLICT, "Email is already registered");
        }
        User user = new User();
        user.setEmail(normalized);
        user.setName(displayName);
        user.setPasswordHash(passwords.encode(password));
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(409, ApiCodes.CONFLICT, "Email is already registered");
        }
        return response(user, true);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(String email, String password) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        User user = normalized.isBlank() ? null : users.findByEmail(normalized).orElse(null);
        String hash = user == null || user.getPasswordHash() == null ? dummyHash : user.getPasswordHash();
        boolean matches = passwords.matches(password == null ? "" : password, hash);
        if (user == null || user.getPasswordHash() == null || !matches) {
            throw new ApiException(401, ApiCodes.UNAUTHORIZED, LOGIN_FAILURE);
        }
        return response(user, true);
    }

    public void requireOAuthConfigured() {
        if (!properties.getOauth().isConfigured()) {
            throw new ApiException(503, ApiCodes.OAUTH_NOT_CONFIGURED, "GitHub OAuth is not configured");
        }
    }

    public String authorizeUrl(String redirectUri) {
        requireOAuthConfigured();
        String state = tokens.issueState(redirectUri);
        return "https://github.com/login/oauth/authorize?client_id="
                + encode(properties.getOauth().getClientId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode("read:user user:email")
                + "&state=" + encode(state);
    }

    public String redirectUri(String requestUri) {
        String configured = properties.getOauth().getRedirectUri();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return requestUri;
    }

    @Transactional
    public AuthResponse completeGitHub(String code, String state) {
        requireOAuthConfigured();
        if (code == null || code.isBlank() || state == null || state.isBlank()) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "OAuth callback is missing code or state");
        }
        String redirectUri = tokens.requireStateRedirect(state);
        GitHubIdentity identity = github.exchange(code, redirectUri);
        if (identity.providerId() == null || identity.providerId().isBlank()) {
            throw new ApiException(502, ApiCodes.OAUTH_FAILED, "GitHub login could not be completed");
        }
        if (identity.email() == null || identity.email().isBlank()) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "GitHub account has no verified email");
        }
        String email = identity.email().trim().toLowerCase(Locale.ROOT);
        OauthAccount linked = oauthAccounts.findByProviderAndProviderId("github", identity.providerId()).orElse(null);
        User user;
        if (linked != null) {
            user = linked.getUser();
            linked.setAccessToken(identity.accessToken());
            if (identity.avatarUrl() != null && !identity.avatarUrl().isBlank()) {
                user.setAvatarUrl(identity.avatarUrl());
            }
            oauthAccounts.save(linked);
            users.save(user);
        } else {
            user = users.findByEmail(email).orElseGet(() -> createOAuthUser(email, identity));
            if ((user.getName() == null || user.getName().isBlank()) && identity.name() != null) {
                user.setName(identity.name());
            }
            if (identity.avatarUrl() != null && !identity.avatarUrl().isBlank()) {
                user.setAvatarUrl(identity.avatarUrl());
            }
            users.save(user);
            OauthAccount account = new OauthAccount();
            account.setUser(user);
            account.setProvider("github");
            account.setProviderId(identity.providerId());
            account.setAccessToken(identity.accessToken());
            oauthAccounts.save(account);
        }
        return response(user, true);
    }

    private User createOAuthUser(String email, GitHubIdentity identity) {
        User user = new User();
        user.setEmail(email);
        user.setName(identity.name() == null || identity.name().isBlank() ? identity.login() : identity.name());
        user.setAvatarUrl(identity.avatarUrl());
        return users.save(user);
    }

    private AuthResponse response(User user, boolean includeName) {
        AuthUser body = new AuthUser(user.getId().toString(), user.getEmail(), includeName ? user.getName() : null);
        return new AuthResponse(body, tokens.issueAccess(user));
    }

    private static String email(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 320 || !EMAIL.matcher(raw.trim()).matches()) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "email is not valid");
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String requiredName(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().length() > 120) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "name is required");
        }
        return raw.trim();
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 8) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "password must be at least 8 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "password must be at most 72 bytes");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
