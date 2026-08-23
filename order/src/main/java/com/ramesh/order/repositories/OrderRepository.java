package com.ramesh.order.repositories;


import com.ramesh.order.entities.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    //@EntityGraph fetches items in the same query: without it this is N+1, one SELECT
    //per order, because Order.items is @OneToMany and therefore LAZY.
    @EntityGraph(attributePaths = "items")
    List<Order> findByUserIdOrderByCreatedAtDesc(String userId);
}
