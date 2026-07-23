package com.ramesh.order.clients;

import com.ramesh.order.dtos.UserResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface UserServiceClient {
    @GetExchange("/api/users/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable("id") String id);
}
