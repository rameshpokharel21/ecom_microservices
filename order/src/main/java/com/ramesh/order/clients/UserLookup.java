package com.ramesh.order.clients;

import com.ramesh.order.dtos.UserResponse;
import com.ramesh.order.exceptions.ServiceUnavailableException;
import com.ramesh.order.exceptions.UserNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

//The user-service counterpart of ProductLookup - same reasoning, see that class for why
//the remote call lives in its own bean instead of a helper method on CartService.
@Component
@RequiredArgsConstructor
public class UserLookup {

    private static final Logger logger = LoggerFactory.getLogger(UserLookup.class);

    private final UserServiceClient userServiceClient;

    //"user-service" must match the instances: key in order-service.yml.
    @CircuitBreaker(name = "user-service", fallbackMethod = "getUserFallback")
    public UserResponse getUser(String userId) {
        ResponseEntity<UserResponse> response = userServiceClient.getUserById(userId);
        return response == null ? null : response.getBody();
    }

    private UserResponse getUserFallback(String userId, HttpClientErrorException.NotFound ex) {
        throw new UserNotFoundException("User with id " + userId + " not found");
    }

    private UserResponse getUserFallback(String userId, Throwable throwable) {
        logger.warn("user-service call failed for userId={}: {}", userId, throwable.toString());
        throw new ServiceUnavailableException("user-service", throwable);
    }
}
