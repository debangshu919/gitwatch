package app.gitwatch.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.gitwatch.config.AppProperties;
import app.gitwatch.github.GitHubIdentity;
import app.gitwatch.github.GitHubOAuthClient;
import app.gitwatch.github.PublicRepoLookup;
import app.gitwatch.model.NotificationLog;
import app.gitwatch.model.RepoPollStateId;
import app.gitwatch.notifier.DiscordClient;
import app.gitwatch.notifier.DiscordResponse;
import app.gitwatch.repository.ChannelRepository;
import app.gitwatch.repository.NotificationLogRepository;
import app.gitwatch.repository.OauthAccountRepository;
import app.gitwatch.repository.RepoPollStateRepository;
import app.gitwatch.repository.RepoRepository;
import app.gitwatch.repository.RepoWatchRepository;
import app.gitwatch.repository.SubscriptionRepository;
import app.gitwatch.repository.UserRepository;
import app.gitwatch.service.ChannelApiService;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "gitwatch.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "gitwatch.jwt-secret=test-jwt-secret-at-least-32-bytes!!",
        "gitwatch.poller.enabled=false",
        "gitwatch.seed.enabled=false",
        "gitwatch.oauth.client-id=",
        "gitwatch.oauth.client-secret="
})
class ManagementApiTest {

    private static final String PASSWORD = "correct-horse";
    private static final String WEBHOOK = "https://example.invalid/hook/secret-value";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("gitwatch.oauth.client-id", () -> "");
        registry.add("gitwatch.oauth.client-secret", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppProperties properties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private UserRepository users;

    @Autowired
    private RepoRepository repos;

    @Autowired
    private RepoWatchRepository watches;

    @Autowired
    private RepoPollStateRepository pollStates;

    @Autowired
    private ChannelRepository channels;

    @Autowired
    private SubscriptionRepository subscriptions;

    @Autowired
    private OauthAccountRepository oauthAccounts;

    @Autowired
    private NotificationLogRepository logs;

    @MockitoBean
    private PublicRepoLookup githubRepos;

    @MockitoBean
    private DiscordClient discord;

    @MockitoBean
    private GitHubOAuthClient githubOAuth;

    @BeforeEach
    void clean() {
        properties.getOauth().setClientId("");
        properties.getOauth().setClientSecret("");
        properties.getRateLimit().setAuthPerMinute(10);
        properties.getRateLimit().setUserPerMinute(100);
        subscriptions.deleteAll();
        channels.deleteAll();
        oauthAccounts.deleteAll();
        watches.deleteAll();
        users.deleteAll();
        repos.deleteAll();
        pollStates.deleteAll();
        logs.deleteAll();
        Set<String> keys = redisTemplate.keys("ratelimit:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        when(discord.post(anyString(), anyString())).thenReturn(DiscordResponse.success());
    }

    @Test
    void registerAndLoginDoNotReturnThePassword() throws Exception {
        MvcResult registered = mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("ada@example.com", PASSWORD, "Ada")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.email").value("ada@example.com"))
                .andExpect(jsonPath("$.data.user.name").value("Ada"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        String body = registered.getResponse().getContentAsString();
        assertFalse(body.contains(PASSWORD));
        assertFalse(body.contains("password_hash"));
        String token = JsonPath.read(body, "$.data.token");
        assertTrue(decode(token.split("\\.")[0]).contains("HS256"));
        assertFalse(decode(token.split("\\.")[1]).contains(PASSWORD));

        var stored = users.findByEmail("ada@example.com").orElseThrow();
        assertNotEquals(PASSWORD, stored.getPasswordHash());
        assertTrue(stored.getPasswordHash().startsWith("$2"));

        MvcResult loggedIn = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("Ada@example.com", PASSWORD, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value("ada@example.com"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        assertFalse(loggedIn.getResponse().getContentAsString().contains(PASSWORD));

        mockMvc.perform(get("/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(stored.getId().toString()))
                .andExpect(jsonPath("$.data.email").value("ada@example.com"))
                .andExpect(jsonPath("$.data.name").value("Ada"))
                .andExpect(jsonPath("$.data.avatar_url").value(nullValue()));
    }

    @Test
    void wrongPasswordMatchesUnknownEmail() throws Exception {
        register("ada@example.com", "Ada");
        String unknown = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String wrong = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ada@example.com\",\"password\":\"not-the-password\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertEquals(unknown, wrong);
        assertTrue(unknown.contains("Invalid email or password"));
        assertFalse(unknown.contains("not-the-password"));
    }

    @Test
    void telegramIsRejected() throws Exception {
        String token = register("ada@example.com", "Ada");
        mockMvc.perform(post("/v1/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"telegram\",\"name\":\"My Telegram\",\"config\":{\"chat_id\":\"123\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_CHANNEL"));
        assertEquals(0, channels.count());
    }

    @Test
    void badGitHubUrlIsRejected() throws Exception {
        String token = register("ada@example.com", "Ada");
        mockMvc.perform(post("/v1/repos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://gitlab.com/apache/iggy\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        verify(githubRepos, never()).requirePublic(any(), any());
        assertEquals(0, repos.count());
    }

    @Test
    void secondUserReusesTheRepoRow() throws Exception {
        String ada = register("ada@example.com", "Ada");
        String grace = register("grace@example.com", "Grace");
        String first = addRepo(ada, "https://github.com/apache/iggy");
        String second = addRepo(grace, "https://github.com/apache/iggy.git");
        assertEquals(first, second);
        assertEquals(1, repos.count());
        assertEquals(2, watches.count());
        assertTrue(pollStates.findById(new RepoPollStateId("apache", "iggy")).isPresent());

        mockMvc.perform(delete("/v1/users/me").header("Authorization", "Bearer " + ada))
                .andExpect(status().isOk());
        assertEquals(1, repos.count());
        assertEquals(1, watches.count());
        mockMvc.perform(get("/v1/repos").header("Authorization", "Bearer " + grace))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(first));
        mockMvc.perform(delete("/v1/users/me").header("Authorization", "Bearer " + grace))
                .andExpect(status().isOk());
        assertEquals(0, repos.count());
        assertEquals(0, pollStates.count());
    }

    @Test
    void subscriptionStoresLabels() throws Exception {
        String token = register("ada@example.com", "Ada");
        addRepo(token, "https://github.com/apache/iggy");
        String repoId = repos.findAll().get(0).getId().toString();
        String channelId = addChannel(token);
        mockMvc.perform(post("/v1/repos/" + repoId + "/subscriptions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"channel_id":"%s","event_type":"issue.opened","filters":{"labels":["good first issue","help wanted"]}}
                                """.formatted(channelId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.event_type").value("issue.opened"))
                .andExpect(jsonPath("$.data.filters.labels[0]").value("good first issue"))
                .andExpect(jsonPath("$.data.filters.labels[1]").value("help wanted"));
        var stored = subscriptions.findAll().get(0);
        assertEquals("issue.opened", stored.getEventType());
        assertEquals(java.util.List.of("good first issue", "help wanted"), stored.getFilters().getLabels());
    }

    @Test
    void testNotificationDoesNotCallDiscord() throws Exception {
        String token = register("ada@example.com", "Ada");
        String channelId = addChannel(token);
        mockMvc.perform(post("/v1/channels/" + channelId + "/test").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("sent"));
        verify(discord).post(eq(WEBHOOK), eq(ChannelApiService.TEST_MESSAGE));
        String stored = channels.findAll().get(0).getConfigJson();
        assertFalse(stored.contains(WEBHOOK));
        assertTrue(stored.contains("enc:v1:"));
        String listed = mockMvc.perform(get("/v1/channels").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertFalse(listed.contains(WEBHOOK));
        assertFalse(listed.contains("secret-value"));
    }

    @Test
    void oauthEndpointsReturn503WhenClientIdIsUnset() throws Exception {
        mockMvc.perform(get("/v1/auth/github"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("OAUTH_NOT_CONFIGURED"));
        mockMvc.perform(get("/v1/auth/github/callback").param("code", "abc").param("state", "state"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("OAUTH_NOT_CONFIGURED"));
        verify(githubOAuth, never()).exchange(any(), any());
    }

    @Test
    void oauthCallbackUsesTheMockedExchange() throws Exception {
        properties.getOauth().setClientId("client");
        properties.getOauth().setClientSecret("secret");
        when(githubOAuth.exchange(eq("code-1"), any())).thenReturn(
                new GitHubIdentity("42", "octo", "octo@example.com", "Octo", "https://example.invalid/a.png", "gho_test"));
        MvcResult start = mockMvc.perform(get("/v1/auth/github"))
                .andExpect(status().isFound())
                .andReturn();
        String state = query(start.getResponse().getRedirectedUrl(), "state");
        MvcResult callback = mockMvc.perform(get("/v1/auth/github/callback").param("code", "code-1").param("state", state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.email").value("octo@example.com"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        assertFalse(callback.getResponse().getContentAsString().contains("gho_test"));
        verify(githubOAuth).exchange(eq("code-1"), any());
    }

    @Test
    void notificationsArePaginatedForTheCaller() throws Exception {
        String token = register("ada@example.com", "Ada");
        String userId = users.findByEmail("ada@example.com").orElseThrow().getId().toString();
        logs.save(log(userId, "success", "Add support"));
        logs.save(log(userId, "failed", "Other"));
        mockMvc.perform(get("/v1/notifications")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "success")
                        .param("limit", "20")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("Add support"))
                .andExpect(jsonPath("$.data[0].status").value("success"))
                .andExpect(jsonPath("$.pagination.limit").value(20))
                .andExpect(jsonPath("$.pagination.offset").value(0))
                .andExpect(jsonPath("$.pagination.total").value(1));
    }

    @Test
    void authRateLimitFailsClosedOnTheRedisCounter() throws Exception {
        properties.getRateLimit().setAuthPerMinute(1);
        String body = "{\"email\":\"missing@example.com\",\"password\":\"" + PASSWORD + "\"}";
        mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void healthStaysOpenAndReposRequireAuth() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/v1/repos"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private String register(String email, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(email, PASSWORD, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.token");
    }

    private String addRepo(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/repos")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String addChannel(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/channels")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"discord\",\"name\":\"Alerts\",\"config\":{\"webhook_url\":\"" + WEBHOOK + "\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.type").value("discord"))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("secret-value"));
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private static NotificationLog log(String userId, String status, String title) {
        NotificationLog log = new NotificationLog();
        log.setUserId(userId);
        log.setStatus(status);
        log.setEventType("issue.opened");
        log.setTitle(title);
        log.setGithubUrl("https://github.com/apache/iggy/issues/1");
        log.setCreatedAt(Instant.parse("2026-11-17T03:00:00Z"));
        return log;
    }

    private static String userJson(String email, String password, String name) {
        if (name == null) {
            return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        }
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"name\":\"" + name + "\"}";
    }

    private static String query(String url, String name) {
        String query = URI.create(url).getQuery();
        for (String part : query.split("&")) {
            if (part.startsWith(name + "=")) {
                return URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("missing " + name);
    }

    private static String decode(String part) {
        int pad = (4 - part.length() % 4) % 4;
        return new String(Base64.getUrlDecoder().decode(part + "=".repeat(pad)), StandardCharsets.UTF_8);
    }
}
