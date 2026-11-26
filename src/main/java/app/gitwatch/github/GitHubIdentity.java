package app.gitwatch.github;

public final class GitHubIdentity {

    private final String providerId;
    private final String login;
    private final String email;
    private final String name;
    private final String avatarUrl;
    private final String accessToken;

    public GitHubIdentity(String providerId, String login, String email, String name, String avatarUrl, String accessToken) {
        this.providerId = providerId;
        this.login = login;
        this.email = email;
        this.name = name;
        this.avatarUrl = avatarUrl;
        this.accessToken = accessToken;
    }

    public String providerId() {
        return providerId;
    }

    public String login() {
        return login;
    }

    public String email() {
        return email;
    }

    public String name() {
        return name;
    }

    public String avatarUrl() {
        return avatarUrl;
    }

    public String accessToken() {
        return accessToken;
    }

    @Override
    public String toString() {
        return "GitHubIdentity{id=" + providerId + ", login=" + login + "}";
    }
}
