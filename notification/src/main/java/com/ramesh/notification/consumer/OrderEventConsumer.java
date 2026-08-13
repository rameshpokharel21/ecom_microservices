package com.ramesh.notification.consumer;

import com.ramesh.notification.payload.OrderCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
public class OrderEventConsumer {

    private final static Logger logger = LoggerFactory.getLogger(OrderEventConsumer.class);

//    @RabbitListener(queues = "${rabbitmq.queue.name}")
//    public void handleOrderEvent(OrderCreatedEvent orderCreatedEvent){
//        logger.info("Received order event: {}", orderCreatedEvent);
//
//        Long orderId = orderCreatedEvent.getOrderId();
//        OrderStatus status = orderCreatedEvent.getStatus();
//        logger.info("order id: {}", orderId);
//        logger.info("order status: {}", status);
//    }
    @Bean
    public Consumer<OrderCreatedEvent> orderCreated(){
        return event -> {
            logger.info("Received order created event for order: {}", event.getOrderId());
            logger.info("Received order crated event for user: {}", event.getUserId());
        };
    }
}
