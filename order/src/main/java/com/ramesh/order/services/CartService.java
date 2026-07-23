package com.ramesh.order.services;


import com.ramesh.order.clients.ProductServiceClient;
import com.ramesh.order.clients.UserServiceClient;
import com.ramesh.order.dtos.CartItemRequest;
import com.ramesh.order.dtos.CartItemResponse;
import com.ramesh.order.dtos.ProductResponse;
import com.ramesh.order.dtos.UserResponse;
import com.ramesh.order.entities.CartItem;
import com.ramesh.order.exceptions.InsufficientStockException;
import com.ramesh.order.exceptions.ProductNotFoundException;
import com.ramesh.order.exceptions.UserNotFoundException;
import com.ramesh.order.mappers.CartItemMapper;
import com.ramesh.order.repositories.CartItemRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class CartService {


    private final CartItemRepository cartItemRepository;
    private final CartItemMapper cartItemMapper;
    private final ProductServiceClient productServiceClient;
    private final UserServiceClient userServiceClient;

    private void ensureUserExists(String userId) {
        ResponseEntity<UserResponse> userResponse;
        try {
            userResponse = userServiceClient.getUserById(userId);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new UserNotFoundException("User with id " + userId + " not found");
        }

        if (userResponse == null || userResponse.getBody() == null) {
            throw new UserNotFoundException("User with id " + userId + " not found");
        }
    }

    public void addToCart(String userId, CartItemRequest request) {

        ProductResponse productResponse;
        try {
            productResponse = productServiceClient.getProductById(Long.valueOf(request.getProductId())).getBody();
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ProductNotFoundException("Product with id " + request.getProductId() + " does not exist");
        }

        if(productResponse == null){
            throw new ProductNotFoundException("Product with id " + request.getProductId() + " does not exist");
        }

        ensureUserExists(userId);

        if(productResponse.getStockQuantity() < request.getQuantity()){
            throw new InsufficientStockException("Insufficient stock for product id " + request.getProductId());
        }

        CartItem existingCartItem = cartItemRepository.findByUserIdAndProductId(userId, request.getProductId());

        if(existingCartItem != null){

            //update quantity
            Integer updatedQuantity = existingCartItem.getQuantity() + request.getQuantity();
            existingCartItem.setQuantity(updatedQuantity);
            existingCartItem.setUnitPrice(productResponse.getPrice());
            cartItemRepository.save(existingCartItem);
        }else{
            //add new cartItem
            CartItem cartItem = new CartItem();
            cartItem.setUserId(userId);
            cartItem.setProductId(request.getProductId());
            cartItem.setQuantity(request.getQuantity());
            cartItem.setUnitPrice(productResponse.getPrice());

            cartItemRepository.save(cartItem);
        }
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
        ensureUserExists(userId);

        return cartItemRepository.findByUserId(userId)
                .stream()
                .map(cartItemMapper::toResponse)
                .toList();
    }

    public List<CartItem> fetchCartItems(String userId) {
        ensureUserExists(userId);
        return cartItemRepository.findByUserId(userId);
    }

    public void clearCart(String userId) {
       cartItemRepository.deleteByUserId(userId);
    }
}
