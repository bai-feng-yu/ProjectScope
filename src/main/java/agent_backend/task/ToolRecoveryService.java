package agent_backend.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import agent_backend.persistence.jdbc.JdbcToolRecoveryStore;
import agent_backend.security.TaskIdentity;
import agent_backend.tool.*;
import agent_backend.toolRegistry.ToolRegistry;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ToolRecoveryService {
    public enum Action { RECONCILE, CONFIRM }
    public record Request(@NotNull UUID requestId, @NotNull Action action,
            Reconciliation.Outcome outcome, @Size(max=2000) String evidence, JsonNode output) {}
    private final JdbcToolRecoveryStore store;
    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final ToolSchemaValidator validator;
    private final ToolRecoveryExecutor executor;
    public ToolRecoveryService(JdbcToolRecoveryStore store, ToolRegistry registry, ObjectMapper mapper,
            ToolSchemaValidator validator, ToolRecoveryExecutor executor) {
        this.store = store; this.registry = registry; this.mapper = mapper; this.validator = validator;
        this.executor = executor;
    }
    public JdbcToolRecoveryStore.View get(String runId, String invocationId) {
        return store.view(runId, invocationId, TaskIdentity.current());
    }

    // Intentionally NOT transactional: external reconciliation runs between begin and finish.
    public JdbcToolRecoveryStore.View recover(String runId, String invocationId, Request request) {
        var identity = TaskIdentity.current();
        // Authorize before validating or consulting adapter metadata.
        var current = store.view(runId, invocationId, identity).call();
        if (request.action() == null || request.requestId() == null) throw bad("Action and requestId are required");
        if (request.action() == Action.CONFIRM) {
            if (request.outcome() != Reconciliation.Outcome.SUCCEEDED && request.outcome() != Reconciliation.Outcome.NOT_EXECUTED)
                throw bad("Manual confirmation requires SUCCEEDED or NOT_EXECUTED");
            if (request.evidence() == null || request.evidence().isBlank() || request.evidence().length() > 2000)
                throw bad("Manual confirmation requires evidence (1-2000 characters)");
            if (request.outcome() == Reconciliation.Outcome.NOT_EXECUTED && request.output() != null)
                throw bad("NOT_EXECUTED cannot include a successful output");
        } else if (request.outcome() != null || request.output() != null || request.evidence() != null) {
            throw bad("Reconciliation results must come from the adapter");
        }
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (request.action() == Action.CONFIRM && request.outcome() == Reconciliation.Outcome.SUCCEEDED)
            validateOutput(current, request.output());
        var claim = store.begin(runId, invocationId, identity, request.requestId().toString(), request.action().name(), hash);
        if (claim.replay()) return store.view(runId, invocationId, identity);
        Reconciliation result;
        if (request.action() == Action.CONFIRM) {
            result = new Reconciliation(request.outcome(), request.output(), request.evidence());
        } else {
            try {
                var call = claim.call();
                var tool = registry.getTool(call.toolName());
                if (!tool.recoveryVersion().equals(call.version()) || tool.recoveryPolicy() != call.policy()
                        || call.idempotencyKey() == null)
                    throw new IllegalStateException("Adapter recovery contract changed");
                result = executor.lookup(() -> lookup(tool, call, identity));
                if (result.outcome() == Reconciliation.Outcome.SUCCEEDED) validateOutput(call, result.output());
            } catch (Exception lookupFailed) {
                result = new Reconciliation(Reconciliation.Outcome.INDETERMINATE, null,
                        "External lookup unavailable, invalid, or adapter contract changed; no execution was retried");
            }
        }
        return store.finish(runId, invocationId, identity, request.requestId().toString(), result);
    }
    private <I,O> Reconciliation lookup(AgentTool<I,O> tool, JdbcToolRecoveryStore.Call call, TaskIdentity identity) {
        identity.requireValid();
        return tool.reconcile(mapper.readValue(call.arguments(), tool.inputType()),
                new ToolRecoveryContext(call.runId(), call.invocationId(), identity.userId(), call.idempotencyKey()));
    }
    private void validateOutput(JdbcToolRecoveryStore.Call call, Object output) {
        try {
            var tool = registry.getTool(call.toolName());
            if (!"legacy".equals(call.version()) && !tool.recoveryVersion().equals(call.version()))
                throw bad("Adapter version changed; cannot validate output");
            if (!validator.validate(registry.describe(call.toolName()).outputSchema(), output).isEmpty())
                throw bad("Output does not match the tool result schema");
        } catch (IllegalArgumentException absent) { throw bad("Tool adapter is unavailable"); }
    }
    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
