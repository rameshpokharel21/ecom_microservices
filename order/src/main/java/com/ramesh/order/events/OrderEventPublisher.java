package com.ramesh.order.events;

import com.ramesh.order.dtos.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessagingException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

//The AMQP publish used to sit in the middle of OrderService.createOrder, inside its
//transaction. RabbitMQ is not enlisted in that transaction, so the message left the
//moment convertAndSend returned while the database commit was still pending - the
//classic dual-write, with two failure modes:
//
//  1. A rollback AFTER the send (clearCart throwing, a constraint violation, a lock
//     timeout at commit) left notification-service holding an event for an order that
//     no row backs.
//  2. A broker outage threw AmqpIOException out of createOrder, rolling the order
//     back. Verified: with rabbitmq stopped, POST /api/orders returned 500 and created
//     nothing. Adding a message queue had made order placement depend on the broker
//     being up, which is the opposite of the point.
//
//Moving the send here fixes both. OrderService now publishes a Spring application
//event; this listener runs on AFTER_COMMIT, so it is reached only if the transaction
//actually committed, and its outcome can no longer affect that transaction.
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private static final Logger logger = LoggerFactory.getLogger(OrderEventPublisher.class);

    //MUST match a key under spring.cloud.stream.bindings in config/order-service.yml.
    //
    //A constant rather than a literal at the call site because StreamBridge does NOT
    //validate this name. An unrecognised name is not an error - it is treated as a
    //DYNAMIC DESTINATION, so the binder creates a brand-new topic exchange named after
    //whatever string was passed and publishes there. Nothing is bound to it, and an
    //unroutable message on a topic exchange is discarded without a word. A typo here
    //("publishOrderCreate-out-0", missing the d) cost an afternoon: every send returned
    //true, the broker grew a phantom publishOrderCreate-out-0 exchange with no bindings,
    //and notification-service sat idle on a correctly configured queue.
    private static final String BINDING = "publishOrderCreated-out-0";

    private final StreamBridge streamBridge;

//    private final RabbitTemplate rabbitTemplate;
//
//    @Value("${rabbitmq.exchange.name}")
//    private String exchangeName;
//    @Value("${rabbitmq.routing.key}")
//    private String routingKey;

    //@Async is the second half of the fix, and it is not decoration.
    //
    //AFTER_COMMIT callbacks run on the REQUEST thread, inside commit(). Without @Async
    //the publish is off the transaction but still on the response path, so a dead broker
    //adds its connect timeout to every order. Measured with rabbitmq stopped: 3.7s per
    //order, and the first one took longer than the gateway's 5s TimeLimiter, so the
    //client got a 503 for an order that had actually been created - which invites a
    //retry and a duplicate. Exactly the outcome the catch below exists to prevent.
    //
    //With @Async the listener is dispatched to Boot's task executor, the request thread
    //returns as soon as the commit is done, and order latency stops depending on
    //RabbitMQ at all. Requires @EnableAsync on OrderApplication - the annotation is
    //ignored without it.
    //
    //Safe here because the event is an immutable snapshot of DTOs (see OrderService),
    //so there is no entity or EntityManager being touched from the pool thread.
    //
    //fallbackExecution = true matters. The DEFAULT for @TransactionalEventListener is to
    //do NOTHING when the event is published outside a transaction - no exception, no log,
    //the event simply evaporates. createOrder is @Transactional today so the default
    //would work, but the failure mode if that ever stops being true is silent event loss
    //discovered weeks later. With the flag, a publish with no transaction in progress
    //runs the listener immediately instead. There is nothing to be inconsistent with in
    //that case: no transaction means no rollback to guard against.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishOrderCreated(OrderCreatedEvent event) {
        try {
            //rabbitTemplate.convertAndSend(exchangeName, routingKey, event);
            //
            //The exchange and routing key are no longer named here. The binding in
            //config/order-service.yml carries destination: order.exchange and
            //routing-key-expression: 'order.tracking', so this class no longer knows it
            //is talking to RabbitMQ at all - that is the point of Stream's binder
            //abstraction. Note the boolean return means "handed to the channel", not
            //"the broker accepted it"; see the comment on the catch below.
            streamBridge.send(BINDING, event);
            logger.debug("Published OrderCreatedEvent for order {}", event.getOrderId());
        }
        //Swallowing is deliberate, and it is the whole reason this method exists.
        //
        //AFTER_COMMIT callbacks are invoked from AbstractPlatformTransactionManager's
        //triggerAfterCommit, INSIDE the commit() call, and anything thrown there
        //propagates to the caller. Without this catch a broker outage would give the
        //client a 500 for an order that is already committed - so the client retries and
        //creates a duplicate. That is strictly worse than the bug being fixed.
        //
        //The cost is honest at-most-once delivery: the order stands and the event is
        //gone. That is why this logs at ERROR with the order id - it is the only record
        //that a downstream consumer is missing something, and it is what a reconciliation
        //job would key off. A transactional outbox (write the event to a table in the
        //same transaction, poll and publish it separately) is the fix that survives a
        //broker outage; it needs a table, a scheduler, and idempotent consumers.
        //
        //MessagingException, not AmqpException. This changed when the send moved to
        //StreamBridge and it is easy to miss, because the old catch still COMPILES - it
        //just never fires. StreamBridge hands the message to a Spring Integration
        //channel, and AbstractMessageHandler wraps whatever the outbound endpoint throws
        //in a MessageHandlingException. So the AmqpException from a dead broker arrives
        //here as the CAUSE of a MessagingException, not as itself. Left unchanged, the
        //at-most-once safety net below would have gone silently dead: no LOST log, and
        //the exception escaping into the @Async executor's uncaught handler instead.
        //
        //MessagingException rather than Exception keeps an NPE in this method visible.
        catch (MessagingException e) {
            logger.error("LOST OrderCreatedEvent for order {}: the order is committed but the "
                    + "event could not be published, so no consumer will ever see it. "
                    + "Requires manual reconciliation.", event.getOrderId(), e);
        }
    }
}
