package com.example.order.dto;

import com.example.order.model.OrderItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public class OrderRequest {
    
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
    
    public OrderRequest() {}
    
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    
    public Double getOrderAmount() { return orderAmount; }
    public void setOrderAmount(Double orderAmount) { this.orderAmount = orderAmount; }
    
    public List<OrderItem> getOrderItems() { return orderItems; }
    public void setOrderItems(List<OrderItem> orderItems) { this.orderItems = orderItems; }
}
