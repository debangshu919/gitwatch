package app.gitwatch.service;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiEnvelopes.Profile;
import app.gitwatch.api.ApiException;
import app.gitwatch.model.Repo;
import app.gitwatch.model.RepoPollStateId;
import app.gitwatch.model.User;
import app.gitwatch.repository.ChannelRepository;
import app.gitwatch.repository.OauthAccountRepository;
import app.gitwatch.repository.RepoPollStateRepository;
import app.gitwatch.repository.RepoRepository;
import app.gitwatch.repository.RepoWatchRepository;
import app.gitwatch.repository.SubscriptionRepository;
import app.gitwatch.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final UserRepository users;
    private final SubscriptionRepository subscriptions;
    private final ChannelRepository channels;
    private final OauthAccountRepository oauthAccounts;
    private final RepoWatchRepository watches;
    private final RepoRepository repos;
    private final RepoPollStateRepository pollStates;
    private final RepoLock repoLock;
    private final EntityManager entityManager;

    public AccountService(
            UserRepository users,
            SubscriptionRepository subscriptions,
            ChannelRepository channels,
            OauthAccountRepository oauthAccounts,
            RepoWatchRepository watches,
            RepoRepository repos,
            RepoPollStateRepository pollStates,
            RepoLock repoLock,
            EntityManager entityManager) {
        this.users = users;
        this.subscriptions = subscriptions;
        this.channels = channels;
        this.oauthAccounts = oauthAccounts;
        this.watches = watches;
        this.repos = repos;
        this.pollStates = pollStates;
        this.repoLock = repoLock;
        this.entityManager = entityManager;
    }

    @Transactional
    public Profile updateName(User user, String name) {
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new ApiException(400, ApiCodes.VALIDATION_ERROR, "name is required");
        }
        User managed = users.findById(user.getId())
                .orElseThrow(() -> new ApiException(401, ApiCodes.UNAUTHORIZED, "Authentication required"));
        managed.setName(name.trim());
        users.save(managed);
        return profile(managed);
    }

    @Transactional
    public void delete(User user) {
        User managed = users.findById(user.getId())
                .orElseThrow(() -> new ApiException(401, ApiCodes.UNAUTHORIZED, "Authentication required"));
        List<UUID> repoIds = watches.findRepoIdsByUserId(managed.getId());
        subscriptions.deleteByUser_Id(managed.getId());
        channels.deleteByUser_Id(managed.getId());
        oauthAccounts.deleteByUser_Id(managed.getId());
        watches.deleteByUserId(managed.getId());
        entityManager.flush();
        entityManager.clear();
        users.delete(users.getReferenceById(managed.getId()));
        entityManager.flush();
        for (UUID repoId : repoIds) {
            repoLock.lock(repoId);
            if (watches.countByRepoId(repoId) == 0 && subscriptions.countByRepo_Id(repoId) == 0) {
                repos.findById(repoId).ifPresent(this::deleteShared);
            }
        }
    }

    public static Profile profile(User user) {
        return new Profile(user.getId().toString(), user.getEmail(), user.getName(), user.getAvatarUrl());
    }

    private void deleteShared(Repo repo) {
        pollStates.deleteById(new RepoPollStateId(repo.getOwner(), repo.getName()));
        repos.delete(repo);
    }
}
