package io.github.opspilot.sample.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@SpringBootApplication
public class SampleGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleGatewayApplication.class, args);
    }

    @Bean
    RestClient restClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(1));
        requests.setReadTimeout(Duration.ofSeconds(5));
        return builder.requestFactory(requests).build();
    }
}
