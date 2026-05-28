package it.unicas.spring.googleapi.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    /**
     * RestClient (Spring 6.1+) per chiamate HTTP a Google Maps API.
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("Accept", "application/json")
                .build();
    }
}

