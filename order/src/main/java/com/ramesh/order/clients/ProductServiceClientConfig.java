package com.ramesh.order.clients;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
@RequiredArgsConstructor
public class ProductServiceClientConfig {

    private final RestClientBuilderConfigurer restClientBuilderConfigurer;

    //prototype, matching Boot's own RestClientAutoConfiguration: RestClient.Builder
    //is mutable (baseUrl() sets a field and returns this), so a singleton would be
    //shared by every injection point. Each consumer gets its own copy instead.
    @Bean
    @Primary
    @Scope("prototype")
    public RestClient.Builder restClientBuilder(){
        return restClientBuilderConfigurer.configure(RestClient.builder());
    }

    @Bean
    @LoadBalanced
    @Scope("prototype")
    public RestClient.Builder loadBalancedRestClientBuilder(){
        return restClientBuilderConfigurer.configure(RestClient.builder());
    }

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
