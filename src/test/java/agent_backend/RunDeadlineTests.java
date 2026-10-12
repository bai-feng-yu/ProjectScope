package agent_backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import agent_backend.task.RunDeadline;

class RunDeadlineTests {
    @Test void entireProviderCallIsBoundedAndInterruptedWithoutRetry() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("run"))).thenReturn(List.of(50000L));
        var interrupted = new CountDownLatch(1);
        assertTimeout(Duration.ofSeconds(1), () -> assertThrows(IllegalStateException.class,
                () -> new RunDeadline(jdbc).call("run", () -> {
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException stopped) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                    return "late";
                })));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        when(jdbc.queryForList(anyString(), eq(Long.class), eq("run"))).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> new RunDeadline(jdbc).call("run", () -> fail("Must not start provider call")));
    }
}
