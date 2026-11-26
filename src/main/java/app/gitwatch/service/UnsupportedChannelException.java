package app.gitwatch.service;

public class UnsupportedChannelException extends RuntimeException {

    public UnsupportedChannelException(String message) {
        super(message);
    }
}
