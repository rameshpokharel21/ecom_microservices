package com.ramesh.notification.consumer;

import com.ramesh.notification.payload.OrderCreatedEvent;
import com.ramesh.notification.payload.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
public class OrderEventConsumer {

    private final static Logger logger = LoggerFactory.getLogger(OrderEventConsumer.class);

    @RabbitListener(queues = "${rabbitmq.queue.name}")
    public void handleOrderEvent(OrderCreatedEvent orderCreatedEvent){
        logger.info("Received order event: {}", orderCreatedEvent);

        Long orderId = orderCreatedEvent.getOrderId();
        OrderStatus status = orderCreatedEvent.getStatus();
        logger.info("order id: {}", orderId);
        logger.info("order status: {}", status);
    }
}
