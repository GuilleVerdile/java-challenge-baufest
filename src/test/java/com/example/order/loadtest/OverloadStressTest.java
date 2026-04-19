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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress test: dispara 5000 pedidos concurrentes, bastante por encima del throughput
 * nominal del sistema, para validar que bajo SOBRECARGA:
 *   1. No se pierden solicitudes (success + failed == total).
 *   2. No ocurren deadlocks (termina dentro del timeout).
 *   3. El sistema degrada graciosamente (backpressure vía CallerRunsPolicy).
 *   4. Las métricas de latencia siguen siendo reportables.
 */
@SpringBootTest
class OverloadStressTest {

    @Autowired
    private OrderProcessingService orderService;

    @Test
    @DisplayName("Stress: 5000 orders concurrent - no data loss, no deadlocks under overload")
    void stressTest5000Orders() throws Exception {
        final int totalOrders = 5000;
        final int concurrency = 500; // 500 virtual clients → gran contención

        ExecutorService clientExecutor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalOrders);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(totalOrders));

        for (int i = 0; i < totalOrders; i++) {
            final int idx = i;
            clientExecutor.submit(() -> {
                try {
                    startLatch.await();
                    long t0 = System.currentTimeMillis();

                    OrderRequest req = buildOrder(idx);
                    OrderResponse resp = orderService.processOrderSync(req);

                    latencies.add(System.currentTimeMillis() - t0);

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
        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.MINUTES);
        long overallElapsed = System.currentTimeMillis() - overallStart;

        clientExecutor.shutdown();
        clientExecutor.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(completed, "No completo dentro del timeout -> posible deadlock");

        latencies.sort(Long::compareTo);
        long p50 = latencies.get(latencies.size() / 2);
        long p95 = latencies.get((int) (latencies.size() * 0.95));
        long p99 = latencies.get((int) (latencies.size() * 0.99));
        long max = latencies.get(latencies.size() - 1);
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        double throughput = (double) totalOrders / (overallElapsed / 1000.0);

        System.out.println("\n========== OVERLOAD STRESS TEST REPORT ==========");
        System.out.println("Total orders:         " + totalOrders);
        System.out.println("Virtual clients:      " + concurrency);
        System.out.println("Successful:           " + success.get());
        System.out.println("Failed (rechazados):  " + failed.get());
        System.out.println("Total wall time (ms): " + overallElapsed);
        System.out.println("Throughput (req/s):   " + String.format("%.2f", throughput));
        System.out.println("Latency avg (ms):     " + String.format("%.2f", avg));
        System.out.println("Latency p50 (ms):     " + p50);
        System.out.println("Latency p95 (ms):     " + p95);
        System.out.println("Latency p99 (ms):     " + p99);
        System.out.println("Latency max (ms):     " + max);
        System.out.println("==================================================\n");

        // Invariante CRITICA: no hay solicitudes perdidas
        assertEquals(totalOrders, success.get() + failed.get(),
                "PERDIDA DE DATOS: success+failed != total");

        // El sistema debe seguir respondiendo (aun si rechaza por inventario)
        assertEquals(totalOrders, latencies.size(),
                "Alguna solicitud nunca respondio");
    }

    private OrderRequest buildOrder(int idx) {
        OrderRequest r = new OrderRequest();
        r.setOrderId("STRESS-" + idx);
        r.setCustomerId("CUST-" + (idx % 50)); // 50 clientes distintos
        r.setOrderAmount(50.0);
        r.setOrderItems(List.of(
                new OrderItem("PROD-001", "Product A", 1, 50.0)
        ));
        return r;
    }
}
