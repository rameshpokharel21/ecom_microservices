package com.ramesh.order.clients;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class ProductServiceClientConfig {

    //@LoadBalanced selects the discovery-aware builder from RestClientConfig, so
    //"product-service" is resolved as a Eureka service ID rather than a hostname.
    @Bean
    public ProductServiceClient productHttpInterface(@LoadBalanced RestClient.Builder builder){
        RestClient restClient = builder
                .baseUrl("http://product-service")
                .build();

        RestClientAdapter adapter = RestClientAdapter.create(restClient);
        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(adapter)
                .build();
        return factory.createClient(ProductServiceClient.class);
    }
}
