package com.taskflow.api.config;

import com.resend.Resend;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResendConfig {

    @Bean
    public Resend resend(AppProperties properties) {
        String apiKey = properties.mail().resendApiKey();
        return new Resend(apiKey == null ? "" : apiKey);
    }
}
