package app.gitwatch.notifier;

import java.time.Duration;

public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    static Sleeper thread() {
        return duration -> {
            if (duration == null || duration.isZero() || duration.isNegative()) {
                return;
            }
            Thread.sleep(duration.toMillis());
        };
    }
}
