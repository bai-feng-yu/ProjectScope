package agent_backend.eval;

import static org.junit.jupiter.api.Assertions.*;
import agent_backend.personal.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Deterministic product contracts, explicitly not Memora benchmark scores. */
@SpringBootTest(properties={"agent.scheduler.enabled=false"})
@Testcontainers(disabledWithoutDocker=true)
class MemoryLifecycleBaselineTests {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
    @Autowired ConversationStore conversations;
    @Autowired MemoryStore store;
    @Autowired MemoryService memory;

    private String user() { return UUID.randomUUID().toString().replace("-", "").repeat(2); }
    private MemoryStore.Memory confirmed(String user) {
        String conversation = conversations.create(user, "Lifecycle baseline");
        var turn = conversations.begin(user, conversation, UUID.randomUUID().toString(), "I prefer remote Java work.");
        conversations.finish(user, conversation, turn.message().messageId(), "Recorded", null);
        store.candidates(user, turn.message().messageId(), List.of(new MemoryStore.Candidate("career", "PREFERENCE", "remote Java work")));
        var candidate = store.list(user).getFirst();
        assertTrue(memory.relevant(user, "career").isEmpty(), "Unconfirmed candidates cannot influence answers");
        return store.confirm(user, candidate.memoryId(), candidate.version(), false);
    }
    @Test void correctionReplacesOldPreferenceInRetrievedContext() {
        String user = user();
        var old = confirmed(user);
        store.correct(user, old.memoryId(), old.version(), "onsite Java work");
        String context = memory.context(user, "career");
        assertTrue(context.contains("onsite Java work"));
        assertFalse(context.contains("remote Java work"));
        assertTrue(memory.relevant(user(), "career").isEmpty());
    }
    @Test void deletionRemovesTopicVersionsButDoesNotErasePriorSnapshots() {
        String user = user();
        var old = confirmed(user);
        String previousSnapshot = memory.context(user, "career");
        var replacement = store.correct(user, old.memoryId(), old.version(), "onsite Java work");
        store.delete(user, replacement.memoryId(), replacement.version());
        assertEquals("[]", memory.context(user, "career"));
        assertTrue(store.list(user).isEmpty());
        assertTrue(previousSnapshot.contains("remote Java work"), "Already materialized Run context is not retroactively erased");
    }
    @Test void disablingMemorySuppressesRetrievalWithoutDeletingConfirmedRecords() {
        String user = user();
        confirmed(user);
        store.settings(user, false);
        assertEquals("[]", memory.context(user, "career"));
        assertEquals(1, store.list(user).size());
        store.settings(user, true);
        assertEquals(1, memory.relevant(user, "career").size());
    }
}
