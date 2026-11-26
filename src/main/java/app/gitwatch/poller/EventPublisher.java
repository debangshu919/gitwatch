package app.gitwatch.poller;

import app.gitwatch.model.GitHubEvent;

public interface EventPublisher {

    void publish(GitHubEvent event);
}
