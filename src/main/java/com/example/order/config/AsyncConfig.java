package com.example.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configures the dedicated thread pool used for asynchronous order processing.
 *
 * <p><b>Why a separate executor?</b><br>
 * Spring Boot's default task executor is shared by all {@code @Async} tasks. Using a
 * dedicated pool (named {@code orderTaskExecutor}) isolates order processing threads
 * from other application tasks and allows independent tuning.</p>
 *
 * <p><b>Pool sizing rationale (Little's Law):</b><br>
 * {@code threads ≈ target_RPS × avg_latency_seconds}<br>
 * For 200 RPS and ~300ms average latency: 200 × 0.3 = 60 threads needed at steady state.
 * Max is set to 100 to absorb bursts, and the queue to 500 to buffer spikes.</p>
 *
 * <p><b>{@code CallerRunsPolicy}</b>: when both the pool and queue are saturated, the
 * thread that submitted the task executes it inline. This creates natural backpressure:
 * the HTTP thread slows down, preventing request loss while signaling overload to clients.</p>
 *
 * <p>{@code setWaitForTasksToCompleteOnShutdown(true)} ensures in-flight orders finish
 * gracefully during application shutdown (e.g. rolling deployments).</p>
 */
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
