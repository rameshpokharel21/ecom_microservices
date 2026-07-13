package com.ramesh.order.repositories;

import com.ramesh.order.entities.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    //because CartItem entity has User, Product as fields
    CartItem findByUserIdAndProductId(String userId, String productId);

    void deleteByUserIdAndProductId(String userId, String productId);
    List<CartItem> findByUserId(String userId);

    void deleteByUserId(String userId);
}
