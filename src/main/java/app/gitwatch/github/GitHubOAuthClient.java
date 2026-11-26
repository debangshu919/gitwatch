package app.gitwatch.github;

public interface GitHubOAuthClient {

    GitHubIdentity exchange(String code, String redirectUri);
}
