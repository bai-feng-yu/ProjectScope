package agent_backend.personal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import agent_backend.security.TaskIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/memories")
public class MemoryController {
    public record Confirm(@Min(1) long version, boolean replaceExisting) {}
    public record Correct(@Min(1) long version, @NotBlank @Size(max=2000) String content) {}
    public record Settings(boolean enabled) {}
    private final MemoryStore store;
    public MemoryController(MemoryStore store) { this.store = store; }
    @GetMapping public List<MemoryStore.Memory> list() { return store.list(TaskIdentity.current().userId()); }
    @GetMapping("/settings") public Map<String,Boolean> settings() {
        return Map.of("enabled", store.enabled(TaskIdentity.current().userId()));
    }
    @PutMapping("/settings") public void settings(@RequestBody Settings request) {
        store.settings(TaskIdentity.current().userId(), request.enabled());
    }
    @PostMapping("/{id}/confirm") public MemoryStore.Memory confirm(@PathVariable UUID id, @Valid @RequestBody Confirm request) {
        return store.confirm(TaskIdentity.current().userId(), id.toString(), request.version(), request.replaceExisting());
    }
    @PutMapping("/{id}") public MemoryStore.Memory correct(@PathVariable UUID id, @Valid @RequestBody Correct request) {
        return store.correct(TaskIdentity.current().userId(), id.toString(), request.version(), request.content());
    }
    @DeleteMapping("/{id}") public void delete(@PathVariable UUID id, @RequestParam long version) {
        store.delete(TaskIdentity.current().userId(), id.toString(), version);
    }
}
