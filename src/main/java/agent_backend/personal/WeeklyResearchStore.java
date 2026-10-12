package agent_backend.personal;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import agent_backend.security.ToolAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class WeeklyResearchStore {
    public record Schedule(String scheduleId, String conversationId, String content, LocalDateTime nextFireAt,
            boolean paused, String lastRunId, String lastOutcome) {}
    private final JdbcTemplate jdbc;
    private final ConversationStore conversations;
    private final ResearchStore research;
    private final ToolAccess access;
    private final int maxRunning;

    public WeeklyResearchStore(JdbcTemplate jdbc, ConversationStore conversations, ResearchStore research,
            ToolAccess access, @Value("${agent.limits.max-running-per-user:2}") int maxRunning) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.research = research;
        this.access = access;
        this.maxRunning = maxRunning;
    }

    @Transactional
    public String create(String user, String conversation, String content, Instant firstFireAt, String key) {
        conversations.requireOwned(user, conversation, true);
        String keyHash = agent_backend.task.RequestKeys.hash(agent_backend.task.RequestKeys.validate(key));
        String requestHash = agent_backend.task.RequestKeys.hash(conversation + "\n" + firstFireAt + "\n" + content);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT IGNORE INTO request_dedup(user_id,operation,key_hash,request_hash,resource_id)
                VALUES (?,'create-schedule',?,?,?)
                """, user, keyHash, requestHash, id);
        var reserved = jdbc.queryForMap("""
                SELECT request_hash,resource_id FROM request_dedup
                WHERE user_id=? AND operation='create-schedule' AND key_hash=? FOR UPDATE
                """, user, keyHash);
        if (!requestHash.equals(reserved.get("request_hash")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Schedule key conflict");
        if (!id.equals(reserved.get("resource_id"))) return (String) reserved.get("resource_id");
        LocalDateTime now = now();
        LocalDateTime first = LocalDateTime.ofInstant(firstFireAt, ZoneOffset.UTC);
        if (!first.isAfter(now) || first.isAfter(now.plusYears(1)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "First trigger must be within the next year");
        jdbc.update("INSERT INTO scheduled_research(schedule_id,user_id,conversation_id,content,next_fire_at) VALUES (?,?,?,?,?)",
                id, user, conversation, content, first);
        return id;
    }

    public List<Schedule> list(String user) {
        access.requireUser(user);
        return jdbc.query("SELECT * FROM scheduled_research WHERE user_id=? ORDER BY created_at DESC LIMIT 100",
                DataClassRowMapper.newInstance(Schedule.class), user);
    }

    @Transactional
    public void pause(String user, String id, boolean paused) {
        access.requireUser(user);
        var rows = jdbc.queryForList("SELECT schedule_id FROM scheduled_research WHERE user_id=? AND schedule_id=? FOR UPDATE",
                String.class, user, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedule not found");
        jdbc.update("UPDATE scheduled_research SET paused=? WHERE schedule_id=?", paused, id);
    }

    public List<String> due() {
        return jdbc.queryForList("""
                SELECT s.schedule_id FROM scheduled_research s WHERE s.paused=FALSE AND s.next_fire_at<=UTC_TIMESTAMP(6)
                  AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.user_id=s.user_id AND u.enabled=FALSE)
                ORDER BY s.next_fire_at,s.schedule_id LIMIT 50
                """, String.class);
    }

    @Transactional
    public void fire(String id) {
        var rows = jdbc.queryForList("SELECT * FROM scheduled_research WHERE schedule_id=? AND paused=FALSE FOR UPDATE", id);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        String user = (String) row.get("user_id");
        access.requireUser(user);
        LocalDateTime now = now();
        LocalDateTime slot = jdbc.queryForObject("SELECT next_fire_at FROM scheduled_research WHERE schedule_id=?", LocalDateTime.class, id);
        if (slot.isAfter(now)) return;
        // Asia/Shanghai has no DST in this supported horizon: seven days preserves the local weekly wall time.
        long weeks = java.time.temporal.ChronoUnit.DAYS.between(slot, now) / 7 + 1;
        LocalDateTime next = slot.plusWeeks(weeks);
        String previous = (String) row.get("last_run_id");
        boolean overlap = previous != null && !jdbc.queryForList(
                "SELECT task_id FROM agent_task WHERE task_id=? AND status IN ('PENDING','RUNNING')", previous).isEmpty();
        int active = jdbc.queryForObject("SELECT COUNT(*) FROM agent_task WHERE user_id=? AND status='RUNNING'", Integer.class, user);
        String outcome = overlap ? "SKIPPED_OVERLAP" : active >= maxRunning ? "SKIPPED_BUSY" : "SUBMITTED";
        String run = previous;
        if ("SUBMITTED".equals(outcome)) {
            var submitted = research.submit(user, (String) row.get("conversation_id"),
                    "weekly:" + id + ":" + slot.toInstant(ZoneOffset.UTC).getEpochSecond(), (String) row.get("content"));
            run = submitted.runId();
        }
        // Advancing the trigger and creating its Run share a transaction; missed weeks coalesce to one trigger.
        jdbc.update("UPDATE scheduled_research SET next_fire_at=?,last_run_id=?,last_outcome=? WHERE schedule_id=?",
                next, run, outcome, id);
    }

    private LocalDateTime now() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class); }
}
