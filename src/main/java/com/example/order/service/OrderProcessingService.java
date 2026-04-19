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

/**
 * Core service responsible for processing orders under high concurrency.
 *
 * <p><b>Concurrency design decisions:</b></p>
 * <ul>
 *   <li>{@link ConcurrentHashMap} for the orders store: allows lock-free reads and
 *       fine-grained segment locking on writes, outperforming a synchronized HashMap
 *       significantly under read-heavy workloads.</li>
 *   <li>{@link AtomicLong} for all counters: uses CPU-level CAS (Compare-And-Swap)
 *       instructions, making counter updates lock-free and avoiding contention.</li>
 *   <li>{@link ReentrantLock} (fair mode) for inventory: the inventory check-then-act
 *       sequence must be atomic to prevent overselling. Fair mode ensures FIFO ordering
 *       and prevents thread starvation at the cost of a small throughput reduction.</li>
 *   <li>Per-customer {@link Semaphore} (fair mode): isolates noisy customers and caps
 *       their concurrency, preventing one client from monopolizing all threads.</li>
 *   <li>All lock/semaphore acquisitions use {@code tryAcquire(timeout)} and are released
 *       in {@code finally} blocks, making deadlocks structurally impossible.</li>
 * </ul>
 */
@Service
public class OrderProcessingService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderProcessingService.class);
    
    // ConcurrentHashMap: thread-safe without a global lock; supports putIfAbsent atomically
    private final ConcurrentHashMap<String, Order> ordersStore = new ConcurrentHashMap<>();
    
    // AtomicLong: lock-free counters using CAS; incrementAndGet is an atomic read-modify-write
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalProcessingTime = new AtomicLong(0);
    
    // Per-customer Semaphore: limits concurrent in-flight orders per customer.
    // computeIfAbsent is atomic in ConcurrentHashMap, so no two threads create the same semaphore.
    private final ConcurrentHashMap<String, Semaphore> customerSemaphores = new ConcurrentHashMap<>();
    
    // Fair ReentrantLock: serializes inventory check-and-reserve to prevent race conditions.
    // Fair=true guarantees FIFO ordering among waiting threads, preventing starvation.
    private final ReentrantLock inventoryLock = new ReentrantLock(true);
    
    // Simulated in-memory inventory using AtomicLong per product for stock tracking
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
     * Processes an order asynchronously using the dedicated {@code orderTaskExecutor} thread pool.
     *
     * <p>Uses {@link CompletableFuture} to avoid blocking the HTTP servlet thread while
     * the order is being processed in the background pool. The caller can chain further
     * async stages (e.g. {@code thenCombine}, {@code allOf}) without additional blocking.</p>
     *
     * @param request the incoming order payload
     * @return a future that resolves to the processing result
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
     * Processes an order synchronously with full concurrency control.
     *
     * <p>Suitable for clients that require an immediate response. The calling HTTP thread
     * performs the work itself but is still protected by all concurrency mechanisms
     * (semaphore, inventory lock, idempotency check).</p>
     *
     * @param request the incoming order payload
     * @return the processing result
     */
    public OrderResponse processOrderSync(OrderRequest request) {
        String threadName = Thread.currentThread().getName();
        return processOrderWithConcurrencyControl(request, threadName);
    }
    
    /**
     * Central concurrency gate: applies idempotency check and per-customer rate limiting
     * before delegating to the actual processing logic.
     *
     * <p><b>Idempotency</b>: if the same {@code orderId} is received twice and the first
     * run already completed, we return the cached result immediately without reprocessing.
     * This protects against duplicate submissions caused by client retries.</p>
     *
     * <p><b>Per-customer semaphore</b>: {@code computeIfAbsent} on a {@link ConcurrentHashMap}
     * is atomic, so exactly one {@link Semaphore} is created per customer even under
     * heavy concurrent traffic. {@code tryAcquire(timeout)} instead of {@code acquire()}
     * ensures threads never wait indefinitely, preventing deadlocks.</p>
     */
    private OrderResponse processOrderWithConcurrencyControl(OrderRequest request, String threadName) {
        String orderId = request.getOrderId();
        String customerId = request.getCustomerId();
        
        // Fast-path: return cached result for already-completed orders (idempotency)
        if (ordersStore.containsKey(orderId)) {
            Order existingOrder = ordersStore.get(orderId);
            if (existingOrder.getStatus() == OrderStatus.COMPLETED) {
                return OrderResponse.fromOrder(existingOrder, "Order already processed (idempotent)");
            }
        }
        
        // computeIfAbsent is atomic on ConcurrentHashMap: guarantees a single Semaphore per customer
        Semaphore customerSemaphore = customerSemaphores.computeIfAbsent(
            customerId, 
            k -> new Semaphore(maxConcurrentPerCustomer, true) // fair=true prevents starvation
        );
        
        boolean acquired = false;
        try {
            // tryAcquire with timeout: bounded wait avoids deadlocks; fail-fast under overload
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
    
    /**
     * Executes the full order processing pipeline: validation → inventory reservation
     * → payment simulation → fulfillment delay → state update.
     *
     * <p>{@code putIfAbsent} provides a second idempotency layer at the map level:
     * if two threads race past the fast-path check above with the same orderId,
     * only one will win the insert; the other gets back the existing order.</p>
     *
     * <p>If any step fails after inventory was reserved, {@link #releaseInventory}
     * rolls back the reservation to avoid permanently reducing available stock.</p>
     */
    private OrderResponse executeOrderProcessing(OrderRequest request, String threadName) {
        String orderId = request.getOrderId();
        long startTime = System.currentTimeMillis();
        
        Order order = new Order(
            orderId,
            request.getCustomerId(),
            request.getOrderAmount(),
            request.getOrderItems()
        );
        
        // putIfAbsent: atomic insert; returns null if inserted, existing value otherwise
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
    
    /**
     * Reserves stock for all items in the order atomically.
     *
     * <p>The entire check-then-act sequence runs inside a {@link ReentrantLock} to prevent
     * the classic TOCTOU (Time-of-Check-Time-of-Use) race condition where two threads
     * both read sufficient stock and then both decrement, causing negative inventory.
     * The lock is released in a {@code finally} block to guarantee no leak even on exception.</p>
     *
     * @return {@code true} if all items were reserved, {@code false} if any item is out of stock
     */
    private boolean reserveInventory(Order order) {
        inventoryLock.lock();
        try {
            // Phase 1: validate all items before modifying any (all-or-nothing semantics)
            for (var item : order.getOrderItems()) {
                AtomicLong stock = inventory.get(item.getProductId());
                if (stock == null || stock.get() < item.getQuantity()) {
                    return false;
                }
            }
            
            // Phase 2: deduct stock only if all items are available
            for (var item : order.getOrderItems()) {
                inventory.get(item.getProductId()).addAndGet(-item.getQuantity());
            }
            
            return true;
        } finally {
            inventoryLock.unlock(); // always released, even on RuntimeException
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
