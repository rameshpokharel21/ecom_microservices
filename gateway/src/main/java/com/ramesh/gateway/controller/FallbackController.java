package com.ramesh.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

//Targets of the setFallbackUri("forward:/fallback/...") in GatewayConfig. The gateway
//re-dispatches the original exchange into its own DispatcherHandler, which is why these
//are ordinary controller methods rather than anything gateway-specific.
//
//@RequestMapping without a method restriction on purpose: a forward keeps the original
//HTTP method, so a POST /api/carts that trips the order breaker arrives here as a POST.
//With @GetMapping it would have come back as 405 Method Not Allowed instead of the
//intended 503, which is a confusing thing to debug.
@RestController
public class FallbackController {

    @RequestMapping("/fallback/products")
    public ResponseEntity<List<String>> productsFallback(){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Collections.singletonList("Product service is unavailable, please try after sometime"));
    }

    @RequestMapping("/fallback/users")
    public ResponseEntity<List<String>> usersFallback(){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Collections.singletonList("User service is unavailable, please try after sometime"));
    }

    @RequestMapping("/fallback/orders")
    public ResponseEntity<List<String>> ordersFallback(){
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Collections.singletonList("Order service is unavailable, please try after sometime"));
    }
}
