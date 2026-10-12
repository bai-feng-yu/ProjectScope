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
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
    @Autowired ConversationStore conversations;
    @Autowired ConversationService chat;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean LlmClient model;
    String user;
    @BeforeEach void login() { user = TestIdentity.login("personal-alice").userId(); }
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
        verify(model, times(1)).completeText(anyString(), anyList());
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
}
