package agent_backend;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

@Configuration
public class LlmHttpConfig {
    @Bean(destroyMethod = "close")
    public CloseableHttpClient llmHttpClient(
            @Value("${llm.http.connection-request-timeout:5s}") Duration poolTimeout,
            @Value("${llm.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${llm.http.response-timeout:120s}") Duration responseTimeout) {
        var pool = positive(poolTimeout);
        var connect = positive(connectTimeout);
        var response = positive(responseTimeout);
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(connect).setSocketTimeout(response).build())
                // Socket timeout also bounds blocking reads during connection setup.
                .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(response).build())
                .build();
        return HttpClients.custom().setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(pool).setResponseTimeout(response).build())
                // Retry policy belongs to the application; never silently repeat a model request.
                .disableAutomaticRetries().disableRedirectHandling().build();
    }

    @Bean
    public RestClient llmRestClient(@Qualifier("llmHttpClient") CloseableHttpClient client,
            @Value("${llm.base-url:https://api.openai.com/v1}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(client)).build();
    }

    private static Timeout positive(Duration duration) {
        Assert.isTrue(duration != null && duration.toMillis() > 0,
                "LLM HTTP timeout must be at least 1ms");
        return Timeout.ofMilliseconds(duration.toMillis());
    }
}
