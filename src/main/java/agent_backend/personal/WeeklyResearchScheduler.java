package agent_backend.personal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="agent.scheduler.enabled", havingValue="true", matchIfMissing=true)
public class WeeklyResearchScheduler {
    private static final Logger log = LoggerFactory.getLogger(WeeklyResearchScheduler.class);
    private final WeeklyResearchStore schedules;
    public WeeklyResearchScheduler(WeeklyResearchStore schedules) { this.schedules = schedules; }

    @Scheduled(fixedDelayString="${agent.scheduler.poll-interval-ms:1000}")
    public void poll() {
        try {
            for (String id : schedules.due()) {
                try { schedules.fire(id); }
                catch (Exception failure) { log.warn("Weekly trigger failed; will retry: {}", id); }
            }
        } catch (Exception failure) { log.warn("Weekly schedule scan unavailable; will retry"); }
    }
}
