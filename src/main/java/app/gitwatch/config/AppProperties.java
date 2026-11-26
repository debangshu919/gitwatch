package app.gitwatch.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gitwatch")
public class AppProperties {

    private String encryptionKey = "";
    private String jwtSecret = "";
    private Duration jwtExpiry = Duration.ofDays(7);
    private Oauth oauth = new Oauth();
    private RateLimit rateLimit = new RateLimit();
    private Github github = new Github();
    private Poller poller = new Poller();
    private Notifier notifier = new Notifier();
    private Seed seed = new Seed();
    private Mongo mongo = new Mongo();

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getJwtExpiry() {
        return jwtExpiry;
    }

    public void setJwtExpiry(Duration jwtExpiry) {
        this.jwtExpiry = jwtExpiry;
    }

    public Oauth getOauth() {
        return oauth;
    }

    public void setOauth(Oauth oauth) {
        this.oauth = oauth;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Github getGithub() {
        return github;
    }

    public void setGithub(Github github) {
        this.github = github;
    }

    public Poller getPoller() {
        return poller;
    }

    public void setPoller(Poller poller) {
        this.poller = poller;
    }

    public Notifier getNotifier() {
        return notifier;
    }

    public void setNotifier(Notifier notifier) {
        this.notifier = notifier;
    }

    public Seed getSeed() {
        return seed;
    }

    public void setSeed(Seed seed) {
        this.seed = seed;
    }

    public Mongo getMongo() {
        return mongo;
    }

    public void setMongo(Mongo mongo) {
        this.mongo = mongo;
    }

    public static class Oauth {
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "";

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
        }
    }

    public static class RateLimit {
        private int authPerMinute = 10;
        private int userPerMinute = 100;

        public int getAuthPerMinute() {
            return authPerMinute;
        }

        public void setAuthPerMinute(int authPerMinute) {
            this.authPerMinute = authPerMinute;
        }

        public int getUserPerMinute() {
            return userPerMinute;
        }

        public void setUserPerMinute(int userPerMinute) {
            this.userPerMinute = userPerMinute;
        }
    }

    public static class Github {
        private String token = "";
        private String apiBase = "https://api.github.com";
        private int minRemaining = 10;
        private Duration rateLimitDelay = Duration.ofMinutes(15);

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public int getMinRemaining() {
            return minRemaining;
        }

        public void setMinRemaining(int minRemaining) {
            this.minRemaining = minRemaining;
        }

        public Duration getRateLimitDelay() {
            return rateLimitDelay;
        }

        public void setRateLimitDelay(Duration rateLimitDelay) {
            this.rateLimitDelay = rateLimitDelay;
        }
    }

    public static class Poller {
        private boolean enabled = true;
        private long intervalMs = 30_000;
        private int batchSize = 10;
        private Duration nextPollDelay = Duration.ofMinutes(3);
        private Duration lease = Duration.ofMinutes(5);
        private int maxPages = 5;
        private int pageSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getNextPollDelay() {
            return nextPollDelay;
        }

        public void setNextPollDelay(Duration nextPollDelay) {
            this.nextPollDelay = nextPollDelay;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(Duration lease) {
            this.lease = lease;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }
    }

    public static class Notifier {
        private int maxAttempts = 3;
        private Duration backoff = Duration.ofSeconds(2);
        private Duration timeout = Duration.ofSeconds(10);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBackoff() {
            return backoff;
        }

        public void setBackoff(Duration backoff) {
            this.backoff = backoff;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class Seed {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Mongo {
        private int logTtlDays = 90;

        public int getLogTtlDays() {
            return logTtlDays;
        }

        public void setLogTtlDays(int logTtlDays) {
            this.logTtlDays = logTtlDays;
        }
    }
}
