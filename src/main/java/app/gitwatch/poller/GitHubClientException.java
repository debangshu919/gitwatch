package app.gitwatch.poller;

public class GitHubClientException extends RuntimeException {

    private final int status;

    public GitHubClientException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
