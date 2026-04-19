package com.example.order.controller;

import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.model.Order;
import com.example.order.service.OrderProcessingService;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RestController
public class OrderController {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);
    
    private final OrderProcessingService orderService;
    
    public OrderController(OrderProcessingService orderService) {
        this.orderService = orderService;
    }
    
    /**
     * POST /api/v1/processOrder - Synchronous order processing
     * For immediate response requirements
     */
    @PostMapping({"/processOrder", "/api/v1/processOrder"})
    public ResponseEntity<OrderResponse> processOrder(@Valid @RequestBody OrderRequest request) {
        logger.info("Received sync order request: {}", request.getOrderId());
        
        try {
            OrderResponse response = orderService.processOrderSync(request);
            
            if ("ERROR".equals(response.getStatus())) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error processing order {}: {}", request.getOrderId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(OrderResponse.error(request.getOrderId(), e.getMessage()));
        }
    }
    
    /**
     * POST /api/v1/processOrder/async - Asynchronous order processing
     * For high-throughput scenarios, returns immediately with accepted status
     */
    @PostMapping({"/processOrder/async", "/api/v1/processOrder/async"})
    public ResponseEntity<OrderResponse> processOrderAsync(@Valid @RequestBody OrderRequest request) {
        logger.info("Received async order request: {}", request.getOrderId());
        
        try {
            // Start async processing
            CompletableFuture<OrderResponse> future = orderService.processOrderAsync(request);
            
            // Wait for completion with timeout (adjust based on requirements)
            OrderResponse response = future.get(10, TimeUnit.SECONDS);
            
            if ("ERROR".equals(response.getStatus())) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response);
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Async processing error for order {}: {}", request.getOrderId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(OrderResponse.error(request.getOrderId(), 
                        "Order accepted for processing. Check status later."));
        }
    }
    
    /**
     * GET /api/v1/orders/{orderId} - Check order status
     */
    @GetMapping({"/orders/{orderId}", "/api/v1/orders/{orderId}"})
    public ResponseEntity<?> getOrderStatus(@PathVariable String orderId) {
        Order order = orderService.getOrder(orderId);
        
        if (order == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Order not found", "orderId", orderId));
        }
        
        return ResponseEntity.ok(OrderResponse.fromOrder(order, "Order found"));
    }
    
    /**
     * GET /api/v1/statistics - System statistics
     */
    @GetMapping({"/statistics", "/api/v1/statistics"})
    public ResponseEntity<Map<String, Object>> getStatistics() {
        return ResponseEntity.ok(orderService.getStatistics());
    }
    
    /**
     * Health check endpoint
     */
    @GetMapping({"/health", "/api/v1/health"})
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "order-processing-api"
        ));
    }
}
