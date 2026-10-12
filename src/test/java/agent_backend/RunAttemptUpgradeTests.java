package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;

/** Upgrade real V3 data rather than only testing a fresh V4 installation. */
@Testcontainers(disabledWithoutDocker = true)
class RunAttemptUpgradeTests {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Test void migratesObservedExecutionsWithoutInventingAttemptsForPendingRuns() {
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().dataSource(source).target("3").load().migrate();
        var jdbc = new JdbcTemplate(source);
        String pending = UUID.randomUUID().toString(), running = UUID.randomUUID().toString();
        String completed = UUID.randomUUID().toString(), oldRunning = UUID.randomUUID().toString();
        String step = UUID.randomUUID().toString();
        for (String id : java.util.List.of(pending, running, completed, oldRunning)) {
            jdbc.update("INSERT INTO agent_task(task_id,task_input) VALUES (?, 'input')", id);
        }
        jdbc.update("UPDATE agent_task SET status='RUNNING', worker_id=?, started_at=UTC_TIMESTAMP(6), "
                + "heartbeat_at=UTC_TIMESTAMP(6), lease_until=UTC_TIMESTAMP(6) + INTERVAL 1 MINUTE WHERE task_id=?",
                UUID.randomUUID().toString(), running);
        jdbc.update("UPDATE agent_task SET status='COMPLETED', final_answer='saved', started_at=UTC_TIMESTAMP(6), "
                + "finished_at=UTC_TIMESTAMP(6) WHERE task_id=?", completed);
        jdbc.update("UPDATE agent_task SET status='RUNNING' WHERE task_id=?", oldRunning);
        jdbc.update("INSERT INTO agent_step(step_id,task_id,step_number) VALUES (?,?,1)", step, running);
        String invocation=UUID.randomUUID().toString(), queued=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_tool_call(invocation_id,step_id,tool_call_id,call_order,tool_name,arguments,status) "
                + "VALUES (?,?,'old',1,'old_tool','{}','UNKNOWN')",invocation,step);
        jdbc.update("INSERT INTO agent_tool_call(invocation_id,step_id,tool_call_id,call_order,tool_name,arguments) "
                + "VALUES (?,?,'queued',2,'old_tool','{}')",queued,step);

        var upgraded = Flyway.configure().dataSource(source).load();
        assertEquals(7, upgraded.migrate().migrationsExecuted);
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM run_event WHERE event_type='run.imported' AND seq=1", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task WHERE last_event_seq=1", Integer.class));
        assertEquals(0, upgraded.migrate().migrationsExecuted);
        assertEquals(invocation,jdbc.queryForObject("SELECT idempotency_key FROM agent_tool_call WHERE invocation_id=?",String.class,invocation));
        assertEquals("MANUAL_ONLY",jdbc.queryForObject("SELECT recovery_policy FROM agent_tool_call WHERE invocation_id=?",String.class,invocation));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM tool_execution_attempt WHERE invocation_id=? AND legacy=TRUE",String.class,invocation));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tool_execution_attempt WHERE invocation_id=?",Integer.class,queued));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt WHERE run_id=?", Integer.class, pending));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM run_attempt WHERE legacy=TRUE", Integer.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM run_attempt WHERE run_id=?", String.class, completed));
        assertEquals("saved", jdbc.queryForObject("SELECT final_answer FROM agent_task WHERE task_id=?", String.class, completed));
        assertNull(jdbc.queryForObject("SELECT active_attempt_id FROM agent_task WHERE task_id=?", String.class, completed));
        assertNotNull(jdbc.queryForObject("SELECT active_attempt_id FROM agent_task WHERE task_id=?", String.class, running));
        assertNotNull(jdbc.queryForObject("SELECT created_attempt_id FROM agent_step WHERE step_id=?", String.class, step));
        assertNull(jdbc.queryForObject("SELECT lease_until FROM run_attempt WHERE run_id=?", java.sql.Timestamp.class, oldRunning));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='agent_task' "
                + "AND column_name IN ('worker_id','heartbeat_at','lease_until')", Integer.class));
    }
}
