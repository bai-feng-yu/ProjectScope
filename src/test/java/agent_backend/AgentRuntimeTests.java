package agent_backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import agent_backend.tool.ToolCall;
import agent_backend.tool.SequentialToolBatchExecutor;
import agent_backend.tool.result.ToolFailure;
import agent_backend.tool.result.ToolSuccess;
import agent_backend.toolRegistry.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentRuntimeTests {
    private final LlmClient llm = mock(LlmClient.class);
    private final ToolCall tools = mock(ToolCall.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);

    private AgentRunner runner(int maxSteps) {
        return new AgentRunner(llm, new SequentialToolBatchExecutor(tools), registry, maxSteps);
    }

    private AgentStep step() {
        return new AgentStep(LlmDecision.toolCall("call-1", "test_tool", Map.of()),
                List.of(new ToolSuccess<>("call-1", "test_tool", "ok")));
    }

    @Test
    void terminalStatesRejectMutationsAndExposeReadOnlyStepSnapshots() {
        for (boolean completed : List.of(true, false)) {
            AgentState state = new AgentState("task");
            var before = state.getSteps();
            state.addStep(step());
            assertTrue(before.isEmpty());
            if (completed) state.complete("done");
            else state.fail("failure");

            assertThrows(IllegalStateException.class, () -> state.addStep(step()));
            assertThrows(IllegalStateException.class, () -> state.complete("changed"));
            assertThrows(IllegalStateException.class, () -> state.fail("changed"));
            assertThrows(IllegalStateException.class, () -> state.setTools(List.of()));
            assertThrows(UnsupportedOperationException.class, () -> state.getSteps().clear());
            assertEquals(completed ? AgentState.AgentStateType.COMPLETED : AgentState.AgentStateType.FAILED,
                    state.getAgentStateType());
            assertEquals(completed ? "done" : null, state.getFinalAnswer());
            assertEquals(completed ? null : "failure", state.getFailureReason());
            assertEquals(1, state.getSteps().size());
        }
    }

    @Test
    void modelFailurePreservesCompletedToolSteps() {
        when(llm.decide(any())).thenReturn(step().getDecision())
                .thenThrow(new IllegalStateException("Invalid model response"));
        when(tools.callTool("call-1", "test_tool", Map.of())).thenAnswer(invocation -> step().getToolResults().getFirst());

        var state = runner(3).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        assertEquals("IllegalStateException: Invalid model response", state.getFailureReason());
        assertEquals(1, state.getSteps().size());
        assertNull(state.getFinalAnswer());
    }

    @Test
    void unexpectedDispatcherFailureBecomesFailedState() {
        when(llm.decide(any())).thenReturn(step().getDecision());
        when(tools.callTool("call-1", "test_tool", Map.of())).thenThrow(new IllegalStateException());
        var state = runner(3).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        assertEquals("IllegalStateException", state.getFailureReason());
    }

    @Test
    void toolFailureCanBeFollowedBySuccessfulAnswer() {
        when(llm.decide(any())).thenReturn(step().getDecision(), LlmDecision.finalAnswer("Recovered"));
        when(tools.callTool("call-1", "test_tool", Map.of())).thenReturn(
                new ToolFailure<>("call-1", "test_tool", "INVALID_ARGUMENT", "Missing field"));
        var state = runner(3).run("task");
        assertEquals(AgentState.AgentStateType.COMPLETED, state.getAgentStateType());
        assertEquals("Recovered", state.getFinalAnswer());
        assertNull(state.getFailureReason());
        assertEquals(1, state.getSteps().size());
    }

    @Test
    void stepLimitReturnsFailureWithHistoryInsteadOfThrowing() {
        when(llm.decide(any())).thenReturn(step().getDecision());
        when(tools.callTool("call-1", "test_tool", Map.of())).thenAnswer(invocation -> step().getToolResults().getFirst());
        var state = runner(1).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        assertEquals("Agent exceeded maximum steps: 1", state.getFailureReason());
        assertEquals(1, state.getSteps().size());
    }

    @Test
    void preparationFailureAlsoReturnsFailedState() {
        when(registry.getAllTools()).thenThrow(new IllegalStateException("Registry unavailable"));
        var state = runner(3).run("task");
        assertEquals(AgentState.AgentStateType.FAILED, state.getAgentStateType());
        assertTrue(state.getFailureReason().contains("Registry unavailable"));
    }

    @Test
    void taskEndpointReturnsStructuredSuccessAndFailure() throws Exception {
        when(llm.decide(any())).thenReturn(LlmDecision.finalAnswer("Hello"))
                .thenThrow(new IllegalStateException("Model unavailable"));
        var mvc = MockMvcBuilders.standaloneSetup(new TaskControler(runner(3))).build();

        mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").value("hello"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.finalAnswer").value("Hello"))
                .andExpect(jsonPath("$.steps").isArray());

        mvc.perform(post("/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"task\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("IllegalStateException: Model unavailable"))
                .andExpect(jsonPath("$.steps").isArray());
    }

    @Test
    void batchRunsInOrderAndContinuesAfterToolFailure() {
        var calls = List.of(
                new agent_backend.tool.ToolInvocation("first", "test_tool", Map.of("value", 1)),
                new agent_backend.tool.ToolInvocation("second", "test_tool", Map.of("value", 2)));
        when(llm.decide(any())).thenReturn(LlmDecision.toolCalls(calls, null), LlmDecision.finalAnswer("done"));
        when(tools.callTool("first", "test_tool", Map.of("value", 1)))
                .thenReturn(new ToolFailure<>("first", "test_tool", "INVALID_ARGUMENT", "Invalid"));
        when(tools.callTool("second", "test_tool", Map.of("value", 2)))
                .thenAnswer(invocation -> new ToolSuccess<>("second", "test_tool", "ok"));
        var state = runner(2).run("task");
        var order = org.mockito.Mockito.inOrder(tools);
        order.verify(tools).callTool("first", "test_tool", Map.of("value", 1));
        order.verify(tools).callTool("second", "test_tool", Map.of("value", 2));
        assertEquals(AgentState.AgentStateType.COMPLETED, state.getAgentStateType());
        assertEquals(1, state.getSteps().size());
        assertEquals(List.of("first", "second"), state.getSteps().getFirst().getToolResults()
                .stream().map(result -> result.toolCallId()).toList());
    }

    @Test
    void batchRejectsDuplicateIdsAndMismatchedResults() {
        var call = new agent_backend.tool.ToolInvocation("id", "test_tool", Map.of());
        assertThrows(IllegalArgumentException.class, () -> LlmDecision.toolCalls(List.of(call, call), null));
        var decision = LlmDecision.toolCalls(List.of(call), null);
        assertThrows(IllegalArgumentException.class, () -> new AgentStep(decision, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AgentStep(decision,
                List.of(new ToolSuccess<>("wrong-id", "test_tool", "ok"))));
    }
}
