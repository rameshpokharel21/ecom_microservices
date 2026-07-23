package com.ramesh.order.clients;

import com.ramesh.order.dtos.ProductResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface ProductServiceClient {

    @GetExchange("/api/products/{id}")
    public ResponseEntity<ProductResponse> getProductById(@PathVariable("id") Long id);
}
