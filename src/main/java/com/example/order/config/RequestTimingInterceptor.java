package com.example.order.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interceptor to log request timing and attach a requestId to the MDC for tracing.
 * Helps identify bottlenecks and correlate logs across threads.
 */
@Component
public class RequestTimingInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(RequestTimingInterceptor.class);
    private static final String START_TIME_ATTR = "startTime";
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader("X-Request-Id", requestId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTR);
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            totalRequests.incrementAndGet();
            totalLatencyMs.addAndGet(duration);
            response.setHeader("X-Processing-Time-Ms", String.valueOf(duration));
            logger.info("REQUEST method={} uri={} status={} durationMs={} thread={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    duration, Thread.currentThread().getName());
        }
        MDC.clear();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    public long getTotalRequests() { return totalRequests.get(); }
    public long getAverageLatencyMs() {
        long n = totalRequests.get();
        return n == 0 ? 0 : totalLatencyMs.get() / n;
    }
}
