package agent_backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import agent_backend.tool.AgentTool;
import agent_backend.tool.ToolCall;
import agent_backend.tool.ToolDescriptor;
import agent_backend.tool.ToolDescriptorFactory;
import agent_backend.tool.ToolSchemaValidator;
import agent_backend.tool.result.ToolFailure;
import agent_backend.tool.result.ToolSuccess;
import agent_backend.toolRegistry.ToolRegistry;
import agent_backend.toolRegistry.calculate.CalculateInput;
import agent_backend.toolRegistry.calculate.CalculateOutput;
import agent_backend.toolRegistry.discovery.SearchToolsOutput;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class AgentBackendApplicationTests {
	@Autowired private ToolRegistry registry;
	@Autowired private ToolCall toolCall;
	@Autowired private ToolDescriptorFactory descriptorFactory;
	@Autowired private JsonMapper mapper;
	@Autowired private ToolSchemaValidator schemaValidator;

	@Test
	void contextLoads() {
		assertEquals(3, registry.getAllTools().size());
	}

	@Test
	void discoveryToolsCanQueryRegistryAfterSpringInitialization() {
		var search = assertInstanceOf(ToolSuccess.class,
				toolCall.callTool("search-call", "search_tools", Map.of("query", "calculate")));
		var matches = assertInstanceOf(SearchToolsOutput.class, search.data());
		assertTrue(matches.tools().stream().anyMatch(tool -> tool.name().equals("CalculateTool")));

		var describe = assertInstanceOf(ToolSuccess.class,
				toolCall.callTool("describe-call", "describe_tool", Map.of("name", "CalculateTool")));
		var descriptor = assertInstanceOf(ToolDescriptor.class, describe.data());
		assertEquals("CalculateTool", descriptor.name());
		var schema = assertInstanceOf(Map.class, descriptor.inputSchema());
		var properties = assertInstanceOf(Map.class, schema.get("properties"));
		assertEquals(Map.of("type", "integer"), properties.get("left"));
		assertEquals(Map.of("type", "string"), properties.get("operation"));
	}

	@Test
	void toolFailuresKeepCallIdAndDistinguishFailureStage() {
		var success = assertInstanceOf(ToolSuccess.class,
				toolCall.callTool("call-0", "describe_tool", Map.of("name", "CalculateTool")));
		assertEquals("call-0", success.toolCallId());

		var missing = assertInstanceOf(ToolFailure.class,
				toolCall.callTool("call-1", "missing_tool", Map.of()));
		assertEquals("call-1", missing.toolCallId());
		assertEquals("TOOL_NOT_FOUND", missing.errorCode());

		var invalid = assertInstanceOf(ToolFailure.class,
				toolCall.callTool("call-2", "CalculateTool",
						Map.of("operation", "multiply", "left", "not-an-integer", "right", 2)));
		assertEquals("call-2", invalid.toolCallId());
		assertEquals("INVALID_ARGUMENT", invalid.errorCode());

		var rejected = assertInstanceOf(ToolFailure.class,
				toolCall.callTool("call-3", "search_tools", Map.of("query", "")));
		assertEquals("INVALID_ARGUMENT", rejected.errorCode());

		AgentTool<CalculateInput, CalculateOutput> failingTool = new AgentTool<>() {
			@Override public String name() { return "failing_tool"; }
			@Override public String description() { return "Fails while executing"; }
			@Override public Class<CalculateInput> inputType() { return CalculateInput.class; }
			@Override public Class<CalculateOutput> outputType() { return CalculateOutput.class; }
			@Override public CalculateInput inputExample() { return new CalculateInput("add", 1, 2); }
			@Override public CalculateOutput outputExample() { return new CalculateOutput(3); }
			@Override public CalculateOutput execute(CalculateInput input) {
				throw new IllegalArgumentException("business failure");
			}
		};
		var dispatcher = new ToolCall(new ToolRegistry(List.of(failingTool), descriptorFactory), mapper, schemaValidator);
		var failed = assertInstanceOf(ToolFailure.class,
				dispatcher.callTool("call-4", "failing_tool",
						Map.of("operation", "add", "left", 1, "right", 2)));
		assertEquals("call-4", failed.toolCallId());
		assertEquals("TOOL_EXECUTION_FAILED", failed.errorCode());
	}

	@Test
	void callsAndResultsRequireNonBlankIds() {
		for (String id : Arrays.asList(null, "", " \t")) {
			assertThrows(IllegalArgumentException.class,
					() -> toolCall.callTool(id, "describe_tool", Map.of("name", "CalculateTool")));
			assertThrows(IllegalArgumentException.class,
					() -> LlmDecision.toolCall(id, "describe_tool", Map.of("name", "CalculateTool")));
			assertThrows(IllegalArgumentException.class, () -> new ToolSuccess<>(id, "test", "result"));
			assertThrows(IllegalArgumentException.class,
					() -> new ToolFailure<>(id, "test", "INVALID_ARGUMENT", "invalid"));
		}
	}

	public enum TestMode { READ, WRITE }
	public record TestItem(Integer value) {}
	public record TestInput(TestMode mode, List<TestItem> items) {}
	public record TestOutput(Integer count) {}

	@Test
	void schemaRejectsInvalidArgumentsBeforeBusinessExecution() {
		AtomicInteger executions = new AtomicInteger();
		AgentTool<TestInput, TestOutput> countingTool = new AgentTool<>() {
			@Override public String name() { return "counting_tool"; }
			@Override public String description() { return "Counts executions"; }
			@Override public Class<TestInput> inputType() { return TestInput.class; }
			@Override public Class<TestOutput> outputType() { return TestOutput.class; }
			@Override public TestInput inputExample() { return new TestInput(TestMode.READ, List.of(new TestItem(1))); }
			@Override public TestOutput outputExample() { return new TestOutput(1); }
			@Override public TestOutput execute(TestInput input) { return new TestOutput(executions.incrementAndGet()); }
		};
		var dispatcher = new ToolCall(new ToolRegistry(List.of(countingTool), descriptorFactory), mapper, schemaValidator);
		List<Object> invalidArguments = Arrays.asList(
				null,
				List.of(),
				Map.of("mode", "READ"),
				Map.of("mode", "UNKNOWN", "items", List.of()),
				Map.of("mode", "READ", "items", List.of(), "extra", true),
				Map.of("mode", "READ", "items", List.of(Map.of())),
				Map.of("mode", "READ", "items", List.of(Map.of("value", "1"))),
				Map.of("mode", "READ", "items", List.of(Map.of("value", 1.5))),
				Map.of("mode", "READ", "items", Arrays.asList((Object) null)));
		for (int i = 0; i < invalidArguments.size(); i++) {
			String id = "invalid-" + i;
			var failure = assertInstanceOf(ToolFailure.class,
					dispatcher.callTool(id, "counting_tool", invalidArguments.get(i)));
			assertEquals("INVALID_ARGUMENT", failure.errorCode());
			assertEquals(id, failure.toolCallId());
			assertTrue(!failure.errorMessage().isBlank());
		}
		assertEquals(0, executions.get());

		var success = assertInstanceOf(ToolSuccess.class, dispatcher.callTool("valid-1", "counting_tool",
				Map.of("mode", "READ", "items", List.of(Map.of("value", 1)))));
		assertEquals("valid-1", success.toolCallId());
		assertEquals(1, executions.get());
	}
}
