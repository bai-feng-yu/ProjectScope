package agent_backend.personal;

import agent_backend.security.TaskIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/conversations/{conversationId}/research")
public class ResearchController {
    public record Request(@NotBlank @Size(max=8000) String content) {}
    private final ResearchStore research;
    public ResearchController(ResearchStore research) { this.research = research; }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public ResearchStore.Submission submit(@PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Request request) {
        return research.submit(TaskIdentity.current().userId(), conversationId, key, request.content());
    }
}
