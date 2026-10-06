package agent_backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import agent_backend.tool.ToolCall;
import agent_backend.tool.ToolDescriptor;
import agent_backend.tool.result.ToolSuccess;
import agent_backend.toolRegistry.ToolRegistry;
import agent_backend.toolRegistry.discovery.SearchToolsOutput;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AgentBackendApplicationTests {
	@Autowired private ToolRegistry registry;
	@Autowired private ToolCall toolCall;

	@Test
	void contextLoads() {
		assertEquals(3, registry.getAllTools().size());
	}

	@Test
	void discoveryToolsCanQueryRegistryAfterSpringInitialization() {
		var search = assertInstanceOf(ToolSuccess.class,
				toolCall.callTool("search_tools", Map.of("query", "calculate")));
		var matches = assertInstanceOf(SearchToolsOutput.class, search.data());
		assertTrue(matches.tools().stream().anyMatch(tool -> tool.name().equals("CalculateTool")));

		var describe = assertInstanceOf(ToolSuccess.class,
				toolCall.callTool("describe_tool", Map.of("name", "CalculateTool")));
		var descriptor = assertInstanceOf(ToolDescriptor.class, describe.data());
		assertEquals("CalculateTool", descriptor.name());
		var schema = assertInstanceOf(Map.class, descriptor.inputSchema());
		var properties = assertInstanceOf(Map.class, schema.get("properties"));
		assertEquals(Map.of("type", "integer"), properties.get("left"));
		assertEquals(Map.of("type", "string"), properties.get("operation"));
	}
}
