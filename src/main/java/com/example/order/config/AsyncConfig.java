package com.example.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    @Value("${app.thread-pool.core-size:20}")
    private int corePoolSize;

    @Value("${app.thread-pool.max-size:100}")
    private int maxPoolSize;

    @Value("${app.thread-pool.queue-capacity:500}")
    private int queueCapacity;

    @Value("${app.thread-pool.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    @Bean(name = "orderTaskExecutor")
    public Executor orderTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("order-processor-");
        
        // Caller runs policy to avoid rejecting tasks under load
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        
        executor.initialize();
        return executor;
    }
}
