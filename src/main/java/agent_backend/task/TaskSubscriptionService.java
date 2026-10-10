package agent_backend.task;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 连接属于当前应用实例，任务进度以数据库为准。
 * HTTP 线程负责订阅，轮询线程读取快照，发送线程写入连接；三者会并发访问订阅表。
 */
@Service
public class TaskSubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(TaskSubscriptionService.class);
    private final TaskService tasks;
    private final ScheduledExecutorService pollExecutor;
    private final Executor sendExecutor;
    private final long pollMs, heartbeatMs, timeoutMs;
    // 一个任务可被多个页面订阅。按 taskId 分组，使每轮只读一次该任务再广播。
    private final ConcurrentHashMap<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private ScheduledFuture<?> polling;

    public TaskSubscriptionService(TaskService tasks,
            // 容器中还有任务执行、租约心跳线程池，用名称明确选中 SSE 专用资源。
            @Qualifier("taskEventPollExecutor") ScheduledExecutorService pollExecutor,
            @Qualifier("taskEventSendExecutor") Executor sendExecutor,
            @Value("${agent.events.poll-interval-ms:1000}") long pollMs,
            @Value("${agent.events.heartbeat-interval-ms:15000}") long heartbeatMs,
            @Value("${agent.events.connection-timeout-ms:300000}") long timeoutMs) {
        Assert.isTrue(pollMs > 0 && heartbeatMs > 0 && timeoutMs > 0, "Invalid SSE timing");
        this.tasks = tasks;
        this.pollExecutor = pollExecutor;
        this.sendExecutor = sendExecutor;
        this.pollMs = pollMs;
        this.heartbeatMs = heartbeatMs;
        this.timeoutMs = timeoutMs;
    }

    @PostConstruct
    public void start() {
        // Bean 注入完成后启动；每轮结束再等待 pollMs，避免慢查询造成轮询重叠。
        // 独立于领取任务的调度器，因此本实例不执行任务时也可以订阅数据库进度。
        polling = pollExecutor.scheduleWithFixedDelay(this::poll, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    public SseEmitter subscribe(String taskId) {
        // 先查数据库再建立流：此时还可以正常返回 404 或 JSON 503。
        var snapshot = tasks.getSnapshot(taskId);
        var emitter = new SseEmitter(timeoutMs);
        var subscription = new Subscription(taskId, emitter);
        // 页面离开、网络断开、连接超时均需清理内存订阅；这些操作不会取消 Task。
        emitter.onCompletion(subscription::remove);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());
        // Controller 尚未返回，MVC 还未初始化 emitter，此处先缓冲首次事件。
        // 先发送再登记，避免轮询线程先推送新快照、HTTP 线程随后又推送旧快照。
        subscription.send(snapshot);
        if (!subscription.closed.get()) {
            // 已终态的任务会在首次发送后关闭，不再加入轮询表。
            // compute 将同一 taskId 下的增删串行化，避免最后一个订阅移除时丢失新订阅。
            subscriptions.compute(taskId, (id, group) -> {
                if (group == null) group = ConcurrentHashMap.newKeySet();
                group.add(subscription);
                return group;
            });
        }
        return emitter;
    }

    void poll() {
        subscriptions.forEach((taskId, group) -> {
            try {
                var snapshot = tasks.getSnapshot(taskId);
                // 这里只提交发送工作，网络写入由独立线程池执行。
                group.forEach(subscription -> subscription.offer(snapshot));
            } catch (Exception exception) {
                log.warn("Could not read SSE snapshot for task {}", taskId, exception);
                // 流建立后不能改为普通 HTTP 错误响应；关闭后由前端重连获取最新快照。
                group.forEach(Subscription::close);
            }
        });
    }

    @PreDestroy
    public void stop() {
        // 应用关闭时先停止产生新推送，再结束连接；线程池由配置类的销毁方法回收。
        if (polling != null) polling.cancel(false);
        subscriptions.values().forEach(group -> group.forEach(Subscription::close));
    }

    private final class Subscription {
        private final String taskId;
        private final SseEmitter emitter;
        // 多个关闭来源可能同时触发，CAS 保证主动关闭只执行一次。
        private final AtomicBoolean closed = new AtomicBoolean();
        // 同一连接至多有一个排队或执行中的发送任务，也保证快照发送顺序。
        private final AtomicBoolean sending = new AtomicBoolean();
        private TaskSnapshot lastSnapshot;
        private long lastSendNanos;

        private Subscription(String taskId, SseEmitter emitter) {
            this.taskId = taskId;
            this.emitter = emitter;
        }

        void offer(TaskSnapshot snapshot) {
            // 忙时跳过本轮，不无限缓存历史快照；下轮重新查库即可追上当前状态。
            if (closed.get() || !sending.compareAndSet(false, true)) return;
            try {
                sendExecutor.execute(() -> {
                    try { if (!closed.get()) send(snapshot); }
                    finally { sending.set(false); }
                });
            } catch (RejectedExecutionException exception) {
                // 队列满时关闭连接让客户端重连，不在轮询线程里降级执行网络写入。
                sending.set(false);
                close();
            }
        }

        void send(TaskSnapshot snapshot) {
            try {
                // 单调时钟只用于计算时间间隔，不受系统时间校准影响。
                long now = System.nanoTime();
                if (!snapshot.equals(lastSnapshot)) {
                    // record 的字段比较用于去重；只在发送成功后更新已发送快照。
                    emitter.send(SseEmitter.event().name("snapshot").data(snapshot));
                    lastSnapshot = snapshot;
                    lastSendNanos = now;
                } else if (now - lastSendNanos >= TimeUnit.MILLISECONDS.toNanos(heartbeatMs)) {
                    // SSE 注释用于保持连接，不会触发前端的 snapshot 事件处理器。
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastSendNanos = now;
                }
                // 必须先发送终态快照再关闭；前端收到终态也要 close，停止自动重连。
                if (snapshot.status().isTerminal()) close();
            } catch (Exception exception) {
                close();
            }
        }

        void remove() {
            closed.set(true);
            subscriptions.computeIfPresent(taskId, (id, group) -> {
                group.remove(this);
                // 返回 null 会移除这个 taskId，无订阅的任务不再被 SSE 轮询。
                return group.isEmpty() ? null : group;
            });
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                remove();
                emitter.complete();
            }
        }
    }
}
