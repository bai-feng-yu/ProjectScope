package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import agent_backend.persistence.*;
import agent_backend.persistence.model.*;
import agent_backend.task.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class TaskApiTests {
    private final TaskStore tasks = mock(TaskStore.class);
    private final StepStore steps = mock(StepStore.class);
    private final ToolInvocationStore invocations = mock(ToolInvocationStore.class);
    private final TaskDispatcher dispatcher = mock(TaskDispatcher.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        var service = new TaskService(tasks, steps, invocations, dispatcher, mapper);
        mvc = MockMvcBuilders.standaloneSetup(new TaskControler(service))
                .setControllerAdvice(new TaskApiExceptionHandler()).build();
    }

    @Test
    void submitPersistsBeforeDispatchAndReturnsAcceptedWithLocation() throws Exception {
        var response = mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"task\":\"hello\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.finalAnswer").doesNotExist()).andReturn().getResponse();
        String id = mapper.readTree(response.getContentAsString()).get("taskId").asText();
        assertEquals(id, UUID.fromString(id).toString());
        assertEquals("/tasks/" + id, response.getHeader("Location"));
        var order = inOrder(tasks, dispatcher);
        order.verify(tasks).create(id, "hello");
        order.verify(dispatcher).dispatch(id);
        verifyNoInteractions(steps, invocations);
    }

    @Test
    void invalidInputsNeverCreateOrScheduleTasks() throws Exception {
        for (String body : List.of("{}", "{\"task\":null}", "{\"task\":\" \"}", "null", "{")) {
            mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(tasks, dispatcher);
    }

    @Test
    void detailsReadPersistedSuccessAndFailureWithoutScheduling() throws Exception {
        String id = UUID.randomUUID().toString();
        var time = LocalDateTime.of(2026, 10, 8, 1, 2, 3);
        when(tasks.find(id)).thenReturn(Optional.of(new TaskRecord(id, "task", TaskStatus.COMPLETED,
                "done", null, time, time, time, time)));
        mvc.perform(get("/tasks/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.finalAnswer").value("done"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-08T01:02:03Z"));
        when(tasks.find(id)).thenReturn(Optional.of(new TaskRecord(id, "task", TaskStatus.FAILED,
                null, "model failed", time, time, time, time)));
        mvc.perform(get("/tasks/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("model failed"));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void stepsExposePartialPersistedResultsAsJsonObjects() throws Exception {
        String id = UUID.randomUUID().toString();
        when(tasks.find(id)).thenReturn(Optional.of(new TaskRecord(id, "task", TaskStatus.RUNNING,
                null, null, null, null, null, null)));
        when(steps.findByTask(id)).thenReturn(List.of(new StepRecord("step", id, 1, StepStatus.RUNNING,
                "{\"choices\":[]}", null, null, null, null)));
        when(invocations.findByStep("step")).thenReturn(List.of(
                new ToolInvocationRecord("inv-1", "step", "model-1", 1, "test", "{\"value\":1}",
                        ToolCallStatus.SUCCEEDED, "{\"data\":\"ok\"}", null, null, null, null, null),
                new ToolInvocationRecord("inv-2", "step", "model-2", 2, "test", "{}",
                        ToolCallStatus.RUNNING, null, null, null, null, null, null)));
        mvc.perform(get("/tasks/{id}/steps", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stepNumber").value(1))
                .andExpect(jsonPath("$[0].modelResponse.choices").isArray())
                .andExpect(jsonPath("$[0].calls[0].arguments.value").value(1))
                .andExpect(jsonPath("$[0].calls[0].result.data").value("ok"))
                .andExpect(jsonPath("$[0].calls[1].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].calls[1].result").doesNotExist());
        verifyNoInteractions(dispatcher);
    }

    @Test
    void missingTasksReturn404AndNewTasksHaveEmptySteps() throws Exception {
        String id = UUID.randomUUID().toString();
        mvc.perform(get("/tasks/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/tasks/{id}/steps", id)).andExpect(status().isNotFound());
        mvc.perform(get("/tasks/not-a-uuid")).andExpect(status().isBadRequest());
        when(tasks.find(id)).thenReturn(Optional.of(new TaskRecord(id, "task", TaskStatus.PENDING,
                null, null, null, null, null, null)));
        mvc.perform(get("/tasks/{id}/steps", id)).andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void rejectedSubmissionHasQueryableIdAndFailedRecord() throws Exception {
        doThrow(new RejectedExecutionException("full")).when(dispatcher).dispatch(anyString());
        var response = mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"task\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TASK_SUBMISSION_REJECTED"))
                .andReturn().getResponse();
        String id = mapper.readTree(response.getContentAsString()).get("taskId").asText();
        verify(tasks).fail(eq(id), contains("submission rejected"));
    }

    @Test
    void databaseFailureDoesNotDispatchAndHidesSqlDetails() throws Exception {
        doThrow(new DataAccessResourceFailureException("secret connection info")).when(tasks).create(any(), any());
        mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TASK_STORAGE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Task storage is temporarily unavailable"));
        verifyNoInteractions(dispatcher);
    }
}
