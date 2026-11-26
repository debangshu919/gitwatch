package app.gitwatch.service;

import app.gitwatch.api.ApiCodes;
import app.gitwatch.api.ApiEnvelopes.RepoView;
import app.gitwatch.api.ApiException;
import app.gitwatch.github.GitHubUrlParser;
import app.gitwatch.github.PublicRepoLookup;
import app.gitwatch.model.Repo;
import app.gitwatch.model.RepoPollState;
import app.gitwatch.model.RepoPollStateId;
import app.gitwatch.model.RepoWatch;
import app.gitwatch.model.User;
import app.gitwatch.repository.RepoPollStateRepository;
import app.gitwatch.repository.RepoRepository;
import app.gitwatch.repository.RepoWatchRepository;
import app.gitwatch.repository.SubscriptionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WatchService {

    private final RepoRepository repos;
    private final RepoWatchRepository watches;
    private final RepoPollStateRepository pollStates;
    private final SubscriptionRepository subscriptions;
    private final PublicRepoLookup github;

    public WatchService(
            RepoRepository repos,
            RepoWatchRepository watches,
            RepoPollStateRepository pollStates,
            SubscriptionRepository subscriptions,
            PublicRepoLookup github) {
        this.repos = repos;
        this.watches = watches;
        this.pollStates = pollStates;
        this.subscriptions = subscriptions;
        this.github = github;
    }

    @Transactional
    public RepoView add(User user, String url) {
        GitHubUrlParser.Coordinates coordinates = GitHubUrlParser.parse(url);
        github.requirePublic(coordinates.owner(), coordinates.repo());
        Repo repo = repos.findByOwnerAndName(coordinates.owner(), coordinates.repo()).orElseGet(() -> create(coordinates));
        ensurePollState(repo);
        if (watches.findByUserIdAndRepoId(user.getId(), repo.getId()).isPresent()) {
            throw new ApiException(409, ApiCodes.CONFLICT, "Repository is already watched");
        }
        RepoWatch watch = new RepoWatch();
        watch.setUserId(user.getId());
        watch.setRepoId(repo.getId());
        watch.setRepo(repo);
        watch.setActive(true);
        watch.setCreatedAt(Instant.now());
        watches.save(watch);
        return view(repo, true);
    }

    @Transactional(readOnly = true)
    public List<RepoView> list(User user) {
        return watches.findWatches(user.getId()).stream()
                .map(watch -> view(watch.getRepo(), watch.isActive()))
                .toList();
    }

    @Transactional
    public RepoView setActive(User user, UUID repoId, boolean active) {
        RepoWatch watch = watches.findByUserIdAndRepoId(user.getId(), repoId)
                .orElseThrow(() -> new ApiException(404, ApiCodes.NOT_FOUND, "Repository is not watched"));
        Repo repo = watch.getRepo();
        RepoView updated = view(repo, active);
        watch.setActive(active);
        watches.save(watch);
        subscriptions.updateActiveForUserAndRepo(user.getId(), repoId, active);
        return updated;
    }

    @Transactional
    public void remove(User user, UUID repoId) {
        RepoWatch watch = watches.findByUserIdAndRepoId(user.getId(), repoId)
                .orElseThrow(() -> new ApiException(404, ApiCodes.NOT_FOUND, "Repository is not watched"));
        Repo repo = watch.getRepo();
        String owner = repo.getOwner();
        String name = repo.getName();
        subscriptions.deleteByUser_IdAndRepo_Id(user.getId(), repoId);
        watches.delete(watch);
        watches.flush();
        if (watches.countByRepoId(repoId) == 0 && subscriptions.countByRepo_Id(repoId) == 0) {
            pollStates.deleteById(new RepoPollStateId(owner, name));
            repos.delete(repo);
        }
    }

    private Repo create(GitHubUrlParser.Coordinates coordinates) {
        Repo repo = new Repo();
        repo.setOwner(coordinates.owner());
        repo.setName(coordinates.repo());
        repo.setFullName(coordinates.owner() + "/" + coordinates.repo());
        repo.setActive(true);
        return repos.saveAndFlush(repo);
    }

    private void ensurePollState(Repo repo) {
        RepoPollStateId id = new RepoPollStateId(repo.getOwner(), repo.getName());
        if (pollStates.findById(id).isPresent()) {
            return;
        }
        RepoPollState state = new RepoPollState();
        state.setId(id);
        state.setIssuePage(1);
        state.setPrPage(1);
        state.setNextPollAt(Instant.now());
        pollStates.save(state);
    }

    static RepoView view(Repo repo, boolean active) {
        return new RepoView(repo.getId().toString(), repo.getOwner(), repo.getName(), repo.getFullName(), active);
    }
}
