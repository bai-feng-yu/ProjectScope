package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import agent_backend.personal.ConversationStore;
import agent_backend.personal.ResearchStore;
import agent_backend.persistence.TaskLease;
import agent_backend.persistence.TaskStore;
import agent_backend.persistence.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Two actual application contexts against the same fresh MySQL; no production database or provider is used. */
@Testcontainers(disabledWithoutDocker=true)
class PersonalAgentRestartTests {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");
    @TestConfiguration(proxyBeanMethods=false)
    static class Fixture {
        @Bean @Primary LlmClient restartModel() {
            var model = mock(LlmClient.class);
            when(model.request(any())).thenReturn(Map.of("fixture", true));
            when(model.parseDecision(any())).thenReturn(LlmDecision.finalAnswer("Result after restart"));
            return model;
        }
    }
    private ConfigurableApplicationContext start(boolean scheduler) {
        return new SpringApplicationBuilder(AgentBackendApplication.class, Fixture.class).run(
                "--server.port=0", "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                "--spring.datasource.url=" + mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?")
                        + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "--spring.datasource.username=" + mysql.getUsername(), "--spring.datasource.password=" + mysql.getPassword(),
                "--agent.scheduler.enabled=" + scheduler, "--agent.scheduler.poll-interval-ms=100",
                "--agent.lease.recovery-interval-ms=100", "--brave.enabled=false");
    }

    @Test void restartExecutesCommittedPendingResearchAndSafelyFailsAnOrphanedAttempt() {
        String user = "d".repeat(64), orphan = UUID.randomUUID().toString();
        ResearchStore.Submission submission;
        try (var first = start(false)) {
            var conversations = first.getBean(ConversationStore.class);
            String conversation = conversations.create(user, "Restart");
            submission = first.getBean(ResearchStore.class).submit(user, conversation, "pending", "Research after restart");
            var tasks = first.getBean(TaskStore.class);
            tasks.createOwned(orphan, "Interrupted work", user);
            tasks.start(new TaskLease(orphan, "old-worker"), 60000);
            first.getBean(JdbcTemplate.class).update("UPDATE run_attempt SET lease_until=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE run_id=?", orphan);
        }
        try (var restarted = start(true)) {
            var tasks = restarted.getBean(TaskStore.class);
            var jdbc = restarted.getBean(JdbcTemplate.class);
            assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
                while (tasks.find(submission.runId()).orElseThrow().status() != TaskStatus.COMPLETED
                        || tasks.find(orphan).orElseThrow().status() != TaskStatus.FAILED
                        || jdbc.queryForObject("SELECT COUNT(*) FROM conversation_message WHERE reply_to=?", Integer.class, submission.messageId()) != 1)
                    Thread.sleep(50);
            });
            assertEquals("Result after restart", tasks.find(submission.runId()).orElseThrow().finalAnswer());
            assertTrue(tasks.find(orphan).orElseThrow().failureReason().contains("lease expired"));
        }
    }
}
