package com.ai2b.crypto.metrics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MetricConfiguration {
    @Bean(name = "metricExecutor")
    ThreadPoolTaskExecutor metricExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("metric-collector-");
        executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(20);
        return executor;
    }
}
