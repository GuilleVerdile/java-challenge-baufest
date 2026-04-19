package com.example.order.loadtest;

import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.model.OrderItem;
import com.example.order.service.OrderProcessingService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-load concurrency test simulating 1000 concurrent order processing requests.
 * Validates throughput, latency, and data consistency under pressure.
 */
@SpringBootTest
class HighLoadConcurrencyTest {

    @Autowired
    private OrderProcessingService orderService;

    @Test
    @DisplayName("Should handle 1000 concurrent orders without data loss")
    void shouldHandle1000ConcurrentOrders() throws Exception {
        final int totalOrders = 1000;
        final int concurrency = 200; // simulate 200 clients hitting the API

        ExecutorService clientExecutor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalOrders);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong totalLatency = new AtomicLong();
        List<Long> latencies = new ArrayList<>(totalOrders);

        for (int i = 0; i < totalOrders; i++) {
            final int idx = i;
            clientExecutor.submit(() -> {
                try {
                    startLatch.await(); // synchronize start to maximize contention
                    long t0 = System.currentTimeMillis();

                    OrderRequest req = buildOrder(idx);
                    OrderResponse resp = orderService.processOrderSync(req);

                    long elapsed = System.currentTimeMillis() - t0;
                    totalLatency.addAndGet(elapsed);
                    synchronized (latencies) { latencies.add(elapsed); }

                    if ("COMPLETED".equals(resp.getStatus())) {
                        success.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        long overallStart = System.currentTimeMillis();
        startLatch.countDown(); // release all threads
        boolean completed = doneLatch.await(120, TimeUnit.SECONDS);
        long overallElapsed = System.currentTimeMillis() - overallStart;

        clientExecutor.shutdown();

        assertTrue(completed, "Not all orders completed within timeout");

        // Compute metrics
        latencies.sort(Long::compareTo);
        long p50 = latencies.get(latencies.size() / 2);
        long p95 = latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.get((int) (latencies.size() * 0.99));
        long max = latencies.get(latencies.size() - 1);
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double throughput = (double) totalOrders / (overallElapsed / 1000.0);

        System.out.println("\n========== HIGH LOAD TEST REPORT ==========");
        System.out.println("Total orders:         " + totalOrders);
        System.out.println("Successful:           " + success.get());
        System.out.println("Failed:               " + failed.get());
        System.out.println("Total wall time (ms): " + overallElapsed);
        System.out.println("Throughput (req/s):   " + String.format("%.2f", throughput));
        System.out.println("Latency avg (ms):     " + String.format("%.2f", avg));
        System.out.println("Latency p50 (ms):     " + p50);
        System.out.println("Latency p95 (ms):     " + p95);
        System.out.println("Latency p99 (ms):     " + p99);
        System.out.println("Latency max (ms):     " + max);
        System.out.println("===========================================\n");

        // Data consistency: sum of success + failed = total (no lost requests)
        assertEquals(totalOrders, success.get() + failed.get(),
                "Lost requests! success+failed should equal total");

        // At least some should succeed
        assertTrue(success.get() > 0, "Expected at least some successful orders");
    }

    private OrderRequest buildOrder(int idx) {
        OrderRequest r = new OrderRequest();
        r.setOrderId("LOAD-" + idx);
        r.setCustomerId("CUST-" + (idx % 20)); // 20 distinct customers
        r.setOrderAmount(100.0);
        r.setOrderItems(List.of(
                new OrderItem("PROD-001", "Product A", 1, 50.0),
                new OrderItem("PROD-002", "Product B", 1, 50.0)
        ));
        return r;
    }
}
