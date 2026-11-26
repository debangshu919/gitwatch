package app.gitwatch.service;

public interface DedupStore {

    boolean tryStart(String owner, String repo, String eventType, int number);

    boolean isCompleted(String owner, String repo, String eventType, int number);

    void complete(String owner, String repo, String eventType, int number);

    void release(String owner, String repo, String eventType, int number);

    boolean alreadyDelivered(String subscriptionId, String eventType, int number);

    void markDelivered(String subscriptionId, String eventType, int number);

    boolean alreadyFailed(String subscriptionId, String eventType, int number);

    void markFailed(String subscriptionId, String eventType, int number);
}
