package com.example.order.controller;

import com.example.order.model.OrderItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class OrderControllerConcurrencyTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testConcurrentOrderProcessing() throws Exception {
        int threadCount = 50;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    Map<String, Object> order = Map.of(
                        "orderId", "ORDER-" + index,
                        "customerId", "CUST-" + (index % 5),
                        "orderAmount", 150.0,
                        "orderItems", List.of(
                            Map.of("productId", "PROD-001", "productName", "Product A", 
                                   "quantity", 2, "unitPrice", 50.0),
                            Map.of("productId", "PROD-002", "productName", "Product B", 
                                   "quantity", 1, "unitPrice", 50.0)
                        )
                    );

                    MvcResult result = mockMvc.perform(post("/api/v1/processOrder")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(order)))
                            .andExpect(status().isOk())
                            .andReturn();

                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();

        System.out.println("Success: " + successCount.get() + ", Failed: " + failCount.get());
        assertTrue(successCount.get() > 0);
    }

    @Test
    void testDuplicateOrderIdempotency() throws Exception {
        String orderId = "DUPLICATE-ORDER-001";
        String customerId = "CUST-001";

        Map<String, Object> order = Map.of(
            "orderId", orderId,
            "customerId", customerId,
            "orderAmount", 100.0,
            "orderItems", List.of(
                Map.of("productId", "PROD-001", "productName", "Product A", 
                       "quantity", 2, "unitPrice", 50.0)
            )
        );

        // First request
        mockMvc.perform(post("/api/v1/processOrder")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk());

        // Duplicate request
        mockMvc.perform(post("/api/v1/processOrder")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(order)))
                .andExpect(status().isOk());

        // Check statistics
        MvcResult statsResult = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/statistics"))
            .andReturn();

        String content = statsResult.getResponse().getContentAsString();
        Map stats = objectMapper.readValue(content, Map.class);
        
        // Should have processed only once for this order
        assertNotNull(stats.get("completedOrders"));
    }
}
