package com.example.order.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Order {
    
    @NotBlank(message = "Order ID is required")
    private String orderId;
    
    @NotBlank(message = "Customer ID is required")
    private String customerId;
    
    @NotNull(message = "Order amount is required")
    @Positive(message = "Order amount must be positive")
    private Double orderAmount;
    
    @NotEmpty(message = "Order must have at least one item")
    @Valid
    private List<OrderItem> orderItems;
    
    private OrderStatus status;
    private Instant createdAt;
    private Instant processedAt;
    private String processedBy;
    
    // Concurrent counter for statistics
    private static final AtomicInteger totalOrdersCreated = new AtomicInteger(0);
    
    public Order() {
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
        totalOrdersCreated.incrementAndGet();
    }
    
    public Order(String orderId, String customerId, Double orderAmount, List<OrderItem> orderItems) {
        this();
        this.orderId = orderId;
        this.customerId = customerId;
        this.orderAmount = orderAmount;
        this.orderItems = orderItems;
    }
    
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    
    public Double getOrderAmount() { return orderAmount; }
    public void setOrderAmount(Double orderAmount) { this.orderAmount = orderAmount; }
    
    public List<OrderItem> getOrderItems() { return orderItems; }
    public void setOrderItems(List<OrderItem> orderItems) { this.orderItems = orderItems; }
    
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    
    public String getProcessedBy() { return processedBy; }
    public void setProcessedBy(String processedBy) { this.processedBy = processedBy; }
    
    public static int getTotalOrdersCreated() {
        return totalOrdersCreated.get();
    }
    
    public double calculateTotal() {
        if (orderItems == null) return 0.0;
        return orderItems.stream()
                .mapToDouble(OrderItem::getSubtotal)
                .sum();
    }
    
    public boolean validateAmount() {
        return Math.abs(calculateTotal() - orderAmount) < 0.01;
    }
    
    @Override
    public String toString() {
        return "Order{orderId='" + orderId + "', customerId='" + customerId + "', status=" + status + "}";
    }
    
    public enum OrderStatus {
        PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    }
}
