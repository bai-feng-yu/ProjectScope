package agent_backend.personal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import agent_backend.security.ToolAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class MemoryStore {
    public record Memory(String memoryId, String sourceMessageId, String topic, String kind, String content,
            String status, long version, LocalDateTime updatedAt) {}
    public record Candidate(String topic, String kind, String content) {}
    private final JdbcTemplate jdbc;
    private final ToolAccess access;
    public MemoryStore(JdbcTemplate jdbc, ToolAccess access) { this.jdbc = jdbc; this.access = access; }

    public List<Memory> list(String user) {
        access.requireUser(user);
        return jdbc.query("""
                SELECT * FROM personal_memory WHERE user_id=? AND status IN ('ACTIVE','CANDIDATE')
                ORDER BY updated_at DESC,memory_id LIMIT 100
                """, DataClassRowMapper.newInstance(Memory.class), user);
    }

    public boolean enabled(String user) {
        access.requireUser(user);
        return jdbc.queryForList("SELECT user_id FROM app_user WHERE user_id=? AND memory_enabled=FALSE FOR SHARE",
                String.class, user).isEmpty();
    }

    public List<Memory> active(String user) {
        access.requireUser(user);
        // Candidate volume must never hide confirmed memories from retrieval.
        return jdbc.query("SELECT * FROM personal_memory WHERE user_id=? AND status='ACTIVE' ORDER BY updated_at DESC LIMIT 100",
                DataClassRowMapper.newInstance(Memory.class), user);
    }

    @Transactional
    public void settings(String user, boolean enabled) {
        lockUser(user);
        jdbc.update("UPDATE app_user SET memory_enabled=? WHERE user_id=?", enabled, user);
        if (!enabled) jdbc.update("""
                UPDATE personal_memory SET status='DELETED',content='',version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE user_id=? AND status='CANDIDATE'
                """, user);
    }

    @Transactional
    public boolean reserveSource(String user, String messageId) {
        lockUser(user);
        requireSource(user, messageId);
        // Keep only the source ID when extraction is disabled/deleted; never silently re-extract it later.
        int inserted = jdbc.update("INSERT IGNORE INTO memory_source_processed(user_id,message_id) VALUES (?,?)", user, messageId);
        return inserted == 1 && enabled(user);
    }

    @Transactional
    public void candidates(String user, String messageId, List<Candidate> candidates) {
        lockUser(user);
        requireSource(user, messageId);
        if (!enabled(user)) return;
        for (var candidate : candidates.stream().limit(3).toList()) {
            if (candidate.topic() == null || candidate.topic().isBlank() || candidate.topic().length() > 80
                    || candidate.content() == null || candidate.content().isBlank() || candidate.content().length() > 2000
                    || candidate.kind() == null || !List.of("FACT","PREFERENCE","INFERENCE").contains(candidate.kind())
                    || MemoryPrivacy.containsCredential(candidate.topic() + " " + candidate.content())) continue;
            jdbc.update("""
                    INSERT INTO personal_memory(memory_id,user_id,source_message_id,topic,kind,content)
                    VALUES (?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), user, messageId, candidate.topic().strip(), candidate.kind(), candidate.content());
        }
    }

    @Transactional
    public Memory confirm(String user, String id, long version, boolean replaceExisting) {
        lockUser(user);
        var memory = owned(user, id, version);
        if (!"CANDIDATE".equals(memory.status())) throw conflict();
        var active = jdbc.queryForList("SELECT memory_id FROM personal_memory WHERE user_id=? AND topic=? AND status='ACTIVE' FOR UPDATE",
                String.class, user, memory.topic());
        if (!active.isEmpty() && !replaceExisting)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Topic already has a confirmed memory; explicitly replace it");
        supersede(user, memory.topic());
        jdbc.update("UPDATE personal_memory SET status='ACTIVE',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE memory_id=?", id);
        return owned(user, id, version + 1);
    }

    @Transactional
    public Memory correct(String user, String id, long version, String content) {
        lockUser(user);
        var memory = owned(user, id, version);
        if (!"ACTIVE".equals(memory.status())) throw conflict();
        if (MemoryPrivacy.containsCredential(content))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credentials cannot be stored as personal memory");
        supersede(user, memory.topic());
        String next = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO personal_memory(memory_id,user_id,source_message_id,topic,kind,content,status,version)
                VALUES (?,?,?,?,?,?,'ACTIVE',?)
                """, next, user, memory.sourceMessageId(), memory.topic(), memory.kind(), content, version + 1);
        return owned(user, next, version + 1);
    }

    @Transactional
    public void delete(String user, String id, long version) {
        lockUser(user);
        var memory = owned(user, id, version);
        // Redact all versions of the topic, not just the currently displayed row.
        jdbc.update("""
                UPDATE personal_memory SET content='',status='DELETED',version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE user_id=? AND topic=?
                """, user, memory.topic());
    }

    private void lockUser(String user) {
        access.requireUser(user);
        jdbc.update("INSERT IGNORE INTO app_user(user_id) VALUES (?)", user);
        jdbc.queryForObject("SELECT user_id FROM app_user WHERE user_id=? FOR UPDATE", String.class, user);
        access.requireUser(user);
    }
    private void requireSource(String user, String messageId) {
        if (jdbc.queryForList("SELECT message_id FROM conversation_message WHERE user_id=? AND message_id=? AND role='USER'",
                String.class, user, messageId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source message not found");
    }
    private Memory owned(String user, String id, long version) {
        var memory = jdbc.query("SELECT * FROM personal_memory WHERE user_id=? AND memory_id=? AND status<>'DELETED' FOR UPDATE",
                DataClassRowMapper.newInstance(Memory.class), user, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory not found"));
        if (memory.version() != version) throw conflict();
        return memory;
    }
    private void supersede(String user, String topic) {
        jdbc.update("""
                UPDATE personal_memory SET status='SUPERSEDED',version=version+1,updated_at=UTC_TIMESTAMP(6)
                WHERE user_id=? AND topic=? AND status='ACTIVE'
                """, user, topic);
    }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Memory version or state changed");
    }
}
