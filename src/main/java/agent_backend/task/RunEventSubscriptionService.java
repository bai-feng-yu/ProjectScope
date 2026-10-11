package agent_backend.task;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import agent_backend.security.TaskIdentity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Connections are local and disposable; their ordered source of truth is MySQL. */
@Service
public class RunEventSubscriptionService {
    private final RunEventQuery query;
    private final ScheduledExecutorService pollExecutor;
    private final Executor sendExecutor;
    private final long pollMs, heartbeatMs, timeoutMs;
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private ScheduledFuture<?> polling;

    public RunEventSubscriptionService(RunEventQuery query,
            @Qualifier("taskEventPollExecutor") ScheduledExecutorService pollExecutor,
            @Qualifier("taskEventSendExecutor") Executor sendExecutor,
            @Value("${agent.events.poll-interval-ms:1000}") long pollMs,
            @Value("${agent.events.heartbeat-interval-ms:15000}") long heartbeatMs,
            @Value("${agent.events.connection-timeout-ms:300000}") long timeoutMs) {
        Assert.isTrue(pollMs > 0 && heartbeatMs > 0 && timeoutMs > 0, "Invalid SSE timing");
        this.query = query;
        this.pollExecutor = pollExecutor;
        this.sendExecutor = sendExecutor;
        this.pollMs = pollMs;
        this.heartbeatMs = heartbeatMs;
        this.timeoutMs = timeoutMs;
    }

    @PostConstruct public void start() {
        polling = pollExecutor.scheduleWithFixedDelay(this::poll, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    /** Null means a terminal run has already been fully consumed: HTTP 204 stops reconnects. */
    public SseEmitter subscribe(String runId, Long cursor) {
        var identity = TaskIdentity.current();
        // Authorize/read before committing the HTTP stream, so errors retain their HTTP status.
        var initial = cursor == null ? query.initial(runId, identity) : null;
        var page = cursor != null ? query.after(runId, identity, cursor) : null;
        if (page != null && page.terminal() && cursor == page.highWatermark()) return null;
        var emitter = new SseEmitter(timeoutMs);
        var subscription = new Subscription(runId, identity, emitter, cursor == null ? initial.seq() : cursor);
        emitter.onCompletion(subscription::remove);
        emitter.onError(error -> subscription.close());
        emitter.onTimeout(subscription::close);
        try {
            if (initial != null) {
                identity.requireValid();
                emitter.send(SseEmitter.event().id(Long.toString(initial.seq())).name("snapshot").data(initial));
                if (initial.snapshot().status().isTerminal()) subscription.close();
            } else {
                subscription.send(page);
            }
        } catch (Exception failure) { subscription.close(); }
        if (!subscription.closed.get()) {
            subscriptions.add(subscription);
            // Completion can race registration.
            if (subscription.closed.get()) subscriptions.remove(subscription);
        }
        return emitter;
    }

    void poll() { subscriptions.forEach(Subscription::offer); }

    @PreDestroy public void stop() {
        if (polling != null) polling.cancel(false);
        subscriptions.forEach(Subscription::close);
    }

    private final class Subscription {
        final String runId;
        final TaskIdentity identity;
        final SseEmitter emitter;
        final AtomicBoolean closed = new AtomicBoolean(), sending = new AtomicBoolean();
        long cursor, lastSendNanos = System.nanoTime();

        Subscription(String runId, TaskIdentity identity, SseEmitter emitter, long cursor) {
            this.runId = runId; this.identity = identity; this.emitter = emitter; this.cursor = cursor;
        }

        void offer() {
            if (closed.get() || !sending.compareAndSet(false, true)) return;
            try {
                sendExecutor.execute(() -> {
                    try {
                        if (!closed.get()) {
                            identity.requireValid();
                            send(query.after(runId, identity, cursor));
                        }
                    } catch (Exception failure) { close(); }
                    finally { sending.set(false); }
                });
            } catch (RejectedExecutionException overloaded) {
                sending.set(false);
                close();
            }
        }

        void send(RunEventQuery.Page page) throws java.io.IOException {
            identity.requireValid();
            for (var event : page.events()) {
                if (closed.get()) return;
                identity.requireValid();
                // Never silently jump over a missing event (retention is not enabled in V5).
                if (event.seq() != Math.addExact(cursor, 1L)) throw new IllegalStateException("Event gap");
                emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name(event.type()).data(event));
                cursor = event.seq(); // Advance only after a successful send; reconnect may duplicate, never skip.
                lastSendNanos = System.nanoTime();
            }
            if (page.terminal() && cursor == page.highWatermark()) { close(); return; }
            if (System.nanoTime() - lastSendNanos >= TimeUnit.MILLISECONDS.toNanos(heartbeatMs)) {
                emitter.send(SseEmitter.event().comment("heartbeat")); // No cursor on transport heartbeats.
                lastSendNanos = System.nanoTime();
            }
        }

        void remove() { closed.set(true); subscriptions.remove(this); }
        void close() {
            if (closed.compareAndSet(false, true)) {
                subscriptions.remove(this);
                emitter.complete();
            }
        }
    }
}
