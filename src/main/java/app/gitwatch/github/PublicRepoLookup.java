package app.gitwatch.github;

public interface PublicRepoLookup {

    void requirePublic(String owner, String repo);
}
