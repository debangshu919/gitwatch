package app.gitwatch.notifier;

public final class DeliveryStoreException extends RuntimeException {

    public DeliveryStoreException(Throwable cause) {
        super("delivery store unavailable", cause);
    }
}
