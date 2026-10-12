package agent_backend.toolRegistry.search;

import java.time.Duration;
import agent_backend.LlmHttpConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class BraveHttpConfig {
    @Bean(destroyMethod="close")
    public CloseableHttpClient braveHttpClient() {
        // Separate bounded pool, with the same no-retry/no-redirect transport policy as model calls.
        return new LlmHttpConfig().llmHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(8));
    }

    @Bean
    public RestClient braveRestClient(@Qualifier("braveHttpClient") CloseableHttpClient client) {
        // A model or client request cannot select a destination or forward our key to arbitrary hosts.
        return RestClient.builder().baseUrl("https://api.search.brave.com")
                .requestFactory(new HttpComponentsClientHttpRequestFactory(client)).build();
    }
}
