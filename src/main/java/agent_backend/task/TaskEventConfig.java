package agent_backend.task;

import java.util.concurrent.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskEventConfig {
    // 仅负责数据库轮询，与 Agent 执行和租约续期隔离；容器关闭时回收线程。
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService taskEventPollExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("task-events-poll-", 0).factory());
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService taskEventSendExecutor() {
        // 网络发送可能被慢客户端阻塞，因此使用独立线程池和有界队列。
        // AbortPolicy 抛出拒绝异常，由订阅服务关闭连接；不占用调用方的轮询线程。
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64),
                Thread.ofPlatform().daemon().name("task-events-send-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
