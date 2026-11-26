package app.gitwatch.notifier;

public interface DlqPublisher {

    void publish(DeliveryFailure failure);
}
