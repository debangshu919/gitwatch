package app.gitwatch.service;

public interface DedupStore {

    boolean claim(String owner, String repo, String eventType, int number);

    void release(String owner, String repo, String eventType, int number);
}
