package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import agent_backend.persistence.TaskStore;
import agent_backend.persistence.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TaskApiMySqlTests {
    @org.junit.jupiter.api.BeforeEach
    void identity() { TestIdentity.login("alice"); }
    @org.junit.jupiter.api.AfterEach
    void clearIdentity() { TestIdentity.clear(); }
    @Container
    static final MySQLContainer mysql = new MySQLContainer("mysql:8.4").withDatabaseName("task_api_test");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> mysql.getJdbcUrl()
                + (mysql.getJdbcUrl().contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        properties.add("spring.datasource.username", mysql::getUsername);
        properties.add("spring.datasource.password", mysql::getPassword);
    }

    @MockitoBean LlmClient llm;
    @Autowired WebApplicationContext context;
    @Autowired TaskStore tasks;
    @Autowired ObjectMapper mapper;

    @Test
    void acceptedTaskIsVisibleToWorkerAndQueryableDuringAndAfterExecution() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var committedBeforeExecution = new AtomicBoolean();
        when(llm.request(any())).thenAnswer(invocation -> {
            AgentState state = invocation.getArgument(0);
            committedBeforeExecution.set(tasks.find(state.getTaskId()).isPresent()
                    && !TransactionSynchronizationManager.isActualTransactionActive());
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Map.of("test", "response");
        });
        when(llm.parseDecision(any())).thenReturn(LlmDecision.finalAnswer("done"));
        var mvc = MockMvcBuilders.webAppContextSetup(context).build();
        String id;
        try {
            var response = mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"task\":\"hello\"}"))
                    .andExpect(status().isAccepted()).andReturn().getResponse();
            id = mapper.readTree(response.getContentAsString()).get("taskId").asText();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(committedBeforeExecution.get());
            mvc.perform(get("/tasks/{id}", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RUNNING"));
            mvc.perform(get("/tasks/{id}/steps", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].status").value("RUNNING"));
        } finally {
            release.countDown();
        }
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (tasks.find(id).orElseThrow().status() != TaskStatus.COMPLETED) {
                Thread.sleep(20);
            }
        });
        mvc.perform(get("/tasks/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.finalAnswer").value("done"));
        mvc.perform(get("/tasks/{id}/steps", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
        verify(llm, times(1)).request(any());
    }

    @Test
    void persistedPendingTaskNeedsNoInMemorySubmissionToBeDiscovered() {
        when(llm.request(any())).thenReturn(Map.of("test", "response"));
        when(llm.parseDecision(any())).thenReturn(LlmDecision.finalAnswer("recovered pending task"));
        String id = java.util.UUID.randomUUID().toString();
        tasks.create(id, "previously submitted task");
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (tasks.find(id).orElseThrow().status() != TaskStatus.COMPLETED) Thread.sleep(20);
        });
        assertEquals("recovered pending task", tasks.find(id).orElseThrow().finalAnswer());
        verify(llm, times(1)).request(any());
    }
}
