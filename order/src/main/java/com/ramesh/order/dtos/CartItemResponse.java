package com.ramesh.order.dtos;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class CartItemResponse {
    private String userId;
    private String productId;
    private Integer quantity;
    private BigDecimal price;
    private BigDecimal subTotal;
}
