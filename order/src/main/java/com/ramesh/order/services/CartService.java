package com.ramesh.order.services;


import com.ramesh.order.clients.ProductLookup;
import com.ramesh.order.clients.UserLookup;
import com.ramesh.order.dtos.CartItemRequest;
import com.ramesh.order.dtos.CartItemResponse;
import com.ramesh.order.dtos.ProductResponse;
import com.ramesh.order.entities.CartItem;
import com.ramesh.order.exceptions.InsufficientStockException;
import com.ramesh.order.exceptions.ProductNotFoundException;
import com.ramesh.order.exceptions.UserNotFoundException;
import com.ramesh.order.mappers.CartItemMapper;
import com.ramesh.order.repositories.CartItemRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class  CartService {

    private final CartItemRepository cartItemRepository;
    private final CartItemMapper cartItemMapper;
    //The circuit breakers live on these two beans, declared with @CircuitBreaker and
    //configured entirely in order-service.yml. Nothing in this class knows a breaker
    //exists: it calls them like ordinary collaborators and handles the same exceptions
    //it always did.
    private final ProductLookup productLookup;
    private final UserLookup userLookup;

    //Still private. The breaker is not on this method - it is on UserLookup.getUser, which
    //is a different bean, so the AOP proxy is crossed even though this call is internal.
    //Annotating a private (or self-invoked public) method here would have been silently
    //inert instead.
    private void ensureUserExists(String userId) {
        if (userLookup.getUser(userId) == null) {
            throw new UserNotFoundException("User with id " + userId + " not found");
        }
    }

    public void addToCart(String userId, CartItemRequest request) {

        //No Long.valueOf here any more: productId is a Long from the DTO inward, so a
        //malformed id is rejected by Jackson before this method runs.
        ProductResponse productResponse = productLookup.getProduct(request.getProductId());

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

    public boolean deleteItemFromCart(String userId, Long productId) {

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
