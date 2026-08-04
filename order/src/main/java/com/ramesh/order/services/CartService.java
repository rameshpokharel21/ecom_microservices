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
import com.ramesh.order.exceptions.ServiceUnavailableException;
import com.ramesh.order.exceptions.UserNotFoundException;
import com.ramesh.order.mappers.CartItemMapper;
import com.ramesh.order.repositories.CartItemRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;

import static com.ramesh.order.config.CustomCircuitBreakerConfig.PRODUCT_SERVICE_CB;
import static com.ramesh.order.config.CustomCircuitBreakerConfig.USER_SERVICE_CB;

@Service
@RequiredArgsConstructor
@Transactional
public class CartService {

    private static final Logger logger = LoggerFactory.getLogger(CartService.class);

    private final CartItemRepository cartItemRepository;
    private final CartItemMapper cartItemMapper;
    private final ProductServiceClient productServiceClient;
    private final UserServiceClient userServiceClient;
    //Spring Cloud's vendor-neutral factory, backed by Resilience4JCircuitBreakerFactory.
    //Used instead of @CircuitBreaker so each breaker wraps exactly one remote call rather
    //than a whole method: the database work and the business rules below must not be able
    //to trip a breaker named after a downstream service.
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private void ensureUserExists(String userId) {
        ResponseEntity<UserResponse> userResponse = circuitBreakerFactory
                .create(USER_SERVICE_CB)
                .run(() -> userServiceClient.getUserById(userId),
                        throwable -> {
                            //404 is a real answer from a healthy service, so keep the
                            //existing semantics rather than reporting an outage
                            if (throwable instanceof HttpClientErrorException.NotFound) {
                                throw new UserNotFoundException("User with id " + userId + " not found");
                            }
                            logger.warn("user-service call failed for userId={}: {}", userId, throwable.toString());
                            throw new ServiceUnavailableException("user-service", throwable);
                        });

        if (userResponse == null || userResponse.getBody() == null) {
            throw new UserNotFoundException("User with id " + userId + " not found");
        }
    }

    public void addToCart(String userId, CartItemRequest request) {

        //parsed outside the breaker: a malformed id is a bad request, not a
        //product-service failure, and must not count against the breaker
        Long productId = Long.valueOf(request.getProductId());

        ProductResponse productResponse = circuitBreakerFactory
                .create(PRODUCT_SERVICE_CB)
                .run(() -> productServiceClient.getProductById(productId).getBody(),
                        throwable -> {
                            if (throwable instanceof HttpClientErrorException.NotFound) {
                                throw new ProductNotFoundException(
                                        "Product with id " + request.getProductId() + " does not exist");
                            }
                            logger.warn("product-service call failed for productId={}: {}",
                                    productId, throwable.toString());
                            throw new ServiceUnavailableException("product-service", throwable);
                        });

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
