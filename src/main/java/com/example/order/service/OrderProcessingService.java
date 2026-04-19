package com.example.order.service;

import com.example.order.dto.OrderRequest;
import com.example.order.dto.OrderResponse;
import com.example.order.model.Order;
import com.example.order.model.Order.OrderStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class OrderProcessingService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderProcessingService.class);
    
    // Thread-safe storage using ConcurrentHashMap
    private final ConcurrentHashMap<String, Order> ordersStore = new ConcurrentHashMap<>();
    
    // Track processing statistics atomically
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    
    // Semaphore per customer to limit concurrent orders (prevents resource exhaustion)
    private final ConcurrentHashMap<String, Semaphore> customerSemaphores = new ConcurrentHashMap<>();
    
    // Lock for inventory operations (prevents race conditions)
    private final ReentrantLock inventoryLock = new ReentrantLock(true); // Fair lock
    
    // Simulate inventory
    private final ConcurrentHashMap<String, AtomicLong> inventory = new ConcurrentHashMap<>();
    
    @Value("${app.order.processing-timeout-ms:5000}")
    private long processingTimeoutMs = 5000;
    
    @Value("${app.order.max-concurrent-per-customer:5}")
    private int maxConcurrentPerCustomer = 5;
    
    public OrderProcessingService() {
        // Initialize mock inventory
        inventory.put("PROD-001", new AtomicLong(1000));
        inventory.put("PROD-002", new AtomicLong(500));
        inventory.put("PROD-003", new AtomicLong(200));
    }
    
    /**
     * Process order with concurrency control and thread safety
     */
    @Async("orderTaskExecutor")
    public CompletableFuture<OrderResponse> processOrderAsync(OrderRequest request) {
        String threadName = Thread.currentThread().getName();
        String orderId = request.getOrderId();
        String customerId = request.getCustomerId();
        
        logger.info("[{}] Starting async processing for order: {}, customer: {}", 
                   threadName, orderId, customerId);
        
        try {
            OrderResponse response = processOrderWithConcurrencyControl(request, threadName);
            return CompletableFuture.completedFuture(response);
        } catch (Exception e) {
            logger.error("[{}] Error processing order {}: {}", threadName, orderId, e.getMessage());
            return CompletableFuture.completedFuture(
                OrderResponse.error(orderId, "Processing failed: " + e.getMessage())
            );
        }
    }
    
    /**
     * Synchronous order processing with full concurrency control
     */
    public OrderResponse processOrderSync(OrderRequest request) {
        String threadName = Thread.currentThread().getName();
        return processOrderWithConcurrencyControl(request, threadName);
    }
    
    private OrderResponse processOrderWithConcurrencyControl(OrderRequest request, String threadName) {
        String orderId = request.getOrderId();
        String customerId = request.getCustomerId();
        
        // Check for duplicate order (idempotent processing)
        if (ordersStore.containsKey(orderId)) {
            Order existingOrder = ordersStore.get(orderId);
            if (existingOrder.getStatus() == OrderStatus.COMPLETED) {
                return OrderResponse.fromOrder(existingOrder, "Order already processed (idempotent)");
            }
        }
        
        // Get or create customer semaphore (limits concurrent orders per customer)
        Semaphore customerSemaphore = customerSemaphores.computeIfAbsent(
            customerId, 
            k -> new Semaphore(maxConcurrentPerCustomer, true) // Fair semaphore
        );
        
        boolean acquired = false;
        try {
            // Try to acquire permit with timeout (prevents deadlocks)
            acquired = customerSemaphore.tryAcquire(processingTimeoutMs, TimeUnit.MILLISECONDS);
            
            if (!acquired) {
                throw new TimeoutException("Too many concurrent orders for customer: " + customerId);
            }
            
            return executeOrderProcessing(request, threadName);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Processing interrupted", e);
        } catch (TimeoutException e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            if (acquired) {
                customerSemaphore.release();
            }
        }
    }
    
    private OrderResponse executeOrderProcessing(OrderRequest request, String threadName) {
        String orderId = request.getOrderId();
        long startTime = System.currentTimeMillis();
        
        // Create order entity
        Order order = new Order(
            orderId,
            request.getCustomerId(),
            request.getOrderAmount(),
            request.getOrderItems()
        );
        
        // Store in thread-safe map (putIfAbsent to prevent overwrites)
        Order existing = ordersStore.putIfAbsent(orderId, order);
        if (existing != null && existing.getStatus() != OrderStatus.PENDING) {
            return OrderResponse.fromOrder(existing, "Order already being processed");
        }
        
        order.setStatus(OrderStatus.PROCESSING);
        order.setProcessedBy(threadName);
        
        try {
            // Validate order amount
            if (!order.validateAmount()) {
                order.setStatus(OrderStatus.FAILED);
                totalFailed.incrementAndGet();
                return OrderResponse.fromOrder(order, "Order amount mismatch with items total");
            }
            
            // Process inventory with lock (prevents overselling)
            boolean inventoryAvailable = reserveInventory(order);
            
            if (!inventoryAvailable) {
                order.setStatus(OrderStatus.FAILED);
                totalFailed.incrementAndGet();
                return OrderResponse.fromOrder(order, "Insufficient inventory");
            }
            
            // Simulate payment processing (idempotent)
            processPayment(order);
            
            // Simulate order fulfillment delay
            simulateProcessingDelay();
            
            // Complete order
            order.setStatus(OrderStatus.COMPLETED);
            order.setProcessedAt(Instant.now());
            
            // Update statistics atomically
            totalProcessed.incrementAndGet();
            totalProcessingTime.addAndGet(System.currentTimeMillis() - startTime);
            
            logger.info("[{}] Order {} completed successfully in {}ms", 
                       threadName, orderId, System.currentTimeMillis() - startTime);
            
            return OrderResponse.fromOrder(order, "Order processed successfully");
            
        } catch (Exception e) {
            order.setStatus(OrderStatus.FAILED);
            totalFailed.incrementAndGet();
            
            // Rollback inventory reservation
            releaseInventory(order);
            
            logger.error("[{}] Order {} failed: {}", threadName, orderId, e.getMessage());
            throw new RuntimeException("Order processing failed: " + e.getMessage(), e);
        }
    }
    
    private boolean reserveInventory(Order order) {
        inventoryLock.lock();
        try {
            // Check availability first
            for (var item : order.getOrderItems()) {
                AtomicLong stock = inventory.get(item.getProductId());
                if (stock == null || stock.get() < item.getQuantity()) {
                    return false;
                }
            }
            
            // Reserve inventory
            for (var item : order.getOrderItems()) {
                inventory.get(item.getProductId()).addAndGet(-item.getQuantity());
            }
            
            return true;
        } finally {
            inventoryLock.unlock();
        }
    }
    
    private void releaseInventory(Order order) {
        if (order.getStatus() == OrderStatus.FAILED) {
            inventoryLock.lock();
            try {
                for (var item : order.getOrderItems()) {
                    AtomicLong stock = inventory.get(item.getProductId());
                    if (stock != null) {
                        stock.addAndGet(item.getQuantity());
                    }
                }
            } finally {
                inventoryLock.unlock();
            }
        }
    }
    
    private void processPayment(Order order) {
        // Idempotent payment processing simulation
        // In real scenario, use payment gateway with idempotency keys
        try {
            Thread.sleep(50); // Simulate payment API call
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Payment processing interrupted");
        }
    }
    
    private void simulateProcessingDelay() throws InterruptedException {
        // Random delay between 100-500ms to simulate processing
        Thread.sleep(ThreadLocalRandom.current().nextInt(100, 500));
    }
    
    // Statistics methods
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("totalOrdersCreated", Order.getTotalOrdersCreated());
        stats.put("totalProcessed", totalProcessed.get());
        stats.put("totalFailed", totalFailed.get());
        stats.put("pendingOrders", 
            ordersStore.values().stream()
                .filter(o -> o.getStatus() == OrderStatus.PENDING || o.getStatus() == OrderStatus.PROCESSING)
                .count());
        stats.put("completedOrders", 
            ordersStore.values().stream()
                .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
                .count());
        
        long avgTime = totalProcessed.get() > 0 
            ? totalProcessingTime.get() / totalProcessed.get() 
            : 0;
        stats.put("averageProcessingTimeMs", avgTime);
        
        stats.put("inventory", inventory);
        
        return stats;
    }
    
    public Order getOrder(String orderId) {
        return ordersStore.get(orderId);
    }
}
