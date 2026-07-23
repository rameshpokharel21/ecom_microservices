package com.ramesh.order.services;

import com.ramesh.order.dtos.OrderItemDto;
import com.ramesh.order.dtos.OrderResponse;
import com.ramesh.order.entities.CartItem;
import com.ramesh.order.entities.Order;
import com.ramesh.order.entities.OrderItem;
import com.ramesh.order.entities.OrderStatus;
import com.ramesh.order.mappers.OrderMapper;
import com.ramesh.order.repositories.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final CartService cartService;

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;

    public Optional<OrderResponse> createOrder(String userId) {

        //validate for cart items
        List<CartItem> cartItems = cartService.fetchCartItems(userId);
        if(cartItems.isEmpty()){
           return Optional.empty();
        }

        //calculate total price
        BigDecimal totalPrice = calculateTotal(cartItems);

        //build order
        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setTotalAmount(totalPrice);
        List<OrderItem> orderItems = buildOrderItems(cartItems, order);
        order.setItems(orderItems);
        Order savedOrder = orderRepository.save(order);

        //clear the cart
        cartService.clearCart(userId);

        return Optional.of(orderMapper.toResponse(savedOrder));
    }

    private List<OrderItem> buildOrderItems(List<CartItem> cartItems, Order order){
        return cartItems.stream()
                .map(item -> {
                    OrderItem orderItem = new OrderItem();
                    orderItem.setProductId(Long.valueOf(item.getProductId()));
                    orderItem.setQuantity(item.getQuantity());
                    orderItem.setUnitPrice(item.getUnitPrice());
                    orderItem.setOrder(order);
                    return orderItem;
                })
                .collect(Collectors.toList());
    }

    private BigDecimal calculateTotal(List<CartItem> cartItems){
        return cartItems.stream()
                .filter(Objects::nonNull)
                .map(item -> item.getUnitPrice()
                        .multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        //.reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    //can use this class instead of OrderMapper and OrderItemMapper
    private OrderResponse mapToOrderResponse(Order order){
        return new OrderResponse(
                order.getId(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getItems().stream()
                        .map(item -> new OrderItemDto(
                                item.getId(),
                                item.getProductId().toString(),
                                item.getQuantity(),
                                item.getUnitPrice(),
                                item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                        ))
                        .toList(),
                order.getCreatedAt()
        );
    }
}
