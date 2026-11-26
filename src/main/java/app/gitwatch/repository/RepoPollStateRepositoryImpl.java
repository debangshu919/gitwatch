package app.gitwatch.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public class RepoPollStateRepositoryImpl implements RepoPollStateRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @SuppressWarnings("unchecked")
    public List<Object[]> lockDueKeys(Instant now, int batch) {
        return entityManager
                .createNativeQuery(PollLockSql.statement(batch, ":now"))
                .setParameter("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .getResultList();
    }
}
