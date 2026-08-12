package com.example.notification.config;

import com.example.notification.domain.RetryPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({NotificationRetryProperties.class, NotificationCircuitBreakerProperties.class})
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

    /**
     * 채널별 CircuitBreaker를 만들 때 공통으로 사용하는 설정. 채널마다 별도 CircuitBreaker
     * 인스턴스를 두므로(각자 독립적으로 open/close), 설정 자체는 하나만 공유해도 된다.
     */
    @Bean
    public CircuitBreakerConfig notificationCircuitBreakerConfig(NotificationCircuitBreakerProperties properties) {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(Duration.ofSeconds(properties.waitDurationInOpenStateSeconds()))
                .build();
    }
}
