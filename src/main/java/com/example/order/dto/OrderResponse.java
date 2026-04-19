package com.example.order.dto;

import com.example.order.model.Order;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class OrderResponse {
    
    private String orderId;
    private String customerId;
    private Double orderAmount;
    private String status;
    private Instant processedAt;
    private Long processingTimeMs;
    private String message;
    private String processedBy;
    
    public OrderResponse() {}
    
    public static OrderResponse fromOrder(Order order, String message) {
        OrderResponse response = new OrderResponse();
        response.setOrderId(order.getOrderId());
        response.setCustomerId(order.getCustomerId());
        response.setOrderAmount(order.getOrderAmount());
        response.setStatus(order.getStatus().name());
        response.setProcessedAt(order.getProcessedAt());
        response.setProcessedBy(order.getProcessedBy());
        response.setMessage(message);
        
        if (order.getProcessedAt() != null && order.getCreatedAt() != null) {
            response.setProcessingTimeMs(
                ChronoUnit.MILLIS.between(order.getCreatedAt(), order.getProcessedAt())
            );
        }
        
        return response;
    }
    
    public static OrderResponse error(String orderId, String message) {
        OrderResponse response = new OrderResponse();
        response.setOrderId(orderId);
        response.setStatus("ERROR");
        response.setMessage(message);
        return response;
    }
    
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    
    public Double getOrderAmount() { return orderAmount; }
    public void setOrderAmount(Double orderAmount) { this.orderAmount = orderAmount; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    
    public Long getProcessingTimeMs() { return processingTimeMs; }
    public void setProcessingTimeMs(Long processingTimeMs) { this.processingTimeMs = processingTimeMs; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public String getProcessedBy() { return processedBy; }
    public void setProcessedBy(String processedBy) { this.processedBy = processedBy; }
}
