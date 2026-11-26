package app.gitwatch.repository;

import app.gitwatch.model.OauthAccount;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OauthAccountRepository extends JpaRepository<OauthAccount, UUID> {
}
