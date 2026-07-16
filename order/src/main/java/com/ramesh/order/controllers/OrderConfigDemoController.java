package com.ramesh.order.controllers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RefreshScope
@RestController
@RequestMapping("/api/order/demo")
public class OrderConfigDemoController {
    @Value("${app.order.message}")
    private String orderMessage;

    @GetMapping("/message")
    public String getOrderMessage(){
        return orderMessage;
    }
}
