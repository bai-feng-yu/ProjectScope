package agent_backend.task;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import agent_backend.persistence.TaskStore;
import agent_backend.security.TaskIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class RunEventQuery {
    public static final int BATCH_SIZE = 100;
    public record Event(String runId, long seq, String type, int schemaVersion, String attemptId,
            String stepId, String invocationId, JsonNode payload, Instant createdAt) {}
    public record Page(long highWatermark, boolean terminal, List<Event> events) {}
    public record Initial(long seq, TaskSnapshot snapshot) {}
    private final JdbcTemplate jdbc;
    private final TaskStore tasks;
    private final TaskService snapshots;
    private final ObjectMapper mapper;

    public RunEventQuery(JdbcTemplate jdbc, TaskStore tasks, TaskService snapshots, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.snapshots = snapshots;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Initial initial(String runId, TaskIdentity identity) {
        // The snapshot and watermark MUST share one MVCC view, before any network write.
        var snapshot = snapshots.getSnapshot(runId, identity);
        return new Initial(sequence(runId), snapshot);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page after(String runId, TaskIdentity identity, long cursor) {
        identity.requireValid();
        var task = tasks.findOwned(runId, identity.userId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        long high = sequence(runId);
        if (cursor < 0 || cursor > high)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event cursor");
        var events = jdbc.query("""
                SELECT * FROM run_event WHERE run_id=? AND seq>? AND seq<=? ORDER BY seq LIMIT ?
                """, (rs, row) -> new Event(rs.getString("run_id"), rs.getLong("seq"),
                rs.getString("event_type"), rs.getInt("schema_version"), rs.getString("attempt_id"),
                rs.getString("step_id"), rs.getString("invocation_id"), mapper.readTree(rs.getString("payload")),
                rs.getTimestamp("created_at").toLocalDateTime().toInstant(ZoneOffset.UTC)),
                runId, cursor, high, BATCH_SIZE);
        return new Page(high, task.status().isTerminal(), List.copyOf(events));
    }

    private long sequence(String runId) {
        return jdbc.queryForObject("SELECT last_event_seq FROM agent_task WHERE task_id=?", Long.class, runId);
    }
}
