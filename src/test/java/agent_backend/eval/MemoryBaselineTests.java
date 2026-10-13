package agent_backend.eval;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import agent_backend.dev.LocalBrowserServices;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker=true)
class MemoryBaselineTests {
    @TempDir Path directory;
    @Test void isolatedRunnerImportsRetrievesAndWritesUngradedEvidenceWithoutProviderKeys() throws Exception {
        try (var fixture = new LocalBrowserServices(0, "evaluation-fixture-control-key-32-characters", "test", true)) {
            Path configuration = directory.resolve("model.properties");
            Files.writeString(configuration, "llm.base-url=" + fixture.address() + "\nllm.model=local-eval-fixture\nllm.api-key=fixture\n");
            Path input = directory.resolve("input.json");
            String source = Files.readString(Path.of("src/test/resources/memory-eval/smoke.json"))
                    .replace("I am a Java backend engineer. I prefer fully remote work and want to build personal AI agents.", "我从事 Java 工作，希望远程办公。");
            Files.writeString(input, source);
            Path output = directory.resolve("results");
            MemoryBaseline.main(new String[]{input.toString(), output.toString(), configuration.toString(), "10"});
            var result = JsonMapper.builder().build().readTree(Files.readString(output.resolve("case-000.json")));
            assertEquals("ANSWERED_UNSCORED", result.path("status").asText());
            assertEquals(2, result.path("importedTurns").asInt());
            assertEquals(1, result.path("confirmedCandidates").asInt());
            assertEquals("intro", result.path("retrievedSessionIds").path(0).asText());
            assertTrue(result.path("hypothesis").asText().contains("远程办公"));
            assertFalse(result.path("noMemoryHypothesis").asText().contains("远程办公"));
            assertThrows(IllegalArgumentException.class, () -> MemoryBaseline.main(
                    new String[]{input.toString(), output.toString(), configuration.toString(), "10"}));
        }
    }
}
