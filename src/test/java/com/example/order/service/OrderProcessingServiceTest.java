package com.example.order.service;

import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.model.OrderItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OrderProcessingServiceTest {

    private OrderProcessingService service;

    @BeforeEach
    void setUp() {
        service = new OrderProcessingService();
    }

    @Test
    void testSuccessfulOrderProcessing() {
        OrderRequest request = createValidOrderRequest("ORDER-001", "CUST-001");
        
        OrderResponse response = service.processOrderSync(request);
        
        assertEquals("COMPLETED", response.getStatus());
        assertNotNull(response.getProcessedAt());
        assertTrue(response.getProcessingTimeMs() >= 0);
    }

    @Test
    void testInvalidAmountValidation() {
        OrderRequest request = new OrderRequest();
        request.setOrderId("ORDER-002");
        request.setCustomerId("CUST-001");
        request.setOrderAmount(999.0); // Mismatched amount
        request.setOrderItems(List.of(
            new OrderItem("PROD-001", "Product A", 1, 50.0)
        ));
        
        OrderResponse response = service.processOrderSync(request);
        
        assertEquals("FAILED", response.getStatus());
    }

    @Test
    void testInventoryReservation() throws InterruptedException {
        int orderCount = 10;
        CountDownLatch latch = new CountDownLatch(orderCount);
        ExecutorService executor = Executors.newFixedThreadPool(5);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // Each order reserves 50 units of PROD-001 (500 total available)
        for (int i = 0; i < orderCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    OrderRequest request = createValidOrderRequest(
                        "INV-ORDER-" + index, 
                        "CUST-INV"
                    );
                    // Use PROD-003 with only 200 units
                    request.setOrderItems(List.of(
                        new OrderItem("PROD-003", "Product C", 25, 10.0)
                    ));
                    request.setOrderAmount(250.0);
                    
                    OrderResponse response = service.processOrderSync(request);
                    
                    if ("COMPLETED".equals(response.getStatus())) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // PROD-003 has 200 units, each order uses 25
        // Max 8 orders should succeed
        System.out.println("Inventory test - Success: " + successCount.get() + ", Failed: " + failCount.get());
        assertTrue(successCount.get() <= 8);
    }

    @Test
    void testAsyncProcessing() throws Exception {
        OrderRequest request = createValidOrderRequest("ASYNC-001", "CUST-ASYNC");
        
        CompletableFuture<OrderResponse> future = service.processOrderAsync(request);
        OrderResponse response = future.get(5, TimeUnit.SECONDS);
        
        assertEquals("COMPLETED", response.getStatus());
    }

    @Test
    void testStatisticsTracking() {
        int orderCount = 5;
        
        for (int i = 0; i < orderCount; i++) {
            OrderRequest request = createValidOrderRequest("STAT-" + i, "CUST-STAT");
            service.processOrderSync(request);
        }
        
        var stats = service.getStatistics();
        
        assertTrue(((Number) stats.get("completedOrders")).longValue() >= orderCount);
        assertTrue(((Number) stats.get("totalProcessed")).longValue() >= orderCount);
    }

    private OrderRequest createValidOrderRequest(String orderId, String customerId) {
        OrderRequest request = new OrderRequest();
        request.setOrderId(orderId);
        request.setCustomerId(customerId);
        request.setOrderAmount(150.0);
        request.setOrderItems(List.of(
            new OrderItem("PROD-001", "Product A", 2, 50.0),
            new OrderItem("PROD-002", "Product B", 1, 50.0)
        ));
        return request;
    }
}
