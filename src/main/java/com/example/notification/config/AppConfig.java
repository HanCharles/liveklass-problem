package com.example.notification.config;

import com.example.notification.domain.RetryPolicy;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotificationRetryProperties.class)
public class AppConfig {

    /** 재시도/lease 시각 계산에 사용하는 시계. 테스트에서 필요 시 다른 Clock으로 교체 가능하도록 빈으로 분리했다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RetryPolicy retryPolicy(NotificationRetryProperties properties) {
        return new RetryPolicy(properties.maxAttempts(), properties.backoffSeconds());
    }
}
