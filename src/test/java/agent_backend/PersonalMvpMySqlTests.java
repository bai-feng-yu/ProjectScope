package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.UUID;
import agent_backend.personal.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = "agent.scheduler.enabled=false")
@Testcontainers(disabledWithoutDocker=true)
class PersonalMvpMySqlTests {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4")
            .withCommand("--log-bin-trust-function-creators=1");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
    @Autowired ConversationStore conversations;
    @Autowired ConversationService chat;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemoryStore memories;
    @Autowired MemoryService memory;
    @Autowired ResearchStore research;
    @Autowired agent_backend.persistence.TaskStore tasks;
    @Autowired agent_backend.persistence.StepStore steps;
    @Autowired WeeklyResearchStore schedules;
    @MockitoBean LlmClient model;
    String user;
    @BeforeEach void login() { user = TestIdentity.login("personal-alice-" + UUID.randomUUID()).userId(); }
    @AfterEach void logout() { TestIdentity.clear(); }

    @Test void conversationsAreOwnedAndRepliesAreIdempotent() {
        String conversation = conversations.create(user, "Career");
        when(model.completeText(anyString(), anyList())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return "What would you like to explore?";
        });
        var result = chat.send(conversation, "first", "I work with Java");
        assertEquals(2, result.size());
        assertEquals(result, chat.send(conversation, "first", "I work with Java"));
        verify(model, times(2)).completeText(anyString(), anyList()); // Reply + best-effort extraction, neither repeated.
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> chat.send(conversation, "first", "changed")).getStatusCode().value());
        String other = TestIdentity.login("personal-bob").userId();
        assertTrue(conversations.list(other).isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> conversations.messages(other, conversation)).getStatusCode().value());
    }

    @Test void modelFailureKeepsUserMessageAndDoesNotPublishAnAnswer() {
        String conversation = conversations.create(user, "Failure");
        when(model.completeText(anyString(), anyList())).thenThrow(new IllegalStateException("offline"));
        var result = chat.send(conversation, "failure", "hello");
        assertEquals(1, result.size());
        assertEquals("FAILED", result.getFirst().status());
    }

    @Test void pendingTurnSerializesConversationAndCrashDoesNotRetryOldPrompt() {
        String conversation = conversations.create(user, "Pending");
        var first = conversations.begin(user, conversation, "one", "hello");
        assertFalse(conversations.begin(user, conversation, "one", "hello").created());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> conversations.begin(user, conversation, "two", "next")).getStatusCode().value());
        jdbc.update("UPDATE conversation_message SET created_at=UTC_TIMESTAMP(6)-INTERVAL 4 MINUTE WHERE message_id=?",
                first.message().messageId());
        assertTrue(conversations.begin(user, conversation, "two", "next").created());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> conversations.finish(user, conversation, first.message().messageId(), "late", null)).getStatusCode().value());
    }

    @Test void confirmedMemorySurvivesSessionsAndCorrectionDeletionAreVersioned() {
        String conversation = conversations.create(user, "Career");
        when(model.completeText(anyString(), anyList())).thenAnswer(call -> {
            String system = call.getArgument(0);
            if (system.startsWith("Extract")) return """
                    {"memories":[{"topic":"career","kind":"PREFERENCE","content":"Wants remote Java work"}]}
                    """;
            return "Let's explore your preferences.";
        });
        chat.send(conversation, "first", "I want remote Java work");
        var candidate = memories.list(user).getFirst();
        assertEquals("CANDIDATE", candidate.status());
        assertEquals("[]", memory.context(user, "career"));
        var confirmed = memories.confirm(user, candidate.memoryId(), candidate.version(), false);
        assertTrue(memory.context(user, "career").contains("remote Java"));
        String next = conversations.create(user, "New session");
        chat.send(next, "second", "What career options fit me?");
        verify(model).completeText(contains("Wants remote Java work"), anyList());
        var corrected = memories.correct(user, confirmed.memoryId(), confirmed.version(), "Now prefers hybrid work");
        assertFalse(memory.context(user, "career").contains("remote Java"));
        assertTrue(memory.context(user, "career").contains("hybrid"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> memories.correct(user, confirmed.memoryId(), confirmed.version(), "stale")).getStatusCode().value());
        String other = TestIdentity.login("memory-other").userId();
        assertTrue(memories.list(other).isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> memories.confirm(other, corrected.memoryId(), corrected.version(), true)).getStatusCode().value());
        memories.delete(user, corrected.memoryId(), corrected.version());
        assertTrue(memories.list(user).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM personal_memory WHERE user_id=? AND content<>''", Integer.class, user));
        clearInvocations(model);
        memory.extract(user, candidate.sourceMessageId(), "I want remote Java work");
        verifyNoInteractions(model);
    }

    @Test void memorySettingsAndConflictingCandidatesNeverSilentlyOverwriteFacts() {
        String conversation = conversations.create(user, "Conflict");
        var turn = conversations.begin(user, conversation, "source", "My goal is Java work");
        String source = turn.message().messageId();
        assertTrue(memories.reserveSource(user, source));
        memories.candidates(user, source, java.util.List.of(new MemoryStore.Candidate("career", "FACT", "Java")));
        var first = memories.list(user).getFirst();
        memories.confirm(user, first.memoryId(), first.version(), false);
        memories.candidates(user, source, java.util.List.of(new MemoryStore.Candidate("career", "INFERENCE", "Python")));
        var second = memories.list(user).stream().filter(m -> m.status().equals("CANDIDATE")).findFirst().orElseThrow();
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> memories.confirm(user, second.memoryId(), second.version(), false)).getStatusCode().value());
        memories.settings(user, false);
        assertEquals("[]", memory.context(user, "career"));
        assertTrue(memories.list(user).stream().noneMatch(m -> m.status().equals("CANDIDATE")));
        memories.settings(user, true);
        assertTrue(memory.context(user, "career").contains("Java"));
        assertFalse(memory.context(user, "career").contains("Python"));
        assertFalse(memories.reserveSource(user, source));
    }

    @Test void researchSubmissionAndResultPublicationAreDurableAndIdempotent() {
        String conversation = conversations.create(user, "Research");
        var first = research.submit(user, conversation, "research", "Compare Java roles");
        assertEquals(first, research.submit(user, conversation, "research", "Compare Java roles"));
        assertEquals(first.runId(), conversations.messages(user, conversation).getFirst().runId());
        assertTrue(tasks.findOwned(first.runId(), user).isPresent());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> chat.send(conversation, "research", "Compare Java roles")).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> research.submit("other", conversation, "key", "query")).getStatusCode().value());
        tasks.failPending(first.runId(), "Search unavailable");
        assertTrue(research.unpublished(50).contains(first.messageId()));
        research.publish(first.messageId());
        research.publish(first.messageId());
        assertEquals(2, conversations.messages(user, conversation).size());
        assertEquals("Research failed: Search unavailable", conversations.messages(user, conversation).getLast().content());
        assertFalse(research.unpublished(50).contains(first.messageId()));
    }

    @Test void researchRunRollsBackWhenMessageCannotBeSaved() {
        String conversation = conversations.create(user, "Rollback");
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM agent_task", Integer.class);
        jdbc.execute("""
                CREATE TRIGGER reject_research BEFORE INSERT ON conversation_message FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected message failure'
                """);
        try {
            assertThrows(org.springframework.dao.DataAccessException.class,
                    () -> research.submit(user, conversation, "fail", "query"));
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task", Integer.class));
        } finally { jdbc.execute("DROP TRIGGER reject_research"); }
        assertNotNull(research.submit(user, conversation, "fail", "query").runId());
    }

    @Test void runningLimitQueuesExcessWorkAndCancelFencesLateWriters() {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString(), third = UUID.randomUUID().toString();
        for (String id : java.util.List.of(first, second, third)) tasks.createOwned(id, "work", user);
        var lease = tasks.start(new agent_backend.persistence.TaskLease(first, "worker"), 60000);
        tasks.start(new agent_backend.persistence.TaskLease(second, "worker"), 60000);
        assertThrows(IllegalStateException.class, () -> tasks.start(new agent_backend.persistence.TaskLease(third, "worker"), 60000));
        assertFalse(tasks.findPending(100).contains(third));
        tasks.cancelOwned(first, user);
        tasks.cancelOwned(first, user);
        assertEquals(agent_backend.persistence.model.TaskStatus.CANCELLED, tasks.findOwned(first, user).orElseThrow().status());
        assertThrows(agent_backend.persistence.LeaseLostException.class, () -> tasks.complete(lease, "late"));
        assertTrue(tasks.findPending(100).contains(third));
        assertNotNull(tasks.start(new agent_backend.persistence.TaskLease(third, "worker"), 60000));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> tasks.cancelOwned(second, "other")).getStatusCode().value());
    }

    @Test void deadlineFencesWritesEvenWithLiveHeartbeatAndRecoveryPublishesFailure() {
        String id = UUID.randomUUID().toString();
        tasks.createOwned(id, "work", user);
        var lease = tasks.start(new agent_backend.persistence.TaskLease(id, "worker"), 60000);
        jdbc.update("UPDATE agent_task SET deadline_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE task_id=?", id);
        assertFalse(tasks.renew(lease, 60000));
        assertThrows(agent_backend.persistence.LeaseLostException.class, () -> tasks.complete(lease, "late"));
        assertTrue(tasks.findExpired(100).contains(id));
        assertTrue(tasks.recoverExpired(id));
        assertTrue(tasks.findOwned(id, user).orElseThrow().failureReason().contains("deadline"));
        tasks.cancelOwned(id, user);
        assertEquals(agent_backend.persistence.model.TaskStatus.FAILED, tasks.findOwned(id, user).orElseThrow().status());
    }

    @Test void weeklyResearchCoalescesMissedSlotsAvoidsOverlapAndHonorsPause() {
        String conversation = conversations.create(user, "Weekly");
        var first = java.time.Instant.now().plusSeconds(3600);
        String id = schedules.create(user, conversation, "Weekly career research", first, "schedule");
        assertEquals(id, schedules.create(user, conversation, "Weekly career research", first, "schedule"));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> schedules.pause("other", id, true)).getStatusCode().value());
        jdbc.update("UPDATE scheduled_research SET next_fire_at=UTC_TIMESTAMP(6)-INTERVAL 15 DAY WHERE schedule_id=?", id);
        assertTrue(schedules.due().contains(id));
        schedules.pause(user, id, true);
        schedules.fire(id);
        assertNull(schedules.list(user).getFirst().lastRunId());
        schedules.pause(user, id, false);
        schedules.fire(id);
        var fired = schedules.list(user).getFirst();
        assertEquals("SUBMITTED", fired.lastOutcome());
        assertNotNull(fired.lastRunId());
        assertTrue(fired.nextFireAt().isAfter(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)));
        schedules.fire(id);
        assertEquals(fired.lastRunId(), schedules.list(user).getFirst().lastRunId());
        jdbc.update("UPDATE scheduled_research SET next_fire_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE schedule_id=?", id);
        schedules.fire(id);
        assertEquals("SKIPPED_OVERLAP", schedules.list(user).getFirst().lastOutcome());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task WHERE user_id=?", Integer.class, user));
        tasks.cancelOwned(fired.lastRunId(), user);
        jdbc.update("UPDATE scheduled_research SET next_fire_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE schedule_id=?", id);
        schedules.fire(id);
        assertNotEquals(fired.lastRunId(), schedules.list(user).getFirst().lastRunId());
    }

    @Test void credentialBearingSourcesAndCandidatesDoNotEnterMemory() {
        String conversation = conversations.create(user, "Privacy");
        var turn = conversations.begin(user, conversation, "secret", "api_key: test-secret-not-real");
        memory.extract(user, turn.message().messageId(), turn.message().content());
        verifyNoInteractions(model);
        assertFalse(memories.reserveSource(user, turn.message().messageId()));
        memories.candidates(user, turn.message().messageId(), java.util.List.of(
                new MemoryStore.Candidate("credential", "FACT", "password: example-not-real")));
        assertTrue(memories.list(user).isEmpty());
    }

    @Test void concurrentCancelAndFinalCompletionHaveOneConsistentTerminalOutcome() throws Exception {
        String run = UUID.randomUUID().toString(), step = UUID.randomUUID().toString();
        tasks.createOwned(run, "race", user);
        var lease = tasks.start(new agent_backend.persistence.TaskLease(run, "worker"), 60000);
        steps.create(lease, step, 1);
        steps.saveResponse(lease, step, Map.of("answer", "done"));
        var gate = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var completed = workers.submit(() -> {
                gate.await();
                try { tasks.completeFinal(lease, step, "done"); }
                catch (agent_backend.persistence.LeaseLostException expected) { /* Cancellation won the Run lock. */ }
                return null;
            });
            var cancelled = workers.submit(() -> { gate.await(); tasks.cancelOwned(run, user); return null; });
            gate.countDown();
            completed.get(10, java.util.concurrent.TimeUnit.SECONDS);
            cancelled.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        var status = tasks.find(run).orElseThrow().status();
        assertTrue(status == agent_backend.persistence.model.TaskStatus.COMPLETED || status == agent_backend.persistence.model.TaskStatus.CANCELLED);
        assertEquals(status == agent_backend.persistence.model.TaskStatus.COMPLETED ? "COMPLETED" : "FAILED",
                steps.findByTask(run).getFirst().status().name());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM run_event WHERE run_id=? AND event_type IN ('run.completed','run.cancelled')", Integer.class, run));
    }

    @Test void concurrentWeeklyScansCreateExactlyOneRun() throws Exception {
        String conversation = conversations.create(user, "Concurrent weekly");
        String id = schedules.create(user, conversation, "Research", java.time.Instant.now().plusSeconds(3600), "weekly-race");
        jdbc.update("UPDATE scheduled_research SET next_fire_at=UTC_TIMESTAMP(6)-INTERVAL 1 SECOND WHERE schedule_id=?", id);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> schedules.fire(id));
            var two = workers.submit(() -> schedules.fire(id));
            one.get(10, java.util.concurrent.TimeUnit.SECONDS);
            two.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task WHERE user_id=?", Integer.class, user));
        assertEquals("Asia/Shanghai", schedules.list(user).getFirst().timeZone());
    }

    @Test void concurrentMessageAndResearchRetriesSeeTheCommittedWinner() throws Exception {
        String conversation = conversations.create(user, "Concurrent conversation");
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> conversations.begin(user, conversation, "same-chat", "hello"));
            var two = workers.submit(() -> conversations.begin(user, conversation, "same-chat", "hello"));
            var first = one.get(10, java.util.concurrent.TimeUnit.SECONDS);
            var second = two.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(first.message().messageId(), second.message().messageId());
            assertNotEquals(first.created(), second.created());
            var researchOne = workers.submit(() -> research.submit(user, conversation, "same-research", "query"));
            var researchTwo = workers.submit(() -> research.submit(user, conversation, "same-research", "query"));
            assertEquals(researchOne.get(10, java.util.concurrent.TimeUnit.SECONDS), researchTwo.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
