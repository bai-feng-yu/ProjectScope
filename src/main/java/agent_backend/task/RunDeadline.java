package agent_backend.task;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Bounds the entire provider operation, including response-body reads, by the persisted Run deadline. */
@Component
public class RunDeadline {
    private final JdbcTemplate jdbc;
    public RunDeadline(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public <T> T call(String runId, Supplier<T> operation) {
        var remaining = jdbc.queryForList("""
                SELECT TIMESTAMPDIFF(MICROSECOND,UTC_TIMESTAMP(6),deadline_at) FROM agent_task r
                WHERE task_id=? AND status='RUNNING' AND deadline_at>UTC_TIMESTAMP(6)
                  AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.user_id=r.user_id AND u.enabled=FALSE)
                """, Long.class, runId);
        if (remaining.isEmpty()) throw new IllegalStateException("Run deadline or execution authorization ended");
        var result = new FutureTask<T>(operation::get);
        // No transaction/context is carried into this I/O-only thread. Interrupted HTTP transport remains bounded too.
        Thread thread = Thread.ofVirtual().name("run-provider-io").start(result);
        try {
            return result.get(remaining.getFirst(), TimeUnit.MICROSECONDS);
        } catch (TimeoutException expired) {
            throw new IllegalStateException("Run total execution deadline exceeded");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Run provider request interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Run provider request failed", failed.getCause());
        } finally {
            if (!result.isDone()) result.cancel(true);
            if (thread.isAlive()) thread.interrupt();
        }
    }
}
