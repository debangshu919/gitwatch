package app.gitwatch.repository;

import java.time.Instant;
import java.util.List;

public interface RepoPollStateRepositoryCustom {

    List<Object[]> lockDueKeys(Instant now, int batch);
}
