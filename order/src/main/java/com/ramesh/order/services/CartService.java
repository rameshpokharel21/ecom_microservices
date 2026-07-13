package com.ramesh.order.services;


import com.ramesh.order.dtos.CartItemRequest;
import com.ramesh.order.dtos.CartItemResponse;
import com.ramesh.order.entities.CartItem;
import com.ramesh.order.mappers.CartItemMapper;
import com.ramesh.order.repositories.CartItemRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class CartService {


    private final CartItemRepository cartItemRepository;
    private final CartItemMapper cartItemMapper;

    public boolean addToCart(String userId, CartItemRequest request) {


        CartItem existingCartItem = cartItemRepository.findByUserIdAndProductId(userId, request.getProductId());

        if(existingCartItem != null){

            //update quantity
            Integer updatedQuantity = existingCartItem.getQuantity() + request.getQuantity();
            existingCartItem.setQuantity(updatedQuantity);
            existingCartItem.setUnitPrice(new BigDecimal(1000.00));
            cartItemRepository.save(existingCartItem);
        }else{
            //add new cartItem
            CartItem cartItem = new CartItem();
            cartItem.setUserId(Long.valueOf(userId));
            cartItem.setProductId(request.getProductId());
            cartItem.setQuantity(request.getQuantity());
            cartItem.setUnitPrice(BigDecimal.valueOf(1000.00));

            cartItemRepository.save(cartItem);
        }

        return true;
    }

    public boolean deleteItemFromCart(String userId, String productId) {

       CartItem cartItem = cartItemRepository.findByUserIdAndProductId(userId, productId);

        if(cartItem != null){
            cartItemRepository.delete(cartItem);
             return true;
        }
        return false;
    }

    public List<CartItemResponse> fetchUserCart(String userId) {

        return cartItemRepository.findByUserId(userId)
                .stream()
                .map(cartItemMapper::toResponse)
                .toList();
    }

    public List<CartItem> fetchCartItems(String userId) {
       return cartItemRepository.findByUserId(userId);
    }

    public void clearCart(String userId) {
       cartItemRepository.deleteByUserId(userId);
    }
}
