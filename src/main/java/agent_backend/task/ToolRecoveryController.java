package agent_backend.task;

import java.util.UUID;
import jakarta.validation.Valid;
import agent_backend.persistence.jdbc.JdbcToolRecoveryStore;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/runs/{runId}/tools/{invocationId}/recovery")
public class ToolRecoveryController {
    private final ToolRecoveryService service;
    public ToolRecoveryController(ToolRecoveryService service) { this.service = service; }
    @GetMapping public JdbcToolRecoveryStore.View get(@PathVariable UUID runId, @PathVariable UUID invocationId) {
        return service.get(runId.toString(), invocationId.toString());
    }
    @PostMapping public JdbcToolRecoveryStore.View recover(@PathVariable UUID runId, @PathVariable UUID invocationId,
            @Valid @RequestBody ToolRecoveryService.Request request) {
        return service.recover(runId.toString(), invocationId.toString(), request);
    }
}
