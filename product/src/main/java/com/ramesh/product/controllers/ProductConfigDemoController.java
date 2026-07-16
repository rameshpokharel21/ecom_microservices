package com.ramesh.product.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RefreshScope
@RestController
@RequestMapping("/api/product/demo")
public class ProductConfigDemoController {

    @Value("${app.product.message}")
    private String productMessage;

    @GetMapping("/message")
    public String getMessage(){
        return productMessage;
    }
}
