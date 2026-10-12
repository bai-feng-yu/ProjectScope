package agent_backend.personal;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import agent_backend.security.ToolAccess;
import agent_backend.task.RequestKeys;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class ConversationStore {
    public record Conversation(String conversationId, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Message(String messageId, String role, String content, String status, String replyTo,
            String errorMessage, LocalDateTime createdAt, String runId) {}
    public record Turn(Message message, boolean created) {}
    private final JdbcTemplate jdbc;
    private final ToolAccess access;
    public ConversationStore(JdbcTemplate jdbc, ToolAccess access) { this.jdbc = jdbc; this.access = access; }

    @Transactional
    public String create(String userId, String title) {
        access.requireUser(userId);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversation(conversation_id,user_id,title) VALUES (?,?,?)", id, userId, title);
        return id;
    }

    public List<Conversation> list(String userId) {
        access.requireUser(userId);
        return jdbc.query("SELECT * FROM conversation WHERE user_id=? ORDER BY updated_at DESC,conversation_id LIMIT 100",
                DataClassRowMapper.newInstance(Conversation.class), userId);
    }

    public void requireOwned(String userId, String id, boolean lock) {
        access.requireUser(userId);
        if (jdbc.queryForList("SELECT conversation_id FROM conversation WHERE user_id=? AND conversation_id=?"
                + (lock ? " FOR UPDATE" : ""), String.class, userId, id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
    }

    @Transactional(readOnly = true)
    public List<Message> messages(String userId, String id) {
        requireOwned(userId, id, false);
        return jdbc.query("""
                SELECT * FROM (SELECT * FROM conversation_message WHERE user_id=? AND conversation_id=?
                    ORDER BY created_at DESC,message_id DESC LIMIT 100) m ORDER BY created_at,message_id
                """, DataClassRowMapper.newInstance(Message.class), userId, id);
    }

    @Transactional
    public Turn begin(String userId, String conversationId, String key, String content) {
        requireOwned(userId, conversationId, true);
        String hash = RequestKeys.hash(RequestKeys.validate(key));
        // A locking read sees the winner after waiting, even if an earlier access check established an RR snapshot.
        var existing = jdbc.query("SELECT * FROM conversation_message WHERE conversation_id=? AND request_key=? FOR UPDATE",
                DataClassRowMapper.newInstance(Message.class), conversationId, hash);
        if (!existing.isEmpty()) {
            var message = existing.getFirst();
            if (message.runId() != null || !message.content().equals(content))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Message key conflict");
            return new Turn(message, false);
        }
        // A crashed synchronous reply is not retried automatically. The user can submit a new turn.
        jdbc.update("""
                UPDATE conversation_message SET status='FAILED',error_message='Reply interrupted; submit a new message'
                WHERE conversation_id=? AND role='USER' AND status='PENDING'
                    AND created_at<UTC_TIMESTAMP(6)-INTERVAL 3 MINUTE
                """, conversationId);
        if (!jdbc.queryForList("SELECT message_id FROM conversation_message WHERE conversation_id=? AND status='PENDING' FOR UPDATE",
                String.class, conversationId).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A reply is already in progress");
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO conversation_message(message_id,user_id,conversation_id,role,content,status,request_key)
                VALUES (?,?,?,'USER',?,'PENDING',?)
                """, id, userId, conversationId, content, hash);
        jdbc.update("UPDATE conversation SET updated_at=UTC_TIMESTAMP(6) WHERE conversation_id=?", conversationId);
        return new Turn(message(id), true);
    }

    @Transactional
    public void finish(String userId, String conversationId, String messageId, String answer, String error) {
        requireOwned(userId, conversationId, true);
        int changed = jdbc.update("""
                UPDATE conversation_message SET status=?,error_message=?
                WHERE message_id=? AND conversation_id=? AND user_id=? AND status='PENDING'
                """, error == null ? "COMPLETED" : "FAILED", error, messageId, conversationId, userId);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Reply already finalized");
        if (error == null) jdbc.update("""
                INSERT INTO conversation_message(message_id,user_id,conversation_id,role,content,status,reply_to)
                VALUES (?,?,?,'ASSISTANT',?,'COMPLETED',?)
                """, UUID.randomUUID().toString(), userId, conversationId, answer, messageId);
        jdbc.update("UPDATE conversation SET updated_at=UTC_TIMESTAMP(6) WHERE conversation_id=?", conversationId);
    }

    private Message message(String id) {
        return jdbc.queryForObject("SELECT * FROM conversation_message WHERE message_id=?",
                DataClassRowMapper.newInstance(Message.class), id);
    }
}
