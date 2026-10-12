package agent_backend.personal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="agent.scheduler.enabled", havingValue="true", matchIfMissing=true)
public class ResearchPublisher {
    private static final Logger log = LoggerFactory.getLogger(ResearchPublisher.class);
    private final ResearchStore research;
    public ResearchPublisher(ResearchStore research) { this.research = research; }

    @Scheduled(fixedDelayString="${agent.scheduler.poll-interval-ms:1000}")
    public void poll() {
        try {
            for (String id : research.unpublished(50)) {
                try { research.publish(id); }
                catch (Exception failure) { log.warn("Research publication failed; will retry: {}", id); }
            }
        } catch (Exception failure) {
            log.warn("Research publication scan unavailable; will retry");
        }
    }
}
