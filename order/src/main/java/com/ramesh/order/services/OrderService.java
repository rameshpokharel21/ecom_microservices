package com.ramesh.order.services;

import com.ramesh.order.dtos.OrderCreatedEvent;
import com.ramesh.order.dtos.OrderItemDto;
import com.ramesh.order.dtos.OrderResponse;
import com.ramesh.order.entities.CartItem;
import com.ramesh.order.entities.Order;
import com.ramesh.order.entities.OrderItem;
import com.ramesh.order.entities.OrderStatus;
import com.ramesh.order.mappers.OrderItemMapper;
import com.ramesh.order.mappers.OrderMapper;
import com.ramesh.order.repositories.OrderRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class OrderService {


    private final CartService cartService;

    private final OrderRepository orderRepository;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;

    //Not a RabbitTemplate. This service knows nothing about AMQP now - it announces that
    //an order was created and OrderEventPublisher decides what that means. Keeping the
    //broker out of here is what lets the publish move to after the commit.
    private final ApplicationEventPublisher eventPublisher;


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

        //The event is BUILT here, inside the transaction, and sent later, after the
        //commit. Both halves of that matter.
        //
        //Built here because everything it needs is entity state: getItems() is a lazy
        //JPA collection, so reading it after the transaction closes would throw
        //LazyInitializationException. Flattening it into DTOs now makes the event a
        //self-contained snapshot that survives the commit - which is also why this
        //publishes the DTO rather than the Order entity itself.
        //
        //Sent later because publishEvent only hands the event to Spring.
        //OrderEventPublisher is annotated AFTER_COMMIT, so nothing reaches RabbitMQ
        //until this transaction has actually committed. If anything below throws - or
        //the commit itself fails - the listener never runs and no event is emitted.
        OrderCreatedEvent event = new OrderCreatedEvent(
                savedOrder.getId(),
                savedOrder.getUserId(),
                savedOrder.getStatus(),
                mapToOrderItemDtos(savedOrder.getItems()),
                savedOrder.getTotalAmount(),
                savedOrder.getCreatedAt()

        );
        eventPublisher.publishEvent(event);


        //clear the cart. Its position relative to publishEvent above no longer matters:
        //when the publish was a direct convertAndSend, a throw here meant the order
        //rolled back but the message was already gone.
        cartService.clearCart(userId);

        return Optional.of(orderMapper.toResponse(savedOrder));
    }

    //Reads the same X-User-ID the cart endpoints do, so a caller can only ever see its
    //own orders - there is no order id in the signature to tamper with.
    public List<OrderResponse> getUserOrders(String userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(orderMapper::toResponse)
                .toList();
    }

    private List<OrderItemDto> mapToOrderItemDtos(List<OrderItem> items){
        return items.stream()
                .map(item -> orderItemMapper.toOrderItemDto(item))
                .toList();
    }

    private List<OrderItem> buildOrderItems(List<CartItem> cartItems, Order order){
        return cartItems.stream()
                .map(item -> {
                    OrderItem orderItem = new OrderItem();
                    orderItem.setProductId(item.getProductId());
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
                                item.getProductId(),
                                item.getQuantity(),
                                item.getUnitPrice(),
                                item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()))
                        ))
                        .toList(),
                order.getCreatedAt()
        );
    }
}
