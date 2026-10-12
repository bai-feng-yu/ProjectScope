package agent_backend.personal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import agent_backend.security.TaskIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/conversations")
public class ConversationController {
    public record Create(@NotBlank @Size(max=120) String title) {}
    public record Send(@NotBlank @Size(max=8000) String content) {}
    private final ConversationStore store;
    private final ConversationService service;
    public ConversationController(ConversationStore store, ConversationService service) { this.store = store; this.service = service; }
    @GetMapping public List<ConversationStore.Conversation> list() { return store.list(TaskIdentity.current().userId()); }
    @PostMapping public ResponseEntity<Map<String,String>> create(@Valid @RequestBody Create request) {
        String id = store.create(TaskIdentity.current().userId(), request.title());
        return ResponseEntity.status(201).body(Map.of("conversationId", id));
    }
    @GetMapping("/{id}/messages")
    public List<ConversationStore.Message> messages(@PathVariable UUID id) {
        return store.messages(TaskIdentity.current().userId(), id.toString());
    }
    @PostMapping("/{id}/messages")
    public List<ConversationStore.Message> send(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Send request) { return service.send(id.toString(), key, request.content()); }
}
