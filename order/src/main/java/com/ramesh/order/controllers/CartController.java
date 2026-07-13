package com.ramesh.order.controllers;


import com.ramesh.order.dtos.CartItemRequest;
import com.ramesh.order.dtos.CartItemResponse;
import com.ramesh.order.services.CartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/carts")
public class CartController {

    private final CartService cartService;

    @PostMapping
    public ResponseEntity<String> addToCart(
            @RequestHeader("X-User-ID") String userId,
            @RequestBody CartItemRequest cartItemRequest
            ){
        if(cartService.addToCart(userId, cartItemRequest)) {
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .build();
        }else{
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Product out of stock or User not found or Product not found");
        }
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<Void> removeFromCart(
            @RequestHeader("X-User-ID") String userId,
            @PathVariable("productId") String productId
    ){
        boolean deleted = cartService.deleteItemFromCart(userId, productId);
        return deleted ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping
    public ResponseEntity<List<CartItemResponse>> getUserCart(@RequestHeader("X-User-ID") String userId){

        return ResponseEntity.ok(cartService.fetchUserCart(userId));

    }
}
